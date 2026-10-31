package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.hand;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import ezmj.model.Tile;
import ezmj.rules.HandAnalyzer;
import ezmj.rules.HandAnalyzer.Decomposition;
import ezmj.rules.HandAnalyzer.TileSet;

/** 胡牌判定：固定案例，再用另一套獨立寫法的判定程式交叉比對大量隨機手牌。 */
final class HandAnalyzerTest {

    private HandAnalyzerTest() {
    }

    static void run() {
        T.section("胡牌判定");

        test("基本胡牌牌型", () -> {
            win("五順一對", "1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p");
            win("五刻一對", "1w1w1w 5p5p5p 9s9s9s 1z1z1z 5z5z5z 7z7z");
            win("順刻混合", "1w1w1w 2w3w4w 7p8p9p 5s5s5s 1z1z1z 6z6z");
            win("亮五組只剩眼睛", "5z5z");
            win("亮四組", "1w2w3w 9p9p");
            win("亮三組", "1w2w3w 7s7s7s 1z1z");
        });

        test("眼睛必須選對才拆得開", () -> {
            win("眼睛在刻子內", "1w1w1w2w3w 4w5w6w 7w8w9w 1p2p3p 4p5p6p");
            win("四張同牌拆成順加刻", "1w1w1w1w 2w3w 5p6p7p 2s3s4s 6s7s8s 1z1z");
            win("連續對子", "1w1w2w2w3w3w 4p5p6p 7p8p9p 1s2s3s 5z5z");
            win("九蓮寶燈形", "1w1w1w2w3w4w5w6w7w8w9w9w9w 5w 1z1z1z");
        });

        test("不成牌的手牌", () -> {
            lose("差一張", "1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s8s 9p9p");
            lose("沒有眼睛", "1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p1z");
            lose("數牌不能跨花色成順", "8w9w1p 2w3w4w 5w6w7w 1s2s3s 4s5s6s 1z1z");
            lose("九一不相連", "8w9w1w 2p3p4p 5p6p7p 1s2s3s 4s5s6s 1z1z");
            lose("風牌不能成順", "1z2z3z 2w3w4w 5w6w7w 1s2s3s 4s5s6s 1p1p");
            lose("三元不能成順", "5z6z7z 2w3w4w 5w6w7w 1s2s3s 4s5s6s 1p1p");
            lose("七對不是胡牌", "1w1w2w2w 3p3p4p4p 5s5s6s6s 1z1z 5z5z 7z");
            lose("全不靠", "1w4w7w 2p5p8p 3s6s9s 1z2z3z 4z5z6z 7z7w");
        });

        test("張數不是 3n+2 一律不算胡", () -> {
            lose("空手", "");
            lose("單張", "1z");
            lose("三張", "1w1w1w");
            lose("四張", "1w1w1w1w");
            lose("十六張", "1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p");
        });

        test("聽牌：兩面、邊張、中洞、單吊、多面", () -> {
            eq(hand("3w6w"), waits("1w2w3w 4w5w 7p8p9p 2s3s4s 5s6s7s 9p9p"), "兩面");
            eq(hand("3w"), waits("1w2w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p"), "邊張 12");
            eq(hand("7w"), waits("8w9w 1w2w3w 7p8p9p 2s3s4s 5s6s7s 1z1z"), "邊張 89");
            eq(hand("8p"), waits("1w2w3w 4w5w6w 7p9p 2s3s4s 5s6s7s 1s1s"), "中洞");
            eq(hand("1z"), waits("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 1z"), "單吊");
            eq(hand("1s4s7s"), waits("1w2w3w 4w5w6w 7p8p9p 2s3s4s5s6s 9p9p"), "三面聽");
            eq(hand("9p"), waits("1w2w3w 4w5w6w 2s3s4s 5s6s7s 9p"), "單吊數牌");
            check(waits("1w3w5w 7w9w 2p4p6p 8p1s 3s5s7s 9s1z").isEmpty(), "沒聽牌");
        });

        test("聽牌：手上已有四張的牌不算聽", () -> {
            List<Tile> waits = waits("1w1w1w1w 2w3w 5p6p7p 2s3s4s 6s7s8s 1z");
            check(!waits.contains(tile("1w")), "已有四張一萬");
            eq(hand("1z"), waits, "只能單吊東");
        });

        test("每種拆法的張數都等於原手牌", () -> {
            List<Tile> tiles = hand("1w1w1w2w2w2w3w3w3w 4p5p6p 7s8s9s 9p9p");
            List<Decomposition> decompositions = HandAnalyzer.decompose(tiles);
            check(decompositions.size() >= 2, "應該有刻子與順子兩種拆法");
            for (Decomposition decomposition : decompositions) {
                assertReassembles(tiles, decomposition);
            }
        });

        test("隨機胡牌型必定判定為胡，且拆解能還原", () -> {
            Random random = new Random(11);
            for (int i = 0; i < 20000; i++) {
                List<Tile> tiles = randomWinningHand(random, 5 - random.nextInt(6));
                check(HandAnalyzer.canWin(tiles), "應該胡: " + tiles);
                for (Decomposition decomposition : HandAnalyzer.decompose(tiles)) {
                    assertReassembles(tiles, decomposition);
                }
            }
        });

        test("與獨立判定程式交叉比對 200000 手隨機牌", () -> {
            Random random = new Random(12);
            int wins = 0;
            for (int i = 0; i < 200000; i++) {
                int sets = 5 - random.nextInt(6);
                List<Tile> tiles = randomWinningHand(random, sets);
                // 換掉 0 到 2 張，產生接近胡牌但不一定胡的手牌
                for (int change = random.nextInt(3); change > 0; change--) {
                    Tile replacement = Tile.fromIndex(random.nextInt(Tile.KINDS));
                    if (Collections.frequency(tiles, replacement) < 4) {
                        tiles.set(random.nextInt(tiles.size()), replacement);
                    }
                }
                boolean expected = referenceCanWin(tiles);
                eq(expected, HandAnalyzer.canWin(tiles), "判定不一致 " + tiles);
                if (expected) {
                    wins++;
                }
            }
            check(wins > 10000 && wins < 190000, "樣本要同時涵蓋胡與不胡，胡牌數 " + wins);
        });

        test("聽牌結果等於逐張試摸的結果", () -> {
            Random random = new Random(13);
            for (int i = 0; i < 5000; i++) {
                List<Tile> tiles = randomWinningHand(random, 5);
                tiles.remove(random.nextInt(tiles.size()));
                List<Tile> expected = new ArrayList<>();
                for (int index = 0; index < Tile.KINDS; index++) {
                    Tile candidate = Tile.fromIndex(index);
                    List<Tile> trial = new ArrayList<>(tiles);
                    trial.add(candidate);
                    if (Collections.frequency(tiles, candidate) < 4 && referenceCanWin(trial)) {
                        expected.add(candidate);
                    }
                }
                eq(expected, HandAnalyzer.waits(tiles), "聽牌 " + tiles);
                check(!expected.isEmpty(), "拿掉一張的胡牌必定有聽");
            }
        });
    }

