package com.pixy.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Settings, saved to config/pixybot.json (missing keys keep these defaults). */
public final class PixyConfig {
    public boolean hunterEnabled = true;     // master switch for Hunter Mode
    public boolean hornActivation = true;    // any goat horn arms the waiting bots
    public boolean pickupEnabled = true;     // bots walk to thrown items
    public double pickupRadius = 6.0;        // blocks
    public boolean autoEquip = true;
    public boolean autoWeapon = true;
    public boolean friendlyFire = false;     // bots hurting bots (always false by default)
    public int targetScanTicks = 20;         // how often a target-less bot looks for a player
    public int repathTicks = 10;             // how often a hunting bot may recompute its path
    public double eatHealth = 8.0;           // eat golden apple at or below this health (4 hearts)

    // ---- Stage 3
    public boolean criticals = true;         // time attacks for critical hits
    public boolean useShield = true;         // shield is OPTIONAL: false = never pick up / raise shields
    public boolean lootBeforeCombat = true;  // grab useful items nearby before engaging
    public double lootRadiusInCombat = 4.0;  // max loot distance while a target exists
    public int pathBudgetPerTick = 4;        // max path searches, all bots together, per server tick

    // ---- Stage 4
    public boolean idleMovement = true;      // natural per-bot idle movement while not fighting
    public double idleLeash = 4.0;           // max blocks a bot wanders from its spawn point
    public boolean useEnderPearls = true;
    public boolean useWindCharges = true;
    public boolean useBridging = true;       // bridge gaps / pillar up with blocks the bot owns

    public static PixyConfig I = new PixyConfig();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("pixybot.json"); }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                PixyConfig c = GSON.fromJson(Files.readString(f), PixyConfig.class);
                if (c != null) I = c;
            }
        } catch (Exception e) {
            PixyBotMod.LOGGER.error("Could not read pixybot.json, using defaults", e);
        }
        if (I.targetScanTicks < 1) I.targetScanTicks = 20;
        if (I.repathTicks < 1) I.repathTicks = 10;
        if (I.pathBudgetPerTick < 1) I.pathBudgetPerTick = 4;
        if (I.idleLeash < 2.0) I.idleLeash = 4.0;
        save();
    }

    public static void save() {
        try { Files.writeString(file(), GSON.toJson(I)); }
        catch (Exception e) { PixyBotMod.LOGGER.error("Could not save pixybot.json", e); }
    }
}
