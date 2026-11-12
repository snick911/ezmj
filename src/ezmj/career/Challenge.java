package ezmj.career;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import ezmj.engine.GameSession;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Player;

/**
 * 修業模式的挑戰關卡。宣告順序就是關卡順序，過了前一關才開放下一關。每關的對手、圈數、籌碼與底台都是固定的，
 * 籌碼不從檔案裡扣；任務成功或失敗一確定，這場就在那一局結束。第一次過關才有獎金，存進檔案的籌碼。
 * 每關最多三顆星：過關一顆，過關當下再達成兩個加星條件各一顆；已經過的關可以再打來多拿星。
 * 新增關卡只要在這裡加一項，記得給兩個加星條件。
 * <p>
 * 後面的「修練」關卡不是完整牌局（見 Drill）：記牌是打到一半停下來問答，看聽是看一手牌點出聽哪幾種。
 * 修練自成一條線，過了前一個修練才開下一個，和完整牌局的關卡互不擋；獎金也少得多。
 * 加星條件固定是「全部答對」與「平均幾秒內作答」，由 Drill.stars 判定，不用 check。
 */
public enum Challenge {

    FIRST_WIN("入門", "初試啼聲", "東風圈內胡牌一次", BotLevel.BEGINNER, 1, 10_000, 100, 20, 2_000,
            reach("胡牌", " 次", ChallengeFacts::wins, 1), noDealIn(), selfDrawn()),
    FIRST_SELF_DRAW("入門", "自摸練習", "東南兩圈內自摸一次", BotLevel.BEGINNER, 2, 10_000, 100, 20, 3_000,
            reach("自摸", " 次", ChallengeFacts::selfDraws, 1), noDealIn(), withinCircles(1)),
    CLEAN_CIRCLE("入門", "守口如瓶", "打完東風圈，一次都沒放槍", BotLevel.BEGINNER, 1, 10_000, 100, 20, 3_000,
            atMost("放槍", ChallengeFacts::dealIns, 0), winsAtLeast(2), topPlace()),

    CONCEALED("進階", "門前清", "東南兩圈內，胡一手門清（沒有吃、碰、明槓，自摸或胡別家都算）", BotLevel.NORMAL, 2,
            20_000, 100, 20, 5_000, winWith("門清", "門清", "門清一摸三"), noDealIn(),
            wonWithStar("胡出門清一摸三", "門清一摸三")),
    FIVE_TAI("進階", "有台有面", "東南兩圈內，胡一手牌型合計 5 台以上（不含莊家台）", BotLevel.NORMAL, 2,
            20_000, 100, 20, 6_000, reach("最大的一手", " 台", ChallengeFacts::bestTai, 5), noDealIn(), taiAtLeast(8)),
    DEALER_RUN("進階", "連二拉二", "四圈內，自己當莊連莊兩次（流局也算）", BotLevel.NORMAL, 4,
            20_000, 100, 20, 8_000, reach("最多連莊", " 次", ChallengeFacts::bestDealerStreak, 2), noDealIn(),
            withinCircles(2)),

    CAREFUL("老手", "步步為營", "打完四圈，放槍不超過一次", BotLevel.AMATEUR, 4, 20_000, 100, 20, 10_000,
            atMost("放槍", ChallengeFacts::dealIns, 1), noDealIn(), topPlace()),
    ONE_SUIT("老手", "一色到底", "四圈內胡一手混一色或清一色（字一色也算）", BotLevel.AMATEUR, 4,
            20_000, 100, 20, 10_000, winWith("一色", "混一色", "清一色", "字一色"), noDealIn(),
            wonWithStar("胡出清一色或字一色", "清一色", "字一色")),
    TOP_AMATEUR("老手", "力爭上游", "打完四圈，籌碼第一名（並列也算）", BotLevel.AMATEUR, 4, 20_000, 100, 20, 15_000,
            finishFirst(), dealInsAtMost(1), chipsAtLeast(30_000)),

    LAST_STAND("高手", "背水一戰", "籌碼只有 3000、每台 50：打完東南兩圈，籌碼沒有輸光", BotLevel.PRO, 2,
            3_000, 100, 50, 15_000, survive(), chipsAtLeast(3_000), topPlace()),
    SWEEP("高手", "掃地出門", "四圈內胡牌讓一家電腦輸光籌碼（被別家弄輸光的不算）", BotLevel.PRO, 4,
            5_000, 100, 50, 20_000, bust(), noDealIn(), withinCircles(2)),
    GRADUATE("高手", "出師", "打完四圈，贏過三家職業拿第一名（並列也算）", BotLevel.PRO, 4, 20_000, 100, 20, 50_000,
            finishFirst(), dealInsAtMost(2), chipsAtLeast(30_000)),

