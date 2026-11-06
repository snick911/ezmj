package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ezmj.career.CareerProfile;
import ezmj.career.CareerSlot;
import ezmj.career.Challenge;
import ezmj.career.Records;
import ezmj.engine.Achievement;
import ezmj.engine.GameSession;
import ezmj.engine.RoundResult;
import ezmj.model.GameConfig;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;

/** 修業模式：檔案的籌碼與存讀、救濟金、關卡開放與獎金、每一關的任務判定，以及每個檔案的紀錄分開。 */
final class CareerTest {

    private static final String DATE = "2026-11-06";

    private CareerTest() {
    }

    private static RoundResult tsumo(int winner, ScoreItem... items) {
        return new RoundResult(winner, -1, tile("1w"), new ScoreResult(List.of(items)));
    }

    private static RoundResult ron(int winner, int from, ScoreItem... items) {
        return new RoundResult(winner, from, tile("1w"), new ScoreResult(List.of(items)));
    }

    private static GameSession session(Challenge challenge) {
        return new GameSession(challenge.config("測試"), new Random(3));
    }

    /** 起莊隨機，找一個由指定座位當莊的牌局。 */
    private static GameSession sessionWithDealer(Challenge challenge, int dealer) {
        for (int seed = 0; ; seed++) {
            GameSession session = new GameSession(challenge.config("測試"), new Random(seed));
            if (session.dealer() == dealer) {
                return session;
            }
        }
    }

    /** 電腦之間互胡，不動到真人的成績；胡的人不是莊家就會下莊，一直打到圈數用完。 */
    private static void endCircles(GameSession session, int circles) {
        while (session.roundWind() <= circles && !session.isOver()) {
            int winner = session.dealer() == 1 ? 2 : 1;
            int from = winner == 1 ? 2 : 1;
            session.settle(ron(winner, from));
        }
    }

    private static Challenge.Status settle(Challenge challenge, GameSession session, RoundResult result) {
        session.settle(result);
        return challenge.check(session).status();
    }

    private static CareerProfile profile(int chips) {
        return new CareerProfile("阿明", chips, DATE);
    }

    private static void clearUpTo(CareerProfile profile, Challenge last) {
        for (Challenge challenge : Challenge.values()) {
            if (challenge.ordinal() > last.ordinal()) {
                return;
            }
            profile.clearChallenge(challenge, DATE);
        }
    }