    private static void win(String name, String tiles) {
        check(HandAnalyzer.canWin(hand(tiles)), name + " 應該胡");
    }

    private static void lose(String name, String tiles) {
        check(!HandAnalyzer.canWin(hand(tiles)), name + " 不應該胡");
    }

    private static List<Tile> waits(String tiles) {
        return HandAnalyzer.waits(hand(tiles));
    }

    private static void assertReassembles(List<Tile> tiles, Decomposition decomposition) {
        List<Tile> rebuilt = new ArrayList<>();
        rebuilt.add(decomposition.pair());
        rebuilt.add(decomposition.pair());
        for (TileSet set : decomposition.sets()) {
            for (int offset = 0; offset < 3; offset++) {
                rebuilt.add(set.sequence() ? Tile.of(set.first().suit(), set.first().rank() + offset) : set.first());
            }
        }
        List<Tile> expected = new ArrayList<>(tiles);
        Collections.sort(expected);
        Collections.sort(rebuilt);
        eq(expected, rebuilt, "拆解還原");
    }

    /** 組出一手合法胡牌（每種牌不超過 4 張）。 */
    private static List<Tile> randomWinningHand(Random random, int sets) {
        while (true) {
            List<Tile> tiles = new ArrayList<>();
            for (int i = 0; i < sets; i++) {
                Tile first = Tile.fromIndex(random.nextInt(Tile.KINDS));
                if (first.isNumber() && first.rank() <= 7 && random.nextBoolean()) {
                    for (int offset = 0; offset < 3; offset++) {
                        tiles.add(Tile.of(first.suit(), first.rank() + offset));
                    }
                } else {
                    tiles.addAll(Collections.nCopies(3, first));
                }
            }
            tiles.addAll(Collections.nCopies(2, Tile.fromIndex(random.nextInt(Tile.KINDS))));
            boolean legal = tiles.stream().allMatch(tile -> Collections.frequency(tiles, tile) <= 4);
            if (legal) {
                Collections.shuffle(tiles, random);
                return tiles;
            }
        }
    }

    /** 參考實作：刻意用排序清單與遞迴移除，不共用受測程式的計數陣列邏輯。 */
    private static boolean referenceCanWin(List<Tile> tiles) {
        if (tiles.size() % 3 != 2) {
            return false;
        }
        List<Tile> sorted = new ArrayList<>(tiles);
        Collections.sort(sorted);
        for (int i = 0; i + 1 < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i + 1)) && (i == 0 || !sorted.get(i - 1).equals(sorted.get(i)))) {
                List<Tile> rest = new ArrayList<>(sorted);
                rest.remove(i + 1);
                rest.remove(i);
                if (referenceAllSets(rest)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean referenceAllSets(List<Tile> sorted) {
        if (sorted.isEmpty()) {
            return true;
        }
        Tile head = sorted.get(0);
        if (sorted.size() >= 3 && sorted.get(1).equals(head) && sorted.get(2).equals(head)) {
            if (referenceAllSets(sorted.subList(3, sorted.size()))) {
                return true;
            }
        }
        if (head.isHonor() || head.rank() > 7) {
            return false;
        }
        List<Tile> rest = new ArrayList<>(sorted);
        rest.remove(0);
        return rest.remove(Tile.of(head.suit(), head.rank() + 1))
                && rest.remove(Tile.of(head.suit(), head.rank() + 2))
                && referenceAllSets(rest);
    }
}
