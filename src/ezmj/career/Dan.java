package ezmj.career;

import ezmj.engine.PlayerStats;
import ezmj.model.BotLevel;

/**
 * 段位證照的九段。局數、胡牌率、自摸率三項都到了就是這一段；段位越高只看越強對手的局，
 * 因為對初學者很容易把胡牌率衝高。初段到三段看全部的局，四段到六段只看三家都是業餘以上的局，
 * 七段到九段只看三家都是職業的局。
 * 門檻參考電腦互打的成績：實力相當時每家胡牌率約 22 到 24%、自摸率 5 到 7%，職業對三家職業是 23.6% 與 6.6%。
 * 宣告順序由低到高；名稱用來存檔，不要改。
 */
public enum Dan {
    FIRST("初段", null, 50, 150, 30),
    SECOND("二段", null, 150, 180, 40),
    THIRD("三段", null, 300, 210, 50),
    FOURTH("四段", BotLevel.AMATEUR, 300, 210, 50),
    FIFTH("五段", BotLevel.AMATEUR, 600, 230, 55),
    SIXTH("六段", BotLevel.AMATEUR, 1000, 250, 60),
    SEVENTH("七段", BotLevel.PRO, 600, 230, 60),
    EIGHTH("八段", BotLevel.PRO, 1200, 250, 65),
    NINTH("九段", BotLevel.PRO, 2000, 270, 70);

    private final String label;
    private final BotLevel opponents;
    private final int rounds;
    private final int winPermille;
    private final int selfDrawPermille;

    /** opponents 是三家最弱要到哪一級，null 是不限；比率用千分之幾，比較時不會有小數的誤差。 */
    Dan(String label, BotLevel opponents, int rounds, int winPermille, int selfDrawPermille) {
        this.label = label;
        this.opponents = opponents;
        this.rounds = rounds;
        this.winPermille = winPermille;
        this.selfDrawPermille = selfDrawPermille;
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

    public boolean isMet(PlayerStats stats) {
        PlayerStats.Totals totals = pool(stats);
        return roundsMet(totals) && winRateMet(totals) && selfDrawRateMet(totals);
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
