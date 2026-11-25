package com.pixy.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Walking for one bot. The bot does not copy the player's direction; it decides HOW to get there:
 *   1. straight line - only if every step of it is verified walkable (ground, headroom, no drop, no lava)
 *   2. a committed vanilla ground path to the target (re-planned only when the target really moved / path ended)
 *   3. if no complete path exists (pit, wall, far ledge): propose waypoints on rings around the bot, take the first
 *      one that has a real walking route AND brings it clearly closer (ExplorePlanner) - e.g. out of the south exit
 *   4. if nothing helps: STRANDED (the brain then tries wind charge / ender pearl, otherwise waits and retries)
 * Plus: edge/lava refusal, stuck detection (forces a new route + sidestep), light separation from other bots.
 * All path searches are charged to a shared per-tick budget so 50+ bots stay cheap.
 */
final class BotNavigator {

    enum Status { MOVING, ARRIVED, STRANDED }

    /** Limits how many path searches ALL bots together may start per server tick. */
    static final class PathBudget {
        private static long tick = -1;
        private static int used = 0;
        static boolean acquire(long serverTick, int max) {
            if (serverTick != tick) { tick = serverTick; used = 0; }
            if (used >= max) return false;
            used++;
            return true;
        }
    }

    private final PixyBotPlayer bot;
    private Zombie navMob;

    // target path (committed)
    private Path path;
    private Vec3 pathGoal;
    private int pathAge;
    private boolean unreachable;

    // exploration
    private BlockPos waypoint;
    private Path wpPath;
    private int wpTimeout;
    private List<ExplorePlanner.Cand> cands;
    private int candIdx;
    private double exploreStartDistSq;
    private int strandedUntil, strandedEvents, lastStrandedTick;
    private int edgeTicks;
    private final ArrayDeque<Long> blockedGoals = new ArrayDeque<>();

    // straight-line check cache
    private int straightAge;
    private boolean straightOk;

    // stuck + detour
    private boolean movingToGoal;
    private Vec3 lastPos;
    private int stuckSample, stuckCount, forceTicks, detourTicks;
    private float detourDir = 1f;

    // separation from other bots
    private int sepAge;
    private double sepX, sepZ;

    BotNavigator(PixyBotPlayer bot) { this.bot = bot; }

    // ====================================================================== per-tick bookkeeping
    void beginTick() {
        movingToGoal = false;
        if (forceTicks > 0) forceTicks--;
        if (edgeTicks > 0) edgeTicks--;
        if (strandedEvents > 0 && bot.botServer().getTickCount() - lastStrandedTick > 600) strandedEvents = 0;
    }

    /** Wanted to move but barely did for ~1 s -> force a real route search, sidestep and hop. */
    void endTick() {
        if (!movingToGoal) { stuckSample = 0; stuckCount = 0; lastPos = null; return; }
        if (++stuckSample < 10) return;
        stuckSample = 0;
        Vec3 now = bot.position();
        if (lastPos != null && now.distanceToSqr(lastPos) < 0.09) {
            if (++stuckCount >= 2) {
                stuckCount = 0;
                forceTicks = 80;
                path = null; pathAge = 0; straightAge = 0;
                if (waypoint != null) { markBlocked(waypoint); waypoint = null; wpPath = null; }
                detourTicks = 14 + bot.getRandom().nextInt(10);
                detourDir = bot.getRandom().nextBoolean() ? 1f : -1f;
                if (bot.onGround()) bot.jumpFromGround();
            }
        } else {
            stuckCount = 0;
        }
        lastPos = now;
    }

    /** Forget every route / waypoint / detour (called when the bot switches between idle and hunting or changes target). */
    void reset() {
        path = null; pathGoal = null; pathAge = 0; waypoint = null; wpPath = null; cands = null;
        unreachable = false; strandedUntil = 0; strandedEvents = 0; detourTicks = 0; forceTicks = 0;
        straightAge = 0; edgeTicks = 0; stuckSample = 0; stuckCount = 0; lastPos = null;
    }

