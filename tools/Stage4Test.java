import com.pixy.bot.*;
import java.util.*;
import java.util.regex.Pattern;

/** Plain-Java checks of the Stage 4 rules (no Minecraft needed). */
public class Stage4Test {
    static int checks = 0;
    static void ok(boolean c, String what) { checks++; if (!c) throw new AssertionError("FAILED: " + what); System.out.println("ok  " + what); }
    static void q(boolean c, String what) { checks++; if (!c) throw new AssertionError("FAILED: " + what); }

    public static void main(String[] a) {
        identities();
        personas();
        aim();
        pit();
        System.out.println("ALL STAGE 4 LOGIC TESTS PASSED (" + checks + " checks)");
    }

    // ---------------------------------------------------------------- 1 + 2: 50 unique names / skins
    static void identities() {
        List<IdentityEntry> all = DefaultIdentities.list();
        Pattern valid = Pattern.compile("[A-Za-z0-9_]{3,16}");
        ok(all.size() == 50, "50 built-in identities");
        Set<String> names = new HashSet<>(), skins = new HashSet<>();
        boolean allValid = true, noDigitsSuffix = true;
        for (IdentityEntry e : all) {
            names.add(e.name.toLowerCase()); skins.add(e.skin.toLowerCase());
            allValid &= valid.matcher(e.name).matches() && valid.matcher(e.skin).matches();
            noDigitsSuffix &= !e.name.matches(".*\\d{2,}$");
        }
        ok(names.size() == 50, "50 distinct names (no duplicates)");
        ok(skins.size() == 50, "50 distinct skin sources");
        ok(allValid, "every name and skin source is a valid Minecraft username (3-16 chars, letters/digits/_)");
        ok(noDigitsSuffix, "no auto-appended number suffixes like Shadow0035");
        for (String must : new String[] {"PixiePlays","WardenPlays","Xyros","Cyrus","Zayox","Sylphie","Rudeus","Aether","Nyx","Kaizen",
                "Vex","Draco","Zephyr","Orion","Axel","Kairo","Frost","Blaze","Echo","Nova"})
            q(names.contains(must.toLowerCase()), "requested name present: " + must);
        System.out.println("ok  all 20 names from the request are included");

        Random rnd = new Random(1);
        Map<String,Integer> use = new HashMap<>();
        Set<String> sn = new HashSet<>(), ss = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            IdentityEntry e = IdentityPicker.pick(all, use, Set.of(), rnd);
            q(sn.add(e.name) && ss.add(e.skin), "bot " + (i + 1) + " is new");
            use.merge(e.name.toLowerCase(), 1, Integer::sum);
        }
        ok(sn.size() == 50 && ss.size() == 50, "TEST: 50 bots -> 50 unique names and 50 unique skins");
        int reused = 0;
        for (int i = 0; i < 25; i++) {
            IdentityEntry e = IdentityPicker.pick(all, use, Set.of(), rnd);
            if (use.getOrDefault(e.name.toLowerCase(), 0) > 0) reused++;
            use.merge(e.name.toLowerCase(), 1, Integer::sum);
        }
        ok(reused == 25, "bots 51+ reuse identities (allowed), still no numbers");
        int maxUse = Collections.max(use.values());
        ok(maxUse == 2, "reuse is spread evenly (nobody used 3 times before everyone used twice)");

