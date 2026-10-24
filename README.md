# Neptune Command Whitelist (NCW)

Paper-Plugin (Minecraft 26.x, Java 25) mit LuckPerms. Befehl: `/ncw` (Aliase `/nwc`, `/neptunewhitelist`).

## Bauen
Voraussetzung: JDK 25 und Maven.

    mvn package

Die Datei `target/NeptuneCommandWhitelist-1.0.0.jar` in den `plugins`-Ordner legen, Server neu starten.
Falls Maven die Paper-Version nicht findet: in `pom.xml` bei `<paper.version>` die Version anpassen
(z. B. `26.3.build.+` oder die Version von https://papermc.io/downloads).

## Erste Schritte
1. `/ncw owner <dein-top-rang>`: dieser Rang darf alles (Ops/`ncw.admin` immer).
2. `/ncw category <rang> staff|buy|other`: Raenge einsortieren (oder im Menue auf die Kategorie klicken).
3. `/ncw manager staff <rang>`, `/ncw manager buy <rang>`, `/ncw manager other <rang>`:
   der oberste Rang je Kategorie. Er darf alle Raenge seiner Kategorie bearbeiten.
4. `default` einige Befehle geben (`/ncw allow default spawn`), dann `/ncw enable`.

Wichtig: Die Whitelist ist beim ersten Start AUS, damit sich niemand aussperrt.

## Befehle
    /ncw gui
    /ncw list
    /ncw info <rang>
    /ncw allow <rang> <befehl|*>
    /ncw deny  <rang> <befehl|*>
    /ncw perm  <rang> <permission> <true|false|unset>
    /ncw category <rang> <staff|buy|other|none>
    /ncw manager  <staff|buy|other> <rang|none>
    /ncw owner <rang|none>
    /ncw enable | disable | reload

## Kategorien
- Staff-Mitglieder (Team)
- Buy-Ranks (kaufbare Raenge)
- Sonstige (Media, Friend, ...)

## Hinweis
Die Whitelist filtert Befehle (und blendet sie im Tab-Complete aus). Ob ein Befehl danach wirklich
funktioniert, entscheidet weiterhin die Berechtigung des jeweiligen Plugins. Dafuer gibt es im Menue
"Berechtigungen" bzw. `/ncw perm`, das jede registrierte Permission jedes Plugins in LuckPerms setzt.

## JAR ohne eigenen PC bauen (GitHub)
1. Auf github.com ein neues Repository anlegen.
2. Den Inhalt dieses Ordners hochladen (inkl. `.github/workflows/build.yml`).
3. Im Repository auf "Actions" gehen, den Lauf "Build JAR" abwarten.
4. Im fertigen Lauf unter "Artifacts" die JAR herunterladen (kommt als ZIP, darin liegt die .jar).