    boolean isStranded() { return strandedUntil > bot.botServer().getTickCount(); }
    boolean isUnreachable() { return unreachable; }
    /** Found no walking route several times in a row (not just once). */
    boolean isTrapped() { return strandedEvents >= 2; }
    /** The bot just refused to step off an edge / into a hole. */
    boolean edgeBlocked() { return edgeTicks > 0; }
    /** Length (nodes) of the complete walking route to the target, or -1. */
    int routeLength() { return path != null && path.canReach() ? path.getNodeCount() : -1; }
    boolean hasCompleteRoute() { return path != null && path.canReach(); }

    // ====================================================================== walking to an entity (combat)
    Status moveToEntity(Entity tgt, boolean sprint, boolean los) {
        PixyConfig cfg = PixyConfig.I;
        Vec3 tp = tgt.position();
        int now = bot.botServer().getTickCount();
        boolean stranded = strandedUntil > now;

        // 1. verified straight walk
        double d2 = bot.distanceToSqr(tgt);
        if (forceTicks <= 0 && los && d2 < 36.0 && straightWalkable(tp)) {
            path = null; waypoint = null; unreachable = false;
            steer(tp, sprint);
            applyDetour();
            applySeparation();
            return Status.MOVING;
        }

        // 2. committed waypoint from an earlier exploration
        if (waypoint != null && !stranded) {
            if (--wpTimeout <= 0 || wpPath == null || wpPath.isDone()) {
                if (wpTimeout <= 0) markBlocked(waypoint);   // never got there: do not try it again soon
                waypoint = null; wpPath = null; path = null; pathAge = 0;
            } else {
                steer(nextGoal(wpPath, Vec3.atBottomCenterOf(waypoint)), sprint);
                applyDetour();
                applySeparation();
                return Status.MOVING;
            }
        }

        // 3. committed path to the target: re-plan only when needed
        pathAge--;
        boolean drifted = pathGoal == null || pathGoal.distanceToSqr(tp) > 9.0;
        boolean need = path == null || path.isDone() || (pathAge <= 0 && drifted) || pathAge < -60;
        if (need && PathBudget.acquire(now, cfg.pathBudgetPerTick)) {
            computePath(tgt);
            pathGoal = tp;
            pathAge = cfg.repathTicks;
        }

        // no path object at all = path search unavailable right now (no budget / invalid): walk straight at the
        // target (edge-safe) instead of freezing. Only a real partial path can mean "unreachable".
        if (path == null) {
            unreachable = false;
            steer(tp, sprint);
            applyDetour();
            applySeparation();
            return Status.MOVING;
        }

        boolean complete = path.canReach();
        // A partial path is what vanilla mobs follow when the target is far: walk it, re-plan every 0.5 s, repeat.
        // It only counts as "stuck" if its end is NOT clearly closer to the target than the bot already is.
        boolean progress = !complete && endIsCloser(tp);
        if (complete || progress || d2 < 25.0) {
            unreachable = false;
            steer(nextGoal(path, tp), sprint);
            applyDetour();
            applySeparation();
            return Status.MOVING;
        }

        // 4. the path leads nowhere (pit, wall, ledge): think instead of pushing toward the player's coordinates
        unreachable = true;
        if (stranded) {                 // already explored without success: keep moving toward the closest point, never freeze
            steer(nextGoal(path, tp), sprint);
            applyDetour();
            return Status.STRANDED;
        }
        return explore(tp, d2);
    }

    private boolean endIsCloser(Vec3 tp) {
        Node n = path.getEndNode();
        if (n == null) return false;
        double ex = n.x + 0.5 - tp.x, ez = n.z + 0.5 - tp.z;
        double bx = bot.getX() - tp.x, bz = bot.getZ() - tp.z;
        return Math.sqrt(ex * ex + ez * ez) < Math.sqrt(bx * bx + bz * bz) - 2.0;
    }

