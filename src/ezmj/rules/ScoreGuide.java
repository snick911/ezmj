package ezmj.rules;

import java.util.List;

import ezmj.model.Meld;
import ezmj.model.Tile;

/** 說明畫面的台數表與範例牌型。內容要與 Scorer 一致，ScorerTest 會比對台數，也會實際計算每個範例。 */
public final class ScoreGuide {

    /** 一種牌型。name 對應 ScoreItem 名稱空白前的部分，例如「圈風 東」對應「圈風」。不看手牌的牌型 example 是 null。 */
    public record Entry(String name, int tai, String condition, WinContext example) {
    }

    /** 一類牌型。 */
    public record Group(String title, List<Entry> entries) {
    }

    /** 基本規則裡示範胡牌的手牌，空白隔開每組面子，最後一組是眼睛。 */
    public static final String WINNING_HAND = "1w2w3w 4p5p6p 7s7s7s 2z2z2z 5z5z5z 9w9w";

    private static final String ALL_SEQUENCES = "1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p";
    private static final String OPEN_REST = "4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p";

    public static final List<Group> GROUPS = List.of(
            new Group("開局", List.of(
                    new Entry("天胡", 24, "莊家第一次摸牌就自摸，之前沒有人吃、碰、槓",
                            tsumo(ALL_SEQUENCES, "6w").seat(1).heavenly().build()),
                    new Entry("地胡", 16, "閒家第一次摸牌就自摸，之前沒有人吃、碰、槓",
                            tsumo(ALL_SEQUENCES, "6w").earthly().build()))),
            new Group("風牌", List.of(
                    new Entry("大四喜", 16, "東南西北四組刻子，不另計圈風、門風",
                            ron("1z1z1z 2z2z2z 3z3z3z 4z4z4z 1w2w3w 9s9s", "3w").build()),
                    new Entry("小四喜", 8, "三組風牌刻子，眼睛是第四種風牌，不另計圈風、門風",
                            ron("1z1z1z 2z2z2z 3z3z3z 4z4z 1w2w3w 4w5w6w", "6w").build()),
                    new Entry("圈風", 1, "有當圈風牌的刻子",
                            ron("1z1z1z 1w2w3w 4w5w6w 1p2p3p 4s5s6s 9s9s", "6w").build()),
                    new Entry("門風", 1, "有自己門風牌的刻子",
                            ron("2z2z2z 1w2w3w 4w5w6w 1p2p3p 4s5s6s 9s9s", "6w").build()))),
            new Group("三元牌", List.of(
                    new Entry("大三元", 8, "中發白三組刻子，不另計三元牌",
                            ron("5z5z5z 6z6z6z 7z7z7z 1w2w3w 4p5p6p 9s9s", "4p").build()),
                    new Entry("小三元", 4, "兩組三元牌刻子，眼睛是第三種三元牌，不另計三元牌",
                            ron("5z5z5z 6z6z6z 7z7z 1w2w3w 4p5p6p 7s8s9s", "7s").build()),
                    new Entry("三元牌", 1, "中、發、白的刻子，每組 1 台",
                            ron("5z5z5z 1w2w3w 4w5w6w 1p2p3p 4s5s6s 9s9s", "6w").build()))),
            new Group("花色", List.of(
                    new Entry("字一色", 16, "全部都是字牌",
                            ron("1z1z1z 2z2z2z 3z3z3z 5z5z5z 6z6z6z 7z7z", "7z").build()),
                    new Entry("清一色", 8, "全部是同一種數牌，沒有字牌",
                            ron("1w2w3w 4w5w6w 7w8w9w 1w2w3w 4w5w6w 9w9w", "6w").build()),
                    new Entry("混一色", 4, "只有一種數牌，加上字牌",
                            ron("1w2w3w 4w5w6w 7w8w9w 1z1z1z 5z5z5z 9w9w", "6w").build()))),
            new Group("刻子", List.of(
                    new Entry("碰碰胡", 4, "五組面子都是刻子或槓",
                            ron("2w2w2w 3p3p3p 7s7s", "7s")
                                    .melds(pong("1w1w1w"), pong("5p5p5p"), pong("9s9s9s")).build()),
                    new Entry("五暗刻", 8, "五組暗刻",
                            tsumo("1w1w1w 5p5p5p 9s9s9s 2z2z2z 3p3p3p 9p9p", "3p").build()),
                    new Entry("四暗刻", 5, "四組暗刻",
                            ron("1w1w1w 5p5p5p 9s9s9s 2z2z2z 5s6s7s 9p9p", "5s").build()),
                    new Entry("三暗刻", 2, "三組暗刻",
                            ron("1w1w1w 5p5p5p 9s9s9s 2w3w4w 5s6s7s 9p9p", "5s").build()))),
            new Group("胡牌方式", List.of(
                    new Entry("平胡", 2, "五組都是順子，眼睛不是字牌，沒有花牌，胡別家打出的牌，且不是獨聽",
                            ron(ALL_SEQUENCES, "6w").build()),
                    new Entry("門清一摸三", 3, "沒有吃、碰、明槓，又自摸",
                            tsumo(ALL_SEQUENCES, "6w").build()),
                    new Entry("門清", 1, "沒有吃、碰、明槓，胡別家打出的牌",
                            ron("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 5z5z", "6w").build()),
                    new Entry("自摸", 1, "有吃、碰或明槓，自己摸到胡牌",
                            tsumo(OPEN_REST, "6w").melds(chow("1w2w3w")).build()),
                    new Entry("全求人", 2, "五組面子都靠吃、碰、明槓或加槓亮出，胡別家打出的牌，不另計獨聽",
                            ron("3z3z", "3z").melds(chow("1w2w3w"), chow("4w5w6w"), chow("1p2p3p"),
                                    pong("9s9s9s"), pong("5s5s5s")).build()),
                    new Entry("獨聽", 1, "聽牌時只聽一種牌",
                            ron(ALL_SEQUENCES, "3w").build()),
                    new Entry("聽牌", 1, "打出牌後已經聽牌，在下一次摸牌或吃碰槓之前報聽。報聽之後全桌都知道，手牌不能再換，也不能吃碰槓。胡別家打出的牌或自摸都算",
                            ron(ALL_SEQUENCES, "6w").listening().build()))),
            new Group("特殊時機", List.of(
                    new Entry("海底撈月", 1, "自摸牌牆最後一張可摸的牌",
                            tsumo(ALL_SEQUENCES, "6w").lastTile().build()),
                    new Entry("河底撈魚", 1, "牌牆已無牌可摸時，胡別家打出的牌",
                            ron(ALL_SEQUENCES, "6w").lastTile().build()),
                    new Entry("槓上開花", 1, "槓牌後補牌自摸",
                            tsumo(OPEN_REST, "6w").melds(concealedKong("9w9w9w9w")).afterKong().build()),
                    new Entry("搶槓", 1, "胡別家加槓的那張牌",
                            ron(ALL_SEQUENCES, "6w").robbed().build()))),
            new Group("花牌", List.of(
                    new Entry("正花", 1, "花牌對應自己的門風，每張 1 台：春梅東、夏蘭南、秋竹西、冬菊北",
                            ron(ALL_SEQUENCES, "6w").flowers("2f").build()),
                    new Entry("花槓", 2, "集滿春夏秋冬，或集滿梅蘭竹菊，每組 2 台",
                            ron(ALL_SEQUENCES, "6w").flowers("1f2f3f4f").build()),
                    new Entry("八仙過海", 8, "八張花牌集滿就立即胡，算自摸，三家付錢。不看手牌，只算這一項",
                            null),
                    new Entry("七搶一", 8, "自己有七張花、別家有一張花就立即胡，拿走那張花，只有那家付錢，算他放槍。不看手牌，只算這一項",
                            null))));

