import com.pixy.bot.Armable;
import com.pixy.bot.HornLogic;
import java.util.*;

/** Plain-Java check of the horn activation rules (no Minecraft needed). Run: see README "Self-test". */
public class HornLogicTest {
    static class FakeBot implements Armable {
        boolean hunting; long armedBy = -1; final long spawnedAt;
        FakeBot(long spawnedAt) { this.spawnedAt = spawnedAt; }
        public boolean isHunting() { return hunting; }
        public void activate(long g) { hunting = true; armedBy = g; }
    }
    static long horns = 0;
    static List<FakeBot> bots = new ArrayList<>();
    static List<FakeBot> spawn(int n) { List<FakeBot> l = new ArrayList<>(); for (int i = 0; i < n; i++) { FakeBot b = new FakeBot(horns); bots.add(b); l.add(b); } return l; }
    static int horn() { return HornLogic.blow(bots, ++horns); }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError("FAILED: " + what); System.out.println("ok  " + what); }

    public static void main(String[] a) {
        List<FakeBot> first = spawn(10);
        check(first.stream().noneMatch(FakeBot::isHunting), "TEST 0: 10 new bots start passive");
        check(horn() == 10 && first.stream().allMatch(FakeBot::isHunting), "TEST 1: horn arms exactly those 10");
        List<FakeBot> second = spawn(20);
        check(second.stream().noneMatch(FakeBot::isHunting), "TEST 2: 20 bots spawned after the horn stay passive");
        check(first.stream().allMatch(FakeBot::isHunting), "      earlier bots keep hunting");
        check(horn() == 20 && second.stream().allMatch(b -> b.armedBy == 2), "TEST 3: next horn arms only the 20 waiting bots");
        check(first.stream().allMatch(b -> b.armedBy == 1), "      first 10 keep their original activation (not re-armed)");

        bots.clear();
        for (int cycle = 0; cycle < 500; cycle++) {
            List<FakeBot> g = spawn(1 + cycle % 7);
            check0(g.stream().noneMatch(FakeBot::isHunting), "cycle " + cycle + ": new group passive");
            int armed = horn();
            check0(armed == g.size() && g.stream().allMatch(b -> b.armedBy == horns), "cycle " + cycle + ": horn armed exactly the new group");
            bots.removeIf(b -> b != null && Math.random() < 0.5); // some bots die between cycles
        }
        System.out.println("ok  TEST 4: 500 spawn/horn/die cycles, no bot ever inherited an old horn");
        List<FakeBot> late = spawn(3);
        check(late.stream().noneMatch(FakeBot::isHunting) && horns == 502, "after many horns, a fresh bot is still passive");
        System.out.println("ALL HORN TESTS PASSED");
    }
    static void check0(boolean ok, String what) { if (!ok) throw new AssertionError("FAILED: " + what); }
}
