package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import ezmj.engine.Achievement;
import ezmj.engine.AchievementBook;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundResult;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.rules.ScoreGuide;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;

/** 成就：各種條件的判定、只算真人、不重複達成，以及存檔。 */
final class AchievementTest {

    private static final String DATE = "2026-10-31";
    private static final int CHIPS = 1_000_000;

    private AchievementTest() {
    }

    private static GameSession session(BotLevel level) {
        return new GameSession(new GameConfig("測試", CHIPS, 100, 20, level), new Random(5));
    }

    /** 起莊隨機，找一個由真人或指定座位當莊的牌局。 */
    private static GameSession sessionWithDealer(int dealer) {
        for (int seed = 0; ; seed++) {
            GameSession session = new GameSession(new GameConfig("測試", CHIPS, 100, 20, BotLevel.NORMAL), new Random(seed));
            if (session.dealer() == dealer) {
                return session;
            }
        }
    }

    private static RoundResult tsumo(int winner, ScoreItem... items) {
        return new RoundResult(winner, -1, tile("1w"), new ScoreResult(List.of(items)));
    }

    private static RoundResult ron(int winner, int from, ScoreItem... items) {
        return new RoundResult(winner, from, tile("1w"), new ScoreResult(List.of(items)));
    }

    private static List<Achievement> play(AchievementBook book, GameSession session, RoundResult result) {
        session.settle(result);
        return book.recordRound(session, DATE);
    }

    private static void endFourWinds(GameSession session) {
        // 電腦之間互胡，不動到真人的成績；胡的人不是莊家就會下莊，一直到四圈打完
        while (session.roundWind() <= 4) {
            int winner = session.dealer() == 1 ? 2 : 1;
            int from = winner == 1 ? 2 : 1;
            session.settle(ron(winner, from));
        }
    }

    static void run() {
        T.section("成就");

        test("牌型成就對應的名稱都是實際的計台項目", () -> {
            for (Achievement achievement : Achievement.values()) {
                if (achievement.handItem() != null) {
                    check(ScoreGuide.find(achievement.handItem()) != null, "台數表沒有 " + achievement.handItem());
                }
                check(!achievement.title().isEmpty() && !achievement.description().isEmpty(), achievement + " 要有名稱與條件");
            }
        });

        test("真人胡出罕見牌型會達成，而且只會達成一次", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = session(BotLevel.NORMAL);
            List<Achievement> first = play(book, session, tsumo(0, new ScoreItem("大四喜", 16)));
            check(first.contains(Achievement.BIG_FOUR_WINDS), "大四喜 " + first);
            check(first.contains(Achievement.WINS_1), "第一次胡牌 " + first);
            eq(DATE, book.unlockedDate(Achievement.BIG_FOUR_WINDS), "日期");
            List<Achievement> again = play(book, session, tsumo(0, new ScoreItem("大四喜", 16)));
            check(!again.contains(Achievement.BIG_FOUR_WINDS), "不會重複達成 " + again);
        });