    // 修練：記牌與看聽交錯排，兩種都早點玩得到
    MEMORY_1("修練", "記牌一", "照常打牌，牌河每家只看得到最後一張；打到第 2～3 輪停下來，問 3 題剛才打過哪些牌",
            BotLevel.NORMAL, 600, Drill.memory(2, 3, 3, 15, 2, 8)),
    WAITS_1("修練", "看聽一", "看一手聽牌的 16 張，牌蓋起來之後點出它聽哪幾種（聽 1～2 種）",
            BotLevel.BEGINNER, 600, Drill.waits(1, 2, false, 15, 15, 5, 4, 8)),
    MEMORY_2("修練", "記牌二", "照常打牌，牌河每家只看得到最後一張；打到第 3～5 輪停下來，問 4 題剛才打過哪些牌",
            BotLevel.AMATEUR, 1_000, Drill.memory(3, 5, 4, 12, 3, 7)),
    WAITS_2("修練", "看聽二", "看一手聽牌的 16 張，牌蓋起來之後點出它聽哪幾種（聽 2～4 種）",
            BotLevel.BEGINNER, 1_000, Drill.waits(2, 4, false, 10, 12, 5, 4, 6)),
    MEMORY_3("修練", "記牌三", "照常打牌，牌河每家只看得到最後一張；打到第 5～7 輪停下來，問 5 題剛才打過哪些牌",
            BotLevel.PRO, 1_500, Drill.memory(5, 7, 5, 10, 4, 6)),
    WAITS_3("修練", "看聽三", "看一手聽牌的 16 張，牌蓋起來之後點出它聽哪幾種（聽 3～6 種，多為一色牌）",
            BotLevel.BEGINNER, 1_500, Drill.waits(3, 6, true, 8, 10, 5, 4, 5));

    /** 每關最多幾顆星：過關一顆，再加兩個加星條件。 */
    public static final int MAX_STARS = 3;
    /** 修練關卡的章名。 */
    public static final String DRILL_CHAPTER = "修練";

    /** 任務的狀態：還在打、成功或失敗。 */
    public enum Status {
        PLAYING, CLEARED, FAILED
    }

    /**
     * 判定的結果與牌桌上顯示的進度。過關時 stars 是拿到哪幾顆星：第 i 個位元是第 i+1 顆，
     * 第一顆一定是「過關」，後面兩顆是達成的加星條件；還沒過關或失敗時是 0。
     */
    public record Check(Status status, String progress, int stars) {
        public Check(Status status, String progress) {
            this(status, progress, 0);
        }

        public boolean decided() {
            return status != Status.PLAYING;
        }

        /** 拿到幾顆星，沒過關是 0。 */
        public int starCount() {
            return Integer.bitCount(stars);
        }
    }

    /** 任務：看這場到目前的局面回答成功、失敗或還沒確定。 */
    private interface Goal {
        Check check(ChallengeFacts facts);
    }

    /**
     * 加星條件：過關那一刻再看一次局面，達成就多一顆星。過關時這場就結束了，
     * 所以條件都是當下就能判定的，例如到目前沒放槍、在第幾圈內過關。
     */
    private record Star(String text, Predicate<ChallengeFacts> met) {
    }

    private static Star noDealIn() {
        return new Star("沒有放槍", facts -> facts.dealIns() == 0);
    }

    private static Star dealInsAtMost(int max) {
        return new Star("放槍不超過 " + max + " 次", facts -> facts.dealIns() <= max);
    }

    private static Star selfDrawn() {
        return new Star("自摸過關", facts -> facts.selfDraws() >= 1);
    }

    private static Star winsAtLeast(int count) {
        return new Star("胡牌 " + count + " 次以上", facts -> facts.wins() >= count);
    }

    private static Star topPlace() {
        return new Star("籌碼第一名", facts -> facts.place() == 1);
    }

    private static Star chipsAtLeast(int chips) {
        return new Star("籌碼 " + chips + " 以上", facts -> facts.humanChips() >= chips);
    }

    private static Star taiAtLeast(int tai) {
        return new Star("胡一手 " + tai + " 台以上", facts -> facts.bestTai() >= tai);
    }

    private static Star withinCircles(int circles) {
        return new Star((circles == 1 ? "東風圈" : "東南兩圈") + "內過關", facts -> facts.lastCircle() <= circles);
    }

    private static Star wonWithStar(String text, String... items) {
        List<String> names = List.of(items);
        return new Star(text, facts -> facts.wonWith(names));
    }