    private Status explore(Vec3 tp, double curDistSq) {
        PixyConfig cfg = PixyConfig.I;
        int now = bot.botServer().getTickCount();
        if (cands == null) {
            cands = ExplorePlanner.candidates(Mth.floor(bot.getX()), Mth.floor(bot.getZ()),
                Mth.floor(tp.x), Mth.floor(tp.z), bot.getRandom().nextDouble() * Math.PI,
                new int[] {6, 10, 16}, 8);
            candIdx = 0;
            exploreStartDistSq = curDistSq;
        }
        // evaluate waypoints best-first, a few per tick at most (shared budget)
        while (candIdx < cands.size()) {
            ExplorePlanner.Cand c = cands.get(candIdx);
            if (!ExplorePlanner.improves(c, exploreStartDistSq, 2.0)) { candIdx = cands.size(); break; } // sorted: rest is worse
            if (!PathBudget.acquire(now, cfg.pathBudgetPerTick)) return Status.MOVING;                  // continue next tick
            candIdx++;
            BlockPos stand = findStand(c.x(), c.z());
            if (stand == null || isBlocked(stand)) continue;
            ensureNavMob();
            navMob.setPos(bot.getX(), bot.getY(), bot.getZ());
            Path p = navMob.getNavigation().createPath(stand, 0);
            if (p != null && p.canReach()) {
                waypoint = stand; wpPath = p; wpTimeout = 200; cands = null;
                return Status.MOVING;
            }
            markBlocked(stand);
        }
        if (candIdx >= cands.size()) {          // nothing walkable gets us closer
            cands = null;
            strandedUntil = now + 60;           // 3 s: then explore again
            strandedEvents++;
            lastStrandedTick = now;
            return Status.STRANDED;
        }
        return Status.MOVING;
    }

    // ====================================================================== walking to a point (idle, loot, retreat)
    Status moveToPoint(Vec3 p, double arrive, boolean sprint) {
        double dx = p.x - bot.getX(), dz = p.z - bot.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= arrive && Math.abs(p.y - bot.getY()) < 1.6) return Status.ARRIVED;