        // churn: bots die and are replaced; while fewer than 50 are alive a new bot never copies a living one
        use.clear(); List<IdentityEntry> alive = new ArrayList<>();
        for (int step = 0; step < 5000; step++) {
            if (!alive.isEmpty() && rnd.nextInt(100) < 45) {
                IdentityEntry d = alive.remove(rnd.nextInt(alive.size()));
                use.merge(d.name.toLowerCase(), -1, Integer::sum);
            } else {
                IdentityEntry e = IdentityPicker.pick(all, use, Set.of(), rnd);
                if (alive.size() < 50) q(use.getOrDefault(e.name.toLowerCase(), 0) == 0, "churn step " + step + ": no duplicate while < 50 alive");
                alive.add(e); use.merge(e.name.toLowerCase(), 1, Integer::sum);
            }
        }
        System.out.println("ok  5000 random spawn/die steps: a living bot's identity is never copied while fewer than 50 exist");
        IdentityEntry e = IdentityPicker.pick(all, new HashMap<>(), Set.of("pixieplays"), new Random(2));
        ok(!e.name.equals("PixiePlays"), "a name currently used by a real online player is skipped");
        ok(DefaultIdentities.backups().size() >= 6, "backup skins exist for failed lookups");
    }

    // ---------------------------------------------------------------- 6: individual idle personalities
    static void personas() {
        List<Persona> ps = new ArrayList<>();
        for (int i = 0; i < 50; i++) ps.add(new Persona(i * 0x9E3779B97F4A7C15L ^ ("name" + i).hashCode()));
        Set<Integer> delays = new HashSet<>(); Set<String> seqs = new HashSet<>();
        double minAct = 9, maxAct = 0;
        for (Persona p : ps) {
            delays.add(p.firstDelay); minAct = Math.min(minAct, p.activity); maxAct = Math.max(maxAct, p.activity);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(p.nextAction().ordinal());
            seqs.add(sb.toString());
        }
        ok(delays.size() >= 40, "first-action delays differ between bots (not synchronized): " + delays.size() + "/50 distinct");
        ok(seqs.size() >= 45, "action sequences differ between bots: " + seqs.size() + "/50 distinct");
        ok(maxAct - minAct > 0.5, "some bots are calm, some restless");
        Persona p1 = new Persona(42), p2 = new Persona(42);
        boolean same = true; for (int i = 0; i < 20; i++) same &= p1.nextAction() == p2.nextAction();
        ok(same, "same seed -> same personality (reproducible)");
        Set<Persona.Action> seen = new HashSet<>();
        for (Persona p : ps) for (int i = 0; i < 40; i++) seen.add(p.nextAction());
        ok(seen.size() == Persona.Action.values().length, "all movement types occur (walk fwd/right/left, look, jump, approach bot, pause)");
        boolean inRange = true;
        for (Persona p : ps) for (int i = 0; i < 50; i++) inRange &= p.walkBlocks() >= 2 && p.walkBlocks() <= 4 && p.pauseTicks() >= 40;
        ok(inRange, "walks are 2-4 blocks and pauses are never zero");
    }

    // ---------------------------------------------------------------- 5: projectile aiming (ender pearl)
    static void aim() {
        ProjectileAim.Solid flat = (x, y, z) -> y <= 0.0;
        ProjectileAim.Result r = ProjectileAim.solve(0, 1.52, 0, 30, 0, 5, 1.5, 0.03, 0.99, flat);
        ok(r.hit && r.miss < 1.5, "pearl aim on flat ground, 30 blocks: miss " + String.format("%.2f", r.miss) + " blocks");
        r = ProjectileAim.solve(0, 1.52, 0, 0, 0, -40, 1.5, 0.03, 0.99, flat);
        ok(r.hit && r.miss < 1.5, "pearl aim behind the bot (yaw wraps correctly): miss " + String.format("%.2f", r.miss));
        ProjectileAim.Solid roof = (x, y, z) -> y <= 0.0 || (y <= 8.0 && x >= 20 && x <= 40);
        r = ProjectileAim.solve(0, 1.52, 0, 30, 8, 0, 1.5, 0.03, 0.99, roof);
        ok(r.hit && r.miss < 2.0, "pearl aim onto a 8-block-high roof: miss " + String.format("%.2f", r.miss));
        r = ProjectileAim.solve(0, 1.52, 0, 70, 0, 0, 1.5, 0.03, 0.99, flat);
        ok(r.miss > 8.0, "out of range (70 blocks) is reported as a miss, so the bot does not waste the pearl: miss " + String.format("%.1f", r.miss));
    }

    // ---------------------------------------------------------------- 3: pit + blocked north, exit south
    static void pit() {
        // 41x41 map. '#' wall. Pit interior x 15..25, z 15..25 (walls around), exit ramp only through the SOUTH wall (z=26..) at x=19..21.
        int N = 41; boolean[][] wall = new boolean[N][N];
        for (int x = 14; x <= 26; x++) for (int z = 14; z <= 26; z++) wall[x][z] = (x == 14 || x == 26 || z == 14 || z == 26);
        for (int x = 19; x <= 21; x++) wall[x][26] = false;           // south exit
        int bx = 20, bz = 20, tx = 20, tz = 2;                        // bot in pit centre, player far NORTH (z smaller)
        ExplorePlanner.Reach reach = bfs(wall, bx, bz);
        double cur = Math.pow(bx - tx, 2) + Math.pow(bz - tz, 2);
        var cands = ExplorePlanner.candidates(bx, bz, tx, tz, 0.3, new int[] {8, 14}, 12);
        var c = ExplorePlanner.firstReachable(cands, cur, 2.0, reach, null);
        ok(c != null && reach.canReach(c.x(), c.z()), "pit with exit only to the south: planner finds a reachable waypoint");
        ok(!(c.x() >= 15 && c.x() <= 25 && c.z() >= 15 && c.z() <= 25), "  ...and it is OUTSIDE the pit (not a spot inside the walls)");
        ok(routePassesSouthGap(wall, bx, bz, c.x(), c.z()),
           "  ...the walking route to it goes THROUGH the south opening (it never tries the blocked north wall): waypoint (" + c.x() + "," + c.z() + ")");

        boolean[][] sealed = new boolean[N][N];
        for (int x = 14; x <= 26; x++) for (int z = 14; z <= 26; z++) sealed[x][z] = (x == 14 || x == 26 || z == 14 || z == 26);
        var none = ExplorePlanner.firstReachable(cands, cur, 2.0, bfs(sealed, bx, bz), null);
        ok(none == null, "sealed pit: planner says 'no walking route' (bot then switches to wind charge / ender pearl or waits)");

        Set<String> blocked = new HashSet<>(); blocked.add(c.x() + "," + c.z());
        var c2 = ExplorePlanner.firstReachable(cands, cur, 2.0, reach, k -> blocked.contains(k.x() + "," + k.z()));
        ok(c2 != null && !(c2.x() == c.x() && c2.z() == c.z()), "a waypoint that failed before is not retried (no repeated impossible route)");
    }

    /** BFS route from (sx,sz) to (gx,gz); true if it passes through the south gap in the pit wall. */
    static boolean routePassesSouthGap(boolean[][] wall, int sx, int sz, int gx, int gz) {
        int N = wall.length; int[][][] par = new int[N][N][]; boolean[][] seen = new boolean[N][N];
        ArrayDeque<int[]> q = new ArrayDeque<>(); q.add(new int[] {sx, sz}); seen[sx][sz] = true;
        int[][] d = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty()) { int[] p = q.poll(); for (int[] m : d) { int x = p[0]+m[0], z = p[1]+m[1];
            if (x<0||z<0||x>=N||z>=N||seen[x][z]||wall[x][z]) continue; seen[x][z] = true; par[x][z] = p; q.add(new int[]{x,z}); } }
        if (!seen[gx][gz]) return false;
        for (int[] c = new int[] {gx, gz}; c != null; c = par[c[0]][c[1]])
            if (c[1] == 26 && c[0] >= 19 && c[0] <= 21) return true;
        return false;
    }

    static ExplorePlanner.Reach bfs(boolean[][] wall, int sx, int sz) {
        int N = wall.length; boolean[][] seen = new boolean[N][N]; ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[] {sx, sz}); seen[sx][sz] = true;
        int[][] d = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty()) { int[] p = q.poll(); for (int[] m : d) { int x = p[0]+m[0], z = p[1]+m[1];
            if (x<0||z<0||x>=N||z>=N||seen[x][z]||wall[x][z]) continue; seen[x][z] = true; q.add(new int[]{x,z}); } }
        return (x, z) -> x >= 0 && z >= 0 && x < N && z < N && seen[x][z];
    }
}
