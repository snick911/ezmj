package ezmj.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import ezmj.model.BotLevel;
import ezmj.model.PropertyFiles;
import ezmj.model.PropertyStore;

/**
 * 真人玩家的長期統計，每局累計。一份總計，再依同桌有哪些電腦等級各一份：
 * 同桌有兩種等級時兩邊都會加，所以各等級的局數加起來會超過總計。
 * 另外依三家裡最弱的是哪一級各記一份，每局只加一份，用來算「三家都是某等級以上」的成績（段位證照看這個）；
 * 這份是有段位證照之後才開始記的，以前的局只在總計與同桌等級裡。存成文字檔，讀不懂的數字當作 0。
 */
public final class PlayerStats {

    private static final int HUMAN_SEAT = 0;
    private static final int SEATS = 4;
    /** 檔案的格式版本，見 PropertyFiles.FORMAT_KEY。 */
    private static final int FORMAT = 1;
    private static final String TOTAL_PREFIX = "total.";
    private static final String LEVEL_PREFIX = "level.";
    private static final String WEAKEST_PREFIX = "weakest.";

    /** 一份累計。比率沒有局數時都是 0。 */
    public static final class Totals {
        private int rounds;
        private int wins;
        private int selfDraws;
        private int dealIns;
        private int drawnGames;
        private long winTai;
        private int bestTai;
        private long winnings;
        private int games;
        private int firstPlaces;
        private long placeSum;

        public int rounds() {
            return rounds;
        }

        public int wins() {
            return wins;
        }

        public int selfDraws() {
            return selfDraws;
        }

        public int dealIns() {
            return dealIns;
        }

        public int drawnGames() {
            return drawnGames;
        }

        public int bestTai() {
            return bestTai;
        }

        public long winnings() {
            return winnings;
        }

        public int games() {
            return games;
        }

        public int firstPlaces() {
            return firstPlaces;
        }

        public double winRate() {
            return share(wins, rounds);
        }

        public double selfDrawRate() {
            return share(selfDraws, rounds);
        }

        public double dealInRate() {
            return share(dealIns, rounds);
        }

        /** 自己胡的時候平均幾台，不含莊家台。 */
        public double averageTai() {
            return share(winTai, wins);
        }

        public double averagePlace() {
            return share(placeSum, games);
        }

        private static double share(double part, double whole) {
            return whole == 0 ? 0 : part / whole;
        }

        private void addRound(RoundRecord record) {
            RoundResult result = record.result();
            rounds++;
            winnings += record.deltas()[HUMAN_SEAT];
            if (result.isDrawnGame()) {
                drawnGames++;
            } else if (result.winner() == HUMAN_SEAT) {
                int tai = result.score().totalTai();
                wins++;
                winTai += tai;
                bestTai = Math.max(bestTai, tai);
                if (result.isSelfDraw()) {
                    selfDraws++;
                }
            } else if (result.from() == HUMAN_SEAT) {
                dealIns++;
            }
        }

        private void addGame(int place) {
            games++;
            placeSum += place;
            if (place == 1) {
                firstPlaces++;
            }
        }

        private void add(Totals other) {
            rounds += other.rounds;
            wins += other.wins;
            selfDraws += other.selfDraws;
            dealIns += other.dealIns;
            drawnGames += other.drawnGames;
            winTai += other.winTai;
            bestTai = Math.max(bestTai, other.bestTai);
            winnings += other.winnings;
            games += other.games;
            firstPlaces += other.firstPlaces;
            placeSum += other.placeSum;
        }

        private void write(Properties values, String prefix) {
            values.setProperty(prefix + "rounds", String.valueOf(rounds));
            values.setProperty(prefix + "wins", String.valueOf(wins));
            values.setProperty(prefix + "selfDraws", String.valueOf(selfDraws));
            values.setProperty(prefix + "dealIns", String.valueOf(dealIns));
            values.setProperty(prefix + "drawnGames", String.valueOf(drawnGames));
            values.setProperty(prefix + "winTai", String.valueOf(winTai));
            values.setProperty(prefix + "bestTai", String.valueOf(bestTai));
            values.setProperty(prefix + "winnings", String.valueOf(winnings));
            values.setProperty(prefix + "games", String.valueOf(games));
            values.setProperty(prefix + "firstPlaces", String.valueOf(firstPlaces));
            values.setProperty(prefix + "placeSum", String.valueOf(placeSum));
        }

