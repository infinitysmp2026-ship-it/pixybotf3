# Changelog

## 1.3.0 - Stage 5 (instant hunt, strategic pearls, mace, bridging)

### Horn -> chase: root causes found and fixed
Reading the Stage 4 code turned up THREE causes, not one:
1. **No path at all.** The path calculator is a never-spawned Zombie. Vanilla returns NO path for a mob that is not "on the
   ground", and that Zombie never was. Stage 3 hid this (a missing path fell back to walking straight at the player);
   Stage 4's new exploration logic read "no path" as "unreachable", declared the bot STRANDED for 5 s and froze it - so it
   only moved once the player was within ~6 blocks (the only case that still used a straight walk). The same false
   "stranded" is why ender pearls were thrown all the time. Fixed: the calculator is marked on-ground, and "no path
   object" now means "walk straight at the target" instead of "unreachable".
2. **Stale idle path.** The idle routine and the chase shared one route variable, so after the horn a bot could follow its
   old "walk home" route for up to 1.5 s. Fixed: arming a bot (and acquiring a new target) wipes all route/idle/loot state.
3. **Detours before the fight.** Loot-before-combat could send an armed bot to grab items while the player was already
   coming. Now only as a quick grab when the player is more than 12 blocks away (default loot radius 4).
- A partial route (what vanilla mobs follow toward a far target) is now walked and re-planned every 0.5 s instead of
  counting as "unreachable"; only a partial route that does NOT get closer triggers exploration. "Stranded" no longer
  freezes the bot: it keeps moving toward the closest reachable point and re-explores every 3 s.
- No artificial delays were added anywhere; the only waits left are the 1-3 tick reaction time between a full attack
  cooldown and the swing.

### Ender pearls (PearlPolicy)
Only for: a target that is running away (gap +6 blocks over 5 s and now 22+ blocks; 35+ for the last pearl), a bot that is
losing (7-10 hp, enemy within 5 blocks, nothing to heal with), a bot that is repeatedly trapped with no blocks/wind
charge, or a target on unreachable high ground 5+ blocks up. Never in the first 5 s of a hunt (except trapped), never
within 15 s of the previous pearl, never at 6.5 hp or less (the teleport itself costs 5), and the simulated throw must land
within 4 blocks of the aim point on safe ground.

### Combat
- Mace: the smash only counts while falling 1.5+ blocks and the window is 1-2 ticks, so inside it the bot swings with no
  reaction delay and no line-of-sight requirement. The wind-charge + mace combo starts only when the cooldown is nearly
  recharged. A bot holding only a mace still fights with it.
- Normal hits are skipped while the player is in damage-immunity frames (several bots hitting the same player used to
  waste swings).

### Bridging / pillaring (BlockPlacer)
When walking fails (edge refusal / no route) and the bot owns plain blocks: sneak-bridge across a gap (max 16 blocks,
along the target's dominant axis, stops at natural ground, cannot fall off its own bridge) or pillar up with jump-and-place
(max 12 blocks) toward a target 3+ blocks higher. Blocks come from the bot's inventory; bots pick up wanted blocks
(cobblestone, stone, dirt, planks, wool, concrete, ... up to 64). `/pixybot bridging on|off`.

### Unchanged
Idle routine, identities, skins, spawning, horn arming, shield timing, healing - untouched apart from the reset above.

## 1.2.0 - Stage 4 (identities, realistic PvP, smart items, natural idle)

### Identities and skins
- 50 built-in identities (config/pixybot-identities.json): real-looking names, no numbers (PixiePlays, WardenPlays,
  Xyros, Cyrus, Zayox, Sylphie, Rudeus, Aether, Nyx, Kaizen, Vex, Draco, Zephyr, Orion, Axel, Kairo, Frost, Blaze, Echo,
  Nova + 30 more), each paired with its own skin source. `/pixybot spawn` and `/pixybot random` give the next FREE
  identity, so the first 50 living bots are all different; bots 51+ reuse identities (no numbering).
- Skins are public skins of 50 different Minecraft accounts, fetched from Mojang at runtime (cached, warmed in the
  background at server start). A failed lookup falls back to an unused backup skin, then the default skin.
  `/pixybot identities check` tests all 50 and lists the ones that do not resolve.
- Names may now repeat after 50, so `/pixybot remove <name>` removes every bot with that name.

### Navigation
- A straight walk is used only when every step is verified walkable (floor, headroom, drop, lava).
- Paths are committed and re-planned only when the target really moved or the path ended (no flip-flopping).
- No complete route (pit, wall, ledge): waypoints on rings around the bot are tried best-first with real path searches
  (shared per-tick budget); the first reachable one that gets clearly closer wins, failed ones are remembered.
  Nothing helps -> "stranded": the bot stops pushing at walls, tries wind charge / ender pearl, and retries every 5 s.
- Light separation so bots do not walk into each other.

### Combat
- Critical hits follow the real rules (falling, not sprinting, cooldown full); works unarmed too.
- Shield: raised only when a hit is coming (enemy near, facing it, its cooldown ready / using an item), for a short
  random time, lowered before the bot attacks, never held permanently. Axes are chosen to disable enemy shields.
- Weapon choice: sword by default, axe vs shields, mace only for wind-charge smash attacks, spear as fallback.
- Healing: golden apple only when hurt and not already absorbing; the bot backs off first if the enemy is on top of it
  (unless emergency / enemy busy).
- Ender pearl: only for very far / unreachable / trapped / roof situations, and only when the simulated throw lands
  within 4 blocks of the target on safe ground.
- Wind charge: jump + throw at own feet to climb ledges or leave a pit; wind charge + mace smash combo.

### Natural idle movement
- Every bot owns a Persona (seeded by spawn order + identity): own favourite actions, pause lengths, restlessness
  and first-action delay. Walk forward/right/left 2-4 blocks and return, look around, hop, wander near another idle
  bot (stops 2.5 blocks away), pause. Leashed to the spawn point. `/pixybot idle on|off`.
- Stops as soon as the bot has a target; resumes if it has had no target for 5 s.

### Commands added
`/pixybot identities [check]`, `/pixybot idle|pearls|windcharges on|off`.

## 1.1.0 - Stage 3
Per-bot horn state, Survival bots, loot-before-combat, cooldown-timed attacks, first critical-hit logic, edge-safe
navigation, skin pool.

## 1.0.0 - Stage 1 + 2
Spawning, exact pose copy, unlimited bots, horn Hunter Mode, melee combat, pickup + auto-equip, config.
