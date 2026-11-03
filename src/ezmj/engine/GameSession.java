package ezmj.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.GameRecord;
import ezmj.model.Gender;
import ezmj.model.Personality;
import ezmj.model.Player;
import ezmj.model.Wall;

/** 整場牌局：籌碼、莊家輪替、連莊、圈風。四圈打完或有人輸光即結束。 */
public final class GameSession implements GameView {

    private static final int SEATS = 4;

    private final GameConfig config;
    private final Random random;
    private final Player[] players = new Player[SEATS];
    private final int firstDealer;
    /** 真人的位置用不到，固定是穩健。 */
    private final Personality[] personalities = new Personality[SEATS];
    private final int[] wins = new int[SEATS];
    private final int[] selfDraws = new int[SEATS];
    private final int[] dealIns = new int[SEATS];
    private final List<RoundRecord> history = new ArrayList<>();

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
        personalities[0] = Personality.BALANCED;
        for (int seat = 1; seat < SEATS; seat++) {
            personalities[seat] = Personality.values()[random.nextInt(Personality.values().length)];
        }
    }

    /** 從存檔接回一場牌局。 */
    private GameSession(GameConfig config, Random random, Player[] players, Personality[] personalities, int firstDealer) {
        this.config = config;
        this.random = random;
        this.firstDealer = firstDealer;
        System.arraycopy(players, 0, this.players, 0, SEATS);
        System.arraycopy(personalities, 0, this.personalities, 0, SEATS);
    }

    /** wins、selfDraws、dealIns 依座位排列。 */
    static GameSession restored(GameConfig config, Random random, Player[] players, Personality[] personalities,
                                int firstDealer, int dealer, int roundWind, int streak, int drawnGames,
                                int[] wins, int[] selfDraws, int[] dealIns, List<RoundRecord> history) {
        GameSession session = new GameSession(config, random, players, personalities, firstDealer);
        session.dealer = dealer;
        session.roundWind = roundWind;
        session.streak = streak;
        session.roundsPlayed = history.size();
        session.drawnGames = drawnGames;
        System.arraycopy(wins, 0, session.wins, 0, SEATS);
        System.arraycopy(selfDraws, 0, session.selfDraws, 0, SEATS);
        System.arraycopy(dealIns, 0, session.dealIns, 0, SEATS);
        session.history.addAll(history);
        return session;
    }

    public Round newRound(Consumer<String> log) {
        for (Player player : players) {
            player.resetForRound();
        }
        return new Round(players, new Wall(random), dealer, roundWind, handWind(), streak, log);
    }

    /** 下一局的局風，見 RoundView.handWind。 */
    public int handWind() {
        return handWind(firstDealer, dealer);
    }

    /** 起莊是東局，往下一家依序是南、西、北局。讀存檔時也用這個算，存檔裡不另外記。 */
    static int handWind(int firstDealer, int dealer) {
        return Math.floorMod(dealer - firstDealer, SEATS) + 1;
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
        history.add(new RoundRecord(roundsPlayed, roundWind, dealer, streak, result, deltas));
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

    @Override
    public Player player(int seat) {
        return players[seat];
    }

    @Override
    public int basePay() {
        return config.basePay();
    }

    @Override
    public int taiPay() {
        return config.taiPay();
    }

    /** 電腦的等級與個性。初學者憑感覺打，沒有個性之分。 */
    @Override
    public String traits(int seat) {
        if (seat == 0) {
            return null;
        }
        BotLevel level = config.botLevels().get(seat - 1);
        return level == BotLevel.BEGINNER ? level.label() : level.label() + "・" + personalities[seat].label();
    }

    /** 到目前為止這個座位的成績。 */
    @Override
    public GameRecord record(int seat, String date) {
        return new GameRecord(players[seat].name(), date, wins[seat], selfDraws[seat],
                players[seat].chips() - config.startingChips(), dealIns[seat],
                config.botLevels(), config.basePay(), config.taiPay());
    }

    /** 已經結算的局數，包含流局。 */
    @Override
    public int roundsPlayed() {
        return roundsPlayed;
    }

    @Override
    public int drawnGames() {
        return drawnGames;
    }

    /** 這場打完的每一局，先打的在前面。 */
    @Override
    public List<RoundRecord> history() {
        return List.copyOf(history);
    }

    /** 電腦的個性。 */
    public Personality personality(int seat) {
        return personalities[seat];
    }

    int firstDealer() {
        return firstDealer;
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
