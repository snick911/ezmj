package ezmj.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.stream.Collectors;

import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Gender;
import ezmj.model.Meld;
import ezmj.model.Personality;
import ezmj.model.Player;
import ezmj.model.PropertyFiles;
import ezmj.model.Tile;
import ezmj.model.Wall;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;

/**
 * 牌局存檔：只有一份，存成文字檔。局與局之間存的話沒有 round，接回來就開下一局。
 * 牌牆順序也存在裡面，所以重開不會換到別的牌。讀不懂或張數不對就丟 IOException，不會接回壞掉的牌局。
 */
public final class GameSave {

    private static final int VERSION = 1;
    private static final int SEATS = 4;
    private static final String LIST = ",";
    private static final String ITEMS = "\n";
    private static final String NONE = "";

    /** 讀回來的牌局。round 是 null 表示下一局還沒開始；listeningWaits 是 null 表示沒有鎖定手牌。 */
    public record Loaded(long seed, GameSession session, Round round, boolean usedAutoPlay,
                         List<Tile> listeningWaits, long thinkNanos, int thinkCount) {
    }

    private GameSave() {
    }

    /** round 是 null 或已經結束，都當作局與局之間。 */
    public static void save(Path file, long seed, GameSession session, Round round, boolean usedAutoPlay,
                            List<Tile> listeningWaits, ThinkTimer thinking) throws IOException {
        Properties values = new Properties();
        values.setProperty("version", String.valueOf(VERSION));
        values.setProperty("seed", String.valueOf(seed));
        values.setProperty("usedAutoPlay", String.valueOf(usedAutoPlay));
        values.setProperty("think.nanos", String.valueOf(thinking.totalNanos()));
        values.setProperty("think.count", String.valueOf(thinking.count()));
        writeSession(values, session);
        if (round != null && round.phase() != Round.Phase.FINISHED) {
            writeRound(values, round, listeningWaits);
        }
        PropertyFiles.store(values, file);
    }

    public static Loaded load(Path file) throws IOException {
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (IllegalArgumentException e) {
            throw new IOException("存檔內容不正確", e);
        }
        try {
            return read(values);
        } catch (RuntimeException e) {
            throw new IOException("存檔內容不正確", e);
        }
    }

    private static void writeSession(Properties values, GameSession session) {
        GameConfig config = session.config();
        values.setProperty("config.name", config.playerName());
        values.setProperty("config.chips", String.valueOf(config.startingChips()));
        values.setProperty("config.base", String.valueOf(config.basePay()));
        values.setProperty("config.tai", String.valueOf(config.taiPay()));
        values.setProperty("config.levels", config.botLevels().stream().map(BotLevel::name).collect(Collectors.joining(LIST)));
        values.setProperty("session.firstDealer", String.valueOf(session.firstDealer()));
        values.setProperty("session.dealer", String.valueOf(session.dealer()));
        values.setProperty("session.roundWind", String.valueOf(session.roundWind()));
        values.setProperty("session.streak", String.valueOf(session.streak()));
        values.setProperty("session.drawnGames", String.valueOf(session.drawnGames()));
        for (int seat = 0; seat < SEATS; seat++) {
            Player player = session.player(seat);
            String prefix = "player." + seat + ".";
            values.setProperty(prefix + "name", player.name());
            values.setProperty(prefix + "gender", player.gender().name());
            values.setProperty(prefix + "chips", String.valueOf(player.chips()));
            values.setProperty(prefix + "personality", session.personality(seat).name());
            values.setProperty(prefix + "wins", String.valueOf(session.record(seat, "").wins()));
            values.setProperty(prefix + "selfDraws", String.valueOf(session.record(seat, "").selfDraws()));
            values.setProperty(prefix + "dealIns", String.valueOf(session.record(seat, "").dealIns()));
        }
        List<RoundRecord> history = session.history();
        values.setProperty("history.count", String.valueOf(history.size()));
        for (int i = 0; i < history.size(); i++) {
            RoundRecord record = history.get(i);
            RoundResult result = record.result();
            String prefix = "history." + i + ".";
            values.setProperty(prefix + "round", record.number() + LIST + record.roundWind() + LIST + record.dealer()
                    + LIST + record.streak());
            values.setProperty(prefix + "result", result.winner() + LIST + result.from());
            values.setProperty(prefix + "tile", result.winTile() == null ? NONE : result.winTile().code());
            values.setProperty(prefix + "deltas", join(record.deltas()));
            if (result.score() != null) {
                values.setProperty(prefix + "items", result.score().items().stream()
                        .map(item -> item.tai() + "=" + item.name()).collect(Collectors.joining(ITEMS)));
            }
        }
    }

