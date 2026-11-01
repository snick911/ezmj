package ezmj.engine;

import java.util.function.Predicate;

/** 全部的成就。都是看真人（座位 0）的表現；新增成就只要在這裡加一項，宣告順序就是顯示順序。 */
public enum Achievement {

    HEAVENLY("天胡", "天命所歸"),
    EARTHLY("地胡", "地利人和"),
    BIG_FOUR_WINDS("大四喜", "四方來喜"),
    SMALL_FOUR_WINDS("小四喜", "喜上眉梢"),
    BIG_THREE_DRAGONS("大三元", "連中三元"),
    SMALL_THREE_DRAGONS("小三元", "小有所成"),
    ALL_HONORS("字一色", "字字珠璣"),
    PURE_SUIT("清一色", "一塵不染"),
    MIXED_SUIT("混一色", "混水摸魚"),
    FIVE_CONCEALED("五暗刻", "暗藏五虎"),
    FOUR_CONCEALED("四暗刻", "深藏不露"),
    THREE_CONCEALED("三暗刻", "暗度陳倉"),
    ALL_TRIPLETS("碰碰胡", "碰碰運氣"),
    ALL_SEQUENCES("平胡", "平平安安"),
    CONCEALED_SELF_DRAW("門清一摸三", "獨善其身"),
    ALL_EXPOSED("全求人", "有求必應"),
    SINGLE_WAIT("獨聽", "孤注一擲"),
    EIGHT_FLOWERS("八仙過海", "各顯神通"),
    FLOWER_KONG("花槓", "花開富貴"),
    AFTER_KONG("槓上開花", "錦上添花"),
    LAST_TILE_DRAW("海底撈月", "撈月高手"),
    LAST_TILE_DISCARD("河底撈魚", "最後一條魚"),
    ROBBED_KONG("搶槓", "橫刀奪愛"),
    NO_TAI(Group.HAND, "無欲則剛", "胡出 0 台的屁胡", facts -> facts.humanWon() && facts.handTai() == 0),

    TAI_8(Group.BIG, "大牌初體驗", "一手胡出 8 台以上", facts -> facts.humanWon() && facts.handTai() >= 8),
    TAI_16(Group.BIG, "一鳴驚人", "一手胡出 16 台以上", facts -> facts.humanWon() && facts.handTai() >= 16),
    TAI_24(Group.BIG, "驚天動地", "一手胡出 24 台以上", facts -> facts.humanWon() && facts.handTai() >= 24),
    TAI_32(Group.BIG, "傳說中的牌", "一手胡出 32 台以上", facts -> facts.humanWon() && facts.handTai() >= 32),

    DEALER_STREAK_3(Group.STREAK, "穩坐莊家", "自己當莊連莊 3 次，流局也算", facts -> facts.humanDealerStreak() >= 3),
    DEALER_STREAK_5(Group.STREAK, "莊家不倒", "自己當莊連莊 5 次，流局也算", facts -> facts.humanDealerStreak() >= 5),
    DEALER_STREAK_10(Group.STREAK, "萬年莊家", "自己當莊連莊 10 次，流局也算", facts -> facts.humanDealerStreak() >= 10),
    WIN_RUN_3(Group.STREAK, "手氣正旺", "連續 3 局胡牌", facts -> facts.humanWinRun() >= 3),
    WIN_RUN_5(Group.STREAK, "勢不可擋", "連續 5 局胡牌", facts -> facts.humanWinRun() >= 5),
    DRAWN_RUN_3(Group.STREAK, "僵持不下", "連續 3 局流局", facts -> facts.drawnRun() >= 3),
    DRAWN_RUN_5(Group.STREAK, "誰也不讓誰", "連續 5 局流局", facts -> facts.drawnRun() >= 5),
    DRAWN_RUN_10(Group.STREAK, "天下太平", "連續 10 局流局", facts -> facts.drawnRun() >= 10),
    DEAL_IN_RUN_3(Group.STREAK, "散財童子", "連續 3 局放槍", facts -> facts.humanDealInRun() >= 3),

    WINS_1(Group.TOTAL, "初次開胡", Counter.WINS, 1),
    WINS_10(Group.TOTAL, "小試身手", Counter.WINS, 10),
    WINS_100(Group.TOTAL, "牌桌常勝軍", Counter.WINS, 100),
    WINS_500(Group.TOTAL, "麻將大師", Counter.WINS, 500),
    SELF_DRAWS_10(Group.TOTAL, "自摸小王子", Counter.SELF_DRAWS, 10),
    SELF_DRAWS_100(Group.TOTAL, "自摸之神", Counter.SELF_DRAWS, 100),
    DEAL_INS_100(Group.TOTAL, "大善人", Counter.DEAL_INS, 100),
    ROUNDS_100(Group.TOTAL, "熟門熟路", Counter.ROUNDS, 100),
    ROUNDS_1000(Group.TOTAL, "以牌會友", Counter.ROUNDS, 1000),
    GAMES_1(Group.TOTAL, "第一場牌局", Counter.GAMES, 1),
    GAMES_10(Group.TOTAL, "牌桌老手", Counter.GAMES, 10),
    GAMES_50(Group.TOTAL, "以此為家", Counter.GAMES, 50),

