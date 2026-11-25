package com.pixy.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Registry + spawning. There is NO bot limit: every /pixybot spawn creates one more bot with its own random UUID.
 * Identity: /pixybot spawn (and /pixybot random) hand out the next free identity from the 50 in
 * config/pixybot-identities.json - a real-looking name (no numbers) plus that identity's own skin. Only when all
 * identities are in use is one reused. Existing bots are never touched by a new spawn.
 */
public final class BotManager {
    private static final Map<UUID, PixyBotPlayer> BOTS = new ConcurrentHashMap<>();
    /** lower-case name -> bots being created right now (skin lookup in flight) */
    private static final Map<String, Integer> PENDING_USE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_SKINS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong SPAWN_INDEX = new AtomicLong();
    private static final AtomicInteger FALLBACK = new AtomicInteger(1);

    /** Everything copied from the player at the moment of the command. */
    public record Rot(float yaw, float headYaw, float bodyYaw, float pitch) { }

    private BotManager() { }

    public static boolean isBot(Player p) { return p instanceof PixyBotPlayer; }
    public static Collection<PixyBotPlayer> all() { return BOTS.values(); }
    public static void unregister(PixyBotPlayer b) { BOTS.remove(b.getUUID()); }

    /** First living bot with this name (names may repeat after the first 50). */
    public static PixyBotPlayer byName(String name) {
        for (PixyBotPlayer b : BOTS.values())
            if (b.getGameProfile().name().equalsIgnoreCase(name)) return b;
        return null;
    }

    private static Set<String> skinsInUse() {
        Set<String> s = new HashSet<>(PENDING_SKINS);
        for (PixyBotPlayer b : BOTS.values())
            if (b.skinSource() != null) s.add(b.skinSource().toLowerCase(Locale.ROOT));
        return s;
    }

    /**
     * Spawn at exactly pos with exactly the rotations in rot.
     * name == null  : next free identity (name + its skin).
     * name given    : that name (must be unused); skin = the identity's skin if it is one of the 50, else the skin of
     *                 a free identity. skinName overrides; "default" = plain default skin.
     * A new bot always starts passive (waits for a NEW horn).
     */
    public static void spawn(MinecraftServer server, ServerLevel level, Vec3 pos, Rot rot,
                             String name, String skinName, Consumer<Component> feedback) {

        String finalName;
        String skinReq;
        boolean explicitSkin = skinName != null && !skinName.isBlank();

        if (name == null) {
            IdentityEntry id = IdentityPool.pick(server, PENDING_USE);
            if (id == null) { finalName = "Bot" + FALLBACK.getAndIncrement(); skinReq = null; }
            else { finalName = id.name; skinReq = id.skin; }
        } else {
            String k = name.toLowerCase(Locale.ROOT);
            var online = server.getPlayerList().getPlayerByName(name);
            if (byName(name) != null || PENDING_USE.containsKey(k) || (online != null && !isBot(online))) {
                feedback.accept(Component.literal("The name " + name + " is already in use."));
                return;
            }
            finalName = name;
            IdentityEntry id = IdentityPool.find(name);
            if (id != null) skinReq = id.skin;
            else {
                IdentityEntry free = IdentityPool.pick(server, PENDING_USE);
                skinReq = free != null ? free.skin : null;
            }
        }
        if (explicitSkin) skinReq = skinName.equalsIgnoreCase("default") ? null : skinName;

        final String botName = finalName;
        PENDING_USE.merge(botName.toLowerCase(Locale.ROOT), 1, Integer::sum);
        UUID uuid = UUID.randomUUID();   // fresh identity per bot: no UUID clashes, no old player-data restored

        if (skinReq == null) {
            server.execute(() -> doSpawn(server, level, uuid, botName, null, null, pos, rot, feedback));
            return;
        }
        PENDING_SKINS.add(skinReq.toLowerCase(Locale.ROOT));
        resolveSkin(server, skinReq, explicitSkin, 3, feedback,
            (usedSkin, props) -> doSpawn(server, level, uuid, botName, usedSkin, props, pos, rot, feedback));
    }

