package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ezmj.bot.ComputerBot;
import ezmj.engine.Claim;
import ezmj.engine.GameSave;
import ezmj.engine.GameSession;
import ezmj.engine.PlayerStats;
import ezmj.engine.Round;
import ezmj.engine.RoundRecord;
import ezmj.engine.RoundResult;
import ezmj.engine.ThinkTimer;
import ezmj.model.BotLevel;
import ezmj.model.ErrorLog;
import ezmj.model.GameConfig;
import ezmj.model.Meld;
import ezmj.model.Personality;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;

/** 牌局存檔、長期統計與錯誤記錄。 */
final class SaveTest {

    private static final int CHIPS = 100_000;

    private SaveTest() {
    }

    private static Path tempFile() throws IOException {
        Path dir = Files.createTempDirectory("ezmj-save");
        return dir.resolve("save.properties");
    }

    private static ComputerBot[] bots() {
        ComputerBot[] bots = new ComputerBot[4];
        for (int seat = 0; seat < 4; seat++) {
            bots[seat] = new ComputerBot(BotLevel.PRO, 100, 20, new Random(seat));
        }
        return bots;
    }

    private static GameSession newSession(long seed) {
        return new GameSession(new GameConfig("小明<&>", CHIPS, 100, 20,
                List.of(BotLevel.NORMAL, BotLevel.AMATEUR, BotLevel.PRO)), new Random(seed));
    }

    /** 走一步：輪到的人打牌或宣告，或大家對打出的牌做決定。 */
    private static void step(Round round, ComputerBot[] bots) {
        if (round.phase() == Round.Phase.TURN) {
            int seat = round.current();
            bots[seat].playTurn(round, seat, round.turnOptions(seat));
            return;
        }
        Map<Integer, Claim> decisions = new HashMap<>();
        for (int seat = 0; seat < 4; seat++) {
            List<Claim> options = round.claimOptions(seat);
            if (!options.isEmpty()) {
                Claim claim = bots[seat].chooseClaim(round, seat, options);
                if (claim != null) {
                    decisions.put(seat, claim);
                }
            }
        }
        round.resolveClaims(decisions);
    }

    private static void playOut(Round round, ComputerBot[] bots) {
        while (round.phase() != Round.Phase.FINISHED) {
            step(round, bots);
        }
    }

    /** 兩局看起來一樣：手牌、面子、花牌、棄牌、牌牆與輪到誰。 */
    private static void sameTable(Round expected, Round actual, String when) {
        eq(expected.phase(), actual.phase(), when + "：階段");
        eq(expected.wall().order(), actual.wall().order(), when + "：牌牆順序");
        for (int seat = 0; seat < 4; seat++) {
            Player a = expected.player(seat);
            Player b = actual.player(seat);
            eq(a.concealed(), b.concealed(), when + "：手牌");
            eq(a.melds(), b.melds(), when + "：面子");
            eq(a.flowers(), b.flowers(), when + "：花牌");
            eq(a.discards(), b.discards(), when + "：棄牌");
            eq(a.chips(), b.chips(), when + "：籌碼");
            eq(a.name(), b.name(), when + "：名字");
            eq(a.gender(), b.gender(), when + "：性別");
        }
    }

    private static void saveAndLoadSameGame(long seed, int[] saved, int[] robbing, int[] claims) throws Exception {
        Path file = tempFile();
        GameSession session = newSession(seed);
        for (int r = 0; r < 3; r++) {
            ComputerBot[] bots = bots();
            Round round = session.newRound(message -> { });
            round.start();
            while (round.phase() != Round.Phase.FINISHED) {
                GameSave.save(file, seed, session, round, false, new ThinkTimer());
                GameSave.Loaded loaded = GameSave.load(file);
                sameTable(round, loaded.round(), "讀檔後");
                saved[0]++;
                robbing[0] += round.isRobbing() ? 1 : 0;
                claims[0] += round.phase() == Round.Phase.CLAIMS ? 1 : 0;

                if (saved[0] % 37 == 0) {
                    // 從這裡分岔，用同樣的電腦打完，結果要和沒存檔時一樣
                    Round copy = loaded.round();
                    Round reference = GameSave.load(file).round();
                    ComputerBot[] copyBots = bots();
                    ComputerBot[] referenceBots = bots();
                    playOut(copy, copyBots);
                    playOut(reference, referenceBots);
                    eq(reference.result().winner(), copy.result().winner(), "讀檔後打完：贏家");
                    eq(reference.result().from(), copy.result().from(), "讀檔後打完：放槍");
                    sameTable(reference, copy, "讀檔後打完");
                }
                step(round, bots);
            }
            session.settle(round.result());
        }
    }

