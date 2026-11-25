package com.pixy.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** The bot. A real ServerPlayer subclass, so it renders, fights and dies exactly like a player. */
public class PixyBotPlayer extends ServerPlayer implements Armable {

    private static final int DEATH_ANIMATION_TICKS = 30; // vanilla death animation is 20 ticks

    public final BotManager.Rot spawnRot;
    private final MinecraftServer botServer;
    private final BotBrain brain;

    // per-bot activation state (no global flag)
    private boolean hunting = false;
    private long spawnGeneration = 0;       // horn count when this bot was created
    private long activationGeneration = -1; // horn that armed it (-1 = never)

    private boolean removing = false;
    private boolean removed = false;
    private boolean warned = false;
    private int rotationLock = 0;
    private int deathCountdown = -1;
    private UUID followOwner;

    // identity + idle "home"
    private String skinSource = null;       // account whose skin this bot wears (null = default skin)
    private Vec3 home = null;               // where it was spawned: the idle routine returns here
    private float homeYaw, homePitch;

    public PixyBotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, BotManager.Rot rot, long seed) {
        super(server, level, profile, ClientInformation.createDefault());
        this.botServer = server;
        this.spawnRot = rot;
        this.brain = new BotBrain(this, seed);
    }

    public BotBrain brain() { return brain; }
    public MinecraftServer botServer() { return botServer; }

    // ------------------------------------------------------------- activation
    @Override public boolean isHunting() { return hunting; }

    @Override
    public void activate(long generation) {
        if (!isAlive()) return;
        hunting = true;
        activationGeneration = generation;
        brain.wake();
    }

    public void deactivate() { hunting = false; }
    void markSpawned(long generation, String skin) {
        spawnGeneration = generation; hunting = false; activationGeneration = -1;
        skinSource = skin;
        home = this.position();
        homeYaw = spawnRot.headYaw();
        homePitch = spawnRot.pitch();
    }

    public String skinSource() { return skinSource; }
    public Vec3 homePos() { return home; }
    public float homeYaw() { return homeYaw; }
    public float homePitch() { return homePitch; }
    public long spawnGeneration() { return spawnGeneration; }
    public long activationGeneration() { return activationGeneration; }

    // ------------------------------------------------------------- follow command state
    public boolean startFollowing(ServerPlayer player) {
        if (player == null || BotManager.isBot(player) || !player.isAlive() || player.level() != this.level()) return false;
        followOwner = player.getUUID();
        brain.onFollowChanged();
        return true;
    }

    public void stopFollowing() {
        if (followOwner == null) return;
        followOwner = null;
        brain.onFollowChanged();
    }

    public boolean isFollowing() { return followOwner != null; }

    /** Resolve the owner each tick so disconnects and dimension changes cannot leave a stale entity reference. */
    public ServerPlayer followTarget() {
        if (followOwner == null) return null;
        ServerPlayer player = botServer.getPlayerList().getPlayer(followOwner);
        if (player == null || !player.isAlive() || player.isRemoved()
                || player.isSpectator() || player.level() != this.level()) {
            stopFollowing();
            return null;
        }
        return player;
    }

    // ------------------------------------------------------------- spawn pose
    /**
     * Writes the player's rotations onto the bot: entity yaw, head yaw, body yaw and pitch, each from its own
     * value, plus the "previous tick" copies so nothing interpolates/swings from a stale angle.
     */
    void applySpawnRotation() {
        BotManager.Rot r = spawnRot;
        this.setYRot(r.yaw());
        this.setXRot(r.pitch());
        this.yRotO = r.yaw();
        this.xRotO = r.pitch();
        this.setYHeadRot(r.headYaw());
        this.yHeadRotO = r.headYaw();
        this.setYBodyRot(r.bodyYaw());
        this.yBodyRotO = r.bodyYaw();
    }

    void lockRotation(int ticks) { this.rotationLock = ticks; }

    // ------------------------------------------------------------- ticking
    /** Fake players have no connection ticking them, so we drive the tick ourselves. */
    @Override
    public void tick() {
        int now = botServer.getTickCount();
        if (now % 10 == 0) {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }
        // Bots are Survival players. Re-checked once a second so nothing can leave a bot in Creative.
        if (now % 20 == 0 && this.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
            this.setGameMode(GameType.SURVIVAL);
        }
        if (deathCountdown > 0 && --deathCountdown == 0) scheduleRemoval();

        if (rotationLock > 0) {
            rotationLock--;
            applySpawnRotation();          // hold the copied rotation; AI stays asleep for these first ticks
        } else {
            try {
                brain.tick();
            } catch (RuntimeException e) {
                if (!warned) { warned = true; PixyBotMod.LOGGER.error("Bot AI error (reported once)", e); }
            }
        }
        try {
            super.tick();
            this.doTick();
        } catch (NullPointerException ignored) { }
    }

    /** Vanilla death (drops, death message, death animation) runs in super.die; we remove the body afterwards. */
    @Override
    public void die(DamageSource source) {
        super.die(source);
        hunting = false;
        if (deathCountdown < 0) deathCountdown = DEATH_ANIMATION_TICKS;
    }

    // ------------------------------------------------------------- removal
    public void scheduleRemoval() {
        if (removing) return;
        removing = true;
        botServer.execute(this::removeNow0);
    }

    /** Immediate removal on the calling (server) thread. */
    public void removeNow() {
        removing = true;
        removeNow0();
    }

    private void removeNow0() {
        if (removed) return;
        removed = true;
        BotManager.unregister(this);
        botServer.getPlayerList().remove(this);
        deletePlayerData();
    }

    /** Bots use a fresh UUID each time, so their saved player data would only pile up. Best effort cleanup. */
    private void deletePlayerData() {
        try {
            Path dir = botServer.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Files.deleteIfExists(dir.resolve(this.getUUID() + ".dat"));
            Files.deleteIfExists(dir.resolve(this.getUUID() + ".dat_old"));
        } catch (Exception ignored) { }
    }
}