    private static void writeRound(Properties values, Round round, List<Tile> listeningWaits) {
        Round.State state = round.state();
        values.setProperty("round.phase", state.phase().name());
        values.setProperty("round.dealer", String.valueOf(round.dealer()));
        values.setProperty("round.roundWind", String.valueOf(round.roundWind()));
        values.setProperty("round.streak", String.valueOf(round.streak()));
        values.setProperty("round.current", String.valueOf(state.current()));
        values.setProperty("round.drawn", code(state.drawn()));
        values.setProperty("round.claimTile", code(state.claimTile()));
        values.setProperty("round.discarder", String.valueOf(state.discarder()));
        values.setProperty("round.robbing", String.valueOf(state.robbing()));
        values.setProperty("round.discardOnly", String.valueOf(state.discardOnly()));
        values.setProperty("round.afterKong", String.valueOf(state.afterKong()));
        values.setProperty("round.anyClaim", String.valueOf(state.anyClaim()));
        values.setProperty("round.turnsTaken", join(state.turnsTaken()));
        values.setProperty("round.wall", codes(round.wall().order()));
        values.setProperty("round.history", state.history().stream()
                .map(event -> event.seat() + event.tile().code()).collect(Collectors.joining(LIST)));
        if (listeningWaits != null) {
            values.setProperty("round.listening", codes(listeningWaits));
        }
        for (int seat = 0; seat < SEATS; seat++) {
            Player player = round.player(seat);
            String prefix = "round.player." + seat + ".";
            values.setProperty(prefix + "concealed", codes(player.concealed()));
            values.setProperty(prefix + "flowers", codes(player.flowers()));
            values.setProperty(prefix + "discards", codes(player.discards()));
            values.setProperty(prefix + "melds", player.melds().stream()
                    .map(meld -> meld.type().name() + ":" + codes(meld.tiles())).collect(Collectors.joining(LIST)));
        }
    }

    private static Loaded read(Properties values) throws IOException {
        if (integer(values, "version") != VERSION) {
            throw new IOException("存檔版本不符");
        }
        long seed = Long.parseLong(text(values, "seed"));
        List<BotLevel> levels = new ArrayList<>();
        for (String level : text(values, "config.levels").split(LIST)) {
            levels.add(BotLevel.valueOf(level));
        }
        GameConfig config = new GameConfig(text(values, "config.name"), integer(values, "config.chips"),
                integer(values, "config.base"), integer(values, "config.tai"), levels);

        Player[] players = new Player[SEATS];
        Personality[] personalities = new Personality[SEATS];
        int[] wins = new int[SEATS];
        int[] selfDraws = new int[SEATS];
        int[] dealIns = new int[SEATS];
        for (int seat = 0; seat < SEATS; seat++) {
            String prefix = "player." + seat + ".";
            players[seat] = new Player(text(values, prefix + "name"), seat, seat == 0,
                    Gender.fromKey(text(values, prefix + "gender")), integer(values, prefix + "chips"));
            personalities[seat] = Personality.fromKey(text(values, prefix + "personality"));
            wins[seat] = integer(values, prefix + "wins");
            selfDraws[seat] = integer(values, prefix + "selfDraws");
            dealIns[seat] = integer(values, prefix + "dealIns");
        }

        List<RoundRecord> history = readHistory(values);
        // Random 的內部狀態存不下來，用種子與局數換一個，之後的牌一樣是隨機的，也一樣重現得出來
        GameSession session = GameSession.restored(config, new Random(seed * 31 + history.size()), players, personalities,
                integer(values, "session.firstDealer"), integer(values, "session.dealer"),
                integer(values, "session.roundWind"), integer(values, "session.streak"),
                integer(values, "session.drawnGames"), wins, selfDraws, dealIns, history);

        Round round = values.containsKey("round.phase") ? readRound(values, players, session.firstDealer()) : null;
        List<Tile> waits = values.containsKey("round.listening") ? Tile.parseAll(text(values, "round.listening")) : null;
        return new Loaded(seed, session, round, Boolean.parseBoolean(text(values, "usedAutoPlay")), waits,
                Long.parseLong(text(values, "think.nanos")), integer(values, "think.count"));
    }

    private static List<RoundRecord> readHistory(Properties values) {
        List<RoundRecord> history = new ArrayList<>();
        int count = integer(values, "history.count");
        for (int i = 0; i < count; i++) {
            String prefix = "history." + i + ".";
            int[] numbers = ints(text(values, prefix + "round"));
            int[] outcome = ints(text(values, prefix + "result"));
            String tile = text(values, prefix + "tile");
            RoundResult result = outcome[0] < 0 ? RoundResult.drawnGame()
                    : new RoundResult(outcome[0], outcome[1], Tile.parseAll(tile).get(0),
                            readScore(values.getProperty(prefix + "items", NONE)));
            history.add(new RoundRecord(numbers[0], numbers[1], numbers[2], numbers[3], result,
                    ints(text(values, prefix + "deltas"))));
        }
        return history;
    }

