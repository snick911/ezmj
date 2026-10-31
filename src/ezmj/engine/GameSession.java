package ezmj.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import ezmj.model.GameConfig;
import ezmj.model.Player;
import ezmj.model.Wall;

/** 整場牌局：籌碼、莊家輪替、連莊、圈風。四圈打完或有人輸光即結束。 */
public final class GameSession {

    private static final int SEATS = 4;

    private final GameConfig config;
    private final Random random;
    private final Player[] players = new Player[SEATS];
    private final int firstDealer;

    private int dealer;
    private int roundWind = 1;
    private int streak;

    public GameSession(GameConfig config, Random random) {
        this.config = config;
        this.random = random;
        players[0] = new Player(config.playerName(), 0, true, config.startingChips());
        List<String> botNames = BotNames.pick(SEATS - 1, config.playerName(), random);
        for (int seat = 1; seat < SEATS; seat++) {
            players[seat] = new Player(botNames.get(seat - 1), seat, false, config.startingChips());
        }
        this.firstDealer = random.nextInt(SEATS);
        this.dealer = firstDealer;
    }

    public Round newRound(Consumer<String> log) {
        for (Player player : players) {
            player.resetForRound();
        }
        return new Round(players, new Wall(random), dealer, roundWind, streak, log);
    }

    /** 結算金額並推進莊家與圈風。 */
    public Settlement settle(RoundResult result) {
        int[] deltas = new int[SEATS];
        List<String> notes = new ArrayList<>();
        if (!result.isDrawnGame()) {
            int dealerTai = 1 + 2 * streak;
            int handTai = result.score().totalTai();
            for (int seat = 0; seat < SEATS; seat++) {
                if (seat == result.winner() || !(result.isSelfDraw() || seat == result.from())) {
                    continue;
                }
                boolean dealerInvolved = seat == dealer || result.winner() == dealer;
                int tai = handTai + (dealerInvolved ? dealerTai : 0);
                int amount = config.basePay() + tai * config.taiPay();
                deltas[seat] -= amount;
                deltas[result.winner()] += amount;
                notes.add(players[seat].name() + " 付 " + amount + "（底 " + config.basePay()
                        + " + " + tai + " 台 × " + config.taiPay() + "）");
            }
            if (streak > 0) {
                notes.add("莊家 1 台＋連" + streak + "拉" + streak + " " + 2 * streak + " 台（只計與莊家相關的付款）");
            } else {
                notes.add("莊家 1 台（只計與莊家相關的付款）");
            }
        }
        for (int seat = 0; seat < SEATS; seat++) {
            players[seat].addChips(deltas[seat]);
        }
        advanceDealer(result);
        return new Settlement(deltas, notes);
    }

    private void advanceDealer(RoundResult result) {
        if (result.isDrawnGame() || result.winner() == dealer) {
            streak++;
            return;
        }
        streak = 0;
        dealer = (dealer + 1) % SEATS;
        if (dealer == firstDealer) {
            roundWind++;
        }
    }

    public boolean isOver() {
        if (roundWind > 4) {
            return true;
        }
        for (Player player : players) {
            if (player.chips() <= 0) {
                return true;
            }
        }
        return false;
    }

    public Player player(int seat) {
        return players[seat];
    }

    public GameConfig config() {
        return config;
    }

    public int dealer() {
        return dealer;
    }

    public int streak() {
        return streak;
    }

    public int roundWind() {
        return roundWind;
    }
}