    private ScoreGuide() {
    }

    /** 依 ScoreItem 名稱找對應的牌型；找不到回傳 null。 */
    public static Entry find(String itemName) {
        String name = itemName.split(" ")[0];
        for (Group group : GROUPS) {
            for (Entry entry : group.entries()) {
                if (entry.name().equals(name)) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static Hand ron(String concealed, String winTile) {
        return new Hand(concealed, winTile, false);
    }

    private static Hand tsumo(String concealed, String winTile) {
        return new Hand(concealed, winTile, true);
    }

    private static Meld chow(String tiles) {
        return new Meld(Meld.Type.CHOW, Tile.parseAll(tiles));
    }

    private static Meld pong(String tiles) {
        return new Meld(Meld.Type.PONG, Tile.parseAll(tiles));
    }

    private static Meld concealedKong(String tiles) {
        return new Meld(Meld.Type.CONCEALED_KONG, Tile.parseAll(tiles));
    }

    /** 範例的情境，預設東風圈、門風南、沒有花牌。 */
    private static final class Hand {
        private final String concealed;
        private final String winTile;
        private final boolean selfDraw;
        private List<Meld> melds = List.of();
        private String flowers = "";
        private int seat = 2;
        private boolean robbed;
        private boolean afterKong;
        private boolean lastTile;
        private boolean heavenly;
        private boolean earthly;
        private boolean listening;

        Hand(String concealed, String winTile, boolean selfDraw) {
            this.concealed = concealed;
            this.winTile = winTile;
            this.selfDraw = selfDraw;
        }

        Hand melds(Meld... value) {
            melds = List.of(value);
            return this;
        }

        Hand flowers(String value) {
            flowers = value;
            return this;
        }

        Hand seat(int value) {
            seat = value;
            return this;
        }

        Hand robbed() {
            robbed = true;
            return this;
        }

        Hand afterKong() {
            afterKong = true;
            return this;
        }

        Hand lastTile() {
            lastTile = true;
            return this;
        }

        Hand heavenly() {
            heavenly = true;
            return this;
        }

        Hand earthly() {
            earthly = true;
            return this;
        }

        Hand listening() {
            listening = true;
            return this;
        }

        WinContext build() {
            return new WinContext(Tile.parseAll(concealed), melds, Tile.parseAll(flowers),
                    Tile.parseAll(winTile).get(0), selfDraw, 1, seat, robbed, afterKong, lastTile, heavenly, earthly, listening);
        }
    }
}
