package de.neptune.ncw;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Neptune Command Whitelist (NCW)
 * Befehl: /ncw (Aliase: /nwc, /neptunewhitelist)
 */
public final class NcwPlugin extends JavaPlugin implements Listener, TabExecutor {

    public enum Category {
        STAFF("Staff-Mitglieder", Material.DIAMOND),
        BUY("Buy-Ranks", Material.EMERALD),
        OTHER("Sonstige (Media, Friend, ...)", Material.AMETHYST_SHARD);

        public final String label;
        public final Material icon;

        Category(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }

        public static Category parse(String s) {
            if (s == null) return null;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "staff", "team", "mitglieder" -> STAFF;
                case "buy", "buyranks", "kauf", "shop" -> BUY;
                case "other", "sonstige", "andere", "media" -> OTHER;
                default -> null;
            };
        }
    }

    private static final String PREFIX = "§8[§bNCW§8] §7";

    private LuckPerms lp;
    private File dataFile;

    private boolean enabled = false;
    private String ownerGroup = "";
    private final Map<Category, String> managers = new EnumMap<>(Category.class);
    private final Map<String, Category> categories = new HashMap<>();
    private final Map<String, Set<String>> allowed = new HashMap<>();

    /** Grund-Befehle, die jeder Spieler bekommt (Gruppe "default"). */
    static final List<String> BASIC_COMMANDS = List.of(
            "home", "homes", "sethome", "delhome", "removehome",
            "rtp", "wild", "betterrtp",
            "spawn", "msg", "tell", "w", "r", "reply",
            "tpa", "tpaccept", "tpdeny", "tpcancel", "tpahere",
            "help", "rules");

    /** Passende LuckPerms-Rechte dazu (Essentials / BetterRTP). */
    static final List<String> BASIC_PERMS = List.of(
            "essentials.home", "essentials.sethome", "essentials.delhome",
            "essentials.spawn", "essentials.msg", "essentials.tpa",
            "essentials.tpaccept", "essentials.tpdeny", "essentials.tpahere",
            "betterrtp.use");

    /** Spieler, die gerade einen Suchbegriff in den Chat tippen sollen. */
    private final Map<UUID, java.util.function.Consumer<String>> pendingInput =
            new java.util.concurrent.ConcurrentHashMap<>();

    private boolean refreshPending = false;

    /** Schickt allen Spielern die Befehlsliste neu, damit gesperrte Befehle sofort verschwinden. */
    void scheduleRefresh() {
        if (!isEnabled() || refreshPending) return;
        refreshPending = true;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            refreshPending = false;
            for (Player pl : Bukkit.getOnlinePlayers()) pl.updateCommands();
        }, 5L);
    }

    @Override
    public void onEnable() {
        try {
            lp = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            getLogger().severe("LuckPerms nicht gefunden - Plugin wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataFile = new File(getDataFolder(), "data.yml");
        boolean firstStart = !dataFile.exists();
        loadData();
        if (firstStart) {
            allowedOf("default").addAll(BASIC_COMMANDS);
            saveData();
        }
        PluginCommand cmd = getCommand("ncw");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NCW aktiv. Whitelist ist " + (enabled ? "AN" : "AUS (erst konfigurieren, dann /ncw enable)"));
    }

    @Override
    public void onDisable() {
        if (dataFile != null) saveData();
    }

    // ------------------------------------------------------------ Daten

    private void loadData() {
        enabled = false;
        ownerGroup = "";
        managers.clear();
        categories.clear();
        allowed.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        enabled = y.getBoolean("enabled", false);
        ownerGroup = y.getString("owner-group", "");
        ConfigurationSection m = y.getConfigurationSection("managers");
        if (m != null) {
            for (String k : m.getKeys(false)) {
                Category c = Category.parse(k);
                String g = m.getString(k, "");
                if (c != null && !g.isEmpty()) managers.put(c, g.toLowerCase(Locale.ROOT));
            }
        }
        ConfigurationSection c = y.getConfigurationSection("categories");
        if (c != null) {
            for (String g : c.getKeys(false)) {
                Category cat = Category.parse(c.getString(g));
                if (cat != null) categories.put(g.toLowerCase(Locale.ROOT), cat);
            }
        }
        ConfigurationSection a = y.getConfigurationSection("allowed");
        if (a != null) {
            for (String g : a.getKeys(false)) {
                allowed.put(g.toLowerCase(Locale.ROOT), new TreeSet<>(a.getStringList(g)));
            }
        }
    }

    void saveData() {
        scheduleRefresh();
        YamlConfiguration y = new YamlConfiguration();
        y.set("enabled", enabled);
        y.set("owner-group", ownerGroup);
        for (Map.Entry<Category, String> e : managers.entrySet()) y.set("managers." + e.getKey().name(), e.getValue());
        for (Map.Entry<String, Category> e : categories.entrySet()) y.set("categories." + e.getKey(), e.getValue().name());
        for (Map.Entry<String, Set<String>> e : allowed.entrySet()) y.set("allowed." + e.getKey(), new ArrayList<>(e.getValue()));
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Konnte data.yml nicht speichern: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ Zugriff / Rechte

    LuckPerms lp() {
        return lp;
    }

    Set<String> groupsOf(Player p) {
        User u = lp.getUserManager().getUser(p.getUniqueId());
        if (u == null) return Collections.emptySet();
        Set<String> s = new HashSet<>();
        for (Group g : u.getInheritedGroups(QueryOptions.nonContextual())) s.add(g.getName().toLowerCase(Locale.ROOT));
        s.add(u.getPrimaryGroup().toLowerCase(Locale.ROOT));
        return s;
    }

    /** Voller Zugriff: Konsole, ncw.admin oder Mitglied der Owner-Gruppe. */
    boolean isAdmin(CommandSender s) {
        if (!(s instanceof Player p)) return true;
        if (p.hasPermission("ncw.admin")) return true;
        return !ownerGroup.isEmpty() && groupsOf(p).contains(ownerGroup);
    }

    /** Darf der Sender die Einstellungen dieser Gruppe aendern? */
    boolean canManage(CommandSender s, String group) {
        if (isAdmin(s)) return true;
        if (!(s instanceof Player p)) return false;
        Category cat = categories.get(group.toLowerCase(Locale.ROOT));
        if (cat == null) return false;
        String mgr = managers.get(cat);
        return mgr != null && groupsOf(p).contains(mgr);
    }

    boolean canUse(CommandSender s) {
        if (isAdmin(s)) return true;
        if (!(s instanceof Player p)) return false;
        Set<String> g = groupsOf(p);
        for (String m : managers.values()) if (g.contains(m)) return true;
        return false;
    }

    Category categoryOf(String group) {
        return categories.get(group.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------ Whitelist

    Set<String> allowedOf(String group) {
        return allowed.computeIfAbsent(group.toLowerCase(Locale.ROOT), k -> new TreeSet<>());
    }

    boolean hasAll(String group) {
        return allowedOf(group).contains("*");
    }

    boolean toggleCommand(String group, String command) {
        Set<String> s = allowedOf(group);
        String c = command.toLowerCase(Locale.ROOT);
        boolean now;
        if (s.contains(c)) {
            s.remove(c);
            now = false;
        } else {
            s.add(c);
            now = true;
        }
        saveData();
        return now;
    }

    void setCategory(String group, Category c) {
        if (c == null) categories.remove(group.toLowerCase(Locale.ROOT));
        else categories.put(group.toLowerCase(Locale.ROOT), c);
        saveData();
    }

    private Set<String> candidates(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        Set<String> c = new HashSet<>();
        c.add(l);
        int i = l.indexOf(':');
        String base = i >= 0 ? l.substring(i + 1) : l;
        c.add(base);
        Command cmd = Bukkit.getCommandMap().getCommand(l);
        if (cmd == null) cmd = Bukkit.getCommandMap().getCommand(base);
        if (cmd != null) {
            c.add(cmd.getName().toLowerCase(Locale.ROOT));
            for (String a : cmd.getAliases()) c.add(a.toLowerCase(Locale.ROOT));
        }
        return c;
    }

    /** Alle Quellen, aus denen ein Rang Freigaben bekommt: er selbst, "default",
     *  seine LuckPerms-Eltern und alle Raenge derselben Kategorie mit niedrigerem Gewicht. */
    List<String> sourcesOf(String group) {
        String g = group.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        out.add(g);
        out.add("default");
        Group lg = lp.getGroupManager().getGroup(g);
        if (lg != null) {
            for (Group parent : lg.getInheritedGroups(QueryOptions.nonContextual()))
                out.add(parent.getName().toLowerCase(Locale.ROOT));
            Category cat = categories.get(g);
            if (cat != null) {
                int w = lg.getWeight().orElse(0);
                for (Map.Entry<String, Category> e : categories.entrySet()) {
                    if (e.getValue() != cat) continue;
                    Group other = lp.getGroupManager().getGroup(e.getKey());
                    if (other != null && other.getWeight().orElse(0) < w) out.add(e.getKey());
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Erste fremde Quelle, die den Befehl (oder "*") freigibt, sonst null. */
    String inheritedFrom(String group, List<String> sources, String command) {
        String self = group.toLowerCase(Locale.ROOT);
        for (String src : sources) {
            if (src.equals(self)) continue;
            Set<String> s = allowed.get(src);
            if (s != null && (s.contains("*") || s.contains(command))) return src;
        }
        return null;
    }

    Set<String> mergedAllowed(Player p) {
        Set<String> srcs = new HashSet<>();
        srcs.add("default");
        for (String g : groupsOf(p)) srcs.addAll(sourcesOf(g));
        Set<String> merged = new HashSet<>();
        for (String s : srcs) {
            Set<String> set = allowed.get(s);
            if (set != null) merged.addAll(set);
        }
        return merged;
    }

    boolean allows(Set<String> merged, String label) {
        if (merged.contains("*")) return true;
        for (String c : candidates(label)) {
            if (c.equals("ncw") || merged.contains(c)) return true;
        }
        return false;
    }

    boolean isAllowed(Player p, String label) {
        if (!enabled || isAdmin(p)) return true;
        return allows(mergedAllowed(p), label);
    }

    /** Alle Befehle aller Plugins (Name -> Plugin). */
    Map<String, String> allCommands() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Command> e : Bukkit.getCommandMap().getKnownCommands().entrySet()) {
            String name = e.getKey();
            if (name.contains(":")) continue;
            Command c = e.getValue();
            String owner = c instanceof PluginCommand pc ? pc.getPlugin().getName() : "Minecraft/Server";
            out.put(name.toLowerCase(Locale.ROOT), owner);
        }
        return out;
    }

    /** Alle registrierten Berechtigungen aller Plugins. */
    List<String> allPermissions() {
        return Bukkit.getPluginManager().getPermissions().stream()
                .map(Permission::getName)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct().sorted().collect(Collectors.toList());
    }

    // ------------------------------------------------------------ LuckPerms-Permissions

    boolean hasPerm(String group, String node) {
        Group g = lp.getGroupManager().getGroup(group);
        if (g == null) return false;
        return g.getNodes(NodeType.PERMISSION).stream()
                .anyMatch(n -> n.getKey().equalsIgnoreCase(node) && n.getValue() && !n.hasExpiry());
    }

    void setPerm(String group, String node, Boolean value) {
        Group g = lp.getGroupManager().getGroup(group);
        if (g == null) return;
        g.data().clear(NodeType.PERMISSION.predicate(n -> n.getKey().equalsIgnoreCase(node) && n.getContexts().isEmpty()));
        if (value != null) g.data().add(Node.builder(node).value(value).build());
        lp.getGroupManager().saveGroup(g);
        scheduleRefresh();
    }

    void togglePerm(String group, String node) {
        setPerm(group, node, hasPerm(group, node) ? null : Boolean.TRUE);
    }

    /** Alle Rechte, die der Rang direkt gesetzt hat (true). */
    Set<String> directPerms(String group) {
        Group g = lp.getGroupManager().getGroup(group);
        if (g == null) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (var n : g.getNodes(NodeType.PERMISSION)) {
            if (n.getValue() && !n.hasExpiry()) out.add(n.getKey().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Gilt das Recht fuer den Rang (inklusive geerbter)? */
    boolean effectivePerm(String group, String node) {
        Group g = lp.getGroupManager().getGroup(group);
        if (g == null) return false;
        return g.getCachedData().getPermissionData(QueryOptions.nonContextual())
                .checkPermission(node).asBoolean();
    }

    /** LuckPerms-Vererbung herstellen: alle erben von "default", innerhalb einer
     *  Kategorie erbt jeder Rang vom naechst niedrigeren (nach Gewicht). */
    int syncInheritance() {
        int changes = 0;
        for (Group g : lp.getGroupManager().getLoadedGroups()) {
            if (g.getName().equalsIgnoreCase("default")) continue;
            if (addParent(g, "default")) changes++;
        }
        for (Category cat : Category.values()) {
            List<Group> list = new ArrayList<>();
            for (Map.Entry<String, Category> e : categories.entrySet()) {
                if (e.getValue() != cat) continue;
                Group g = lp.getGroupManager().getGroup(e.getKey());
                if (g != null) list.add(g);
            }
            list.sort(Comparator.comparingInt(g -> g.getWeight().orElse(0)));
            for (int i = 1; i < list.size(); i++) {
                Group lower = list.get(i - 1);
                Group higher = list.get(i);
                if (higher.getWeight().orElse(0) > lower.getWeight().orElse(0)
                        && addParent(higher, lower.getName())) changes++;
            }
        }
        return changes;
    }

    private boolean addParent(Group g, String parent) {
        if (g.getName().equalsIgnoreCase(parent)) return false;
        net.luckperms.api.node.types.InheritanceNode n =
                net.luckperms.api.node.types.InheritanceNode.builder(parent).build();
        if (g.data().add(n).wasSuccessful()) {
            lp.getGroupManager().saveGroup(g);
            scheduleRefresh();
            return true;
        }
        return false;
    }

    /** Grund-Befehle und Grund-Rechte fuer "default" setzen. */
    void applyDefaults() {
        allowedOf("default").addAll(BASIC_COMMANDS);
        saveData();
        if (lp.getGroupManager().getGroup("default") != null) {
            for (String perm : BASIC_PERMS) setPerm("default", perm, Boolean.TRUE);
        }
    }

    /** Naechste Chat-Nachricht des Spielers an den Callback geben. */
    void awaitInput(Player p, java.util.function.Consumer<String> callback) {
        pendingInput.put(p.getUniqueId(), callback);
    }

    // ------------------------------------------------------------ Events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        String label = msg.startsWith("/") ? msg.substring(1) : msg;
        int sp = label.indexOf(' ');
        if (sp >= 0) label = label.substring(0, sp);
        if (label.isEmpty()) return;
        if (!isAllowed(e.getPlayer(), label)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text("Unknown command. Type \"/help\" for help.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSend(PlayerCommandSendEvent e) {
        if (!enabled || isAdmin(e.getPlayer())) return;
        Set<String> merged = mergedAllowed(e.getPlayer());
        e.getCommands().removeIf(name -> !allows(merged, name));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        java.util.function.Consumer<String> cb = pendingInput.remove(e.getPlayer().getUniqueId());
        if (cb == null) return;
        e.setCancelled(true);
        String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> cb.accept(text));
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        pendingInput.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        menu.click(e.getRawSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ------------------------------------------------------------ Befehl

    private void msg(CommandSender s, String m) {
        s.sendMessage(PREFIX + m);
    }

    private boolean groupExists(CommandSender s, String name) {
        if (lp.getGroupManager().getGroup(name) != null) return true;
        msg(s, "§cDie LuckPerms-Gruppe §f" + name + " §cgibt es nicht.");
        return false;
    }

    private void help(CommandSender s) {
        msg(s, "§bNeptune Command Whitelist");
        s.sendMessage("§f/ncw gui §7- Menue oeffnen");
        s.sendMessage("§f/ncw list §7- Raenge und Kategorien");
        s.sendMessage("§f/ncw info <rang> §7- Freigaben eines Rangs");
        s.sendMessage("§f/ncw allow <rang> <befehl|*> §7- Befehl freigeben");
        s.sendMessage("§f/ncw deny <rang> <befehl|*> §7- Befehl sperren");
        s.sendMessage("§f/ncw perm <rang> <permission> <true|false|unset> §7- LuckPerms-Recht setzen");
        if (isAdmin(s)) {
            s.sendMessage("§f/ncw category <rang> <staff|buy|other|none> §7- Kategorie");
            s.sendMessage("§f/ncw manager <staff|buy|other> <rang|none> §7- Oberster Rang der Kategorie");
            s.sendMessage("§f/ncw owner <rang|none> §7- Rang, der alles darf");
            s.sendMessage("§f/ncw sync §7- Vererbung herstellen (default -> alle, Kategorie nach Gewicht)");
            s.sendMessage("§f/ncw defaults §7- Grund-Befehle setzen (home, rtp, ...)");
            s.sendMessage("§f/ncw enable|disable §7- Whitelist an/aus");
            s.sendMessage("§f/ncw reload §7- Daten neu laden");
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!canUse(s)) {
            msg(s, "§cDazu hast du keine Berechtigung.");
            return true;
        }
        String sub = a.length == 0 ? "gui" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(s);
            case "gui", "menu" -> {
                if (s instanceof Player p) new Menu(this, p).openGroups(0);
                else msg(s, "§cDas Menue gibt es nur im Spiel.");
            }
            case "list" -> {
                msg(s, "§7Raenge:");
                for (Group g : sortedGroups()) {
                    if (!canManage(s, g.getName())) continue;
                    Category cat = categoryOf(g.getName());
                    s.sendMessage(" §f" + g.getName() + " §8| §7" + (cat == null ? "keine Kategorie" : cat.label)
                            + " §8| §7" + allowedOf(g.getName()).size() + " Eintraege");
                }
                msg(s, "§7Whitelist: " + (enabled ? "§aAN" : "§cAUS")
                        + " §8| §7Owner: §f" + (ownerGroup.isEmpty() ? "-" : ownerGroup));
                for (Category cat : Category.values())
                    s.sendMessage(" §7" + cat.label + ": §f" + managers.getOrDefault(cat, "-"));
            }
            case "info" -> {
                if (a.length < 2 || !groupExists(s, a[1])) return true;
                if (!canManage(s, a[1])) {
                    msg(s, "§cDiesen Rang darfst du nicht verwalten.");
                    return true;
                }
                Category cat = categoryOf(a[1]);
                msg(s, "§f" + a[1] + " §8| §7" + (cat == null ? "keine Kategorie" : cat.label));
                Set<String> set = allowedOf(a[1]);
                s.sendMessage(" §7Befehle: §f" + (set.isEmpty() ? "-" : String.join(", ", set)));
            }
            case "allow", "deny" -> {
                if (a.length < 3 || !groupExists(s, a[1])) {
                    msg(s, "§7Nutzung: /ncw " + sub + " <rang> <befehl|*>");
                    return true;
                }
                if (!canManage(s, a[1])) {
                    msg(s, "§cDiesen Rang darfst du nicht verwalten.");
                    return true;
                }
                String cmdName = a[2].toLowerCase(Locale.ROOT);
                if (cmdName.startsWith("/")) cmdName = cmdName.substring(1);
                Set<String> set = allowedOf(a[1]);
                if (sub.equals("allow")) set.add(cmdName);
                else set.remove(cmdName);
                saveData();
                msg(s, "§f/" + cmdName + " §7fuer §f" + a[1] + (sub.equals("allow") ? " §afreigegeben" : " §cgesperrt"));
            }
            case "perm" -> {
                if (a.length < 4 || !groupExists(s, a[1])) {
                    msg(s, "§7Nutzung: /ncw perm <rang> <permission> <true|false|unset>");
                    return true;
                }
                if (!canManage(s, a[1])) {
                    msg(s, "§cDiesen Rang darfst du nicht verwalten.");
                    return true;
                }
                Boolean v = switch (a[3].toLowerCase(Locale.ROOT)) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    case "unset" -> null;
                    default -> {
                        msg(s, "§cWert muss true, false oder unset sein.");
                        yield Boolean.FALSE;
                    }
                };
                if (!a[3].equalsIgnoreCase("true") && !a[3].equalsIgnoreCase("false") && !a[3].equalsIgnoreCase("unset"))
                    return true;
                setPerm(a[1], a[2], v);
                msg(s, "§7Recht §f" + a[2] + " §7fuer §f" + a[1] + " §7-> §f" + (v == null ? "entfernt" : v));
            }
            case "category" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen Kategorien aendern.");
                    return true;
                }
                if (a.length < 3 || !groupExists(s, a[1])) {
                    msg(s, "§7Nutzung: /ncw category <rang> <staff|buy|other|none>");
                    return true;
                }
                if (a[2].equalsIgnoreCase("none")) {
                    setCategory(a[1], null);
                    msg(s, "§f" + a[1] + " §7hat jetzt keine Kategorie.");
                } else {
                    Category cat = Category.parse(a[2]);
                    if (cat == null) {
                        msg(s, "§cKategorie: staff, buy, other oder none.");
                        return true;
                    }
                    setCategory(a[1], cat);
                    msg(s, "§f" + a[1] + " §7ist jetzt in §f" + cat.label);
                }
            }
            case "manager" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                if (a.length < 3) {
                    msg(s, "§7Nutzung: /ncw manager <staff|buy|other> <rang|none>");
                    return true;
                }
                Category cat = Category.parse(a[1]);
                if (cat == null) {
                    msg(s, "§cKategorie: staff, buy oder other.");
                    return true;
                }
                if (a[2].equalsIgnoreCase("none")) managers.remove(cat);
                else {
                    if (!groupExists(s, a[2])) return true;
                    managers.put(cat, a[2].toLowerCase(Locale.ROOT));
                }
                saveData();
                msg(s, "§7Oberster Rang fuer §f" + cat.label + "§7: §f" + managers.getOrDefault(cat, "-"));
            }
            case "owner" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                if (a.length < 2) {
                    msg(s, "§7Nutzung: /ncw owner <rang|none>");
                    return true;
                }
                if (a[1].equalsIgnoreCase("none")) ownerGroup = "";
                else {
                    if (!groupExists(s, a[1])) return true;
                    ownerGroup = a[1].toLowerCase(Locale.ROOT);
                }
                saveData();
                msg(s, "§7Owner-Rang: §f" + (ownerGroup.isEmpty() ? "-" : ownerGroup));
            }
            case "enable", "disable" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                enabled = sub.equals("enable");
                saveData();
                msg(s, "§7Whitelist ist jetzt " + (enabled ? "§aAN" : "§cAUS"));
                if (enabled) msg(s, "§7Tipp: Gib dem Rang 'default' z.B. /spawn, /msg usw., sonst kann niemand etwas tippen.");
            }
            case "sync" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                int n = syncInheritance();
                msg(s, "§7Vererbung abgeglichen (§f" + n + " §7neue Verknuepfungen).");
                msg(s, "§7Tipp: Staff-Raenge brauchen ein Gewicht, z.B. /lp group owner setweight 100");
            }
            case "defaults" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                applyDefaults();
                msg(s, "§7Grund-Befehle (home, sethome, delhome, rtp, spawn, msg, tpa ...) fuer §fdefault §7gesetzt.");
            }
            case "reload" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                loadData();
                msg(s, "§7Daten neu geladen.");
            }
            default -> help(s);
        }
        return true;
    }

    List<Group> sortedGroups() {
        List<Group> list = new ArrayList<>(lp.getGroupManager().getLoadedGroups());
        list.sort(Comparator.<Group>comparingInt(g -> g.getWeight().orElse(0)).reversed()
                .thenComparing(Group::getName));
        return list;
    }

    // ------------------------------------------------------------ Tab-Completion

    private List<String> filter(Collection<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).limit(80).collect(Collectors.toList());
    }

    private List<String> groupNames() {
        return lp.getGroupManager().getLoadedGroups().stream().map(Group::getName).sorted().collect(Collectors.toList());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (!canUse(s)) return Collections.emptyList();
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("gui", "help", "list", "info", "allow", "deny", "perm"));
            if (isAdmin(s)) subs.addAll(List.of("category", "manager", "owner", "sync", "defaults", "enable", "disable", "reload"));
            return filter(subs, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            return switch (sub) {
                case "info", "allow", "deny", "perm", "category" -> filter(groupNames(), a[1]);
                case "manager" -> filter(List.of("staff", "buy", "other"), a[1]);
                case "owner" -> {
                    List<String> o = new ArrayList<>(groupNames());
                    o.add("none");
                    yield filter(o, a[1]);
                }
                default -> Collections.emptyList();
            };
        }
        if (a.length == 3) {
            switch (sub) {
                case "allow" -> {
                    List<String> o = new ArrayList<>(allCommands().keySet());
                    o.add("*");
                    return filter(o, a[2]);
                }
                case "deny" -> {
                    return filter(allowedOf(a[1]), a[2]);
                }
                case "perm" -> {
                    return filter(allPermissions(), a[2]);
                }
                case "category" -> {
                    return filter(List.of("staff", "buy", "other", "none"), a[2]);
                }
                case "manager" -> {
                    List<String> o = new ArrayList<>(groupNames());
                    o.add("none");
                    return filter(o, a[2]);
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        if (a.length == 4 && sub.equals("perm")) return filter(List.of("true", "false", "unset"), a[3]);
        return Collections.emptyList();
    }

    // ============================================================ GUI

    /** Das komplette GUI: Raenge -> Rang -> Befehle / Berechtigungen. */
    static final class Menu implements InventoryHolder {

        private static final int PAGE_SIZE = 45;

        private final NcwPlugin plugin;
        private final Player viewer;
        private Inventory inv;
        private final Map<Integer, Runnable> handlers = new HashMap<>();
        /** Aktueller Suchbegriff (leer = keine Suche). */
        private String search = "";

        Menu(NcwPlugin plugin, Player viewer) {
            this.plugin = plugin;
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        void click(int slot) {
            Runnable r = handlers.get(slot);
            if (r != null) r.run();
        }

        // ------------------------------------------------------------ Helfer

        private static Component text(String s, NamedTextColor color) {
            return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
        }

        private static ItemStack item(Material m, String name, NamedTextColor color, String... lore) {
            ItemStack it = new ItemStack(m);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(text(name, color));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(text(s, NamedTextColor.GRAY));
            meta.lore(l);
            it.setItemMeta(meta);
            return it;
        }

        private void reset(int size, String title) {
            handlers.clear();
            inv = Bukkit.createInventory(this, size, Component.text(title));
        }

        private void set(int slot, ItemStack it, Runnable onClick) {
            inv.setItem(slot, it);
            if (onClick != null) handlers.put(slot, onClick);
        }

        private boolean stillAllowed(String group) {
            if (plugin.canManage(viewer, group)) return true;
            viewer.sendMessage("§8[§bNCW§8] §cDiesen Rang darfst du nicht verwalten.");
            viewer.closeInventory();
            return false;
        }

        private void show() {
            viewer.openInventory(inv);
        }

        /** Eine Seite mit Eintraegen plus Navigationsleiste. */
        private void paged(String title, List<ItemStack> items, List<Runnable> actions, int page,
                           java.util.function.IntConsumer reopen, Runnable back,
                           ItemStack extra, Runnable extraAction) {
            paged(title, items, actions, page, reopen, back, extra, extraAction, false);
        }

        private void paged(String title, List<ItemStack> items, List<Runnable> actions, int page,
                           java.util.function.IntConsumer reopen, Runnable back,
                           ItemStack extra, Runnable extraAction, boolean searchable) {
            int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int p = Math.min(Math.max(page, 0), pages - 1);
            reset(54, title + " §8(" + (p + 1) + "/" + pages + ")");
            int start = p * PAGE_SIZE;
            for (int i = 0; i < PAGE_SIZE && start + i < items.size(); i++) {
                set(i, items.get(start + i), actions.get(start + i));
            }
            ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
            for (int i = 45; i < 54; i++) set(i, filler, null);
            if (p > 0) set(45, item(Material.ARROW, "Vorherige Seite", NamedTextColor.YELLOW), () -> reopen.accept(p - 1));
            if (back != null) set(49, item(Material.BARRIER, "Zurueck", NamedTextColor.RED), back);
            if (p < pages - 1) set(53, item(Material.ARROW, "Naechste Seite", NamedTextColor.YELLOW), () -> reopen.accept(p + 1));
            if (extra != null) set(47, extra, extraAction);
            if (searchable) {
                set(51, item(Material.COMPASS, search.isEmpty() ? "Suchen" : "Suche: " + search, NamedTextColor.AQUA,
                        "Klicken, dann Begriff in den Chat schreiben",
                        "z.B. essentials oder home",
                        search.isEmpty() ? "" : "Im Chat '-' schreiben, um die Suche zu loeschen"), () -> {
                    viewer.closeInventory();
                    viewer.sendMessage("§8[§bNCW§8] §7Schreibe den Suchbegriff in den Chat §8(§f-§8 = Suche loeschen, §fabbrechen§8 = nichts aendern)§7:");
                    plugin.awaitInput(viewer, text -> {
                        if (text.equalsIgnoreCase("abbrechen")) {
                            // Suche unveraendert lassen
                        } else if (text.equals("-")) {
                            search = "";
                        } else {
                            search = text.toLowerCase(Locale.ROOT);
                        }
                        reopen.accept(0);
                    });
                });
            }
            show();
        }

        private boolean matches(String... fields) {
            if (search.isEmpty()) return true;
            for (String f : fields) if (f != null && f.toLowerCase(Locale.ROOT).contains(search)) return true;
            return false;
        }

        // ------------------------------------------------------------ Rang-Liste

        void openGroups(int page) {
            List<ItemStack> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (Group g : plugin.sortedGroups()) {
                String name = g.getName();
                if (!plugin.canManage(viewer, name)) continue;
                NcwPlugin.Category cat = plugin.categoryOf(name);
                Material mat = cat == null ? Material.PAPER : cat.icon;
                items.add(item(mat, name, NamedTextColor.AQUA,
                        "Kategorie: " + (cat == null ? "keine" : cat.label),
                        "Freigaben: " + (plugin.hasAll(name) ? "ALLE" : String.valueOf(plugin.allowedOf(name).size())),
                        "Klicken zum Bearbeiten"));
                actions.add(() -> openGroup(name));
            }
            paged("NCW - Raenge", items, actions, page, this::openGroups, null, null, null);
        }

        // ------------------------------------------------------------ Einzelner Rang

        void openGroup(String group) {
            if (!stillAllowed(group)) return;
            reset(27, "NCW - " + group);
            NcwPlugin.Category cat = plugin.categoryOf(group);

            set(10, item(Material.COMMAND_BLOCK, "Befehle", NamedTextColor.GREEN,
                    "Befehle aller Plugins", "freigeben oder sperren"), () -> {
                search = "";
                openCommands(group, 0, 0);
            });
            set(12, item(Material.WRITABLE_BOOK, "Berechtigungen", NamedTextColor.GOLD,
                    "LuckPerms-Rechte aller Plugins", "setzen oder entfernen"), () -> {
                search = "";
                openPerms(group, 0, 0);
            });

            boolean all = plugin.hasAll(group);
            set(14, item(all ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                    "Alle Befehle: " + (all ? "AN" : "AUS"), all ? NamedTextColor.GREEN : NamedTextColor.RED,
                    "Klicken zum Umschalten"), () -> {
                if (!stillAllowed(group)) return;
                plugin.toggleCommand(group, "*");
                openGroup(group);
            });

            boolean admin = plugin.isAdmin(viewer);
            set(16, item(cat == null ? Material.PAPER : cat.icon,
                    "Kategorie: " + (cat == null ? "keine" : cat.label), NamedTextColor.LIGHT_PURPLE,
                    admin ? "Klicken zum Wechseln" : "Nur Admins koennen das aendern"), admin ? () -> {
                NcwPlugin.Category[] all2 = NcwPlugin.Category.values();
                NcwPlugin.Category next;
                if (cat == null) next = all2[0];
                else if (cat.ordinal() + 1 < all2.length) next = all2[cat.ordinal() + 1];
                else next = null;
                plugin.setCategory(group, next);
                openGroup(group);
            } : null);

            set(22, item(Material.BARRIER, "Zurueck", NamedTextColor.RED), () -> openGroups(0));
            show();
        }

        // ------------------------------------------------------------ Befehle

        private void openCommands(String group, int page, int filterIdx) {
            if (!stillAllowed(group)) return;
            Map<String, String> cmds = new TreeMap<>(plugin.allCommands());
            // eigene Eintraege, die es (noch) nicht als Befehl gibt
            for (String s : plugin.allowedOf(group)) if (!s.equals("*")) cmds.putIfAbsent(s, "Eigener Eintrag");

            List<String> owners = new ArrayList<>(new TreeSet<>(cmds.values()));
            owners.add(0, "Alle Plugins");
            int fi = Math.floorMod(filterIdx, owners.size());
            String filter = owners.get(fi);

            List<ItemStack> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            Set<String> set = plugin.allowedOf(group);
            boolean all = set.contains("*");
            List<String> sources = plugin.sourcesOf(group);
            for (Map.Entry<String, String> e : cmds.entrySet()) {
                if (fi != 0 && !e.getValue().equals(filter)) continue;
                String cmd = e.getKey();
                if (!matches(cmd, e.getValue())) continue;
                boolean own = all || set.contains(cmd);
                String from = own ? null : plugin.inheritedFrom(group, sources, cmd);
                boolean on = own || from != null;
                if (from != null) {
                    items.add(item(Material.GREEN_DYE, "/" + cmd, NamedTextColor.DARK_GREEN,
                            "Plugin: " + e.getValue(), "Geerbt von: " + from,
                            "Dort aendern, nicht hier"));
                    actions.add(() -> viewer.sendMessage("§8[§bNCW§8] §7/" + cmd + " kommt von §f" + from
                            + "§7. Aendere es dort, dann gilt es automatisch ueberall."));
                } else {
                    items.add(item(on ? Material.LIME_DYE : Material.GRAY_DYE, "/" + cmd,
                            on ? NamedTextColor.GREEN : NamedTextColor.RED,
                            "Plugin: " + e.getValue(), on ? "Freigegeben" : "Gesperrt", "Klicken zum Umschalten"));
                    actions.add(() -> {
                        if (!stillAllowed(group)) return;
                        plugin.toggleCommand(group, cmd);
                        openCommands(group, page, fi);
                    });
                }
            }
            ItemStack f = item(Material.HOPPER, "Filter: " + filter, NamedTextColor.YELLOW, "Klicken fuer naechstes Plugin");
            paged("NCW - Befehle " + group, items, actions, page,
                    pg -> openCommands(group, pg, fi), () -> openGroup(group),
                    f, () -> openCommands(group, 0, fi + 1), true);
        }

        // ------------------------------------------------------------ Berechtigungen

        private void openPerms(String group, int page, int filterIdx) {
            if (!stillAllowed(group)) return;
            List<String> perms = plugin.allPermissions();
            TreeSet<String> prefixes = new TreeSet<>();
            for (String p : perms) {
                int i = p.indexOf('.');
                prefixes.add(i > 0 ? p.substring(0, i) : p);
            }
            List<String> filters = new ArrayList<>(prefixes);
            filters.add(0, "Alle");
            int fi = Math.floorMod(filterIdx, filters.size());
            String filter = filters.get(fi);

            List<ItemStack> items = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            Set<String> own = plugin.directPerms(group);
            Map<String, Set<String>> parentPerms = new LinkedHashMap<>();
            for (String src : plugin.sourcesOf(group)) {
                if (!src.equalsIgnoreCase(group)) parentPerms.put(src, plugin.directPerms(src));
            }
            for (String node : perms) {
                if (fi != 0) {
                    int i = node.indexOf('.');
                    String pre = i > 0 ? node.substring(0, i) : node;
                    if (!pre.equals(filter)) continue;
                }
                if (!matches(node)) continue;
                String from = null;
                if (!own.contains(node)) {
                    for (Map.Entry<String, Set<String>> pe : parentPerms.entrySet()) {
                        if (pe.getValue().contains(node)) {
                            from = pe.getKey();
                            break;
                        }
                    }
                }
                if (from != null) {
                    String src = from;
                    items.add(item(Material.GREEN_DYE, node, NamedTextColor.DARK_GREEN,
                            "Geerbt von: " + src, "Dort aendern, nicht hier"));
                    actions.add(() -> viewer.sendMessage("§8[§bNCW§8] §7" + node + " kommt von §f" + src
                            + "§7. Aendere es dort, dann gilt es automatisch ueberall."));
                } else {
                    boolean on = own.contains(node);
                    items.add(item(on ? Material.LIME_DYE : Material.GRAY_DYE, node,
                            on ? NamedTextColor.GREEN : NamedTextColor.RED,
                            on ? "Gesetzt (true)" : "Nicht gesetzt", "Klicken zum Umschalten"));
                    actions.add(() -> {
                        if (!stillAllowed(group)) return;
                        plugin.togglePerm(group, node);
                        openPerms(group, page, fi);
                    });
                }
            }
            ItemStack f = item(Material.HOPPER, "Filter: " + filter, NamedTextColor.YELLOW, "Klicken fuer naechsten Bereich");
            paged("NCW - Rechte " + group, items, actions, page,
                    pg -> openPerms(group, pg, fi), () -> openGroup(group),
                    f, () -> openPerms(group, 0, fi + 1), true);
        }
    }
}
