package com.pixy.bot;

import java.util.Random;

/**
 * Per-bot idle "personality": how restless it is, what it likes to do, how long it pauses.
 * Generated from a seed (spawn order + identity), so every bot differs and they do not move in sync.
 * Pure Java on purpose (no Minecraft types) so it can be unit-tested.
 */
public final class Persona {
    public enum Action { WALK_FORWARD, WALK_RIGHT, WALK_LEFT, LOOK_AROUND, JUMP, APPROACH_BOT, PAUSE }

    public final double activity;      // 0.15 (calm) .. 1.0 (restless)
    public final int firstDelay;       // ticks before the very first action (staggers the bots)
    public final int minPause, maxPause;
    public final int walkMin, walkMax; // blocks
    public final int holdMin, holdMax; // ticks to stand at the far end of a walk
    public final float turnRate;       // degrees per tick when turning the head/body

    private final double[] weights = new double[Action.values().length];
    private final Random rnd;

    public Persona(long seed) {
        rnd = new Random(seed);
        activity = 0.15 + rnd.nextDouble() * 0.85;
        firstDelay = 80 + rnd.nextInt(700);
        double calm = 1.0 / activity;
        minPause = (int) (40 * calm);
        maxPause = minPause + 40 + rnd.nextInt(160);
        walkMin = 2;
        walkMax = 3 + rnd.nextInt(2);          // 3..4 blocks
        holdMin = 20 + rnd.nextInt(20);
        holdMax = holdMin + 20 + rnd.nextInt(60);
        turnRate = 5f + rnd.nextFloat() * 8f;

        for (int i = 0; i < weights.length; i++) weights[i] = 0.2 + rnd.nextDouble();
        weights[rnd.nextInt(weights.length)] *= 3;   // each bot has a couple of favourites
        weights[rnd.nextInt(weights.length)] *= 3;
        if (activity < 0.35) {                       // calm bots mostly look around / stand
            weights[Action.LOOK_AROUND.ordinal()] *= 2.5;
            weights[Action.PAUSE.ordinal()] *= 2.5;
            weights[Action.JUMP.ordinal()] *= 0.3;
        }
    }

    public Action nextAction() {
        double sum = 0;
        for (double w : weights) sum += w;
        double r = rnd.nextDouble() * sum;
        for (int i = 0; i < weights.length; i++) { r -= weights[i]; if (r <= 0) return Action.values()[i]; }
        return Action.PAUSE;
    }

    public int pauseTicks()  { return minPause + rnd.nextInt(Math.max(1, maxPause - minPause)); }
    public int walkBlocks()  { return walkMin + rnd.nextInt(Math.max(1, walkMax - walkMin + 1)); }
    public int holdTicks()   { return holdMin + rnd.nextInt(Math.max(1, holdMax - holdMin)); }
    public int glances()     { return 2 + rnd.nextInt(3); }
    public float glanceYaw() { return (rnd.nextBoolean() ? 1 : -1) * (40f + rnd.nextFloat() * 100f); }
    public float glancePitch() { return -15f + rnd.nextFloat() * 35f; }
    public boolean chance(double p) { return rnd.nextDouble() < p; }
}
