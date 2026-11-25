package com.pixy.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * "Think about how to reach the player" when the direct route fails: propose waypoints on rings around the bot,
 * best (closest to the target) first. The caller checks each with a real path search (limited per tick) and walks to
 * the first reachable one that gets it clearly closer - e.g. out of the pit's south exit instead of into its north wall.
 */
public final class ExplorePlanner {
    private ExplorePlanner() { }

    public interface Reach { boolean canReach(int x, int z); }
    public record Cand(int x, int z, double distSqToTarget) { }

    public static List<Cand> candidates(int bx, int bz, int tx, int tz, double angleOffset, int[] radii, int dirs) {
        List<Cand> l = new ArrayList<>();
        for (int r : radii) {
            for (int k = 0; k < dirs; k++) {
                double a = angleOffset + 2 * Math.PI * k / dirs;
                int x = bx + (int) Math.round(r * Math.cos(a));
                int z = bz + (int) Math.round(r * Math.sin(a));
                double dx = x - tx, dz = z - tz;
                l.add(new Cand(x, z, dx * dx + dz * dz));
            }
        }
        l.sort(Comparator.comparingDouble(Cand::distSqToTarget));
        return l;
    }

    /** Is this waypoint worth walking to? It must bring the bot clearly closer than it already is. */
    public static boolean improves(Cand c, double currentDistSq, double minGainBlocks) {
        double cur = Math.sqrt(currentDistSq), now = Math.sqrt(c.distSqToTarget());
        return now < cur - minGainBlocks;
    }

    /** Test helper / reference behaviour: first reachable improving candidate, or null. */
    public static Cand firstReachable(List<Cand> cands, double currentDistSq, double minGain,
                                      Reach reach, Predicate<Cand> blocked) {
        for (Cand c : cands) {
            if (!improves(c, currentDistSq, minGain)) continue;
            if (blocked != null && blocked.test(c)) continue;
            if (reach.canReach(c.x(), c.z())) return c;
        }
        return null;
    }
}
