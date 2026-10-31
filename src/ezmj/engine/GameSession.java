package ezmj.engine;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import ezmj.model.GameConfig;
import ezmj.model.GameRecord;
import ezmj.model.Gender;
import ezmj.model.Player;
import ezmj.model.Wall;

/** 整場牌局：籌碼、莊家輪替、連莊、圈風。四圈打完或有人輸光即結束。 */
public final class GameSession {

    private static final int SEATS = 4;

    private final GameConfig config;
    private final Random random;
    private final Player[] players = new Player[SEATS];
    private final int firstDealer;
    private final int[] wins = new int[SEATS];
    private final int[] selfDraws = new int[SEATS];
    private final int[] dealIns = new int[SEATS];

    private int roundsPlayed;
    private int drawnGames;
    private int dealer;
    private int roundWind = 1;
    private int streak;

    public GameSession(GameConfig config, Random random) {
        this.config = config;
        this.random = random;
        // 真人的聲音牌局中也能改，喊牌時直接讀環境設定，不看這裡的性別
        players[0] = new Player(config.playerName(), 0, true, Gender.FEMALE, config.startingChips());
        List<BotNames.Identity> bots = BotNames.pick(SEATS - 1, config.playerName(), random);
        for (int seat = 1; seat < SEATS; seat++) {
            BotNames.Identity bot = bots.get(seat - 1);
            players[seat] = new Player(bot.name(), seat, false, bot.gender(), config.startingChips());
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
        int[] tais = new int[SEATS];
        roundsPlayed++;
        if (result.isDrawnGame()) {
            drawnGames++;
        } else {
            wins[result.winner()]++;
            if (result.isSelfDraw()) {
                selfDraws[result.winner()]++;
            } else {
                dealIns[result.from()]++;
            }
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
                tais[seat] = tai;
            }
        }
        for (int seat = 0; seat < SEATS; seat++) {
            players[seat].addChips(deltas[seat]);
        }
        advanceDealer(result);
        return new Settlement(deltas, tais);
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

    /** 到目前為止這個座位的成績。 */
    public GameRecord record(int seat, String date) {
        return new GameRecord(players[seat].name(), date, wins[seat], selfDraws[seat],
                players[seat].chips() - config.startingChips(), dealIns[seat],
                config.botLevel(), config.basePay(), config.taiPay());
    }

    /** 已經結算的局數，包含流局。 */
    public int roundsPlayed() {
        return roundsPlayed;
    }

    public int drawnGames() {
        return drawnGames;
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