    private static ScoreResult readScore(String text) {
        List<ScoreItem> items = new ArrayList<>();
        if (!text.isEmpty()) {
            for (String item : text.split(ITEMS)) {
                int split = item.indexOf('=');
                items.add(new ScoreItem(item.substring(split + 1), Integer.parseInt(item.substring(0, split))));
            }
        }
        return new ScoreResult(items);
    }

    /** 局風不在存檔裡，用起莊與這局的莊家算，舊的存檔也讀得出來。 */
    private static Round readRound(Properties values, Player[] players, int firstDealer) throws IOException {
        for (int seat = 0; seat < SEATS; seat++) {
            String prefix = "round.player." + seat + ".";
            for (Tile tile : Tile.parseAll(text(values, prefix + "concealed"))) {
                players[seat].addToHand(tile);
            }
            for (Tile tile : Tile.parseAll(text(values, prefix + "flowers"))) {
                players[seat].addFlower(tile);
            }
            for (Tile tile : Tile.parseAll(text(values, prefix + "discards"))) {
                players[seat].addDiscard(tile);
            }
            String melds = text(values, prefix + "melds");
            if (!melds.isEmpty()) {
                for (String meld : melds.split(LIST)) {
                    String[] parts = meld.split(":");
                    players[seat].addMeld(new Meld(Meld.Type.valueOf(parts[0]), Tile.parseAll(parts[1])));
                }
            }
        }
        List<DiscardEvent> discards = new ArrayList<>();
        String events = text(values, "round.history");
        if (!events.isEmpty()) {
            for (String event : events.split(LIST)) {
                discards.add(new DiscardEvent(event.charAt(0) - '0', Tile.parseAll(event.substring(1)).get(0)));
            }
        }
        Round.State state = new Round.State(Round.Phase.valueOf(text(values, "round.phase")),
                integer(values, "round.current"), tile(text(values, "round.drawn")), tile(text(values, "round.claimTile")),
                integer(values, "round.discarder"), bool(values, "round.robbing"), bool(values, "round.discardOnly"),
                bool(values, "round.afterKong"), bool(values, "round.anyClaim"), ints(text(values, "round.turnsTaken")),
                discards);
        int dealer = integer(values, "round.dealer");
        Round round = Round.restored(players, new Wall(Tile.parseAll(text(values, "round.wall"))), dealer,
                integer(values, "round.roundWind"), GameSession.handWind(firstDealer, dealer), integer(values, "round.streak"),
                state);
        requireFullSet(round);
        return round;
    }

    /** 牌一定要剛好是完整的 144 張，不然就是檔案壞了或被改過。 */
    private static void requireFullSet(Round round) throws IOException {
        List<Tile> all = new ArrayList<>(round.wall().order());
        for (int seat = 0; seat < SEATS; seat++) {
            Player player = round.player(seat);
            all.addAll(player.concealed());
            all.addAll(player.flowers());
            all.addAll(player.discards());
            for (Meld meld : player.melds()) {
                all.addAll(meld.tiles());
            }
        }
        if (round.isRobbing()) {
            all.add(round.claimTile());
        }
        List<Tile> full = new ArrayList<>(Tile.fullSet());
        all.sort(null);
        full.sort(null);
        if (!all.equals(full)) {
            throw new IOException("存檔裡的牌不是完整的一副");
        }
    }

    private static String code(Tile tile) {
        return tile == null ? NONE : tile.code();
    }

    private static Tile tile(String code) {
        return code.isEmpty() ? null : Tile.parseAll(code).get(0);
    }

    private static String codes(List<Tile> tiles) {
        return tiles.stream().map(Tile::code).collect(Collectors.joining());
    }

    private static String join(int[] numbers) {
        List<String> parts = new ArrayList<>();
        for (int number : numbers) {
            parts.add(String.valueOf(number));
        }
        return String.join(LIST, parts);
    }

    private static int[] ints(String text) {
        String[] parts = text.split(LIST);
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = Integer.parseInt(parts[i]);
        }
        return numbers;
    }

    private static String text(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("存檔少了 " + key);
        }
        return value;
    }

    private static int integer(Properties values, String key) {
        return Integer.parseInt(text(values, key));
    }

    private static boolean bool(Properties values, String key) {
        return Boolean.parseBoolean(text(values, key));
    }
}
