package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ezmj.bot.ComputerBot;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.WallRing;
import ezmj.model.BotLevel;
import ezmj.model.Dice;
import ezmj.model.GameConfig;
import ezmj.model.Tile;
import ezmj.model.Wall;

/** 開門的骰子與畫面上的牌牆：點數、開門的位置，以及從局面推回牌頭與牌尾各拿了幾張。 */
final class WallRingTest {

    private WallRingTest() {
    }

    static void run() {
        T.section("骰子與牌牆");
        test("骰子：同樣的 key 一定一樣，網頁版用同樣的數字比對", () -> {
            eq(Dice.of("ABC234/0/1/1/0"), Dice.of("ABC234/0/1/1/0"), "同樣的 key");
            // 網頁版的 AllTests 也比對這兩組，兩邊算法要一起改
            eq(new Dice(1, 5, 1), Dice.of("ABC234/0/1/1/0"), "固定的點數");
            eq(new Dice(1, 6, 2), Dice.of(""), "空字串");
        });
        test("骰子：每一面出現的次數差不多", () -> {
            int[][] counts = new int[3][7];
            for (int i = 0; i < 6000; i++) {
                Dice dice = Dice.of("R" + i + "/" + (i % 4) + "/1/1/" + (i % 3));
                counts[0][dice.first()]++;
                counts[1][dice.second()]++;
                counts[2][dice.third()]++;
            }
            for (int die = 0; die < 3; die++) {
                for (int face = 1; face <= 6; face++) {
                    int count = counts[die][face];
                    check(count > 850 && count < 1150, "第 " + (die + 1) + " 顆 " + face + " 點出現 " + count + " 次");
                }
            }
        });
        test("骰子：只有 1 到 6 點，總和 3 到 18", () -> {
            fails(IllegalArgumentException.class, () -> new Dice(0, 1, 1), "0 點");
            fails(IllegalArgumentException.class, () -> new Dice(1, 7, 1), "7 點");
            Random random = new Random(3);
            for (int i = 0; i < 200; i++) {
                int total = Dice.roll(random).total();
                check(total >= 3 && total <= 18, "總和 " + total);
            }
            eq("4、5、2，共 11 點", new Dice(4, 5, 2).describe(), "紀錄的寫法");
        });
        test("牌牆：每家 18 墩，順時針從自己接到上家、對家、下家", () -> {
            eq(Tile.fullSet().size(), WallRing.TILES, "和牌一樣多");
            eq(0, WallRing.side(0), "自己最右邊");
            eq(0, WallRing.side(17), "自己最左邊");
            eq(17, WallRing.column(17), "自己最左邊是第 17 墩");
            eq(3, WallRing.side(18), "接著是上家");
            eq(0, WallRing.column(18), "上家最右邊");
            eq(2, WallRing.side(36), "對家");
            eq(1, WallRing.side(71), "最後是下家");
            eq(0, WallRing.side(72), "繞回自己");
            for (int stack = 0; stack < WallRing.STACKS; stack++) {
                eq(stack, WallRing.stack(WallRing.side(stack), WallRing.column(stack)), "第 " + stack + " 墩換回編號");
            }
        });
        test("開門：從莊家照行牌順序數點數，再從那家牌牆的右端數墩數", () -> {
            eq(0, WallRing.openSeat(0, 5), "5 點是莊家自己");
            eq(1, WallRing.openSeat(0, 6), "6 點是下家");
            eq(2, WallRing.openSeat(0, 11), "11 點是對家");
            eq(3, WallRing.openSeat(0, 16), "16 點是上家");
            eq(0, WallRing.openSeat(2, 11), "對家當莊、11 點是自己");
            WallRing ring = new WallRing(0, new Dice(4, 5, 2));
            eq(2, ring.openSeat(), "對家開門");
            eq(2, WallRing.side(ring.start()), "牌頭在對家的牌牆");
            eq(11, WallRing.column(ring.start()), "右端數過 11 墩");
            eq(ring.start(), ring.headStack(1), "一墩兩張");
            eq(ring.start() + 1, ring.headStack(2), "牌頭往順時針");
            eq(ring.start() - 1, ring.tailStack(0), "牌尾在開門處的另一邊");
            eq(ring.tailStack(0), ring.headStack(WallRing.TILES - 1), "牌頭繞一圈，最後一墩就是牌尾第一墩");
            WallRing far = new WallRing(1, new Dice(6, 6, 6));
            eq(2, far.openSeat(), "下家當莊、18 點數到對家");
            eq(1, WallRing.side(far.start()), "數完整面 18 墩，牌頭在順時針下一面，也就是下家的牌牆");
            eq(0, WallRing.column(far.start()), "下家的最右邊");
        });
        test("開門：拿走的墩依序空掉，每墩先拿上面那張", () -> {
            WallRing ring = new WallRing(0, new Dice(1, 1, 1));
            int start = ring.start();
            eq(2, ring.remaining(start, 0, 0), "還沒拿");
            eq(1, ring.remaining(start, 1, 0), "拿走上面");
            eq(0, ring.remaining(start, 2, 0), "整墩拿走");
            eq(2, ring.remaining(start + 1, 2, 0), "下一墩還在");
            eq(1, ring.remaining(start - 1, 0, 1), "牌尾拿走上面");
            eq(2, ring.remaining(start - 2, 0, 1), "牌尾的下一墩還在");
            int left = 0;
            for (int stack = 0; stack < WallRing.STACKS; stack++) {
                left += ring.remaining(stack, 65, 5);
            }
            eq(WallRing.TILES - 70, left, "剩下的張數");
        });
        test("牌牆：實際打牌時，畫面從局面推回的牌頭與牌尾和引擎一樣，開門處也一樣", () -> {
            Random random = new Random(21);
            ComputerBot[] bots = new ComputerBot[4];
            for (int seat = 0; seat < 4; seat++) {
                bots[seat] = new ComputerBot(BotLevel.AMATEUR, 100, 20, new Random(seat));
            }
            int checkedTail = 0;
            for (int r = 0; r < 40; r++) {
                GameSession session = new GameSession(new GameConfig("測試", 100_000, 100, 20, BotLevel.AMATEUR), random);
                Round round = session.newRound(message -> { });
                check(round.dice() != null, "開局就擲好骰子");
                round.start();
                if (round.phase() != Round.Phase.FINISHED) {
                    eq(65, WallRing.taken(round).head(), "發完牌，莊家多一張");
                }
                while (round.phase() != Round.Phase.FINISHED) {
                    checkedTail += matches(round);
                    if (round.phase() == Round.Phase.TURN) {
                        int seat = round.current();
                        bots[seat].playTurn(round, seat, round.turnOptions(seat));
                    } else {
                        Map<Integer, Claim> decisions = new HashMap<>();
                        for (int seat = 0; seat < 4; seat++) {
                            List<Claim> options = round.claimOptions(seat);
                            if (!options.isEmpty()) {
                                Claim claim = bots[seat].chooseClaim(round, seat, options);
                                if (claim != null) {
                                    decisions.put(seat, claim);
                                }
                            }
                        }
                        round.resolveClaims(decisions);
                    }
                }
            }
            check(checkedTail > 0, "有比對到從牌尾補過牌的局面");
        });
    }

    /** 畫面推回的牌頭與牌尾張數、開門處都要和引擎的牌牆一樣。回傳這次牌尾有沒有拿過牌。 */
    private static int matches(Round round) {
        WallRing.Taken taken = WallRing.taken(round);
        Wall wall = round.wall();
        eq(wall.headTaken(), taken.head(), "牌頭拿了幾張");
        eq(wall.tailTaken(), taken.tail(), "牌尾拿了幾張");
        WallRing ring = new WallRing(round.dealer(), round.dice());
        eq(wall.start(), ring.start(), "開門處");
        int left = 0;
        for (int stack = 0; stack < WallRing.STACKS; stack++) {
            left += ring.remaining(stack, taken.head(), taken.tail());
        }
        eq(wall.size(), left, "畫面上剩下的張數");
        return taken.tail() > 0 ? 1 : 0;
    }
}
