package ezmj.career;

import java.util.List;
import java.util.function.ToIntFunction;

import ezmj.engine.GameSession;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Player;

/**
 * 修業模式的挑戰關卡。宣告順序就是關卡順序，過了前一關才開放下一關。每關的對手、圈數、籌碼與底台都是固定的，
 * 籌碼不從檔案裡扣；任務成功或失敗一確定，這場就在那一局結束。第一次過關才有獎金，存進檔案的籌碼。
 * 新增關卡只要在這裡加一項。
 */
public enum Challenge {

    FIRST_WIN("入門", "初試啼聲", "東風圈內胡牌一次", BotLevel.BEGINNER, 1, 10_000, 100, 20, 2_000,
            reach("胡牌", " 次", ChallengeFacts::wins, 1)),
    FIRST_SELF_DRAW("入門", "自摸練習", "東南兩圈內自摸一次", BotLevel.BEGINNER, 2, 10_000, 100, 20, 3_000,
            reach("自摸", " 次", ChallengeFacts::selfDraws, 1)),
    CLEAN_CIRCLE("入門", "守口如瓶", "打完東風圈，一次都沒放槍", BotLevel.BEGINNER, 1, 10_000, 100, 20, 3_000,
            atMost("放槍", ChallengeFacts::dealIns, 0)),

    CONCEALED("進階", "門前清", "東南兩圈內，胡一手門清（沒有吃、碰、明槓，自摸或胡別家都算）", BotLevel.NORMAL, 2,
            20_000, 100, 20, 5_000, winWith("門清", "門清", "門清一摸三")),
    FIVE_TAI("進階", "有台有面", "東南兩圈內，胡一手牌型合計 5 台以上（不含莊家台）", BotLevel.NORMAL, 2,
            20_000, 100, 20, 6_000, reach("最大的一手", " 台", ChallengeFacts::bestTai, 5)),
    DEALER_RUN("進階", "連二拉二", "四圈內，自己當莊連莊兩次（流局也算）", BotLevel.NORMAL, 4,
            20_000, 100, 20, 8_000, reach("最多連莊", " 次", ChallengeFacts::bestDealerStreak, 2)),

    CAREFUL("老手", "步步為營", "打完四圈，放槍不超過一次", BotLevel.AMATEUR, 4, 20_000, 100, 20, 10_000,
            atMost("放槍", ChallengeFacts::dealIns, 1)),
    ONE_SUIT("老手", "一色到底", "四圈內胡一手混一色或清一色（字一色也算）", BotLevel.AMATEUR, 4,
            20_000, 100, 20, 10_000, winWith("一色", "混一色", "清一色", "字一色")),
    TOP_AMATEUR("老手", "力爭上游", "打完四圈，籌碼第一名（並列也算）", BotLevel.AMATEUR, 4, 20_000, 100, 20, 15_000,
            finishFirst()),

    LAST_STAND("高手", "背水一戰", "籌碼只有 3000、每台 50：打完東南兩圈，籌碼沒有輸光", BotLevel.PRO, 2,
            3_000, 100, 50, 15_000, survive()),
    SWEEP("高手", "掃地出門", "四圈內胡牌讓一家電腦輸光籌碼（被別家弄輸光的不算）", BotLevel.PRO, 4,
            5_000, 100, 50, 20_000, bust()),
    GRADUATE("高手", "出師", "打完四圈，贏過三家職業拿第一名（並列也算）", BotLevel.PRO, 4, 20_000, 100, 20, 50_000,
            finishFirst());

    /** 任務的狀態：還在打、成功或失敗。 */
    public enum Status {
        PLAYING, CLEARED, FAILED
    }

    /** 判定的結果與牌桌上顯示的進度。 */
    public record Check(Status status, String progress) {
        public boolean decided() {
            return status != Status.PLAYING;
        }
    }

    /** 任務：看這場到目前的局面回答成功、失敗或還沒確定。 */
    private interface Goal {
        Check check(ChallengeFacts facts);
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

    Challenge(String chapter, String title, String goalText, BotLevel level, int circles, int chips, int basePay,
              int taiPay, int reward, Goal goal) {
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

    /** 這場打到現在的任務狀態。每局結算完呼叫。 */
    public Check check(GameSession session) {
        return goal.check(new ChallengeFacts(session, circles));
    }

    public GameConfig config(String playerName) {
        return new GameConfig(playerName, chips, basePay, taiPay, level);
    }

    /** 第幾關，從 1 起算。 */
    public int number() {
        return ordinal() + 1;
    }

    /** 過了前一關才能挑戰；第一關一開始就能挑戰。 */
    public Challenge previous() {
        return ordinal() == 0 ? null : values()[ordinal() - 1];
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

    /** 關卡的牌桌，例如「三家初學者・東風圈・籌碼 10000・底 100／每台 20」。 */
    public String setup() {
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
