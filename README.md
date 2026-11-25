# PIXY BOT PvP MOD (PP Bot) - v1.3.0 (Stage 5)

Fabric mod for Minecraft Java 1.21.11. Server-side: bots are real `ServerPlayer`s (same technique as
Carpet's /player), so they have the real player model, skin, name tag, armor rendering, vanilla combat
and vanilla death messages. No client mod needed.

## Build the JAR
Requirements: **JDK 21**, **Gradle 9.2+** (or use the included GitHub Action), internet access for the first build.
Dependencies (downloaded automatically): Minecraft 1.21.11 (Mojang mappings), Fabric Loader, Fabric API, Fabric Loom.

A. GitHub (easiest): push this folder to a repo -> Actions tab -> "build" -> download artifact `pixy-bot-mod`.
B. Local: `gradle build`   (no Gradle wrapper is included; install Gradle 9.2+ or run `gradle wrapper` once).

Output: `build/libs/pixy-bot-mod-1.3.0.jar`  (ignore the `-sources.jar`).
Install: put the JAR **and Fabric API** into `mods/`, run with a Fabric 1.21.11 profile, Java 21.

IMPORTANT: `gradle.properties` holds loader / Fabric API / Loom versions. If your build already worked with
different values from https://fabricmc.net/develop (1.21.11), keep yours.

## Commands (OP / permission level 2)
Spawning
- `/pixybot spawn`                          next free identity (name + skin) at your exact position and pose
- `/pixybot spawn <name> [skinUsername]`    custom name (skin: that identity's, or a free one)
- `/pixybot random [skinUsername]`         same as spawn (identity names; optional skin override)
- `/pixybot remove <name>`  `/pixybot removeall`  `/pixybot list`
- `/pixybot info <name>`   prints bot + your position/yaw/head/body/pitch to compare
Combat / items
- `/pixybot hunter on|off|status`  (on/off act on bots that exist now; new bots wait for the next horn)
- `/pixybot horn on|off`            any goat horn arms the waiting bots
- `/pixybot shield on|off`  `/pixybot crits on|off`  `/pixybot loot on|off`
- `/pixybot identities`            lists the 50 identities; `/pixybot identities check` tests their skins
- `/pixybot idle|pearls|windcharges|bridging on|off`
- `/pixybot pickup on|off`  `/pixybot pickup radius <1-32>`
- `/pixybot autoequip on|off`  `/pixybot autoweapon on|off`
- `/pixybot config reload`
Settings file: `config/pixybot.json`

## How it works
- Spawn: fake ServerPlayer + fake network connection. Position and the four rotation values (entity yaw,
  head yaw, body yaw, pitch) are copied separately from the player and re-applied after the join logic and
  for the first 5 ticks. Each bot gets a random UUID, so bots never clash and never reload old saved data.
- Hunter Mode: a real player using ANY goat horn (UseItemCallback, server side) arms every bot that exists at that
  moment. Each bot keeps its own state; bots spawned after a horn stay passive until the NEXT horn.
  Bots target the nearest survival real player, path with vanilla ground navigation, melee with attack
  cooldown, eat golden apples when hurt, retarget when the target dies. Bots never target or damage bots.
- Item pickup: bots walk to thrown items they want (radius configurable) and use vanilla item pickup
  (no duplication, enchantments/names/data preserved); armor/shield auto-equip, best weapon is selected.

## Known limits
- Not tested in a live game by the author (no Minecraft server in the build sandbox). Compile errors from
  1.21.11 API differences are possible; send them to get them fixed.
- Other players' clients derive a bot's BODY direction from its head/entity yaw (vanilla network protocol has
  no separate body-yaw field at spawn). The server stores all values separately; `/pixybot info` shows them.
- Bots are not saved across server restarts (they are removed on shutdown).
- With pickup disabled, bots stop walking to items, but vanilla can still pick up an item that lands on them.
- No kit GUI, idle look-at-player AI or persistence yet (not part of Stage 1/2).

## Self-test (no Minecraft needed)
`javac -d out src/main/java/com/pixy/bot/{Armable,HornLogic,IdentityEntry,DefaultIdentities,IdentityPicker,Persona,ProjectileAim,ExplorePlanner,PearlPolicy,BridgePlanner}.java tools/*.java && java -cp out Stage5Test && java -cp out Stage4Test && java -cp out HornLogicTest`

## Limits
- Nothing that needs Minecraft (combat, walking, pits, items, skins on screen, idle movement) could be run by the
  author. The compile/syntax check and the plain-Java logic tests above are all that was verified.
- Skins: Minecraft clients only accept skins hosted by Mojang, so original skin images cannot be bundled. The 50
  skins come from 50 real accounts' public skins, fetched live; the account names are a best guess - run
  `/pixybot identities check` and edit config/pixybot-identities.json for any that fail.
- Not implemented: TNT strategies, bows/crossbows/other projectiles, breaking blocks, potions, elytra. Bridging only uses blocks the bot already carries or picks up.
  Spear support is basic (treated as a normal melee weapon).
- Bots are not saved across restarts. Pit exits only by walking, wind charge or pearl (no digging/bridging).
