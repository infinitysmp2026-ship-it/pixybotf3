package com.pixy.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * WHEN to use the special items a bot carries. Nothing is random:
 *  - Ender pearl : decided by {@link PearlPolicy} (target running away, bot losing, bot trapped, target on unreachable
 *                  high ground) AND the simulated throw must land within 4 blocks of the aim point on safe ground.
 *  - Wind charge : jump + throw at its own feet to climb (target on a ledge, no route) or to leave a pit; or the
 *                  wind-charge + mace smash when it owns both, the target is near on level ground and the mace is
 *                  (almost) recharged so the smash lands at full strength.
 * Healing / shield / weapon choice live in BotBrain and BotEquipment; bridging in BlockPlacer.
 */
final class ItemTactics {
    private final PixyBotPlayer bot;
    private int windCd, windState, windTicks;
    private int sincePearl = 99999, huntTicks, histTick, histCount;
    private final double[] hist = new double[6];       // distance samples, one per second, oldest first

    ItemTactics(PixyBotPlayer bot) { this.bot = bot; }

    /** A new hunt (horn, new target): start the "never open with a pearl" clock again. */
    void onHuntStart() { huntTicks = 0; histCount = 0; histTick = 0; windState = 0; }

    void beginTick() {
        if (windCd > 0) windCd--;
        sincePearl++;
        huntTicks++;
    }

    boolean windActive() { return windState != 0; }

    /** One distance sample per second; keeps the last 6 so "5 seconds ago" is available. */
    private void sample(double dist) {
        if (++histTick < 20) return;
        histTick = 0;
        if (histCount < hist.length) { hist[histCount++] = dist; return; }
        System.arraycopy(hist, 1, hist, 0, hist.length - 1);
        hist[hist.length - 1] = dist;
    }

    /** @return true if this tick was used up (throw / launch preparation), so the brain skips melee */
    boolean tick(ServerPlayer target, BotNavigator nav, boolean unreachable, double d2, boolean canBridge, float attackStrength) {
        PixyConfig cfg = PixyConfig.I;
        double dist = Math.sqrt(d2);
        sample(dist);
        if (bot.isUsingItem()) return false;
        if (windState != 0) return windTick(target, nav);

        double dy = target.getY() - bot.getY();
        int pearls = BotEquipment.count(bot, Items.ENDER_PEARL);
        boolean hasWind = cfg.useWindCharges && BotEquipment.count(bot, Items.WIND_CHARGE) > 0;

        // ---- ender pearl (strategic only)
        if (cfg.useEnderPearls && pearls > 0) {
            PearlPolicy.In in = new PearlPolicy.In();
            in.pearls = pearls;
            in.ticksSinceLastPearl = sincePearl;
            in.huntTicks = huntTicks;
            in.dist = dist;
            in.distAgo = histCount >= hist.length ? hist[0] : -1;
            in.routeExists = nav.hasCompleteRoute();
            in.unreachable = unreachable;
            in.trapped = nav.isTrapped();
            in.canBridgeOrWind = canBridge || hasWind;
            in.botHealth = bot.getHealth();
            in.hasHealing = BotEquipment.hasHealing(bot);
            in.heightDiff = dy;
            PearlPolicy.Decision d = PearlPolicy.decide(in);
            if (d != PearlPolicy.Decision.NONE && throwPearl(target, d)) return true;
        }

        // ---- wind charge
        if (hasWind && windCd == 0 && bot.onGround()) {
            boolean climb = unreachable && dy > 3.0 && dist < 24.0;
            boolean escape = nav.isTrapped() && !canBridge && dist < 40.0;
            boolean smash = BotEquipment.hasMace(bot) && d2 > 9.0 && d2 < 144.0 && Math.abs(dy) < 2.5
                && bot.getHealth() > 10f && target.onGround() && attackStrength >= 0.6f;
            if (climb || escape || smash) {
                bot.jumpFromGround();
                windState = 1; windTicks = 0;
                return true;
            }
        }
        return false;
    }

    private boolean windTick(ServerPlayer target, BotNavigator nav) {
        windTicks++;
        if (windState == 1) {                                   // jump started: throw at own feet while rising
            boolean rising = !bot.onGround() && bot.getDeltaMovement().y > 0.1;
            if (rising || windTicks > 4) {
                if (BotEquipment.holdItem(bot, Items.WIND_CHARGE)) {
                    bot.setXRot(90f);
                    bot.xRotO = 90f;
                    bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
                    windCd = 60; windState = 2; windTicks = 0;
                    return true;
                }
                windState = 0; windCd = 100;
                return false;
            }
            return true;
        }
        // state 2: launched - steer toward the target, melee stays allowed (mace smash on the way down)
        if ((bot.onGround() && windTicks > 5) || windTicks > 80) { windState = 0; return false; }
        nav.steerAir(target.position());
        return false;
    }

    private boolean throwPearl(ServerPlayer target, PearlPolicy.Decision why) {
        Vec3 eye = bot.getEyePosition();
        Vec3 aim = target.position();
        if (why == PearlPolicy.Decision.ESCAPE) {                // pearl AWAY from the enemy
            double dx = bot.getX() - target.getX(), dz = bot.getZ() - target.getZ();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            aim = new Vec3(bot.getX() + dx / len * 18.0, bot.getY(), bot.getZ() + dz / len * 18.0);
        }
        ProjectileAim.Result r = ProjectileAim.solve(eye.x, eye.y - 0.1, eye.z, aim.x, aim.y, aim.z,
            1.5, 0.03, 0.99, (x, y, z) -> solid(x, y, z));
        sincePearl = 200;                                       // failed solves retry after ~5 s, not every tick
        if (r == null || !r.hit || r.miss > 4.0 || !safeLanding(r.ix, r.iy, r.iz)) return false;
        if (!BotEquipment.holdItem(bot, Items.ENDER_PEARL)) return false;
        bot.setYRot(r.yaw);
        bot.setYHeadRot(r.yaw);
        bot.setXRot(r.pitch);
        bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        sincePearl = 0;
        return true;
    }

    private boolean solid(double x, double y, double z) {
        BlockPos p = new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        if (!bot.level().hasChunkAt(p)) return true;
        BlockState s = bot.level().getBlockState(p);
        return !s.getCollisionShape(bot.level(), p).isEmpty();
    }

    /** Landing spot must not be lava and must have something to stand on right there. */
    private boolean safeLanding(double x, double y, double z) {
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        boolean ground = false;
        for (int dy = 0; dy >= -3; dy--) {
            BlockPos p = new BlockPos(bx, by + dy, bz);
            if (!bot.level().hasChunkAt(p)) return false;
            BlockState s = bot.level().getBlockState(p);
            if (s.getFluidState().is(FluidTags.LAVA)) return false;
            if (!s.getCollisionShape(bot.level(), p).isEmpty()) { ground = true; break; }
        }
        return ground;
    }
}