        test("電腦胡牌不算真人的成就", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = session(BotLevel.NORMAL);
            List<Achievement> earned = play(book, session, tsumo(1, new ScoreItem("天胡", 24)));
            check(!book.isUnlocked(Achievement.HEAVENLY), "電腦的天胡 " + earned);
            check(!book.isUnlocked(Achievement.TAI_24), "電腦的大牌");
            eq(0, book.count(Achievement.Counter.WINS), "真人胡牌數");
            eq(1, book.count(Achievement.Counter.ROUNDS), "局數還是算");
        });

        test("帶細節的項目也對得上：花槓", () -> {
            AchievementBook book = new AchievementBook();
            play(book, session(BotLevel.NORMAL), tsumo(0, new ScoreItem("花槓 梅蘭竹菊", 2)));
            check(book.isUnlocked(Achievement.FLOWER_KONG), "花槓");
        });

        test("屁胡：0 台胡牌才算", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = session(BotLevel.NORMAL);
            play(book, session, ron(0, 1, new ScoreItem("門清", 1)));
            check(!book.isUnlocked(Achievement.NO_TAI), "有台數");
            play(book, session, ron(0, 1));
            check(book.isUnlocked(Achievement.NO_TAI), "0 台");
        });

        test("大牌：依台數門檻達成", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = session(BotLevel.NORMAL);
            play(book, session, ron(0, 1, new ScoreItem("清一色", 7)));
            check(!book.isUnlocked(Achievement.TAI_8), "7 台不夠");
            play(book, session, ron(0, 1, new ScoreItem("清一色", 8), new ScoreItem("門清", 9)));
            check(book.isUnlocked(Achievement.TAI_16), "17 台");
            check(book.isUnlocked(Achievement.TAI_8), "同時達成較低門檻");
            check(!book.isUnlocked(Achievement.TAI_24), "還沒到 24 台");
        });

        test("連莊 10 次：自己當莊才算，流局也算", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = sessionWithDealer(0);
            for (int i = 1; i <= 9; i++) {
                play(book, session, i % 2 == 0 ? RoundResult.drawnGame() : tsumo(0));
                eq(i >= 3, book.isUnlocked(Achievement.DEALER_STREAK_3), "第 " + i + " 次連莊 3");
                eq(i >= 5, book.isUnlocked(Achievement.DEALER_STREAK_5), "第 " + i + " 次連莊 5");
                check(!book.isUnlocked(Achievement.DEALER_STREAK_10), "第 " + i + " 次還沒 10");
            }
            play(book, session, tsumo(0));
            eq(10, session.streak(), "連 10");
            check(book.isUnlocked(Achievement.DEALER_STREAK_10), "連莊 10 次");
        });

        test("別家連莊不算", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = sessionWithDealer(2);
            for (int i = 0; i < 10; i++) {
                play(book, session, tsumo(2));
            }
            eq(10, session.streak(), "別家連 10");
            check(!book.isUnlocked(Achievement.DEALER_STREAK_3), "不是自己");
        });

        test("連續流局 10 次，中間有人胡就重算", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = session(BotLevel.NORMAL);
            for (int i = 0; i < 9; i++) {
                play(book, session, RoundResult.drawnGame());
            }
            check(book.isUnlocked(Achievement.DRAWN_RUN_5), "流局 5");
            check(!book.isUnlocked(Achievement.DRAWN_RUN_10), "才 9 次");
            play(book, session, tsumo(1));
            for (int i = 0; i < 9; i++) {
                play(book, session, RoundResult.drawnGame());
            }
            check(!book.isUnlocked(Achievement.DRAWN_RUN_10), "被打斷，又只有 9 次");
            play(book, session, RoundResult.drawnGame());
            check(book.isUnlocked(Achievement.DRAWN_RUN_10), "連續 10 次");
        });

        test("連續胡牌與連續放槍", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = session(BotLevel.NORMAL);
            play(book, session, ron(0, 1));
            play(book, session, ron(0, 1));
            play(book, session, ron(1, 2));
            play(book, session, ron(0, 1));
            check(!book.isUnlocked(Achievement.WIN_RUN_3), "被打斷");
            play(book, session, ron(0, 1));
            play(book, session, ron(0, 1));
            check(book.isUnlocked(Achievement.WIN_RUN_3), "連胡 3");

            play(book, session, ron(1, 0));
            play(book, session, ron(2, 0));
            check(!book.isUnlocked(Achievement.DEAL_IN_RUN_3), "才 2 次");
            play(book, session, ron(3, 0));
            check(book.isUnlocked(Achievement.DEAL_IN_RUN_3), "連放 3");
        });

        test("累計次數跨牌局，達標才達成", () -> {
            AchievementBook book = new AchievementBook();
            for (int i = 0; i < 9; i++) {
                play(book, session(BotLevel.NORMAL), tsumo(0));
            }
            eq(9, book.count(Achievement.Counter.WINS), "胡牌數");
            eq(9, book.count(Achievement.Counter.SELF_DRAWS), "自摸數");
            check(!book.isUnlocked(Achievement.WINS_10), "還差一次");
            play(book, session(BotLevel.NORMAL), ron(0, 1));
            eq(10, book.count(Achievement.Counter.WINS), "胡牌數含放槍");
            eq(9, book.count(Achievement.Counter.SELF_DRAWS), "自摸數不變");
            check(book.isUnlocked(Achievement.WINS_10), "胡牌 10 次");
            play(book, session(BotLevel.NORMAL), ron(1, 0));
            eq(1, book.count(Achievement.Counter.DEAL_INS), "放槍數");
        });

        test("整場成就只在打完才判定", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = session(BotLevel.PRO);
            play(book, session, tsumo(0, new ScoreItem("清一色", 40)));
            check(!book.isUnlocked(Achievement.GAME_TOP), "還沒打完");
            endFourWinds(session);
            int top = Math.max(session.player(1).chips(), Math.max(session.player(2).chips(), session.player(3).chips()));
            check(session.player(0).chips() >= top, "這個劇本真人最多");
            List<Achievement> earned = book.recordGame(session, 5, 30, DATE);
            check(earned.contains(Achievement.GAME_TOP), "籌碼最多 " + earned);
            check(earned.contains(Achievement.BEAT_PROS), "職業等級 " + earned);
            check(earned.contains(Achievement.NO_DEAL_IN), "沒有放槍 " + earned);
            check(earned.contains(Achievement.GAMES_1), "第一場 " + earned);
            check(!earned.contains(Achievement.NO_WINS), "有胡牌 " + earned);
            check(!earned.contains(Achievement.FAST_THINKER), "5 秒不算快 " + earned);
        });

        test("三家不全是職業就沒有勝過職業", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = new GameSession(new GameConfig("測試", CHIPS, 100, 20,
                    List.of(BotLevel.PRO, BotLevel.PRO, BotLevel.BEGINNER)), new Random(5));
            play(book, session, tsumo(0, new ScoreItem("清一色", 40)));
            endFourWinds(session);
            List<Achievement> earned = book.recordGame(session, 5, 30, DATE);
            check(earned.contains(Achievement.GAME_TOP), "籌碼最多 " + earned);
            check(!earned.contains(Achievement.BEAT_PROS), "有一家不是職業 " + earned);
        });

        test("不是職業等級就沒有勝過職業；有放槍就不算滴水不漏", () -> {
            AchievementBook book = new AchievementBook();
            GameSession session = session(BotLevel.NORMAL);
            play(book, session, tsumo(0, new ScoreItem("清一色", 8)));
            play(book, session, ron(1, 0));
            endFourWinds(session);
            List<Achievement> earned = book.recordGame(session, 5, 30, DATE);
            check(!earned.contains(Achievement.BEAT_PROS), "等級 " + earned);
            check(!earned.contains(Achievement.NO_DEAL_IN), "放過槍 " + earned);
        });

        test("輸光：自己輸光與讓別家輸光，提早結束不算四圈打完", () -> {
            GameConfig small = new GameConfig("測試", 100, 100, 20, BotLevel.NORMAL);
            GameSession lost = new GameSession(small, new Random(5));
            lost.settle(ron(1, 0));
            AchievementBook book = new AchievementBook();
            List<Achievement> earned = book.recordGame(lost, 5, 30, DATE);
            check(earned.contains(Achievement.WENT_BROKE), "自己輸光 " + earned);
            check(!earned.contains(Achievement.BROKE_SOMEONE), "不算讓別家輸光 " + earned);
            check(!earned.contains(Achievement.NO_WINS), "沒打滿四圈 " + earned);
            check(!earned.contains(Achievement.NO_DEAL_IN), "沒打滿四圈 " + earned);

            GameSession won = new GameSession(small, new Random(5));
            won.settle(ron(0, 1));
            AchievementBook other = new AchievementBook();
            List<Achievement> second = other.recordGame(won, 5, 30, DATE);
            check(second.contains(Achievement.BROKE_SOMEONE), "讓別家輸光 " + second);
            check(!second.contains(Achievement.WENT_BROKE), "自己沒輸光 " + second);

            GameSession others = new GameSession(small, new Random(5));
            others.settle(ron(1, 2));
            List<Achievement> third = new AchievementBook().recordGame(others, 5, 30, DATE);
            check(!third.contains(Achievement.BROKE_SOMEONE), "電腦之間放槍不算 " + third);

            GameSession selfDraw = new GameSession(small, new Random(5));
            selfDraw.settle(tsumo(0));
            List<Achievement> fourth = new AchievementBook().recordGame(selfDraw, 5, 30, DATE);
            check(fourth.contains(Achievement.BROKE_SOMEONE), "自摸讓別家輸光也算 " + fourth);

            GameSession big = new GameSession(new GameConfig("測試", CHIPS, 100, 20, BotLevel.NORMAL), new Random(5));
            big.settle(ron(0, 1));
            List<Achievement> fifth = new AchievementBook().recordGame(big, 5, 30, DATE);
            check(!fifth.contains(Achievement.BROKE_SOMEONE), "胡牌但沒有人輸光不算 " + fifth);
        });

        test("思考速度：要有足夠次數才算", () -> {
            AchievementBook few = new AchievementBook();
            GameSession session = session(BotLevel.NORMAL);
            check(!few.recordGame(session, 1, 19, DATE).contains(Achievement.FAST_THINKER), "次數不夠");
            AchievementBook fast = new AchievementBook();
            check(fast.recordGame(session, 2, 20, DATE).contains(Achievement.FAST_THINKER), "2 秒內");
            AchievementBook slow = new AchievementBook();
            check(slow.recordGame(session, 30, 20, DATE).contains(Achievement.SLOW_THINKER), "30 秒以上");
        });

        test("實際牌局胡出清一色會達成", () -> {
            String hand = "1w2w3w 4w5w6w 7w8w9w 1w2w3w 4w5w 9w9w";
            Round round = Table.dealer(3).hand(3, "6w").hand(0, hand).draws("1p").start();
            round.discard(3, tile("6w"));
            Claim win = round.claimOptions(0).stream().filter(c -> c.type() == Claim.Type.WIN).findFirst().orElseThrow();
            round.resolveClaims(Map.of(0, win));
            check(round.result().score().has("清一色"), "算到清一色 " + round.result().score());
            AchievementBook book = new AchievementBook();
            play(book, session(BotLevel.NORMAL), round.result());
            check(book.isUnlocked(Achievement.PURE_SUIT), "達成");
            check(book.isUnlocked(Achievement.TAI_8), "8 台以上");
        });

        test("存檔後可以讀回來，資料夾不存在時會建立", () -> {
            Path dir = Files.createTempDirectory("ezmj-achievement");
            Path nested = dir.resolve("ezmj");
            Path file = nested.resolve("achievements.properties");
            try {
                AchievementBook book = new AchievementBook();
                play(book, session(BotLevel.NORMAL), tsumo(0, new ScoreItem("大三元", 8)));
                book.save(file);
                AchievementBook loaded = AchievementBook.load(file);
                check(loaded.isUnlocked(Achievement.BIG_THREE_DRAGONS), "成就");
                eq(DATE, loaded.unlockedDate(Achievement.BIG_THREE_DRAGONS), "日期");
                eq(1, loaded.count(Achievement.Counter.WINS), "次數");
                eq(book.unlockedCount(), loaded.unlockedCount(), "成就數");
                eq(Set.of(file.getFileName().toString()), Set.of(nested.toFile().list()), "不留暫存檔");
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(nested);
                Files.delete(dir);
            }
        });

        test("重開遊戲：達成的成就與次數都在，次數接著累計，不會重複達成", () -> {
            Path dir = Files.createTempDirectory("ezmj-achievement");
            Path file = dir.resolve("achievements.properties");
            try {
                AchievementBook first = AchievementBook.load(file);
                check(!play(first, session(BotLevel.NORMAL), tsumo(0, new ScoreItem("清一色", 8))).isEmpty(), "第一次達成");
                first.save(file);

                AchievementBook second = AchievementBook.load(file);
                check(second.isUnlocked(Achievement.PURE_SUIT), "重開後還在");
                eq(DATE, second.unlockedDate(Achievement.PURE_SUIT), "日期還在");
                eq(1, second.count(Achievement.Counter.WINS), "次數還在");
                check(!play(second, session(BotLevel.NORMAL), tsumo(0, new ScoreItem("清一色", 8)))
                        .contains(Achievement.PURE_SUIT), "不重複達成");
                eq(2, second.count(Achievement.Counter.WINS), "接著累計");
                second.save(file);

                eq(2, AchievementBook.load(file).count(Achievement.Counter.WINS), "再重開也在");
            } finally {
                Files.deleteIfExists(file);
                Files.delete(dir);
            }
        });

        test("檔案不存在或內容不正確：能讀的留著，其他略過", () -> {
            Path dir = Files.createTempDirectory("ezmj-achievement");
            Path file = dir.resolve("achievements.properties");
            try {
                eq(0, AchievementBook.load(file).unlockedCount(), "沒有檔案");
                Files.writeString(file, "unlocked.PURE_SUIT=2026-01-02\nunlocked.NOT_REAL=2026-01-02\n"
                        + "count.WINS=abc\ncount.GAMES=-3\ncount.ROUNDS=7\ncount.NOT_REAL=9\n");
                AchievementBook book = AchievementBook.load(file);
                eq(1, book.unlockedCount(), "不認得的成就略過");
                eq("2026-01-02", book.unlockedDate(Achievement.PURE_SUIT), "認得的留著");
                eq(0, book.count(Achievement.Counter.WINS), "數字不對當 0");
                eq(0, book.count(Achievement.Counter.GAMES), "負數當 0");
                eq(7, book.count(Achievement.Counter.ROUNDS), "正常的次數");
            } finally {
                Files.deleteIfExists(file);
                Files.delete(dir);
            }
        });
    }
}
