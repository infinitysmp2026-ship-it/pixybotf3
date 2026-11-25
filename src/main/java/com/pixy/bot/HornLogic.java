package com.pixy.bot;

import java.util.Collection;

/**
 * One horn blast arms every bot that exists RIGHT NOW and is not already hunting.
 * Bots created later are untouched, so there is no global flag that could leak into new bots.
 */
public final class HornLogic {
    private HornLogic() { }

    /** @return how many bots were newly activated */
    public static int blow(Collection<? extends Armable> bots, long generation) {
        int n = 0;
        for (Armable b : bots) {
            if (!b.isHunting()) { b.activate(generation); n++; }
        }
        return n;
    }
}