        if (forceTicks <= 0 && dist < 10.0 && straightWalkable(p)) {
            steer(p, sprint);
        } else {
            pathAge--;
            boolean need = path == null || path.isDone() || pathGoal == null
                || pathGoal.distanceToSqr(p) > 4.0 || pathAge < -60;
            if (need && PathBudget.acquire(bot.botServer().getTickCount(), PixyConfig.I.pathBudgetPerTick)) {
                ensureNavMob();
                navMob.setPos(bot.getX(), bot.getY(), bot.getZ());
                path = navMob.getNavigation().createPath(new BlockPos(Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z)), 0);
                pathGoal = p;
                pathAge = PixyConfig.I.repathTicks * 3;
            }
            if (path == null || !path.canReach()) {
                if (dist < 2.5) steer(p, sprint); else { movingToGoal = false; return Status.STRANDED; }
            } else {
                steer(nextGoal(path, p), sprint);
            }
        }
        applyDetour();
        applySeparation();
        return Status.MOVING;
    }

    // ====================================================================== low-level movement
    /** Convert "go to this point" into forward/strafe input relative to the bot's facing; refuses to step off edges. */
    void steer(Vec3 goal, boolean allowSprint) {
        movingToGoal = true;
        double dx = goal.x - bot.getX(), dz = goal.z - bot.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.05) {
            dx /= len; dz /= len;
            if (bot.onGround() && !hasGroundAt(bot.getX() + dx * 0.9, bot.getZ() + dz * 0.9)) {
                forceTicks = Math.max(forceTicks, 40);      // edge / hole ahead: stop and plan a way around
                edgeTicks = 20;                             // (BlockPlacer may bridge it)
                return;
            }
            double yaw = Math.toRadians(bot.getYRot());
            double s = Math.sin(yaw), c = Math.cos(yaw);
            bot.zza = (float) (dx * -s + dz * c);
            bot.xxa = (float) (dx * c + dz * s);
            if (allowSprint && bot.zza > 0.6f) bot.setSprinting(true);
        }
        if (bot.onGround() && (bot.horizontalCollision || goal.y - bot.getY() > 0.6)) bot.jumpFromGround();
        bot.setJumping(bot.isInWater());
    }

    /** In the air (after a wind charge): steer toward a point, no edge checks. */
    void steerAir(Vec3 goal) {
        double dx = goal.x - bot.getX(), dz = goal.z - bot.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05) return;
        dx /= len; dz /= len;
        double yaw = Math.toRadians(bot.getYRot());
        bot.zza = (float) (dx * -Math.sin(yaw) + dz * Math.cos(yaw));
        bot.xxa = (float) (dx * Math.cos(yaw) + dz * Math.sin(yaw));
    }

    /** Turn the body/head toward a direction at a limited speed (natural looking). */
    void turnToward(float yaw, float pitch, float rate) {
        float dy = Mth.wrapDegrees(yaw - bot.getYRot());
        float ny = bot.getYRot() + Mth.clamp(dy, -rate, rate);
        bot.setYRot(ny);
        bot.setYHeadRot(ny);
        float dp = pitch - bot.getXRot();
        bot.setXRot(bot.getXRot() + Mth.clamp(dp, -rate, rate));
    }

    static float yawTo(double dx, double dz) { return ProjectileAim.yawTo(dx, dz); }

    private Vec3 nextGoal(Path p, Vec3 fallback) {
        if (p == null) return fallback;
        for (int i = 0; i < 2 && !p.isDone(); i++) {
            Vec3 c = Vec3.atBottomCenterOf(p.getNextNodePos());
            double dx = c.x - bot.getX(), dz = c.z - bot.getZ();
            if (dx * dx + dz * dz < 0.5 && Math.abs(c.y - bot.getY()) < 1.5) p.advance();
            else break;
        }
        return p.isDone() ? fallback : Vec3.atBottomCenterOf(p.getNextNodePos());
    }

    private void applyDetour() {
        if (detourTicks <= 0) return;
        detourTicks--;
        double yaw = Math.toRadians(bot.getYRot());
        double lx = Math.cos(yaw) * detourDir, lz = Math.sin(yaw) * detourDir;
        if (hasGroundAt(bot.getX() + lx * 0.9, bot.getZ() + lz * 0.9)) bot.xxa = detourDir * 0.9f;
        else detourDir = -detourDir;
    }

    /** Gentle push away from bots standing right next to us, so a crowd does not bump into itself. */
    private void applySeparation() {
        if (--sepAge <= 0) {
            sepAge = 4;
            sepX = 0; sepZ = 0;
            List<PixyBotPlayer> near = bot.level().getEntitiesOfClass(PixyBotPlayer.class,
                bot.getBoundingBox().inflate(1.0), e -> e != bot && e.isAlive());
            for (PixyBotPlayer o : near) {
                double dx = bot.getX() - o.getX(), dz = bot.getZ() - o.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 0.01) { dx = bot.getRandom().nextBoolean() ? 1 : -1; dz = 0; d = 1; }
                sepX += dx / d; sepZ += dz / d;
            }
        }
        if (sepX == 0 && sepZ == 0) return;
        double len = Math.sqrt(sepX * sepX + sepZ * sepZ);
        double yaw = Math.toRadians(bot.getYRot());
        bot.zza = Mth.clamp(bot.zza + (float) (sepX / len * -Math.sin(yaw) + sepZ / len * Math.cos(yaw)) * 0.5f, -1f, 1f);
        bot.xxa = Mth.clamp(bot.xxa + (float) (sepX / len * Math.cos(yaw) + sepZ / len * Math.sin(yaw)) * 0.5f, -1f, 1f);
    }

    // ====================================================================== world checks
    private void ensureNavMob() {
        if (navMob == null || navMob.level() != bot.level()) {
            navMob = new Zombie(EntityType.ZOMBIE, bot.level());   // never spawned: only used as a path calculator
            navMob.setOnGround(true);   // vanilla refuses to compute paths for a mob that is not "on the ground"
            var range = navMob.getAttribute(Attributes.FOLLOW_RANGE);
            if (range != null) range.setBaseValue(64.0);
        }
    }

    private void computePath(Entity tgt) {
        ensureNavMob();
        navMob.setPos(bot.getX(), bot.getY(), bot.getZ());
        path = navMob.getNavigation().createPath(tgt, 1);
    }

    private boolean loaded(int x, int y, int z) { return bot.level().hasChunkAt(new BlockPos(x, y, z)); }

    private boolean solidAt(int x, int y, int z) {
        if (!loaded(x, y, z)) return true;
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = bot.level().getBlockState(p);
        return !s.getCollisionShape(bot.level(), p).isEmpty();
    }

    private boolean lavaAt(int x, int y, int z) {
        if (!loaded(x, y, z)) return false;
        return bot.level().getBlockState(new BlockPos(x, y, z)).getFluidState().is(FluidTags.LAVA);
    }

    /** Is there something to stand on (within 3 blocks down, and no lava) at this spot? */
    boolean hasGroundAt(double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z), by = Mth.floor(bot.getY()) - 1;
        for (int dy = 0; dy <= 3; dy++) {
            if (lavaAt(bx, by - dy, bz)) return false;
            if (solidAt(bx, by - dy, bz)) return true;
        }
        return false;
    }

    /** Feet-level Y of a standable spot in this column near fromY (solid floor + 2 free blocks), or MIN_VALUE. */
    private int standY(int bx, int bz, int fromY) {
        for (int yy = fromY + 1; yy >= fromY - 3; yy--) {
            if (lavaAt(bx, yy, bz)) return Integer.MIN_VALUE;
            if (solidAt(bx, yy, bz) && !solidAt(bx, yy + 1, bz) && !solidAt(bx, yy + 2, bz)) return yy + 1;
        }
        return Integer.MIN_VALUE;
    }

    /** Every step of the straight line is walkable: floor, headroom, at most a 1-block step up / 3 down. */
    private boolean straightWalkable(Vec3 to) {
        if (--straightAge > 0) return straightOk;
        straightAge = 5;
        double dx = to.x - bot.getX(), dz = to.z - bot.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 12.0) return straightOk = false;
        int steps = (int) Math.ceil(len);
        int y = Mth.floor(bot.getY());
        for (int i = 1; i <= steps; i++) {
            double f = Math.min(1.0, i / Math.max(len, 0.001));
            int sy = standY(Mth.floor(bot.getX() + dx * f), Mth.floor(bot.getZ() + dz * f), y);
            if (sy == Integer.MIN_VALUE || sy > y + 1 || sy < y - 3) return straightOk = false;
            y = sy;
        }
        return straightOk = true;
    }

    /** A standable block position in column (x,z) within a few blocks of the bot's height. */
    private BlockPos findStand(int x, int z) {
        int top = Mth.floor(bot.getY()) + 6;
        for (int yy = top; yy >= Mth.floor(bot.getY()) - 8; yy--) {
            if (!loaded(x, yy, z)) return null;
            if (solidAt(x, yy, z) && !solidAt(x, yy + 1, z) && !solidAt(x, yy + 2, z) && !lavaAt(x, yy + 1, z))
                return new BlockPos(x, yy + 1, z);
        }
        return null;
    }

    private static long key(BlockPos p) { return p.asLong(); }
    private boolean isBlocked(BlockPos p) { return blockedGoals.contains(key(p)); }
    private void markBlocked(BlockPos p) {
        blockedGoals.addLast(key(p));
        while (blockedGoals.size() > 12) blockedGoals.pollFirst();
    }
}