    static void run() {
        T.section("修業模式");

        test("關卡的設定都合理，名稱不重複，也不和成就同名", () -> {
            Set<String> titles = new HashSet<>();
            for (Achievement achievement : Achievement.values()) {
                titles.add(achievement.title());
            }
            Set<String> seen = new HashSet<>();
            for (Challenge challenge : Challenge.values()) {
                GameConfig config = challenge.config("測試");
                check(GameConfig.paysFit(config.startingChips(), config.basePay(), config.taiPay()), challenge + " 底台");
                check(challenge.circles() >= 1 && challenge.circles() <= 4, challenge + " 圈數");
                check(challenge.reward() > 0, challenge + " 獎金");
                check(!challenge.goalText().isEmpty() && !challenge.chapter().isEmpty(), challenge + " 說明");
                check(seen.add(challenge.title()), "關卡名稱重複：" + challenge.title());
                check(!titles.contains(challenge.title()), "和成就同名：" + challenge.title());
                eq(challenge, Challenge.fromKey(challenge.name()), "讀檔用的名稱");
                eq(Challenge.Status.PLAYING, challenge.check(session(challenge)).status(), challenge + " 還沒開打");
            }
        });

        test("建立檔案：籌碼就是起始籌碼，名字有長度限制", () -> {
            CareerProfile profile = profile(20_000);
            eq(20_000L, profile.bankroll(), "帳上籌碼");
            eq(CareerProfile.Game.NONE, profile.game(), "沒有牌局");
            fails(IllegalArgumentException.class, () -> new CareerProfile("", 20_000, DATE), "空的名字");
            fails(IllegalArgumentException.class, () -> new CareerProfile("一二三四五六七八九", 20_000, DATE), "九個字");
            fails(IllegalArgumentException.class, () -> new CareerProfile("阿明", 0, DATE), "沒有籌碼");
        });

        test("一般對戰：每局結算照桌上的籌碼改帳，重複結算同一局不會多算", () -> {
            CareerProfile profile = profile(20_000);
            eq(20_000, profile.startGeneral(), "全部帶上桌");
            profile.settleGeneral(21_500);
            eq(21_500L, profile.bankroll(), "贏了");
            profile.settleGeneral(21_500);
            eq(21_500L, profile.bankroll(), "同一局再算一次");
            profile.settleGeneral(-300);
            eq(-300L, profile.bankroll(), "輸光可以是負的");
            eq(21_500L, profile.peak(), "最高");
            fails(IllegalStateException.class, profile::startGeneral, "打到一半不能再開");
            profile.endGame();
            eq(CareerProfile.Game.NONE, profile.game(), "結束");
            fails(IllegalStateException.class, () -> profile.settleGeneral(100), "沒在打就不能結算");
        });

        test("一般對戰：帶上桌最多一億，其餘留在帳上", () -> {
            CareerProfile rich = profile(10_000_000);
            for (int i = 0; i < 12; i++) {
                rich.startGeneral();
                rich.settleGeneral(rich.tableChips() * 2);
                rich.endGame();
            }
            check(rich.bankroll() > CareerProfile.MAX_TABLE_CHIPS, "滾到一億以上 " + rich.bankroll());
            long before = rich.bankroll();
            eq(CareerProfile.MAX_TABLE_CHIPS, rich.startGeneral(), "上桌一億");
            rich.settleGeneral(CareerProfile.MAX_TABLE_CHIPS - 5_000);
            eq(before - 5_000, rich.bankroll(), "只扣桌上輸的");
        });

        test("救濟金：籌碼不到門檻、沒有打到一半的牌局才能領，記一次破產", () -> {
            CareerProfile profile = profile(10_000);
            check(!profile.canTakeRelief(), "還有錢");
            profile.startGeneral();
            profile.settleGeneral(CareerProfile.RELIEF_BELOW - 1);
            check(!profile.canTakeRelief(), "牌局打到一半");
            profile.endGame();
            check(profile.canTakeRelief(), "可以領");
            profile.takeRelief();
            eq((long) CareerProfile.RELIEF_TO, profile.bankroll(), "補到");
            eq(1, profile.bankruptcies(), "破產次數");
            fails(IllegalStateException.class, profile::takeRelief, "補完就不能再領");
        });

        test("關卡依序開放，第一次過關才有獎金", () -> {
            CareerProfile profile = profile(20_000);
            check(profile.isUnlocked(Challenge.FIRST_WIN), "第一關一開始就開放");
            check(!profile.isUnlocked(Challenge.FIRST_SELF_DRAW), "第二關還沒開放");
            fails(IllegalStateException.class, () -> profile.startChallenge(Challenge.FIRST_SELF_DRAW), "不能跳關");
            profile.startChallenge(Challenge.FIRST_WIN);
            eq(Challenge.FIRST_WIN.reward(), profile.clearChallenge(Challenge.FIRST_WIN, DATE), "獎金");
            eq(CareerProfile.Game.NONE, profile.game(), "過關就結束這場");
            eq(20_000L + Challenge.FIRST_WIN.reward(), profile.bankroll(), "獎金入帳");
            check(profile.isUnlocked(Challenge.FIRST_SELF_DRAW), "開放第二關");
            profile.startChallenge(Challenge.FIRST_WIN);
            eq(0, profile.clearChallenge(Challenge.FIRST_WIN, DATE), "再過一次沒有獎金");
            eq(1, profile.clearedCount(), "過關數");
        });

        test("檔案存讀：籌碼、破產、過關與打到一半的牌局都讀得回來", () -> {
            Path dir = Files.createTempDirectory("ezmj-career");
            Path file = dir.resolve("profile.properties");
            CareerProfile profile = new CareerProfile("小美<b>", 50_000, DATE);
            clearUpTo(profile, Challenge.CLEAN_CIRCLE);
            profile.startGeneral();
            profile.settleGeneral(48_000);
            profile.save(file);
            CareerProfile loaded = CareerProfile.load(file);
            eq("小美<b>", loaded.name(), "名字");
            eq(50_000, loaded.startingChips(), "起始");
            eq(profile.bankroll(), loaded.bankroll(), "籌碼");
            eq(profile.peak(), loaded.peak(), "最高");
            eq(3, loaded.clearedCount(), "過關");
            eq(DATE, loaded.clearedDate(Challenge.FIRST_WIN), "過關日期");
            eq(CareerProfile.Game.GENERAL, loaded.game(), "一般對戰打到一半");
            loaded.settleGeneral(50_000);
            eq(profile.bankroll() + 2_000, loaded.bankroll(), "接著打，帳還是對的");

            loaded.endGame();
            loaded.startChallenge(Challenge.CONCEALED);
            loaded.save(file);
            CareerProfile challenge = CareerProfile.load(file);
            eq(CareerProfile.Game.CHALLENGE, challenge.game(), "挑戰打到一半");
            eq(Challenge.CONCEALED, challenge.challenge(), "哪一關");

            Files.writeString(file, "name=amin\nstartingChips=10000\nbankroll=x\ngame=NOPE\ncleared.NOPE=2026\n");
            CareerProfile messy = CareerProfile.load(file);
            eq(10_000L, messy.bankroll(), "讀不懂的籌碼用起始籌碼");
            eq(CareerProfile.Game.NONE, messy.game(), "讀不懂的牌局當作沒有");
            eq(0, messy.clearedCount(), "認不得的關卡略過");
            Files.writeString(file, "startingChips=10000\n");
            boolean refused = false;
            try {
                CareerProfile.load(file);
            } catch (IOException e) {
                refused = true;
            }
            check(refused, "沒有名字的檔案讀不出來");
        });

        test("初試啼聲：胡一次就過關；東風圈打完還沒胡就失敗", () -> {
            Challenge stage = Challenge.FIRST_WIN;
            eq(Challenge.Status.PLAYING, settle(stage, session(stage), ron(1, 2)), "別人胡");
            eq(Challenge.Status.CLEARED, settle(stage, session(stage), ron(0, 1)), "自己胡");
            GameSession session = session(stage);
            endCircles(session, stage.circles());
            eq(2, session.roundWind(), "東風圈打完");
            eq(Challenge.Status.FAILED, stage.check(session).status(), "沒胡");
        });

        test("自摸練習：胡別家的不算，自摸才算", () -> {
            Challenge stage = Challenge.FIRST_SELF_DRAW;
            GameSession session = session(stage);
            eq(Challenge.Status.PLAYING, settle(stage, session, ron(0, 1)), "胡別家");
            eq(Challenge.Status.CLEARED, settle(stage, session, tsumo(0)), "自摸");
            eq("自摸 1 / 1 次", stage.check(session).progress(), "進度");
        });

        test("守口如瓶：放槍就失敗，撐完東風圈就過關", () -> {
            Challenge stage = Challenge.CLEAN_CIRCLE;
            eq(Challenge.Status.FAILED, settle(stage, session(stage), ron(1, 0)), "放槍");
            eq(Challenge.Status.PLAYING, settle(stage, session(stage), tsumo(1)), "被自摸不算放槍");
            GameSession session = session(stage);
            endCircles(session, stage.circles());
            eq(Challenge.Status.CLEARED, stage.check(session).status(), "撐完");
        });

        test("門前清與一色到底：胡出指定的牌型", () -> {
            eq(Challenge.Status.PLAYING, settle(Challenge.CONCEALED, session(Challenge.CONCEALED),
                    ron(0, 1, new ScoreItem("平胡", 2))), "沒有門清");
            eq(Challenge.Status.CLEARED, settle(Challenge.CONCEALED, session(Challenge.CONCEALED),
                    tsumo(0, new ScoreItem("門清一摸三", 3))), "門清一摸三");
            eq(Challenge.Status.PLAYING, settle(Challenge.CONCEALED, session(Challenge.CONCEALED),
                    ron(1, 2, new ScoreItem("門清", 1))), "電腦門清不算");
            eq(Challenge.Status.CLEARED, settle(Challenge.ONE_SUIT, session(Challenge.ONE_SUIT),
                    ron(0, 1, new ScoreItem("清一色", 8))), "清一色");
            eq(Challenge.Status.CLEARED, settle(Challenge.ONE_SUIT, session(Challenge.ONE_SUIT),
                    ron(0, 1, new ScoreItem("字一色", 16))), "字一色也算");
        });

        test("有台有面：牌型合計 5 台，不算莊家台", () -> {
            Challenge stage = Challenge.FIVE_TAI;
            GameSession session = sessionWithDealer(stage, 0);
            eq(Challenge.Status.PLAYING, settle(stage, session, tsumo(0, new ScoreItem("門清一摸三", 3),
                    new ScoreItem("門風 東", 1))), "4 台加莊家台還是不夠");
            eq("最大的一手 4 / 5 台", stage.check(session).progress(), "進度");
            eq(Challenge.Status.CLEARED, settle(stage, session, tsumo(0, new ScoreItem("門清一摸三", 3),
                    new ScoreItem("平胡", 2))), "5 台");
        });

        test("連二拉二：自己當莊連莊兩次，流局也算；別家連莊不算", () -> {
            Challenge stage = Challenge.DEALER_RUN;
            GameSession session = sessionWithDealer(stage, 0);
            eq(Challenge.Status.PLAYING, settle(stage, session, tsumo(0)), "連一");
            eq(Challenge.Status.CLEARED, settle(stage, session, RoundResult.drawnGame()), "流局再連");
            GameSession other = sessionWithDealer(stage, 2);
            settle(stage, other, tsumo(2));
            eq(Challenge.Status.PLAYING, settle(stage, other, tsumo(2)), "別家連莊");
        });

        test("步步為營：放槍第二次才失敗", () -> {
            Challenge stage = Challenge.CAREFUL;
            GameSession session = session(stage);
            eq(Challenge.Status.PLAYING, settle(stage, session, ron(1, 0)), "一次");
            eq(Challenge.Status.FAILED, settle(stage, session, ron(2, 0)), "兩次");
            GameSession clean = session(stage);
            settle(stage, clean, ron(1, 0));
            endCircles(clean, stage.circles());
            eq(Challenge.Status.CLEARED, stage.check(clean).status(), "只放一次，撐完四圈");
        });

        test("力爭上游與出師：打完時第一名才過關", () -> {
            for (Challenge stage : List.of(Challenge.TOP_AMATEUR, Challenge.GRADUATE)) {
                GameSession win = session(stage);
                eq(Challenge.Status.PLAYING, settle(stage, win, ron(0, 1, new ScoreItem("大牌", 100))), "還沒打完");
                endCircles(win, stage.circles());
                eq(Challenge.Status.CLEARED, stage.check(win).status(), stage + " 第一名");
                GameSession lose = session(stage);
                settle(stage, lose, ron(1, 0, new ScoreItem("大牌", 100)));
                endCircles(lose, stage.circles());
                eq(Challenge.Status.FAILED, stage.check(lose).status(), stage + " 輸了");
            }
        });

        test("背水一戰：輸光就失敗，撐完或電腦先輸光都過關", () -> {
            Challenge stage = Challenge.LAST_STAND;
            eq(Challenge.Status.FAILED, settle(stage, session(stage), ron(1, 0, new ScoreItem("大牌", 100))), "輸光");
            eq(Challenge.Status.CLEARED, settle(stage, session(stage), ron(2, 1, new ScoreItem("大牌", 100))),
                    "電腦先輸光");
            GameSession session = session(stage);
            endCircles(session, stage.circles());
            eq(Challenge.Status.CLEARED, stage.check(session).status(), "撐完兩圈");
        });

        test("掃地出門：自己胡牌讓電腦輸光才算", () -> {
            Challenge stage = Challenge.SWEEP;
            eq(Challenge.Status.CLEARED, settle(stage, session(stage), ron(0, 1, new ScoreItem("大牌", 120))), "胡到輸光");
            eq(Challenge.Status.FAILED, settle(stage, session(stage), ron(2, 1, new ScoreItem("大牌", 120))),
                    "別家弄輸光的");
            eq(Challenge.Status.PLAYING, settle(stage, session(stage), ron(0, 1, new ScoreItem("小牌", 10))), "還沒輸光");
        });

        test("檔案的格子：建立、讀取、刪除，紀錄每格分開", () -> {
            Path home = Files.createTempDirectory("ezmj-home");
            List<CareerSlot> slots = CareerSlot.all(home);
            eq(CareerSlot.COUNT, slots.size(), "格數");
            CareerSlot first = slots.get(0);
            CareerSlot second = slots.get(1);
            check(!first.exists(), "一開始是空的");
            first.create(profile(20_000));
            check(first.exists() && !second.exists(), "只有第一格");
            eq("阿明", first.loadProfile().name(), "讀回名字");

            Records records = first.loadRecords();
            GameSession session = session(Challenge.FIRST_WIN);
            session.settle(ron(0, 1));
            records.achievements().recordRound(session, DATE);
            records.stats().recordRound(session);
            records.saveAchievements();
            records.saveStats();
            Files.writeString(records.saveFile(), "version=1\n");
            check(first.loadRecords().achievements().isUnlocked(Achievement.WINS_1), "第一格有成就");
            eq(1, first.loadRecords().stats().total().wins(), "第一格有統計");
            check(!second.loadRecords().achievements().isUnlocked(Achievement.WINS_1), "第二格沒有");
            check(!Records.load(home).achievements().isUnlocked(Achievement.WINS_1), "練習牌局也沒有");

            first.delete();
            check(!first.exists(), "刪掉了");
            check(!Files.exists(records.saveFile()), "存檔也刪了");
            check(!first.loadRecords().achievements().isUnlocked(Achievement.WINS_1), "成就也刪了");
            first.create(profile(10_000));
            eq(10_000L, first.loadProfile().bankroll(), "同一格重新建立");
        });
    }
}