    private final String chapter;
    private final String title;
    private final String goalText;
    private final BotLevel level;
    private final int circles;
    private final int chips;
    private final int basePay;
    private final int taiPay;
    private final int reward;
    private final Goal goal;
    /** 兩個加星條件，依序是第二顆與第三顆星。 */
    private final List<Star> extraStars;
    /** 修練關卡的設定；完整牌局的關卡是 null。 */
    private final Drill drill;

    Challenge(String chapter, String title, String goalText, BotLevel level, int circles, int chips, int basePay,
              int taiPay, int reward, Goal goal, Star second, Star third) {
        this(chapter, title, goalText, level, circles, chips, basePay, taiPay, reward, goal, second, third, null);
    }

    /** 修練關卡：level 是記牌時三家電腦的等級；牌桌固定籌碼 10000、底 100／每台 20，只打到停下來為止。 */
    Challenge(String chapter, String title, String goalText, BotLevel level, int reward, Drill drill) {
        this(chapter, title, goalText, level, 1, 10_000, 100, 20, reward, null,
                new Star(drill.allCorrectText(), null), new Star(drill.fastText(), null), drill);
    }

    private Challenge(String chapter, String title, String goalText, BotLevel level, int circles, int chips,
                      int basePay, int taiPay, int reward, Goal goal, Star second, Star third, Drill drill) {
        this.chapter = chapter;
        this.title = title;
        this.goalText = goalText;
        this.level = level;
        this.circles = circles;
        this.chips = chips;
        this.basePay = basePay;
        this.taiPay = taiPay;
        this.reward = reward;
        this.goal = goal;
        this.extraStars = List.of(second, third);
        this.drill = drill;
    }

    /** 數值到 target 就成功，打完還沒到就失敗。 */
    private static Goal reach(String what, String unit, ToIntFunction<ChallengeFacts> value, int target) {
        return facts -> {
            int now = value.applyAsInt(facts);
            String progress = what + " " + now + " / " + target + unit;
            if (now >= target) {
                return new Check(Status.CLEARED, progress);
            }
            return new Check(facts.finished() ? Status.FAILED : Status.PLAYING, progress);
        };
    }

    /** 次數超過 max 就失敗，撐到打完就成功。 */
    private static Goal atMost(String what, ToIntFunction<ChallengeFacts> value, int max) {
        return facts -> {
            int now = value.applyAsInt(facts);
            String progress = what + " " + now + " 次" + (max == 0 ? "，" + what + "就失敗" : "，最多 " + max + " 次");
            if (now > max) {
                return new Check(Status.FAILED, progress);
            }
            return new Check(facts.finished() ? Status.CLEARED : Status.PLAYING, progress);
        };
    }

    /** 胡出其中一種牌型就成功。 */
    private static Goal winWith(String label, String... items) {
        List<String> names = List.of(items);
        return facts -> {
            if (facts.wonWith(names)) {
                return new Check(Status.CLEARED, "胡出" + label + "了");
            }
            return new Check(facts.finished() ? Status.FAILED : Status.PLAYING, "還沒胡出" + label);
        };
    }

    /** 打完時籌碼第一名，並列也算。 */
    private static Goal finishFirst() {
        return facts -> {
            String progress = "目前第 " + facts.place() + " 名";
            if (!facts.finished()) {
                return new Check(Status.PLAYING, progress);
            }
            return new Check(facts.place() == 1 ? Status.CLEARED : Status.FAILED, progress);
        };
    }

    /** 自己輸光就失敗，撐到打完就成功；電腦先輸光也算撐到了。 */
    private static Goal survive() {
        return facts -> {
            String progress = "籌碼 " + facts.humanChips();
            if (facts.humanChips() <= 0) {
                return new Check(Status.FAILED, progress);
            }
            return new Check(facts.finished() ? Status.CLEARED : Status.PLAYING, progress);
        };
    }

    /** 自己胡牌讓電腦輸光就成功；牌局因為別的原因結束就失敗。 */
    private static Goal bust() {
        return facts -> {
            Player poorest = facts.poorestBot();
            String progress = "籌碼最少的電腦：" + poorest.name() + " " + poorest.chips();
            if (facts.bustedByHuman()) {
                return new Check(Status.CLEARED, progress);
            }
            return new Check(facts.finished() ? Status.FAILED : Status.PLAYING, progress);
        };
    }

