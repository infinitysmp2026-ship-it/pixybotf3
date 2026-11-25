package com.pixy.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;

/**
 * Bridging and pillaring with blocks from the bot's own inventory (blocks it picked up / was given).
 *  - BRIDGE: the target is across a gap -> sneak to the edge, place the block in front, step onto it, repeat until
 *            natural ground is reached (max 16 blocks). Sneaking means the bot cannot fall off its own bridge.
 *  - PILLAR: the target is 3+ blocks higher and not reachable on foot -> jump and place a block under itself, repeat.
 * Started only when walking has failed (edge refusal / no route), so it is used for a purpose, not randomly.
 */
final class BlockPlacer {
    private enum Mode { NONE, BRIDGE, PILLAR }

    private static final int MAX_BRIDGE = 16, MAX_PILLAR = 12;

    private final PixyBotPlayer bot;
    private Mode mode = Mode.NONE;
    private int dirX, dirZ, placed, placeCd, budget, cooldown;
    private final Set<Long> mine = new HashSet<>();
    private BlockPos pillarBase;

    BlockPlacer(PixyBotPlayer bot) { this.bot = bot; }

    boolean active() { return mode != Mode.NONE; }

    void stop() {
        if (mode != Mode.NONE) bot.setShiftKeyDown(false);
        mode = Mode.NONE;
        mine.clear();
        pillarBase = null;
        placed = 0;
    }

    /** @return true while the placer owns the bot's movement this tick */
    boolean tick(ServerPlayer target, BotNavigator nav, boolean needHelp) {
        PixyConfig cfg = PixyConfig.I;
        if (cooldown > 0) cooldown--;
        if (!cfg.useBridging) { stop(); return false; }

        if (mode != Mode.NONE) return run(target, nav);

        if (!needHelp || cooldown > 0 || bot.isUsingItem() || !bot.onGround()) return false;
        if (BotEquipment.placeableCount(bot) <= 0) return false;

        double dx = target.getX() - bot.getX(), dz = target.getZ() - bot.getZ();
        double dy = target.getY() - bot.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        if (dy >= 3.0 && dy <= 14.0 && horiz <= 8.0) {
            mode = Mode.PILLAR; placed = 0; budget = 300; pillarBase = null;
            return true;
        }
        if (dy < 3.0 && dy > -8.0 && horiz >= 2.0 && horiz <= 24.0) {
            int axis = BridgePlanner.dominantAxis(dx, dz);
            dirX = BridgePlanner.dirX(axis);
            dirZ = BridgePlanner.dirZ(axis);
            if (!gapAhead()) return false;                 // nothing to bridge in that direction
            mode = Mode.BRIDGE; placed = 0; budget = 400; mine.clear();
            return true;
        }
        return false;
    }

    private boolean run(ServerPlayer target, BotNavigator nav) {
        // fighting takes priority if an enemy is on top of us and hurting us
        if (--budget <= 0 || !target.isAlive() || (bot.hurtTime > 0 && bot.distanceToSqr(target) < 12.0)) {
            finish(nav, false);
            return false;
        }
        if (placeCd > 0) placeCd--;
        return mode == Mode.BRIDGE ? bridge(nav) : pillar(target, nav);
    }

    private void finish(BotNavigator nav, boolean ok) {
        stop();
        cooldown = ok ? 20 : 100;      // do not retry the same failed idea every tick
        nav.reset();                   // plan a fresh route from the new spot
    }

    // ------------------------------------------------------------------ bridge
    private boolean bridge(BotNavigator nav) {
        float yaw = BotNavigator.yawTo(dirX, dirZ);
        bot.setYRot(yaw); bot.setYHeadRot(yaw); bot.setXRot(75f);
        bot.setShiftKeyDown(true);
        if (!bot.onGround()) return true;

        BlockPos support = new BlockPos(Mth.floor(bot.getX()), Mth.floor(bot.getY()) - 1, Mth.floor(bot.getZ()));
        BlockPos ahead = support.offset(dirX, 0, dirZ);
        BridgePlanner.Act act = BridgePlanner.next(solid(ahead), mine.contains(ahead.asLong()), placed, MAX_BRIDGE,
            BotEquipment.placeableCount(bot) > 0);
        switch (act) {
            case PLACE -> {
                if (placeCd > 0) return true;
                if (placeBlock(ahead)) { mine.add(ahead.asLong()); placed++; placeCd = 3; }
                else { finish(nav, false); return false; }
            }
            case STEP -> bot.zza = 0.35f;                  // slow sneak-walk onto the new block
            case DONE -> { finish(nav, placed > 0); return false; }
            case ABORT -> { finish(nav, false); return false; }
        }
        return true;
    }

    /** Is there a gap (no floor) within the next few columns in the bridge direction, with ground behind it? */
    private boolean gapAhead() {
        BlockPos support = new BlockPos(Mth.floor(bot.getX()), Mth.floor(bot.getY()) - 1, Mth.floor(bot.getZ()));
        for (int i = 1; i <= 2; i++) {
            if (!solid(support.offset(dirX * i, 0, dirZ * i))) {
                for (int j = i + 1; j <= MAX_BRIDGE + 2; j++)           // ground on the far side?
                    if (solid(support.offset(dirX * j, 0, dirZ * j))) return true;
                return true;                                           // a void with nothing beyond is bridged as far as blocks last
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ pillar
    private boolean pillar(ServerPlayer target, BotNavigator nav) {
        bot.setXRot(90f);
        if (placed >= MAX_PILLAR || bot.getY() >= target.getY() - 0.5) { finish(nav, placed > 0); return false; }
        if (bot.onGround()) {
            pillarBase = new BlockPos(Mth.floor(bot.getX()), Mth.floor(bot.getY()) - 1, Mth.floor(bot.getZ()));
            if (placeCd <= 0) bot.jumpFromGround();
            return true;
        }
        if (pillarBase != null && BridgePlanner.pillarReady(bot.getY(), pillarBase.getY())) {
            BlockPos at = pillarBase.above();
            if (placeBlock(at)) { placed++; placeCd = 4; pillarBase = null; }
            else { finish(nav, false); return false; }
        }
        return true;
    }

    // ------------------------------------------------------------------ world access
    private boolean solid(BlockPos p) {
        Level lvl = bot.level();
        if (!lvl.hasChunkAt(p)) return true;
        return !lvl.getBlockState(p).getCollisionShape(lvl, p).isEmpty();
    }

    private boolean placeBlock(BlockPos pos) {
        ItemStack st = BotEquipment.findPlaceable(bot);
        if (st == null) return false;
        Level lvl = bot.level();
        BlockState old = lvl.getBlockState(pos);
        if (!old.canBeReplaced()) return false;
        if (new AABB(pos).intersects(bot.getBoundingBox())) return false;   // never wall the bot into the block
        BlockState state = Block.byItem(st.getItem()).defaultBlockState();
        BotEquipment.holdItem(bot, st.getItem());
        lvl.setBlockAndUpdate(pos, state);
        lvl.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 0.8f);
        bot.swing(InteractionHand.MAIN_HAND);
        st.shrink(1);
        return true;
    }
}
