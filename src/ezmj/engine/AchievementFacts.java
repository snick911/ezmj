package ezmj.engine;

import java.util.List;
import java.util.function.Predicate;

import ezmj.model.BotLevel;
import ezmj.model.GameRecord;

/** 判定成就時看的局面：剛結算完的牌局，加上累計次數。gameEnded 表示整場已經結束。 */
final class AchievementFacts {

    private static final int HUMAN_SEAT = 0;
    private static final int LAST_ROUND_WIND = 4;

    private final GameSession session;
    private final AchievementBook book;
    private final boolean gameEnded;
    private final double averageThinkSeconds;
    private final int thinkCount;
    private final GameRecord record;

    AchievementFacts(GameSession session, AchievementBook book, boolean gameEnded,
                     double averageThinkSeconds, int thinkCount) {
        this.session = session;
        this.book = book;
        this.gameEnded = gameEnded;
        this.averageThinkSeconds = averageThinkSeconds;
        this.thinkCount = thinkCount;
        this.record = session.record(HUMAN_SEAT, "");
    }

    private RoundResult lastResult() {
        List<RoundRecord> history = session.history();
        return history.isEmpty() ? RoundResult.drawnGame() : history.get(history.size() - 1).result();
    }

    boolean humanWon() {
        return lastResult().winner() == HUMAN_SEAT;
    }

    int handTai() {
        return lastResult().score().totalTai();
    }

    /** 台數項目可能帶細節，例如「花槓 春夏秋冬」，只比前面的牌型名稱。 */
    boolean hasHandItem(String name) {
        return humanWon() && lastResult().score().items().stream()
                .anyMatch(item -> item.name().split(" ")[0].equals(name));
    }

    /** 連莊的次數；不是自己當莊就是 0。流局和胡牌都會連莊，結算完 streak 已經更新。 */
    int humanDealerStreak() {
        return session.dealer() == HUMAN_SEAT ? session.streak() : 0;
    }

    int humanWinRun() {
        return run(round -> round.result().winner() == HUMAN_SEAT);
    }

    int drawnRun() {
        return run(round -> round.result().isDrawnGame());
    }

    int humanDealInRun() {
        return run(round -> round.result().from() == HUMAN_SEAT);
    }

    /** 從最新一局往回數，連續符合條件的局數。 */
    private int run(Predicate<RoundRecord> condition) {
        List<RoundRecord> history = session.history();
        int count = 0;
        for (int i = history.size() - 1; i >= 0 && condition.test(history.get(i)); i--) {
            count++;
        }
        return count;
    }

    int count(Achievement.Counter counter) {
        return book.count(counter);
    }

    boolean gameEnded() {
        return gameEnded;
    }

    /** 四圈全部打完，不是有人輸光提早結束。 */
    boolean fullGame() {
        return gameEnded && session.roundWind() > LAST_ROUND_WIND;
    }

    boolean humanOnTop() {
        if (!gameEnded) {
            return false;
        }
        for (int seat = 0; seat < 4; seat++) {
            if (session.player(seat).chips() > humanChips()) {
                return false;
            }
        }
        return true;
    }

    boolean someoneElseBroke() {
        if (!gameEnded || humanChips() <= 0) {
            return false;
        }
        for (int seat = 1; seat < 4; seat++) {
            if (session.player(seat).chips() <= 0) {
                return true;
            }
        }
        return false;
    }

    int humanChips() {
        return session.player(HUMAN_SEAT).chips();
    }

    int startingChips() {
        return session.config().startingChips();
    }

    int wins() {
        return record.wins();
    }

    int dealIns() {
        return record.dealIns();
    }

    int winnings() {
        return record.winnings();
    }

    int drawnGames() {
        return session.drawnGames();
    }

    BotLevel botLevel() {
        return session.config().botLevel();
    }

    double averageThinkSeconds() {
        return averageThinkSeconds;
    }

    int thinkCount() {
        return thinkCount;
    }
}
