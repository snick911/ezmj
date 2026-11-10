package ezmj.career;

import ezmj.engine.PlayerStats;
import ezmj.model.BotLevel;

/**
 * 段位證照的九段。局數、胡牌率、自摸率到門檻，放槍率不超過門檻，四項都做到就是這一段；
 * 段位越高只看越強對手的局，因為對初學者很容易把胡牌率衝高。初段到三段看全部的局，
 * 四段到六段只看三家都是業餘以上的局，七段到九段只看三家都是職業的局。
 * 門檻參考電腦互打的成績：實力相當時每家胡牌率約 22 到 24%、自摸率 5 到 7%、放槍率 15 到 20%；
 * 職業對三家職業是胡 23.6%、自摸 6.6%、放槍 16.9%，職業在混桌是胡 28.2%、放槍 15.2%，初學者在混桌放槍 21.9%。
 * 自摸看運氣的成分大，門檻壓低，七段到九段每段只多 0.2%；八段、九段要長期穩定贏過三家職業一大截，
 * 九段 4000 局大約要打 180 場以上。宣告順序由低到高；名稱用來存檔，不要改。
 */
public enum Dan {
    FIRST("初段", null, 50, 150, 30, 250),
    SECOND("二段", null, 150, 180, 35, 230),
    THIRD("三段", null, 300, 210, 40, 210),
    FOURTH("四段", BotLevel.AMATEUR, 400, 210, 40, 200),
    FIFTH("五段", BotLevel.AMATEUR, 800, 230, 43, 185),
    SIXTH("六段", BotLevel.AMATEUR, 2000, 250, 45, 170),
    SEVENTH("七段", BotLevel.PRO, 800, 230, 47, 165),
    EIGHTH("八段", BotLevel.PRO, 2000, 270, 49, 155),
    NINTH("九段", BotLevel.PRO, 4000, 300, 51, 145);

    private final String label;
    private final BotLevel opponents;
    private final int rounds;
    private final int winPermille;
    private final int selfDrawPermille;
    private final int dealInPermille;

    /**
     * opponents 是三家最弱要到哪一級，null 是不限；比率用千分之幾，比較時不會有小數的誤差。
     * dealInPermille 是放槍率的上限，不能超過。
     */
    Dan(String label, BotLevel opponents, int rounds, int winPermille, int selfDrawPermille, int dealInPermille) {
        this.label = label;
        this.opponents = opponents;
        this.rounds = rounds;
        this.winPermille = winPermille;
        this.selfDrawPermille = selfDrawPermille;
        this.dealInPermille = dealInPermille;
    }

    /** 例如「七段」。 */
    public String label() {
        return label;
    }

    /** 1 到 9。 */
    public int number() {
        return ordinal() + 1;
    }

    /** 三家最弱要到哪一級；null 是不限。 */
    public BotLevel opponents() {
        return opponents;
    }

    public int rounds() {
        return rounds;
    }

    /** 胡牌率門檻，千分之幾。 */
    public int winPermille() {
        return winPermille;
    }

    /** 自摸率門檻，千分之幾。 */
    public int selfDrawPermille() {
        return selfDrawPermille;
    }

    /** 放槍率上限，千分之幾，不能超過。 */
    public int dealInPermille() {
        return dealInPermille;
    }

    /** 例如「三家都是職業」「三家都是業餘以上」「對手不限」。 */
    public String opponentsText() {
        if (opponents == null) {
            return "對手不限";
        }
        return opponents == BotLevel.values()[BotLevel.values().length - 1]
                ? "三家都是" + opponents.label() : "三家都是" + opponents.label() + "以上";
    }

    /** 這一段看的那些局。 */
    public PlayerStats.Totals pool(PlayerStats stats) {
        return opponents == null ? stats.total() : stats.weakestAtLeast(opponents);
    }

    public boolean roundsMet(PlayerStats.Totals totals) {
        return totals.rounds() >= rounds;
    }

    public boolean winRateMet(PlayerStats.Totals totals) {
        return reaches(totals.wins(), totals.rounds(), winPermille);
    }

    public boolean selfDrawRateMet(PlayerStats.Totals totals) {
        return reaches(totals.selfDraws(), totals.rounds(), selfDrawPermille);
    }

    /** 放槍率不超過上限；一局都沒打不算。 */
    public boolean dealInRateMet(PlayerStats.Totals totals) {
        return totals.rounds() > 0 && (long) totals.dealIns() * 1000 <= (long) totals.rounds() * dealInPermille;
    }

    public boolean isMet(PlayerStats stats) {
        PlayerStats.Totals totals = pool(stats);
        return roundsMet(totals) && winRateMet(totals) && selfDrawRateMet(totals) && dealInRateMet(totals);
    }

    private static boolean reaches(int count, int rounds, int permille) {
        return rounds > 0 && (long) count * 1000 >= (long) rounds * permille;
    }

    /** 成績到得了的最高段位；一段都不到是 null。中間的段不一定也到，例如只跟職業打的人可能直接是七段。 */
    public static Dan best(PlayerStats stats) {
        Dan best = null;
        for (Dan dan : values()) {
            if (dan.isMet(stats)) {
                best = dan;
            }
        }
        return best;
    }

    /** current 的下一段；還沒入段是初段，九段之後是 null。 */
    public static Dan after(Dan current) {
        if (current == null) {
            return values()[0];
        }
        return current.ordinal() + 1 < values().length ? values()[current.ordinal() + 1] : null;
    }

    /** 存檔裡的名稱；認不得是 null。 */
    static Dan parse(String name) {
        for (Dan dan : values()) {
            if (dan.name().equals(name)) {
                return dan;
            }
        }
        return null;
    }
}
