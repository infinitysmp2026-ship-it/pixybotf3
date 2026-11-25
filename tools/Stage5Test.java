import com.pixy.bot.*;
import java.util.*;

/** Plain-Java checks of the Stage 5 decision rules (pearl policy, bridging, pillaring). No Minecraft needed. */
public class Stage5Test {
    static int checks = 0;
    static void ok(boolean c, String what) { checks++; if (!c) throw new AssertionError("FAILED: " + what); System.out.println("ok  " + what); }

    static PearlPolicy.In base() { PearlPolicy.In i = new PearlPolicy.In(); i.pearls = 3; i.huntTicks = 400; i.dist = 30; i.routeExists = true; return i; }

    public static void main(String[] a) {
        pearls();
        bridge();
        pillar();
        System.out.println("ALL STAGE 5 LOGIC TESTS PASSED (" + checks + " checks)");
    }

    static void pearls() {
        PearlPolicy.In i = base(); i.dist = 45; i.distAgo = 45;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "far but standing still, route exists: NO pearl (it was walking-distance, not an escape)");
        i = base(); i.huntTicks = 30; i.dist = 40; i.distAgo = 28;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "first 1.5 s of the hunt: never opens with a pearl");
        i = base(); i.dist = 30; i.distAgo = 18;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.CHASE, "player running away (gap +12 in 5 s, now 30): CHASE pearl");
        i = base(); i.dist = 30; i.distAgo = 26;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "gap grew only 4 blocks: keep walking");
        i = base(); i.pearls = 1; i.dist = 30; i.distAgo = 18;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "last pearl is kept for emergencies (30 blocks is not enough)");
        i = base(); i.pearls = 1; i.dist = 40; i.distAgo = 28;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.CHASE, "...unless the target is really far (40) and escaping");
        i = base(); i.dist = 30; i.distAgo = 18; i.ticksSinceLastPearl = 100;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "5 s after the previous pearl: no second pearl (15 s cooldown)");
        i = base(); i.dist = 30; i.distAgo = 18; i.botHealth = 5;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "5 health: the teleport itself would hurt too much");
        i = base(); i.dist = 20; i.distAgo = 20; i.routeExists = false; i.trapped = true;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.TRAPPED, "trapped with no route, no blocks, no wind charge: TRAPPED pearl");
        i.canBridgeOrWind = true;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "...but not if it can bridge or wind-charge out instead");
        i = base(); i.heightDiff = 9; i.unreachable = true; i.routeExists = false; i.dist = 15;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.HIGH_GROUND, "target 9 blocks up on an unreachable roof: HIGH_GROUND pearl");
        i = base(); i.dist = 3; i.distAgo = 3; i.botHealth = 8.5;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.ESCAPE, "losing a duel (8.5 hp, enemy 3 blocks, no healing): ESCAPE pearl");
        i.hasHealing = true;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "...with a golden apple it fights/heals instead");
        i = base(); i.dist = 4; i.botHealth = 20;
        ok(PearlPolicy.decide(i) == PearlPolicy.Decision.NONE, "healthy duel at melee range: no pearl");
        // frequency: simulate a 60 s chase where the target keeps running (gap grows 12 blocks / 5 s)
        int thrown = 0, since = 99999;
        for (int t = 0; t < 1200; t += 20) {
            since += 20;
            PearlPolicy.In s = base(); s.huntTicks = 200 + t; s.dist = 30 + (t % 400) / 20.0; s.distAgo = s.dist - 12; s.ticksSinceLastPearl = since;
            if (PearlPolicy.decide(s) == PearlPolicy.Decision.CHASE) { thrown++; since = 0; }
        }
        ok(thrown <= 4, "even against a target that never stops running: at most 1 pearl per 15 s (" + thrown + " in 60 s)");
    }

    // A bot walks along a line: columns 0..N. ground[c] = solid under that column.
    static void bridge() {
        boolean[] ground = new boolean[40];
        for (int c = 0; c <= 3; c++) ground[c] = true;      // start platform (bot at column 3, the edge)
        for (int c = 9; c < 40; c++) ground[c] = true;      // far side; gap = columns 4..8 (5 blocks)
        Set<Integer> mine = new HashSet<>();
        int col = 3, placed = 0, blocks = 20, steps = 0, falls = 0;
        BridgePlanner.Act act = null;
        for (; steps < 200; steps++) {
            if (!(ground[col] || mine.contains(col))) falls++;
            act = BridgePlanner.next(ground[col + 1] || mine.contains(col + 1), mine.contains(col + 1), placed, 16, blocks > 0);
            if (act == BridgePlanner.Act.PLACE) { mine.add(col + 1); placed++; blocks--; }
            else if (act == BridgePlanner.Act.STEP) col++;
            else break;
        }
        ok(act == BridgePlanner.Act.DONE && placed == 5 && col == 8 && falls == 0,
           "bridge over a 5-block gap: placed exactly 5 blocks, never stood on air, stopped at the far ground (col " + col + ")");
        ok(blocks == 15, "  ...and used exactly 5 blocks from the inventory");

        // not enough blocks: aborts safely before the void
        mine.clear(); col = 3; placed = 0; blocks = 3; falls = 0;
        for (steps = 0; steps < 200; steps++) {
            if (!(ground[col] || mine.contains(col))) falls++;
            act = BridgePlanner.next(ground[col + 1] || mine.contains(col + 1), mine.contains(col + 1), placed, 16, blocks > 0);
            if (act == BridgePlanner.Act.PLACE) { mine.add(col + 1); placed++; blocks--; }
            else if (act == BridgePlanner.Act.STEP) col++;
            else break;
        }
        ok(act == BridgePlanner.Act.ABORT && falls == 0, "only 3 blocks for a 5-block gap: aborts while still standing on its own bridge (no fall)");

        // 20-block gap exceeds the 16-block limit
        boolean[] g2 = new boolean[60]; for (int c = 0; c <= 3; c++) g2[c] = true; for (int c = 25; c < 60; c++) g2[c] = true;
        mine.clear(); col = 3; placed = 0; blocks = 64;
        for (steps = 0; steps < 500; steps++) {
            act = BridgePlanner.next(g2[col + 1] || mine.contains(col + 1), mine.contains(col + 1), placed, 16, blocks > 0);
            if (act == BridgePlanner.Act.PLACE) { mine.add(col + 1); placed++; blocks--; }
            else if (act == BridgePlanner.Act.STEP) col++;
            else break;
        }
        ok(act == BridgePlanner.Act.ABORT && placed == 16, "a 21-block gap is not attempted beyond the 16-block limit");

        ok(BridgePlanner.dominantAxis(10, 2) == 0 && BridgePlanner.dominantAxis(-10, 2) == 1
           && BridgePlanner.dominantAxis(1, 9) == 2 && BridgePlanner.dominantAxis(1, -9) == 3, "bridge direction follows the target's dominant axis (E/W/S/N)");
    }

    static void pillar() {
        int base = 64;
        ok(!BridgePlanner.pillarReady(65.0, base), "pillar: no block while the bot is still at standing height");
        ok(!BridgePlanner.pillarReady(65.9, base), "pillar: no block while the feet are still inside the new block's space");
        ok(BridgePlanner.pillarReady(66.2, base), "pillar: block placed once the jump has lifted the feet above it");
        // a vanilla jump rises ~1.25 blocks: from feet 65.0 the apex is 66.25 >= 66.0 -> pillaring works with plain jumps
        double y = 65.0, vy = 0.42; double apex = y;
        for (int t = 0; t < 20; t++) { y += vy; vy = (vy - 0.08) * 0.98; apex = Math.max(apex, y); }
        ok(BridgePlanner.pillarReady(apex, base), "a normal jump (apex " + String.format("%.2f", apex) + ") reaches the height needed to place under itself");
    }
}
