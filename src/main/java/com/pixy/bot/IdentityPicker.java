package com.pixy.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Chooses which identity the next bot gets. An identity nobody is using right now is always preferred, so the
 * first N bots (N = number of identities) are all different; only after every identity is taken is a name reused.
 */
public final class IdentityPicker {
    private IdentityPicker() { }

    /**
     * @param nameUse      lower-case name -> how many bots (alive or being spawned) currently use it
     * @param blockedNames lower-case names that must not be used (e.g. a real player with that name is online)
     */
    public static IdentityEntry pick(List<IdentityEntry> all, Map<String, Integer> nameUse,
                                     Set<String> blockedNames, Random rnd) {
        List<IdentityEntry> best = new ArrayList<>();
        int bestUse = Integer.MAX_VALUE;
        for (IdentityEntry e : all) {
            String k = e.name.toLowerCase(java.util.Locale.ROOT);
            if (blockedNames.contains(k)) continue;
            int use = nameUse.getOrDefault(k, 0);
            if (use < bestUse) { bestUse = use; best.clear(); }
            if (use == bestUse) best.add(e);
        }
        return best.isEmpty() ? null : best.get(rnd.nextInt(best.size()));
    }
}
