package com.pixy.bot;

/**
 * WHEN an ender pearl is worth throwing. Pure Java (unit-tested). A pearl costs 5 damage and a 20-tick cooldown,
 * so the bot only uses one when walking is clearly worse:
 *   CHASE       the target is running away: the gap grew by 6+ blocks over the last 5 s and is now 22+ blocks
 *               (35+ if it is the bot's last pearl - keep one for emergencies)
 *   ESCAPE      the bot is losing (7-10 health, enemy within 5 blocks, nothing to heal with): pearl away
 *   TRAPPED     the bot has repeatedly found no walking route, no blocks to bridge and no wind charge
 *   HIGH_GROUND the target stands 5+ blocks higher and cannot be reached on foot
 * Never: during the first 5 s of a hunt (except TRAPPED), within 15 s of the previous pearl, below 6.5 health.
 */
public final class PearlPolicy {
    public enum Decision { NONE, CHASE, ESCAPE, TRAPPED, HIGH_GROUND }

    public static final class In {
        public int pearls;
        public int ticksSinceLastPearl = 99999;
        public int huntTicks;
        public double dist;            // bot -> target now
        public double distAgo = -1;    // bot -> target 5 s ago (-1 unknown)
        public boolean routeExists;    // a complete walking route exists
        public boolean unreachable;    // no route and exploration found nothing
        public boolean trapped;        // repeatedly stranded
        public boolean canBridgeOrWind;// has blocks / wind charge that could solve the obstacle instead
        public double botHealth = 20;
        public boolean hasHealing;
        public double heightDiff;      // target.y - bot.y
    }

    private PearlPolicy() { }

    public static Decision decide(In i) {
        if (i.pearls <= 0) return Decision.NONE;
        if (i.ticksSinceLastPearl < 300) return Decision.NONE;
        if (i.botHealth <= 6.5) return Decision.NONE;                       // the teleport itself hurts

        if (i.trapped && !i.routeExists && !i.canBridgeOrWind && i.dist > 4) return Decision.TRAPPED;
        if (i.huntTicks < 100) return Decision.NONE;                         // never open a fight with a pearl

        if (i.heightDiff > 5.0 && i.unreachable && !i.canBridgeOrWind && i.dist < 30) return Decision.HIGH_GROUND;

        boolean fleeing = i.distAgo >= 0 && i.dist - i.distAgo >= 6.0;
        double minDist = i.pearls == 1 ? 35.0 : 22.0;
        if (fleeing && i.dist >= minDist) return Decision.CHASE;

        if (i.botHealth > 7.0 && i.botHealth <= 10.0 && i.dist < 5.0 && !i.hasHealing) return Decision.ESCAPE;
        return Decision.NONE;
    }
}
