package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import ezmj.career.CareerProfile;
import ezmj.career.CareerSlot;
import ezmj.career.Challenge;
import ezmj.career.Records;
import ezmj.engine.Achievement;
import ezmj.engine.AchievementBook;
import ezmj.engine.GameSave;
import ezmj.engine.GameSession;
import ezmj.engine.PlayerStats;
import ezmj.engine.RoundResult;
import ezmj.engine.ThinkTimer;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.GameRecord;
import ezmj.model.HonorRoll;
import ezmj.model.PropertyStore;
import ezmj.model.SealedStore;
import ezmj.rules.ScoreResult;

/** 存檔封存：改不了、看不懂、舊的明碼檔案會轉過來，以及新增項目之後舊檔還讀得出來。 */
final class SealTest {

    private static final PropertyStore SEALED = SealedStore.INSTANCE;

    private SealTest() {
    }

    private static Properties values(String... pairs) {
        Properties values = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            values.setProperty(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    /** 讀了要丟 IOException。 */
    private static void refused(Path file, String message) {
        try {
            SEALED.read(file);
        } catch (IOException e) {
            return;
        }
        throw new AssertionError(message + "：應該讀不出來");
    }

    private static GameSession session() {
        return new GameSession(new GameConfig("阿明", 20_000, 100, 20, BotLevel.NORMAL), new Random(2));
    }

    static void run() {
        T.section("存檔封存");

        test("封存的內容讀得回來，檔案裡看不到原本的字", () -> {
            Path file = Files.createTempDirectory("ezmj-seal").resolve("profile.properties");
            SEALED.write(values("name", "阿明<&>", "bankroll", "123456789"), file);
            Properties back = SEALED.read(file);
            eq("阿明<&>", back.getProperty("name"), "名字");
            eq("123456789", back.getProperty("bankroll"), "籌碼");
            String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            check(!raw.contains("bankroll") && !raw.contains("123456789"), "檔案裡不該有明碼");
            check(SealedStore.isSealed(file), "看得出是封存的");
            SEALED.write(values("name", "阿明<&>", "bankroll", "123456789"), file);
            check(!raw.equals(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)), "每次加密的結果都不一樣");
        });

        test("改過、截短、換檔名、明碼或看不懂的版本都讀不出來", () -> {
            Path dir = Files.createTempDirectory("ezmj-seal");
            Path file = dir.resolve("profile.properties");
            SEALED.write(values("bankroll", "1000"), file);
            byte[] good = Files.readAllBytes(file);
            for (int index : new int[] {20, good.length / 2, good.length - 1}) {
                byte[] bad = good.clone();
                bad[index] ^= 1;
                Files.write(file, bad);
                refused(file, "改了第 " + index + " 個位元組");
            }
            Files.write(file, java.util.Arrays.copyOf(good, good.length - 5));
            refused(file, "截短");
            Path renamed = dir.resolve("stats.properties");
            Files.write(renamed, good);
            refused(renamed, "換了檔名");
            byte[] future = good.clone();
            future[8] = 9;
            Files.write(file, future);
            refused(file, "看不懂的版本");
            Files.writeString(file, "bankroll=99999999\n");
            refused(file, "自己寫的明碼");
            check(!SealedStore.isSealed(file), "明碼不是封存的");
            Files.write(file, good);
            eq("1000", SEALED.read(file).getProperty("bankroll"), "原本的還讀得出來");
        });

        test("紀錄、牌局存檔與修業檔案都封存，被改過就當作讀不懂", () -> {
            Path home = Files.createTempDirectory("ezmj-home");
            Records.sealLegacy(home);
            CareerSlot slot = CareerSlot.all(home).get(0);
            slot.create(new CareerProfile("阿明", 20_000, "2026-11-06"));
            Path profile = home.resolve("career").resolve("1").resolve("profile.properties");
            check(SealedStore.isSealed(profile), "修業檔案封存了");

            Records records = slot.loadRecords();
            GameSession session = session();
            session.settle(new RoundResult(0, 1, tile("1w"), new ScoreResult(List.of())));
            records.achievements().recordRound(session, "2026-11-06");
            records.saveAchievements();
            records.saveGame(5, session, null, false, new ThinkTimer());
            check(SealedStore.isSealed(records.saveFile()), "牌局存檔封存了");
            eq(1, records.loadGame().session().roundsPlayed(), "接得回來");
            check(slot.loadRecords().achievements().isUnlocked(Achievement.WINS_1), "成就讀得回來");

            Path achievements = home.resolve("career").resolve("1").resolve("achievements.properties");
            byte[] bytes = Files.readAllBytes(achievements);
            bytes[bytes.length - 2] ^= 1;
            Files.write(achievements, bytes);
            check(!slot.loadRecords().achievements().isUnlocked(Achievement.WINS_1), "被改過的成就檔當作空的");

            byte[] money = Files.readAllBytes(profile);
            money[money.length / 2] ^= 1;
            Files.write(profile, money);
            boolean failed = false;
            try {
                slot.loadProfile();
            } catch (IOException e) {
                failed = true;
            }
            check(failed, "被改過的修業檔案讀不出來");

            Files.writeString(records.saveFile(), "version=1\n");
            failed = false;
            try {
                records.loadGame();
            } catch (IOException e) {
                failed = true;
            }
            check(failed, "明碼的牌局存檔接不回來");
        });

        test("以前的明碼存檔第一次開新版時封存起來，內容不變；之後的明碼不收", () -> {
            Path home = Files.createTempDirectory("ezmj-home");
            AchievementBook book = new AchievementBook();
            GameSession session = session();
            session.settle(new RoundResult(0, -1, tile("1w"), new ScoreResult(List.of())));
            book.recordRound(session, "2026-10-01");
            book.save(home.resolve("achievements.properties"));
            PlayerStats stats = new PlayerStats();
            stats.recordRound(session);
            stats.save(home.resolve("stats.properties"));
            HonorRoll roll = new HonorRoll();
            roll.add(new GameRecord("阿明", "2026-10-01", 3, 1, 500, 0, BotLevel.PRO, 100, 20));
            roll.save(home.resolve("honor.properties"));
            GameSave.save(home.resolve("save.properties"), 7, session, null, false, new ThinkTimer());
            Path legacyProfile = home.resolve("career").resolve("2").resolve("profile.properties");
            new CareerProfile("小美", 50_000, "2026-11-01").save(legacyProfile);

            Records records = Records.openHome(home);
            check(records.achievements().isUnlocked(Achievement.WINS_1), "成就還在");
            eq(1, records.stats().total().selfDraws(), "統計還在");
            eq(3, records.honorRoll().top(HonorRoll.Category.WINS).get(0).wins(), "榮譽榜還在");
            eq(1, records.loadGame().session().roundsPlayed(), "練習牌局接得回來");
            eq("小美", CareerSlot.all(home).get(1).loadProfile().name(), "修業檔案也轉過來");
            for (String name : List.of("achievements.properties", "stats.properties", "honor.properties", "save.properties")) {
                check(SealedStore.isSealed(home.resolve(name)), name + " 封存了");
            }
            check(Files.exists(home.resolve("sealed.properties")), "留下記號");

            new AchievementBook().save(home.resolve("achievements.properties"));
            check(!Records.openHome(home).achievements().isUnlocked(Achievement.WINS_1), "沒有記號以外的明碼都不轉");
            Files.writeString(home.resolve("achievements.properties"), "unlocked.HEAVENLY=2026-11-06\n");
            check(!Records.openHome(home).achievements().isUnlocked(Achievement.HEAVENLY), "自己寫的明碼成就不算");
        });

        test("擴充：舊檔沒有新的項目照樣讀得出來，看不懂的新項目略過", () -> {
            Path dir = Files.createTempDirectory("ezmj-ext");
            Path achievements = dir.resolve("achievements.properties");
            SEALED.write(values("unlocked.PURE_SUIT", "2026-01-02", "unlocked.FROM_THE_FUTURE", "2030-01-01",
                    "count.WINS", "7", "count.FROM_THE_FUTURE", "3", "future.setting", "x"), achievements);
            AchievementBook book = AchievementBook.load(achievements, SEALED);
            check(book.isUnlocked(Achievement.PURE_SUIT), "認得的成就");
            eq(1, book.unlockedCount(), "認不得的略過");
            eq(7, book.count(Achievement.Counter.WINS), "次數");

            Path stats = dir.resolve("stats.properties");
            SEALED.write(values("total.rounds", "12", "total.newCounter", "5"), stats);
            PlayerStats loaded = PlayerStats.load(stats, SEALED);
            eq(12, loaded.total().rounds(), "舊檔有的");
            eq(0, loaded.total().wins(), "舊檔沒有的從 0 開始");

            Path profile = dir.resolve("profile.properties");
            SEALED.write(values("name", "阿明", "startingChips", "30000", "cleared.CONCEALED", "2026-11-01",
                    "cleared.FUTURE_STAGE", "2030-01-01", "title", "雀聖"), profile);
            CareerProfile career = CareerProfile.load(profile, SEALED);
            eq(30_000L, career.bankroll(), "沒寫籌碼就是起始籌碼");
            eq(CareerProfile.Game.NONE, career.game(), "沒寫牌局就是沒有");
            eq(1, career.clearedCount(), "認不得的關卡略過");
            check(career.isUnlocked(Challenge.CONCEALED), "過過的關一直開放，就算前面插了新關卡");
            check(career.isUnlocked(Challenge.FIVE_TAI), "下一關也開放");
            check(!career.isUnlocked(Challenge.FIRST_SELF_DRAW), "沒過的前一關照舊鎖著");

            career.save(profile, SEALED);
            eq("1", SEALED.read(profile).getProperty("format"), "存檔記著格式版本");
        });

        test("擴充：牌局存檔比程式新就不接回，舊的版本接得回來", () -> {
            Path file = Files.createTempDirectory("ezmj-ext").resolve("save.properties");
            GameSave.save(file, SEALED, 3, session(), null, false, new ThinkTimer());
            Properties values = SEALED.read(file);
            values.setProperty("future.item", "x");
            SEALED.write(values, file);
            eq(0, GameSave.load(file, SEALED).session().roundsPlayed(), "看不懂的新項目略過");
            values.setProperty("version", "2");
            SEALED.write(values, file);
            boolean refused = false;
            try {
                GameSave.load(file, SEALED);
            } catch (IOException e) {
                refused = true;
            }
            check(refused, "新版程式寫的存檔不接回");
        });
    }
}
