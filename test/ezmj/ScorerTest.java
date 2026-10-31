package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.hand;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ezmj.model.Meld;
import ezmj.model.Tile;
import ezmj.rules.ScoreGuide;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;
import ezmj.rules.Scorer;
import ezmj.rules.WinContext;

/** 計台：每個牌型都驗證成立與不成立的情況，重要案例驗證總台數。 */
final class ScorerTest {

    private static final String PLAIN = "1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p";
    private static final Set<ScoreItem> SCORED_ITEMS = new HashSet<>();

    private ScorerTest() {
    }

    /** 預設情境：東風圈、坐南、放槍、無花、無面子。 */
    private static final class Spec {
        private final String concealed;
        private final String winTile;
        private List<Meld> melds = List.of();
        private List<Tile> flowers = List.of();
        private boolean selfDraw;
        private int roundWind = 1;
        private int seatWind = 2;
        private boolean robbed;
        private boolean afterKong;
        private boolean lastTile;
        private boolean heavenly;
        private boolean earthly;

        Spec(String concealed, String winTile) {
            this.concealed = concealed;
            this.winTile = winTile;
        }

        Spec selfDraw() {
            selfDraw = true;
            return this;
        }

        Spec melds(Meld... value) {
            melds = List.of(value);
            return this;
        }

        Spec flowers(String value) {
            flowers = hand(value);
            return this;
        }

        Spec winds(int round, int seat) {
            roundWind = round;
            seatWind = seat;
            return this;
        }

        Spec robbed() {
            robbed = true;
            return this;
        }

        Spec afterKong() {
            afterKong = true;
            return this;
        }

        Spec lastTile() {
            lastTile = true;
            return this;
        }

        Spec heavenly() {
            heavenly = true;
            return this;
        }

        Spec earthly() {
            earthly = true;
            return this;
        }

        ScoreResult score() {
            ScoreResult result = Scorer.score(new WinContext(hand(concealed), melds, flowers, tile(winTile), selfDraw,
                    roundWind, seatWind, robbed, afterKong, lastTile, heavenly, earthly));
            SCORED_ITEMS.addAll(result.items());
            return result;
        }
    }

    private static Spec ron(String concealed, String winTile) {
        return new Spec(concealed, winTile);
    }

    private static Spec tsumo(String concealed, String winTile) {
        return new Spec(concealed, winTile).selfDraw();
    }

    private static Meld chow(String tiles) {
        return new Meld(Meld.Type.CHOW, hand(tiles));
    }

    private static Meld pong(String tiles) {
        return new Meld(Meld.Type.PONG, hand(tiles));
    }

    private static Meld openKong(String tiles) {
        return new Meld(Meld.Type.OPEN_KONG, hand(tiles));
    }

    private static Meld concealedKong(String tiles) {
        return new Meld(Meld.Type.CONCEALED_KONG, hand(tiles));
    }

    private static void has(ScoreResult result, String... names) {
        for (String name : names) {
            check(result.has(name), "應該有 " + name + "：" + result);
        }
    }

    private static void hasNot(ScoreResult result, String... names) {
        for (String name : names) {
            check(!result.has(name), "不應該有 " + name + "：" + result);
        }
    }

    private static void total(int expected, ScoreResult result) {
        eq(expected, result.totalTai(), "總台數 " + result);
    }