    static void run() {
        T.section("牌局存檔");

        test("牌局中每一步存檔再讀回，牌桌都一樣，也能接著打完", () -> {
            int[] saved = {0};
            int[] robbing = {0};
            int[] claims = {0};
            for (long seed = 1; seed <= 12; seed++) {
                saveAndLoadSameGame(seed, saved, robbing, claims);
            }
            check(saved[0] > 500, "要涵蓋夠多局面：" + saved[0]);
            check(claims[0] > 100, "要涵蓋等宣告的局面：" + claims[0]);
        });

        test("讀檔接回的局和原本那局走一樣的路", () -> {
            Path file = tempFile();
            GameSession session = newSession(9);
            Round round = session.newRound(message -> { });
            round.start();
            ComputerBot[] bots = bots();
            for (int i = 0; i < 30 && round.phase() != Round.Phase.FINISHED; i++) {
                step(round, bots);
            }
            GameSave.save(file, 9, session, round, false, new ThinkTimer());
            Round restored = GameSave.load(file).round();
            ComputerBot[] same = bots();
            playOut(round, bots());
            playOut(restored, same);
            eq(round.result().winner(), restored.result().winner(), "贏家");
            eq(round.result().from(), restored.result().from(), "放槍");
            eq(round.discardHistory(), restored.discardHistory(), "打牌順序");
        });

        test("局風不在存檔裡，讀檔時用起莊算回來，舊的存檔也一樣", () -> {
            Path file = tempFile();
            GameSession session = newSession(4);
            int firstDealer = session.dealer();
            // 下莊兩次，這局是西局
            for (int i = 1; i <= 2; i++) {
                int dealer = session.dealer();
                session.settle(new RoundResult((dealer + 1) % 4, (dealer + 2) % 4, tile("1w"),
                        new ScoreResult(List.of(new ScoreItem("測試", 1)))));
            }
            eq((firstDealer + 2) % 4, session.dealer(), "下莊兩次");
            Round round = session.newRound(message -> { });
            round.start();
            eq(3, round.handWind(), "西局");
            GameSave.save(file, 4, session, round, false, new ThinkTimer());
            check(!Files.readString(file, StandardCharsets.ISO_8859_1).contains("handWind"), "存檔沒有多一個欄位");
            GameSave.Loaded loaded = GameSave.load(file);
            eq(3, loaded.round().handWind(), "讀回來的這局");
            eq(3, loaded.session().handWind(), "讀回來的整場");
        });

        test("專家模式：存檔讀回來還是專家模式，以前的存檔沒有這個欄位就是一般模式", () -> {
            Path file = tempFile();
            GameSession session = new GameSession(newSession(6).config().withExpert(true), new Random(6));
            Round round = session.newRound(message -> { });
            round.start();
            GameSave.save(file, 6, session, round, false, new ThinkTimer());
            check(GameSave.load(file).session().config().expert(), "專家模式讀回來");
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            check(text.contains("config.expert=true"), "存了專家模式");
            Files.writeString(file, text.replace("config.expert=true", ""), StandardCharsets.ISO_8859_1);
            check(!GameSave.load(file).session().config().expert(), "沒有欄位是一般模式");
        });

        test("局與局之間存檔：沒有 round，整場的資料都留著，再來的局也正常", () -> {
            Path file = tempFile();
            GameSession session = newSession(3);
            for (int r = 0; r < 4; r++) {
                Round round = session.newRound(message -> { });
                round.start();
                playOut(round, bots());
                session.settle(round.result());
                GameSave.save(file, 3, session, round, true, new ThinkTimer());
            }
            GameSave.Loaded loaded = GameSave.load(file);
            GameSession restored = loaded.session();
            check(loaded.round() == null, "下一局還沒開始");
            check(loaded.usedAutoPlay(), "用過電腦代打要記得");
            eq(session.config(), restored.config(), "設定");
            eq(session.dealer(), restored.dealer(), "莊家");
            eq(session.streak(), restored.streak(), "連莊");
            eq(session.roundWind(), restored.roundWind(), "圈風");
            eq(session.handWind(), restored.handWind(), "局風");
            eq(session.roundsPlayed(), restored.roundsPlayed(), "局數");
            eq(session.drawnGames(), restored.drawnGames(), "流局");
            for (int seat = 0; seat < 4; seat++) {
                eq(session.personality(seat), restored.personality(seat), "個性");
                eq(session.player(seat).chips(), restored.player(seat).chips(), "籌碼");
                eq(session.record(seat, "d"), restored.record(seat, "d"), "成績");
            }
            List<RoundRecord> before = session.history();
            List<RoundRecord> after = restored.history();
            eq(before.size(), after.size(), "歷程局數");
            for (int i = 0; i < before.size(); i++) {
                eq(before.get(i).result(), after.get(i).result(), "第 " + i + " 局結果");
                check(java.util.Arrays.equals(before.get(i).deltas(), after.get(i).deltas()), "第 " + i + " 局輸贏");
            }
            Round next = restored.newRound(message -> { });
            next.start();
            playOut(next, bots());
            restored.settle(next.result());
            eq(session.roundsPlayed() + 1, restored.roundsPlayed(), "接著打下一局");
        });

        test("報聽、報聽的機會與思考時間也接得回來", () -> {
            Path file = tempFile();
            Round round = Table.dealer(0).hand(0, "1w2w3w4w5w6w7w8w9w1w2w3w4w5w6w9w").draws("2z").start();
            round.discard(0, tile("2z"));
            check(round.canDeclareListening(0), "打完聽九萬");
            GameSave.save(file, 77, newSession(4), round, false, new ThinkTimer());
            check(GameSave.load(file).round().canDeclareListening(0), "還沒報，機會留著");

            round.declareListening(0);
            ThinkTimer thinking = new ThinkTimer();
            thinking.start(0);
            thinking.finish(2_000_000_000L);
            GameSave.save(file, 77, newSession(4), round, false, thinking);
            GameSave.Loaded loaded = GameSave.load(file);
            eq(77L, loaded.seed(), "種子");
            check(loaded.round().isListening(0), "報聽了");
            check(!loaded.round().isListening(1), "別家沒有");
            check(!loaded.round().canDeclareListening(0), "已經報了");
            eq(T.hand("3w6w9w"), loaded.round().listeningWaits(0), "等的牌");
            eq(2_000_000_000L, loaded.thinkNanos(), "思考時間");
            eq(1, loaded.thinkCount(), "思考次數");
        });

        test("以前的存檔：報聽不公開，只記自己等的牌，讀回來當作自己報聽了", () -> {
            Path file = tempFile();
            Round round = Table.dealer(0).hand(0, "1w2w3w4w5w6w7w8w9w1w2w3w4w5w6w9w").draws("2z").start();
            round.discard(0, tile("2z"));
            GameSave.save(file, 77, newSession(4), round, false, new ThinkTimer());
            String text = Files.readString(file, StandardCharsets.ISO_8859_1)
                    .replaceAll("round\\.listeners=.*\\R", "").replaceAll("round\\.listenChances=.*\\R", "");
            Files.writeString(file, text + "round.listening=9w\n", StandardCharsets.ISO_8859_1);
            Round old = GameSave.load(file).round();
            check(old.isListening(0), "自己報聽了");
            check(!old.canDeclareListening(0), "不用再報");
            eq(T.hand("3w6w9w"), old.listeningWaits(0), "等的牌");
        });

        test("等別家搶槓時存檔：加槓的那張牌不在手上也不在面子裡，讀回來沒人搶就補成槓", () -> {
            Path file = tempFile();
            Round round = Table.dealer(1).hand(0, "5w5w").draws("5w 1s 2s 3s 5w").backs("9w").start();
            round.discard(1, tile("5w"));
            round.resolveClaims(Map.of(0, round.claimOptions(0).stream()
                    .filter(claim -> claim.type() == Claim.Type.PONG).findFirst().orElseThrow()));
            round.discard(0, round.player(0).concealed().get(0));
            round.resolveClaims(Map.of());
            Table.discardDrawnAndPass(round);
            Table.discardDrawnAndPass(round);
            Table.discardDrawnAndPass(round);
            round.declareAddKong(0, tile("5w"));
            check(round.isRobbing(), "等搶槓");
            GameSave.save(file, 1, newSession(5), round, false, new ThinkTimer());
            Round restored = GameSave.load(file).round();
            check(restored.isRobbing(), "讀回來還在等搶槓");
            eq(tile("5w"), restored.claimTile(), "加槓的牌");
            restored.resolveClaims(Map.of());
            eq(Meld.Type.OPEN_KONG, restored.player(0).melds().get(0).type(), "補成明槓");
            eq(0, restored.current(), "補牌的人");
        });

        test("壞掉的存檔：丟 IOException，不會接回壞掉的牌局", () -> {
            Path file = tempFile();
            GameSession session = newSession(6);
            Round round = session.newRound(message -> { });
            round.start();
            GameSave.save(file, 1, session, round, false, new ThinkTimer());
            String good = Files.readString(file, StandardCharsets.ISO_8859_1);

            for (String bad : List.of("", "version=99\n", "not a save at all",
                    good.replace("version=1", "version=99"),
                    good.replaceAll("round\\.wall=..", "round.wall="),
                    good.replace("round.player.0.concealed=", "round.player.0.concealed=1w"),
                    good.replaceAll("round\\.phase=\\w+", "round.phase=NOPE"),
                    good.replaceAll("player\\.0\\.chips=\\d+", "player.0.chips=lots"),
                    good.replaceAll("config\\.base=\\d+", "config.base=" + (CHIPS + 1)))) {
                Files.writeString(file, bad, StandardCharsets.ISO_8859_1);
                fails(UncheckedIOException.class, () -> {
                    try {
                        GameSave.load(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }, "應該讀不出來：" + bad.length());
            }
        });

        test("牌的記法存了再讀回來都一樣", () -> {
            for (Tile each : Tile.fullSet()) {
                eq(each, tile(each.code()), "牌");
            }
        });

        T.section("長期統計");
        stats();

        T.section("錯誤記錄");
        errorLog();
    }

    private static void stats() {
        test("累計胡牌、自摸、放槍與台數，依同桌等級分開算", () -> {
            GameSession session = new GameSession(new GameConfig("測試", CHIPS, 100, 20,
                    List.of(BotLevel.NORMAL, BotLevel.NORMAL, BotLevel.PRO)), new Random(1));
            PlayerStats stats = new PlayerStats();
            ScoreResult three = new ScoreResult(List.of(new ezmj.rules.ScoreItem("門清一摸三", 3)));
            ScoreResult one = new ScoreResult(List.of(new ezmj.rules.ScoreItem("自摸", 1)));

            session.settle(new RoundResult(0, -1, tile("1w"), three));
            stats.recordRound(session);
            session.settle(new RoundResult(0, 2, tile("1w"), one));
            stats.recordRound(session);
            session.settle(new RoundResult(1, 0, tile("1w"), one));
            stats.recordRound(session);
            session.settle(new RoundResult(2, 3, tile("1w"), one));
            stats.recordRound(session);
            session.settle(RoundResult.drawnGame());
            stats.recordRound(session);

            PlayerStats.Totals total = stats.total();
            eq(5, total.rounds(), "局數");
            eq(2, total.wins(), "胡牌");
            eq(1, total.selfDraws(), "自摸");
            eq(1, total.dealIns(), "放槍");
            eq(1, total.drawnGames(), "流局");
            eq(3, total.bestTai(), "最高台數");
            eq(2.0, total.averageTai(), "平均台數");
            eq(0.4, total.winRate(), "胡牌率");
            eq(0.2, total.dealInRate(), "放槍率");
            eq((long) session.player(0).chips() - CHIPS, total.winnings(), "輸贏");

            eq(5, stats.against(BotLevel.NORMAL).rounds(), "同桌有一般");
            eq(5, stats.against(BotLevel.PRO).rounds(), "同桌有職業");
            eq(0, stats.against(BotLevel.AMATEUR).rounds(), "同桌沒有業餘");
        });

        test("整場名次：籌碼相同是同一名，存檔讀檔後還在", () -> {
            GameSession session = new GameSession(new GameConfig("測試", CHIPS, 100, 20, BotLevel.PRO), new Random(1));
            PlayerStats stats = new PlayerStats();
            ScoreResult one = new ScoreResult(List.of(new ezmj.rules.ScoreItem("自摸", 1)));
            session.settle(new RoundResult(0, 1, tile("1w"), one));
            stats.recordRound(session);
            stats.recordGame(session);
            session.settle(new RoundResult(1, 0, tile("1w"), one));
            session.settle(new RoundResult(1, 0, tile("1w"), one));
            stats.recordRound(session);
            stats.recordGame(session);
            int secondPlace = 1;
            for (int seat = 1; seat < 4; seat++) {
                secondPlace += session.player(seat).chips() > session.player(0).chips() ? 1 : 0;
            }
            eq(2, stats.total().games(), "場數");
            eq(1, stats.total().firstPlaces(), "第一名");
            eq((1 + secondPlace) / 2.0, stats.total().averagePlace(), "平均名次");

            PlayerStats tied = new PlayerStats();
            tied.recordGame(new GameSession(new GameConfig("測試", CHIPS, 100, 20, BotLevel.PRO), new Random(1)));
            eq(1, tied.total().firstPlaces(), "四家籌碼相同都是第一名");

            Path file = tempFile();
            stats.save(file);
            PlayerStats loaded = PlayerStats.load(file);
            eq(2, loaded.total().rounds(), "讀回局數");
            eq(2, loaded.total().games(), "讀回場數");
            eq(stats.total().winnings(), loaded.total().winnings(), "讀回輸贏");
            eq(2, loaded.against(BotLevel.PRO).rounds(), "讀回等級");
        });

        test("統計檔不存在或內容不對：從零開始，不會出錯", () -> {
            Path file = tempFile();
            eq(0, PlayerStats.load(file).total().rounds(), "沒有檔案");
            Files.writeString(file, "total.rounds=abc\ntotal.wins=2\n");
            PlayerStats loaded = PlayerStats.load(file);
            eq(0, loaded.total().rounds(), "讀不懂的數字當作 0");
            eq(2, loaded.total().wins(), "讀得懂的留著");
            eq(0.0, new PlayerStats().total().winRate(), "沒有局數時比率是 0");
        });
    }

    private static void errorLog() {
        test("記下局面與錯誤內容，一次執行有上限，不會一直長大", () -> {
            Path file = tempFile();
            ErrorLog log = new ErrorLog(file);
            log.write("AWT-EventQueue-0", "種子 42，打完 3 局", new IllegalStateException("壞掉了"));
            String text = Files.readString(file, StandardCharsets.UTF_8);
            check(text.contains("種子 42"), "有局面：" + text);
            check(text.contains("壞掉了"), "有錯誤內容：" + text);
            check(text.contains("AWT-EventQueue-0"), "有執行緒：" + text);
            for (int i = 0; i < 100; i++) {
                log.write("t", "c", new IllegalStateException("重複"));
            }
            List<String> marks = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("==== ")) {
                    marks.add(line);
                }
            }
            eq(20, marks.size(), "筆數上限");
        });

        test("檔案太大就換成 .old 重新開始", () -> {
            Path file = tempFile();
            Files.writeString(file, "x".repeat(600 * 1024));
            new ErrorLog(file).write("t", "c", new IllegalStateException("新的"));
            check(Files.size(file) < 100 * 1024, "新檔案從頭開始");
            check(Files.exists(file.resolveSibling("save.properties.old")), "舊的留著");
        });

        test("個性存檔裡的值不認得就當穩健", () -> {
            eq(Personality.BALANCED, Personality.fromKey("???"), "不認得");
            eq(Personality.GREEDY, Personality.fromKey("GREEDY"), "認得");
        });
    }
}