    /**
     * Looks the skin up. If an identity's own skin cannot be fetched, a backup skin nobody wears is tried (max 3
     * times) so the first 50 bots still look different. An explicit skin that fails falls back to the default skin.
     */
    private static void resolveSkin(MinecraftServer server, String skin, boolean explicit, int retries,
                                    Consumer<Component> feedback, BiConsumer<String, PropertyMap> done) {
        SkinPool.lookup(server, skin).whenComplete((opt, err) -> server.execute(() -> {
            if (opt != null && opt.isPresent()) { done.accept(skin, opt.get()); return; }
            SkinPool.markFailed(skin);
            PENDING_SKINS.remove(skin.toLowerCase(Locale.ROOT));
            if (!explicit && retries > 0) {
                String other = IdentityPool.backupSkin(skinsInUse());
                if (other != null && !other.equalsIgnoreCase(skin)) {
                    PENDING_SKINS.add(other.toLowerCase(Locale.ROOT));
                    resolveSkin(server, other, false, retries - 1, feedback, done);
                    return;
                }
            }
            if (explicit) feedback.accept(Component.literal("Skin '" + skin + "' not found, using default skin."));
            else PixyBotMod.LOGGER.warn("Skin '{}' could not be fetched; bot uses the default skin.", skin);
            done.accept(null, null);
        }));
    }

    private static void doSpawn(MinecraftServer server, ServerLevel level, UUID uuid, String name,
                                String skinUsed, PropertyMap props, Vec3 pos, Rot rot,
                                Consumer<Component> feedback) {
        String key = name.toLowerCase(Locale.ROOT);
        try {
            GameProfile profile = props != null ? new GameProfile(uuid, name, props) : new GameProfile(uuid, name);
            long seed = SPAWN_INDEX.incrementAndGet() * 0x9E3779B97F4A7C15L ^ name.hashCode();

            PixyBotPlayer bot = new PixyBotPlayer(server, level, profile, rot, seed);
            bot.snapTo(pos.x, pos.y, pos.z, rot.yaw(), rot.pitch());
            bot.applySpawnRotation();

            server.getPlayerList().placeNewPlayer(new FakeConnection(), bot,
                    CommonListenerCookie.createInitial(profile, false));

            // placeNewPlayer positions the player itself (world spawn / saved data) and gives it the server's
            // default game mode -> force Survival, our position and our rotations again, and keep re-applying
            // the rotation for a few ticks so nothing during the join can turn the bot.
            bot.setGameMode(GameType.SURVIVAL);
            bot.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), rot.yaw(), rot.pitch(), false);
            bot.applySpawnRotation();
            bot.lockRotation(5);

            bot.markSpawned(HunterMode.hornCount(), skinUsed);   // passive until a horn is blown AFTER this moment
            BOTS.put(bot.getUUID(), bot);
            feedback.accept(Component.literal(String.format(Locale.ROOT,
                "Spawned bot %s (skin: %s) at (%.3f, %.3f, %.3f) yaw=%.2f head=%.2f body=%.2f pitch=%.2f",
                name, skinUsed == null ? "default" : skinUsed, bot.getX(), bot.getY(), bot.getZ(),
                bot.getYRot(), bot.getYHeadRot(), bot.yBodyRot, bot.getXRot())));
        } finally {
            PENDING_USE.merge(key, -1, Integer::sum);
            PENDING_USE.remove(key, 0);
            if (skinUsed != null) PENDING_SKINS.remove(skinUsed.toLowerCase(Locale.ROOT));
        }
    }

    /** Removes every living bot with this name. @return how many were removed */
    public static int remove(String name) {
        int n = 0;
        for (PixyBotPlayer b : new ArrayList<>(BOTS.values()))
            if (b.getGameProfile().name().equalsIgnoreCase(name)) { b.scheduleRemoval(); n++; }
        return n;
    }

    public static int removeAll() {
        int n = BOTS.size();
        for (PixyBotPlayer b : new ArrayList<>(BOTS.values())) b.scheduleRemoval();
        return n;
    }

    /** Immediate removal (used while the server is shutting down). */
    public static int removeAllNow() {
        int n = BOTS.size();
        for (PixyBotPlayer b : new ArrayList<>(BOTS.values())) b.removeNow();
        return n;
    }
}