    static void run() {
        T.section("計台");

        test("門清、自摸、門清一摸三互斥", () -> {
            ScoreResult ronClosed = ron(PLAIN, "6w").score();
            has(ronClosed, "門清");
            hasNot(ronClosed, "自摸", "門清一摸三");

            ScoreResult tsumoClosed = tsumo(PLAIN, "6w").score();
            has(tsumoClosed, "門清一摸三");
            hasNot(tsumoClosed, "門清", "自摸");
            total(3, tsumoClosed);

            ScoreResult tsumoOpen = tsumo("4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p", "6w")
                    .melds(chow("1w2w3w")).score();
            has(tsumoOpen, "自摸");
            hasNot(tsumoOpen, "門清", "門清一摸三");
            total(1, tsumoOpen);

            ScoreResult ronOpen = ron("4w5w6w 7p8p9p 2s3s4s 5s6s7s 5z5z", "6w")
                    .melds(pong("1w1w1w")).score();
            hasNot(ronOpen, "門清", "自摸", "門清一摸三");
        });

        test("暗槓不破門清，明槓與碰吃會破", () -> {
            String rest = "4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p";
            has(ron(rest, "6w").melds(concealedKong("1z1z1z1z")).score(), "門清");
            hasNot(ron(rest, "6w").melds(openKong("1z1z1z1z")).score(), "門清");
            hasNot(ron(rest, "6w").melds(pong("1z1z1z")).score(), "門清");
            hasNot(ron(rest, "6w").melds(chow("1w2w3w")).score(), "門清");
        });

        test("平胡成立時的總台數", () -> {
            ScoreResult result = ron(PLAIN, "6w").score();
            has(result, "平胡", "門清");
            total(3, result);
        });

        test("平胡不成立的情況", () -> {
            hasNot(tsumo(PLAIN, "6w").score(), "平胡");
            hasNot(ron(PLAIN, "6w").flowers("3f").score(), "平胡");
            hasNot(ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 5z5z", "6w").score(), "平胡");
            hasNot(ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 1z1z", "6w").score(), "平胡");
            hasNot(ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s5s5s 9p9p", "6w").score(), "平胡");
            hasNot(ron(PLAIN, "3w").score(), "平胡");
        });

        test("面子有吃仍可平胡", () -> {
            ScoreResult result = ron("4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p", "6w").melds(chow("1w2w3w")).score();
            has(result, "平胡");
            hasNot(result, "門清");
            total(2, result);
        });

        test("獨聽：邊張、中洞、單吊成立，兩面與多面不成立", () -> {
            ScoreResult edge = ron(PLAIN, "3w").score();
            has(edge, "獨聽", "門清");
            total(2, edge);
            has(ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 1s1s", "8p").score(), "獨聽");
            has(ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 1z1z", "1z").score(), "獨聽");
            hasNot(ron(PLAIN, "6w").score(), "獨聽");
            hasNot(ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 4s5s6s 9p9p", "4s").score(), "獨聽");
        });

        test("碰碰胡", () -> {
            ScoreResult result = ron("2w2w2w 3p3p3p 7s7s", "7s")
                    .melds(pong("1w1w1w"), pong("5p5p5p"), pong("9s9s9s")).score();
            has(result, "碰碰胡", "獨聽");
            hasNot(result, "三暗刻", "門清");
            total(5, result);
            hasNot(ron(PLAIN, "6w").score(), "碰碰胡");
            has(ron("3p3p3p 7s7s", "7s").melds(openKong("1w1w1w1w"), pong("5p5p5p"), pong("9s9s9s"),
                    concealedKong("2w2w2w2w")).score(), "碰碰胡");
        });

        test("清一色、混一色、字一色", () -> {
            ScoreResult pure = ron("1w2w3w 4w5w6w 7w8w9w 1w2w3w 4w5w6w 9w9w", "6w").score();
            has(pure, "清一色");
            hasNot(pure, "混一色", "字一色");

            ScoreResult mixed = ron("1w2w3w 4w5w6w 7w8w9w 1z1z1z 5z5z5z 9w9w", "6w").score();
            has(mixed, "混一色", "三元牌 中", "圈風 東");
            hasNot(mixed, "清一色", "字一色");

            ScoreResult honors = ron("1z1z1z 2z2z2z 3z3z3z 5z5z5z 6z6z6z 7z7z", "7z").score();
            has(honors, "字一色", "碰碰胡", "五暗刻", "小三元", "圈風 東", "門風 南", "門清", "獨聽");
            hasNot(honors, "混一色", "清一色");
            total(36, honors);

            has(ron("4w5w6w 7w8w9w 1w2w3w 5w5w", "6w").melds(pong("1z1z1z"), concealedKong("2w2w2w2w")).score(), "混一色");
            hasNot(ron(PLAIN, "6w").score(), "清一色", "混一色", "字一色");
            has(ron("3w4w5w 7w8w9w 1w2w3w 5w5w", "5w").melds(chow("6w7w8w"), concealedKong("9w9w9w9w")).score(), "清一色");
            has(ron("3p3p", "3p").melds(chow("1p2p3p"), chow("4p5p6p"), chow("7p8p9p"), pong("9p9p9p"),
                    chow("1p2p3p")).score(), "清一色");
        });

        test("大三元、小三元、單一三元牌", () -> {
            ScoreResult big = ron("1w2w3w 5z5z5z 6z6z6z 7z7z7z 4p5p6p 9s9s", "4p").score();
            has(big, "大三元", "三暗刻", "門清");
            hasNot(big, "三元牌 中", "三元牌 發", "三元牌 白", "小三元");
            total(11, big);

            ScoreResult small = ron("5z5z5z 6z6z6z 7z7z 1w2w3w 4p5p6p 7s8s9s", "7s").score();
            has(small, "小三元", "門清", "獨聽");
            hasNot(small, "大三元", "三元牌 中", "三元牌 發");
            total(6, small);

            ScoreResult one = ron("5z5z5z 1w2w3w 4w5w6w 1p2p3p 4s5s6s 9s9s", "6w").score();
            has(one, "三元牌 中");
            total(2, one);

            ScoreResult two = ron("5z5z5z 6z6z6z 1w2w3w 4w5w6w 7p8p9p 9s9s", "6w").score();
            has(two, "三元牌 中", "三元牌 發");
            hasNot(two, "小三元");

            hasNot(ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 5z5z", "6w").score(), "三元牌 中");
            has(ron("4w5w6w 1p2p3p 7s8s9s 1z1z", "6w").melds(pong("5z5z5z"), openKong("7z7z7z7z")).score(),
                    "三元牌 中", "三元牌 白");
        });

        test("大四喜、小四喜", () -> {
            ScoreResult big = ron("1z1z1z 2z2z2z 3z3z3z 4z4z4z 1w2w3w 9s9s", "3w").score();
            has(big, "大四喜", "四暗刻", "門清", "獨聽");
            hasNot(big, "小四喜", "圈風 東", "門風 南");
            total(23, big);

            ScoreResult small = ron("1z1z1z 2z2z2z 3z3z3z 4z4z 1w2w3w 4w5w6w", "6w").score();
            has(small, "小四喜", "三暗刻", "門清", "混一色");
            hasNot(small, "大四喜", "圈風 東", "門風 南");
            total(15, small);
        });

        test("圈風與門風", () -> {
            String east = "1z1z1z 1w2w3w 4w5w6w 1p2p3p 4s5s6s 9s9s";
            ScoreResult both = ron(east, "6w").winds(1, 1).score();
            has(both, "圈風 東", "門風 東");
            total(3, both);

            ScoreResult roundOnly = ron(east, "6w").winds(1, 2).score();
            has(roundOnly, "圈風 東");
            hasNot(roundOnly, "門風 南", "門風 東");

            ScoreResult seatOnly = ron(east, "6w").winds(2, 1).score();
            has(seatOnly, "門風 東");
            hasNot(seatOnly, "圈風 南", "圈風 東");

            ScoreResult neither = ron(east, "6w").winds(2, 3).score();
            total(1, neither);

            hasNot(ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 1z1z", "6w").winds(1, 1).score(), "圈風 東", "門風 東");
            has(ron("1w2w3w 4w5w6w 7p8p9p 5s6s7s 9p9p", "6w").winds(1, 1).melds(pong("1z1z1z")).score(), "圈風 東");
        });

        test("暗刻數量與放槍成刻的扣除", () -> {
            String three = "1w1w1w 5p5p5p 9s9s9s 2w3w4w 5s6s7s 9p9p";
            ScoreResult result = ron(three, "5s").score();
            has(result, "三暗刻", "門清");
            total(3, result);

            ScoreResult four = ron("1w1w1w 5p5p5p 9s9s9s 2z2z2z 5s6s7s 9p9p", "5s").winds(1, 1).score();
            has(four, "四暗刻");
            hasNot(four, "三暗刻", "五暗刻");
            total(6, four);

            ScoreResult fiveTsumo = tsumo("1w1w1w 5p5p5p 9s9s9s 2z2z2z 3p3p3p 9p9p", "3p").winds(1, 1).score();
            has(fiveTsumo, "五暗刻", "碰碰胡", "門清一摸三");
            hasNot(fiveTsumo, "四暗刻");

            ScoreResult ronFive = ron("1w1w1w 5p5p5p 9s9s9s 2z2z2z 3p3p3p 9p9p", "3p").winds(1, 1).score();
            has(ronFive, "四暗刻");
            hasNot(ronFive, "五暗刻");

            has(ron("1w1w1w 5p5p5p 9s9s9s 2z2z2z 3p3p3p 9p9p", "9p").winds(1, 1).score(), "五暗刻");
            has(ron("1w1w1w 1w2w3w 5p5p5p 9s9s9s 2s3s4s 7p7p", "1w").score(), "三暗刻");
        });

        test("暗槓算暗刻，明槓與碰不算", () -> {
            String rest = "5p5p5p 9s9s9s 2s3s4s 5s6s7s 9p9p";
            has(ron(rest, "5s").melds(concealedKong("1w1w1w1w")).score(), "三暗刻");
            hasNot(ron(rest, "5s").melds(openKong("1w1w1w1w")).score(), "三暗刻");
            hasNot(ron(rest, "5s").melds(pong("1w1w1w")).score(), "三暗刻");
        });

        test("全求人", () -> {
            Meld[] open = {chow("1w2w3w"), chow("4w5w6w"), chow("1p2p3p"), pong("9s9s9s"), pong("5s5s5s")};
            ScoreResult result = ron("3z3z", "3z").melds(open).score();
            has(result, "全求人");
            hasNot(result, "獨聽", "門清");
            total(2, result);

            ScoreResult selfDrawn = tsumo("3z3z", "3z").melds(open).score();
            hasNot(selfDrawn, "全求人");
            has(selfDrawn, "自摸", "獨聽");
            total(2, selfDrawn);

            hasNot(ron("3z3z", "3z").melds(chow("1w2w3w"), chow("4w5w6w"), chow("1p2p3p"), pong("9s9s9s"),
                    concealedKong("5s5s5s5s")).score(), "全求人");
        });

        test("海底撈月、河底撈魚、槓上開花、搶槓", () -> {
            ScoreResult sea = tsumo(PLAIN, "6w").lastTile().score();
            has(sea, "海底撈月");
            hasNot(sea, "河底撈魚");
            ScoreResult river = ron(PLAIN, "6w").lastTile().score();
            has(river, "河底撈魚");
            hasNot(river, "海底撈月");

            has(tsumo(PLAIN, "6w").afterKong().score(), "槓上開花");
            hasNot(ron(PLAIN, "6w").afterKong().score(), "槓上開花");
            has(ron(PLAIN, "6w").robbed().score(), "搶槓");
            hasNot(ron(PLAIN, "6w").score(), "搶槓", "海底撈月", "河底撈魚", "槓上開花");
        });

        test("天胡與地胡", () -> {
            ScoreResult heaven = tsumo(PLAIN, "6w").heavenly().score();
            has(heaven, "天胡", "門清一摸三");
            total(27, heaven);
            ScoreResult earth = tsumo(PLAIN, "6w").earthly().score();
            has(earth, "地胡");
            total(19, earth);
            hasNot(tsumo(PLAIN, "6w").score(), "天胡", "地胡");
        });

        test("正花依門風對應", () -> {
            for (int seat = 1; seat <= 4; seat++) {
                for (int rank = 1; rank <= 8; rank++) {
                    Spec spec = ron(PLAIN, "6w").flowers(rank + "f").winds(1, seat);
                    ScoreResult result = spec.score();
                    boolean own = rank == seat || rank == seat + 4;
                    eq(own ? 2 : 1, result.totalTai(), "門風 " + seat + " 花 " + rank + " " + result);
                }
            }
        });

        test("花槓與八仙過海", () -> {
            ScoreResult seasons = ron(PLAIN, "6w").flowers("1f2f3f4f").score();
            has(seasons, "花槓 春夏秋冬", "正花 夏");
            hasNot(seasons, "花槓 梅蘭竹菊", "平胡");
            total(4, seasons);

            ScoreResult plants = ron(PLAIN, "6w").flowers("5f6f7f8f").score();
            has(plants, "花槓 梅蘭竹菊", "正花 蘭");
            hasNot(plants, "花槓 春夏秋冬");

            ScoreResult notSet = ron(PLAIN, "6w").flowers("1f2f3f5f").score();
            hasNot(notSet, "花槓 春夏秋冬", "花槓 梅蘭竹菊");

            ScoreResult eight = ron(PLAIN, "6w").flowers("1f2f3f4f5f6f7f8f").score();
            has(eight, "八仙過海", "門清");
            hasNot(eight, "花槓 春夏秋冬", "正花 夏");
            total(9, eight);
        });

        test("多種拆法取最高台", () -> {
            ScoreResult result = tsumo("1w1w1w 2w2w2w 3w3w3w 4p5p6p 7s8s9s 9p9p", "6p").score();
            has(result, "三暗刻", "門清一摸三");
            total(5, result);
        });

        test("不是胡牌丟出例外", () -> {
            T.fails(IllegalArgumentException.class,
                    () -> ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s8s 9p9p", "8s").score(), "不成牌");
        });

        test("手牌順序不影響計台", () -> {
            Random random = new Random(21);
            for (String tiles : List.of(PLAIN, "1w1w1w 5p5p5p 9s9s9s 2z2z2z 3p3p3p 9p9p",
                    "1w1w1w 2w2w2w 3w3w3w 4p5p6p 7s8s9s 9p9p")) {
                int expected = ron(tiles, tiles.substring(0, 2)).score().totalTai();
                for (int i = 0; i < 50; i++) {
                    List<Tile> shuffled = new ArrayList<>(hand(tiles));
                    Collections.shuffle(shuffled, random);
                    int actual = Scorer.score(new WinContext(shuffled, List.of(), List.of(), tile(tiles.substring(0, 2)),
                            false, 1, 2, false, false, false, false, false)).totalTai();
                    eq(expected, actual, "順序不同台數變了");
                }
            }
        });

        test("說明畫面的台數表與實際計台一致", () -> {
            Set<String> seen = new HashSet<>();
            for (ScoreItem item : SCORED_ITEMS) {
                ScoreGuide.Entry entry = ScoreGuide.find(item.name());
                check(entry != null, "台數表沒有 " + item.name());
                eq(entry.tai(), item.tai(), "台數表的 " + entry.name());
                seen.add(entry.name());
            }
            for (ScoreGuide.Group group : ScoreGuide.GROUPS) {
                for (ScoreGuide.Entry entry : group.entries()) {
                    check(seen.contains(entry.name()), "台數表的 " + entry.name() + " 沒有測試案例算出來");
                }
            }
        });
    }
}
