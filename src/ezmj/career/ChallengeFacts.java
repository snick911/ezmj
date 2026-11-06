package ezmj.career;

import java.util.List;

import ezmj.engine.GameSession;
import ezmj.engine.RoundRecord;
import ezmj.engine.RoundResult;
import ezmj.model.Player;

/** 判定挑戰任務時看的局面：這場到目前打完的每一局，都是看真人（座位 0）。 */
final class ChallengeFacts {

    private static final int HUMAN_SEAT = 0;
    private static final int SEATS = 4;

    private final GameSession session;
    private final int circles;

    ChallengeFacts(GameSession session, int circles) {
        this.session = session;
        this.circles = circles;
    }

    /** 規定的圈數打完，或有人輸光。 */
    boolean finished() {
        return session.isOver() || session.roundWind() > circles;
    }

    int wins() {
        return session.record(HUMAN_SEAT, "").wins();
    }

    int selfDraws() {
        return session.record(HUMAN_SEAT, "").selfDraws();
    }

    int dealIns() {
        return session.record(HUMAN_SEAT, "").dealIns();
    }

    int humanChips() {
        return session.player(HUMAN_SEAT).chips();
    }

    /** 籌碼相同就是同一名。 */
    int place() {
        int place = 1;
        for (int seat = 0; seat < SEATS; seat++) {
            if (session.player(seat).chips() > humanChips()) {
                place++;
            }
        }
        return place;
    }

    /** 自己胡過最大的一手，牌型合計，不含莊家台；還沒胡過是 0。 */
    int bestTai() {
        int best = 0;
        for (RoundRecord record : session.history()) {
            if (record.result().winner() == HUMAN_SEAT) {
                best = Math.max(best, record.result().score().totalTai());
            }
        }
        return best;
    }

    /** 自己胡過的牌裡有沒有其中一種牌型。台數項目可能帶細節，例如「花槓 春夏秋冬」，只比前面的名稱。 */
    boolean wonWith(List<String> items) {
        for (RoundRecord record : session.history()) {
            RoundResult result = record.result();
            if (result.winner() == HUMAN_SEAT && result.score().items().stream()
                    .anyMatch(item -> items.contains(item.name().split(" ")[0]))) {
                return true;
            }
        }
        return false;
    }

    /** 這場自己當莊最多連莊幾次。自己當莊時胡牌或流局，下一局就多連一次。 */
    int bestDealerStreak() {
        int best = 0;
        for (RoundRecord record : session.history()) {
            RoundResult result = record.result();
            if (record.dealer() == HUMAN_SEAT && (result.isDrawnGame() || result.winner() == HUMAN_SEAT)) {
                best = Math.max(best, record.streak() + 1);
            }
        }
        return best;
    }

    /** 自己胡的這一局，有電腦付了錢因此輸光。有人輸光牌局就結束，所以只要看最後一局。 */
    boolean bustedByHuman() {
        List<RoundRecord> history = session.history();
        if (history.isEmpty()) {
            return false;
        }
        RoundRecord last = history.get(history.size() - 1);
        if (last.result().winner() != HUMAN_SEAT) {
            return false;
        }
        for (int seat = 1; seat < SEATS; seat++) {
            if (last.deltas()[seat] < 0 && session.player(seat).chips() <= 0) {
                return true;
            }
        }
        return false;
    }

    /** 籌碼最少的電腦，同樣少時取座位前面的。 */
    Player poorestBot() {
        Player poorest = session.player(1);
        for (int seat = 2; seat < SEATS; seat++) {
            if (session.player(seat).chips() < poorest.chips()) {
                poorest = session.player(seat);
            }
        }
        return poorest;
    }
}