    /** 這場打到現在的任務狀態。每局結算完呼叫。過關時一併判定加星條件。修練關卡不是牌局，不能用這個判定。 */
    public Check check(GameSession session) {
        if (drill != null) {
            throw new IllegalStateException("修練關卡用 Drill.stars 判定：" + this);
        }
        ChallengeFacts facts = new ChallengeFacts(session, circles);
        Check check = goal.check(facts);
        if (check.status() != Status.CLEARED) {
            return check;
        }
        int stars = 1;
        for (int i = 0; i < extraStars.size(); i++) {
            if (extraStars.get(i).met().test(facts)) {
                stars |= 1 << (i + 1);
            }
        }
        return new Check(check.status(), check.progress(), stars);
    }

    /** 第一顆星的說明。 */
    public static final String CLEAR_STAR = "過關";
    /** 三顆星全拿的位元。 */
    public static final int ALL_STARS = (1 << MAX_STARS) - 1;

    /** 三顆星各要做到什麼，第一顆是「過關」。 */
    public List<String> starTexts() {
        return List.of(CLEAR_STAR, extraStars.get(0).text(), extraStars.get(1).text());
    }

    /** stars 位元裡有的那幾顆星的說明，照順序。 */
    public List<String> starTexts(int stars) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < MAX_STARS; i++) {
            if ((stars & (1 << i)) != 0) {
                texts.add(starTexts().get(i));
            }
        }
        return texts;
    }

    /** 這關的牌桌。記牌的修練一律照專家模式顯示：牌河每家只留最後一張，要自己記。 */
    public GameConfig config(String playerName) {
        GameConfig config = new GameConfig(playerName, chips, basePay, taiPay, level);
        return drill != null && drill.isMemory() ? config.withExpert(true) : config;
    }

    /** 修練關卡：不是完整牌局，記牌或看聽。 */
    public boolean isDrill() {
        return drill != null;
    }

    /** 修練關卡的設定；完整牌局的關卡是 null。 */
    public Drill drill() {
        return drill;
    }

    /** 同一條線的關卡：完整牌局的是一條，修練是另一條，宣告順序就是開放順序。 */
    public static List<Challenge> line(boolean drills) {
        List<Challenge> line = new ArrayList<>();
        for (Challenge challenge : values()) {
            if (challenge.isDrill() == drills) {
                line.add(challenge);
            }
        }
        return line;
    }

    /** 在自己那條線上是第幾關，從 1 起算。 */
    public int number() {
        return line(isDrill()).indexOf(this) + 1;
    }

    /** 例如「第 3 關」「修練 2」。 */
    public String label() {
        return isDrill() ? DRILL_CHAPTER + " " + number() : "第 " + number() + " 關";
    }

    /** 過了同一條線的前一關才能挑戰；每條線的第一關一開始就能挑戰。 */
    public Challenge previous() {
        List<Challenge> line = line(isDrill());
        int at = line.indexOf(this);
        return at == 0 ? null : line.get(at - 1);
    }

    /** 同一條線的下一關；最後一關是 null。 */
    public Challenge next() {
        List<Challenge> line = line(isDrill());
        int at = line.indexOf(this);
        return at + 1 < line.size() ? line.get(at + 1) : null;
    }

    /** 列表上這一章的標題，例如「入門・三家初學者」「修練・記牌與看聽」。 */
    public String chapterHeading() {
        return isDrill() ? DRILL_CHAPTER + "・記牌與看聽" : chapter + "・三家" + level.label();
    }

    public String chapter() {
        return chapter;
    }

    public String title() {
        return title;
    }

    public String goalText() {
        return goalText;
    }

    public BotLevel level() {
        return level;
    }

    public int circles() {
        return circles;
    }

    public int chips() {
        return chips;
    }

    public int basePay() {
        return basePay;
    }

    public int taiPay() {
        return taiPay;
    }

    public int reward() {
        return reward;
    }

    /** 例如「東風圈」「東南兩圈」「四圈」。 */
    public String lengthLabel() {
        switch (circles) {
            case 1:
                return "東風圈";
            case 2:
                return "東南兩圈";
            case 3:
                return "東南西三圈";
            default:
                return "四圈";
        }
    }

    /**
     * 關卡的牌桌，例如「三家初學者・東風圈・籌碼 10000・底 100／每台 20」；
     * 修練是怎麼考，例如「三家一般・第 2～3 輪停下來問・3 題・每題 15 秒・答對 2 題過關」。
     */
    public String setup() {
        if (drill != null) {
            return drill.setup(level.label());
        }
        return "三家" + level.label() + "・" + lengthLabel() + "・籌碼 " + chips + "・底 " + basePay + "／每台 " + taiPay;
    }

    /** 讀檔用；認不得是 null。 */
    public static Challenge fromKey(String key) {
        for (Challenge challenge : values()) {
            if (challenge.name().equals(key)) {
                return challenge;
            }
        }
        return null;
    }
}
