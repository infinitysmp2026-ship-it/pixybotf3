package com.pixy.bot;

/** Pure decision logic for bridging along one axis and for choosing the bridge direction. */
public final class BridgePlanner {
    public enum Act { PLACE, STEP, DONE, ABORT }

    private BridgePlanner() { }

    /**
     * The bot stands on solid ground looking at the next column.
     * @param aheadSolid  the block under the next step already exists
     * @param aheadIsMine ...and the bot placed it itself (so it must walk onto it, not stop)
     */
    public static Act next(boolean aheadSolid, boolean aheadIsMine, int placed, int max, boolean hasBlocks) {
        if (aheadSolid) return aheadIsMine ? Act.STEP : Act.DONE;   // natural ground reached (or nothing to do)
        if (!hasBlocks || placed >= max) return Act.ABORT;
        return Act.PLACE;
    }

    /** 0 = +x, 1 = -x, 2 = +z, 3 = -z : the axis along which the target is farthest. */
    public static int dominantAxis(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? 0 : 1;
        return dz >= 0 ? 2 : 3;
    }

    public static int dirX(int axis) { return axis == 0 ? 1 : axis == 1 ? -1 : 0; }
    public static int dirZ(int axis) { return axis == 2 ? 1 : axis == 3 ? -1 : 0; }

    /** May a block be put at the spot the bot just jumped from (pillaring up)? Feet must clear the new block. */
    public static boolean pillarReady(double feetY, int baseBlockY) { return feetY >= baseBlockY + 2.0 - 0.02; }
}
