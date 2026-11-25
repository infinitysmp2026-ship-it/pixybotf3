package com.pixy.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** The 50 identities (name + skin) from config/pixybot-identities.json, created from the built-in defaults. */
public final class IdentityPool {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Random RNG = new Random();

    private static List<IdentityEntry> identities = DefaultIdentities.list();
    private static List<String> backups = DefaultIdentities.backups();

    /** JSON shape of the file. */
    private static final class Data {
        List<IdentityEntry> identities;
        List<String> backupSkins;
    }

    private IdentityPool() { }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("pixybot-identities.json"); }

    public static List<IdentityEntry> all() { return identities; }
    public static List<String> backupSkins() { return backups; }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                Data d = GSON.fromJson(Files.readString(f), Data.class);
                List<IdentityEntry> ok = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                if (d != null && d.identities != null) {
                    for (IdentityEntry e : d.identities) {
                        if (e == null || e.name == null || !VALID.matcher(e.name).matches()) continue;
                        if (!seen.add(e.name.toLowerCase(Locale.ROOT))) continue;   // names stay unique
                        ok.add(e);
                    }
                }
                if (!ok.isEmpty()) identities = ok;
                if (d != null && d.backupSkins != null) backups = new ArrayList<>(d.backupSkins);
            } else {
                Data d = new Data();
                d.identities = identities;
                d.backupSkins = backups;
                Files.writeString(f, GSON.toJson(d));
            }
        } catch (Exception e) {
            PixyBotMod.LOGGER.error("Could not read pixybot-identities.json, using built-in identities", e);
        }
    }

    public static IdentityEntry find(String name) {
        for (IdentityEntry e : identities) if (e.name.equalsIgnoreCase(name)) return e;
        return null;
    }

    /** Next identity: one nobody is using right now; only when all are taken is one reused. */
    public static IdentityEntry pick(MinecraftServer server, Map<String, Integer> pendingUse) {
        Map<String, Integer> use = new HashMap<>(pendingUse);
        for (PixyBotPlayer b : BotManager.all())
            if (b.isAlive()) use.merge(b.getGameProfile().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        Set<String> blocked = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (!BotManager.isBot(p)) blocked.add(p.getGameProfile().name().toLowerCase(Locale.ROOT));
        return IdentityPicker.pick(identities, use, blocked, RNG);
    }

    /** A backup skin that no living or pending bot wears (used when an identity's own skin cannot be fetched). */
    public static String backupSkin(Set<String> skinsInUse) {
        for (String s : backups) {
            String k = s.toLowerCase(Locale.ROOT);
            if (!skinsInUse.contains(k) && !SkinPool.recentlyFailed(s)) return s;
        }
        return null;
    }
}
