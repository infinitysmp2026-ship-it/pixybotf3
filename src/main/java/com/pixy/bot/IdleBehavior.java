package com.pixy.bot;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Natural idle movement for a bot that is NOT fighting. Every bot owns a Persona (seeded from spawn order +
 * identity), so each one walks out / back, glances around, hops, wanders next to a neighbour and pauses on its own
 * schedule. Stops the moment combat starts (interrupt()) and is bounded by a leash around the spawn point.
 */
final class IdleBehavior {
    private enum S { WAIT, WALK_OUT, HOLD, WALK_BACK, LOOK, FACE_HOME }

    private final PixyBotPlayer bot;
    private final BotNavigator nav;
    private final Persona persona;

    private S state = S.WAIT;
    private int timer;
    private int budget;
    private Vec3 goal;
    private int glancesLeft;
    private boolean glanceHolding;
    private float glanceYaw, glancePitch;
    private boolean active;

    IdleBehavior(PixyBotPlayer bot, BotNavigator nav, long seed) {
        this.bot = bot;
        this.nav = nav;
        this.persona = new Persona(seed);
        this.timer = persona.firstDelay;      // staggered: bots do not start together
    }

    /** Combat (or a loot run) takes over: forget the current routine. */
    void interrupt() {
        if (!active) return;
        active = false;
        state = S.WAIT;
        goal = null;
        timer = 40 + persona.pauseTicks();
    }

    void tick(PixyConfig cfg) {
        active = true;
        Vec3 home = bot.homePos();
        if (!cfg.idleMovement || home == null || bot.isUsingItem()) return;

        double hx = bot.getX() - home.x, hz = bot.getZ() - home.z;
        double leash = cfg.idleLeash;
        if (hx * hx + hz * hz > (leash + 1.5) * (leash + 1.5) && state != S.WALK_BACK && state != S.FACE_HOME) {
            state = S.WALK_BACK; budget = 400;             // wandered too far (loot run, push...): go home
        }

        switch (state) {
            case WAIT -> {
                if (--timer > 0) return;
                startAction(home, leash);
            }
            case WALK_OUT -> {
                faceGoal();
                BotNavigator.Status st = nav.moveToPoint(goal, 0.7, false);
                if (st != BotNavigator.Status.MOVING || --budget <= 0) { state = S.HOLD; timer = persona.holdTicks(); }
            }
            case HOLD -> {
                if (persona.chance(0.02)) nav.turnToward(bot.getYRot() + persona.glanceYaw() * 0.4f, 0f, persona.turnRate);
                if (--timer <= 0) { state = S.WALK_BACK; budget = 300; }
            }
            case WALK_BACK -> {
                faceHome(home);
                BotNavigator.Status st = nav.moveToPoint(home, 0.6, false);
                if (st != BotNavigator.Status.MOVING || --budget <= 0) state = S.FACE_HOME;
            }
            case FACE_HOME -> {
                nav.turnToward(bot.homeYaw(), bot.homePitch(), persona.turnRate);
                if (Math.abs(Mth.wrapDegrees(bot.homeYaw() - bot.getYRot())) < 3f) {
                    state = S.WAIT;
                    timer = persona.pauseTicks();
                }
            }
            case LOOK -> look();
        }
    }

    private void startAction(Vec3 home, double leash) {
        double yaw = Math.toRadians(bot.homeYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);       // forward
        double lx = Math.cos(yaw), lz = Math.sin(yaw);        // left
        Persona.Action a = persona.nextAction();
        int blocks = Math.min(persona.walkBlocks(), (int) leash);
        switch (a) {
            case WALK_FORWARD -> beginWalk(home.add(fx * blocks, 0, fz * blocks));
            case WALK_RIGHT   -> beginWalk(home.add(-lx * blocks, 0, -lz * blocks));
            case WALK_LEFT    -> beginWalk(home.add(lx * blocks, 0, lz * blocks));
            case JUMP -> {
                if (bot.onGround()) bot.jumpFromGround();
                timer = persona.pauseTicks();
            }
            case APPROACH_BOT -> {
                PixyBotPlayer friend = nearestFriend(home, 16.0);
                if (friend == null) { beginLook(); return; }
                Vec3 f = friend.position();
                double dx = home.x - f.x, dz = home.z - f.z, d = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                Vec3 spot = f.add(dx / d * 2.5, 0, dz / d * 2.5);     // stop 2.5 blocks from it, never on top
                double sx = spot.x - home.x, sz = spot.z - home.z, sd = Math.sqrt(sx * sx + sz * sz);
                if (sd > leash) spot = home.add(sx / sd * leash, 0, sz / sd * leash);
                beginWalk(spot);
            }
            case LOOK_AROUND -> beginLook();
            case PAUSE -> timer = persona.pauseTicks();
        }
    }

    private void beginWalk(Vec3 g) { goal = g; state = S.WALK_OUT; budget = 160; }

    private void beginLook() {
        state = S.LOOK;
        glancesLeft = persona.glances();
        glanceHolding = false;
        glanceYaw = bot.homeYaw() + persona.glanceYaw();
        glancePitch = persona.glancePitch();
        timer = 0;
    }

    private void look() {
        nav.turnToward(glanceYaw, glancePitch, persona.turnRate);
        boolean there = Math.abs(Mth.wrapDegrees(glanceYaw - bot.getYRot())) < 3f;
        if (!glanceHolding && there) { glanceHolding = true; timer = 15 + (int) (persona.pauseTicks() * 0.1); }
        if (glanceHolding && --timer <= 0) {
            if (--glancesLeft <= 0) { state = S.FACE_HOME; return; }
            glanceHolding = false;
            glanceYaw = bot.homeYaw() + persona.glanceYaw();
            glancePitch = persona.glancePitch();
        }
    }

    private void faceGoal() {
        if (goal == null) return;
        nav.turnToward(BotNavigator.yawTo(goal.x - bot.getX(), goal.z - bot.getZ()), 0f, persona.turnRate * 1.5f);
    }

    private void faceHome(Vec3 home) {
        nav.turnToward(BotNavigator.yawTo(home.x - bot.getX(), home.z - bot.getZ()), 0f, persona.turnRate * 1.5f);
    }

    private PixyBotPlayer nearestFriend(Vec3 home, double radius) {
        PixyBotPlayer best = null;
        double bd = radius * radius;
        for (PixyBotPlayer o : BotManager.all()) {
            if (o == bot || !o.isAlive() || o.isHunting() || o.level() != bot.level()) continue;
            double d = o.position().distanceToSqr(home);
            if (d < bd && d > 4.0) { bd = d; best = o; }
        }
        return best;
    }
}