        private void read(Properties values, String prefix) {
            rounds = (int) number(values, prefix + "rounds");
            wins = (int) number(values, prefix + "wins");
            selfDraws = (int) number(values, prefix + "selfDraws");
            dealIns = (int) number(values, prefix + "dealIns");
            drawnGames = (int) number(values, prefix + "drawnGames");
            winTai = number(values, prefix + "winTai");
            bestTai = (int) number(values, prefix + "bestTai");
            winnings = number(values, prefix + "winnings");
            games = (int) number(values, prefix + "games");
            firstPlaces = (int) number(values, prefix + "firstPlaces");
            placeSum = number(values, prefix + "placeSum");
        }

        private static long number(Properties values, String key) {
            try {
                return Long.parseLong(values.getProperty(key, "").trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private final Totals total = new Totals();
    private final Map<BotLevel, Totals> byLevel = new EnumMap<>(BotLevel.class);
    private final Map<BotLevel, Totals> byWeakest = new EnumMap<>(BotLevel.class);

    public PlayerStats() {
        for (BotLevel level : BotLevel.values()) {
            byLevel.put(level, new Totals());
            byWeakest.put(level, new Totals());
        }
    }

    public Totals total() {
        return total;
    }

    /** 同桌有這個等級的局，不管另外兩家是誰。 */
    public Totals against(BotLevel level) {
        return byLevel.get(level);
    }

    /** 三家都是這個等級以上的局，加起來的一份新的累計。 */
    public Totals weakestAtLeast(BotLevel level) {
        Totals sum = new Totals();
        byWeakest.forEach((weakest, totals) -> {
            if (weakest.compareTo(level) >= 0) {
                sum.add(totals);
            }
        });
        return sum;
    }

    /** 一局結算完呼叫。 */
    public void recordRound(GameSession session) {
        List<RoundRecord> history = session.history();
        RoundRecord last = history.get(history.size() - 1);
        total.addRound(last);
        session.config().botLevels().stream().distinct().forEach(level -> byLevel.get(level).addRound(last));
        byWeakest.get(weakest(session)).addRound(last);
    }

    /** 整場打完呼叫。 */
    public void recordGame(GameSession session) {
        int place = place(session);
        total.addGame(place);
        session.config().botLevels().stream().distinct().forEach(level -> byLevel.get(level).addGame(place));
        byWeakest.get(weakest(session)).addGame(place);
    }

    /** 三家電腦裡最弱的等級。 */
    private static BotLevel weakest(GameSession session) {
        return Collections.min(session.config().botLevels());
    }

    /** 籌碼相同就是同一名。 */
    static int place(GameSession session) {
        int place = 1;
        for (int seat = 0; seat < SEATS; seat++) {
            if (session.player(seat).chips() > session.player(HUMAN_SEAT).chips()) {
                place++;
            }
        }
        return place;
    }

    /** 明碼的文字檔，測試用；遊戲裡的紀錄用 Records 封存。 */
    public static PlayerStats load(Path file) {
        return load(file, PropertyStore.PLAIN);
    }

    /** 讀不到或讀不懂就是空的；新增的數字在舊檔裡沒有，從 0 開始累計。 */
    public static PlayerStats load(Path file, PropertyStore store) {
        PlayerStats stats = new PlayerStats();
        Properties values;
        try {
            values = store.read(file);
        } catch (IOException e) {
            return stats;
        }
        stats.total.read(values, TOTAL_PREFIX);
        stats.byLevel.forEach((level, totals) -> totals.read(values, LEVEL_PREFIX + level.name() + "."));
        stats.byWeakest.forEach((level, totals) -> totals.read(values, WEAKEST_PREFIX + level.name() + "."));
        return stats;
    }

    public void save(Path file) throws IOException {
        save(file, PropertyStore.PLAIN);
    }

    public void save(Path file, PropertyStore store) throws IOException {
        Properties values = new Properties();
        values.setProperty(PropertyFiles.FORMAT_KEY, String.valueOf(FORMAT));
        total.write(values, TOTAL_PREFIX);
        byLevel.forEach((level, totals) -> totals.write(values, LEVEL_PREFIX + level.name() + "."));
        byWeakest.forEach((level, totals) -> totals.write(values, WEAKEST_PREFIX + level.name() + "."));
        store.write(values, file);
    }
}
