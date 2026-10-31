package ezmj.rules;

import java.util.List;

/** 說明畫面的台數表。內容要與 Scorer 一致，ScorerTest 會比對實際算出的牌型與台數。 */
public final class ScoreGuide {

    /** 一種牌型。name 對應 ScoreItem 名稱空白前的部分，例如「圈風 東」對應「圈風」。 */
    public record Entry(String name, int tai, String condition) {
    }

    /** 一類牌型。 */
    public record Group(String title, List<Entry> entries) {
    }

    public static final List<Group> GROUPS = List.of(
            new Group("開局", List.of(
                    new Entry("天胡", 24, "莊家第一次摸牌就自摸，之前沒有人吃、碰、槓"),
                    new Entry("地胡", 16, "閒家第一次摸牌就自摸，之前沒有人吃、碰、槓"))),
            new Group("風牌", List.of(
                    new Entry("大四喜", 16, "東南西北四組刻子，不另計圈風、門風"),
                    new Entry("小四喜", 8, "三組風牌刻子，眼睛是第四種風牌，不另計圈風、門風"),
                    new Entry("圈風", 1, "有當圈風牌的刻子"),
                    new Entry("門風", 1, "有自己門風牌的刻子"))),
            new Group("三元牌", List.of(
                    new Entry("大三元", 8, "中發白三組刻子，不另計三元牌"),
                    new Entry("小三元", 4, "兩組三元牌刻子，眼睛是第三種三元牌，不另計三元牌"),
                    new Entry("三元牌", 1, "中、發、白的刻子，每組 1 台"))),
            new Group("花色", List.of(
                    new Entry("字一色", 16, "全部都是字牌"),
                    new Entry("清一色", 8, "全部是同一種數牌，沒有字牌"),
                    new Entry("混一色", 4, "只有一種數牌，加上字牌"))),
            new Group("刻子", List.of(
                    new Entry("碰碰胡", 4, "五組面子都是刻子或槓"),
                    new Entry("五暗刻", 8, "五組暗刻"),
                    new Entry("四暗刻", 5, "四組暗刻"),
                    new Entry("三暗刻", 2, "三組暗刻"))),
            new Group("胡牌方式", List.of(
                    new Entry("平胡", 2, "五組都是順子，眼睛不是字牌，沒有花牌，胡別家打出的牌，且不是獨聽"),
                    new Entry("門清一摸三", 3, "沒有吃、碰、明槓，又自摸"),
                    new Entry("門清", 1, "沒有吃、碰、明槓，胡別家打出的牌"),
                    new Entry("自摸", 1, "有吃、碰或明槓，自己摸到胡牌"),
                    new Entry("全求人", 2, "五組面子都靠吃、碰、明槓或加槓亮出，胡別家打出的牌，不另計獨聽"),
                    new Entry("獨聽", 1, "聽牌時只聽一種牌"))),
            new Group("特殊時機", List.of(
                    new Entry("海底撈月", 1, "自摸牌牆最後一張可摸的牌"),
                    new Entry("河底撈魚", 1, "牌牆已無牌可摸時，胡別家打出的牌"),
                    new Entry("槓上開花", 1, "槓牌後補牌自摸"),
                    new Entry("搶槓", 1, "胡別家加槓的那張牌"))),
            new Group("花牌", List.of(
                    new Entry("正花", 1, "花牌對應自己的門風，每張 1 台：春梅東、夏蘭南、秋竹西、冬菊北"),
                    new Entry("花槓", 2, "集滿春夏秋冬，或集滿梅蘭竹菊，每組 2 台"),
                    new Entry("八仙過海", 8, "八張花牌全到，不另計正花、花槓"))));

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
}