    GAME_TOP(Group.GAME, "一場之冠", "打完一場，籌碼最多（並列也算）", AchievementFacts::humanOnTop),
    BEAT_PROS(Group.GAME, "勝過職業", "三家電腦都是職業等級，打完一場籌碼最多",
            facts -> facts.humanOnTop() && facts.allBotsPro()),
    NO_DEAL_IN(Group.GAME, "滴水不漏", "四圈打完，整場沒有放槍", facts -> facts.fullGame() && facts.dealIns() == 0),
    TEN_WINS_IN_GAME(Group.GAME, "大殺四方", "一場胡牌 10 次以上", facts -> facts.gameEnded() && facts.wins() >= 10),
    DOUBLED(Group.GAME, "翻倍奉還", "打完一場，贏的錢達到本金以上", facts -> facts.gameEnded() && facts.winnings() >= facts.startingChips()),
    BROKE_SOMEONE(Group.GAME, "趕盡殺絕", "胡牌讓別家輸光籌碼", AchievementFacts::humanBrokeSomeone),
    WENT_BROKE(Group.GAME, "傾家蕩產", "自己輸光籌碼", facts -> facts.gameEnded() && facts.humanChips() <= 0),
    NO_WINS(Group.GAME, "全程陪打", "四圈打完，整場沒有胡牌", facts -> facts.fullGame() && facts.wins() == 0),
    MANY_DRAWN(Group.GAME, "和氣生財", "一場有 5 局以上流局", facts -> facts.gameEnded() && facts.drawnGames() >= 5),
    FAST_THINKER(Group.GAME, "閃電手", "打完一場，平均思考 2 秒內（至少思考 20 次）",
            facts -> facts.gameEnded() && facts.thinkCount() >= 20 && facts.averageThinkSeconds() <= 2),
    SLOW_THINKER(Group.GAME, "老僧入定", "打完一場，平均思考 30 秒以上（至少思考 20 次）",
            facts -> facts.gameEnded() && facts.thinkCount() >= 20 && facts.averageThinkSeconds() >= 30);

    /** 成就的分類，也是選單上的項目。 */
    public enum Group {
        HAND("牌型"), BIG("大牌"), STREAK("連續"), TOTAL("累計"), GAME("整場");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 跨牌局累計的次數。 */
    public enum Counter {
        WINS("胡牌"), SELF_DRAWS("自摸"), DEAL_INS("放槍"), ROUNDS("打完"), GAMES("打完");

        private final String verb;

        Counter(String verb) {
            this.verb = verb;
        }

        private String description(int target) {
            return this == ROUNDS ? "累計打完 " + target + " 局"
                    : this == GAMES ? "累計打完 " + target + " 場牌局"
                    : "累計" + verb + " " + target + " 次";
        }
    }

    private final Group group;
    private final String title;
    private final String description;
    private final String handItem;
    private final Counter counter;
    private final int target;
    private final Predicate<AchievementFacts> condition;

    /** 胡出某個牌型。 */
    Achievement(String handItem, String title) {
        this(Group.HAND, title, "胡出" + handItem, handItem, null, 0, facts -> facts.hasHandItem(handItem));
    }

    Achievement(Group group, String title, String description, Predicate<AchievementFacts> condition) {
        this(group, title, description, null, null, 0, condition);
    }

    /** 累計次數達標。 */
    Achievement(Group group, String title, Counter counter, int target) {
        this(group, title, counter.description(target), null, counter, target, facts -> facts.count(counter) >= target);
    }

    Achievement(Group group, String title, String description, String handItem, Counter counter, int target,
                Predicate<AchievementFacts> condition) {
        this.group = group;
        this.title = title;
        this.description = description;
        this.handItem = handItem;
        this.counter = counter;
        this.target = target;
        this.condition = condition;
    }

    public Group group() {
        return group;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    /** 牌型成就對應的台數項目；其他成就是 null。 */
    public String handItem() {
        return handItem;
    }

    /** 累計成就看的次數；其他成就是 null。 */
    public Counter counter() {
        return counter;
    }

    public int target() {
        return target;
    }

    boolean isMet(AchievementFacts facts) {
        return condition.test(facts);
    }
}
