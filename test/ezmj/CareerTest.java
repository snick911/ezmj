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
import ezmj.career.CareerTitle;
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
            for (Challenge drill : Challenge.line(true)) {
                check(seen.add(drill.title()), "關卡名稱重複：" + drill.title());
                check(!titles.contains(drill.title()), "和成就同名：" + drill.title());
                eq(drill, Challenge.fromKey(drill.name()), "讀檔用的名稱");
            }
            // 修練關卡不是牌局，設定與判定在 DrillTest
            for (Challenge challenge : Challenge.line(false)) {
                GameConfig config = challenge.config("測試");
                check(GameConfig.paysFit(config.startingChips(), config.basePay(), config.taiPay()), challenge + " 底台");
                check(challenge.circles() >= 1 && challenge.circles() <= 4, challenge + " 圈數");
                check(challenge.reward() > 0, challenge + " 獎金");
                check(challenge.reward() % CareerProfile.EXPERT_REWARD_SHARE == 0, challenge + " 專家獎金除得盡");
                check(!challenge.goalText().isEmpty() && !challenge.chapter().isEmpty(), challenge + " 說明");
                check(seen.add(challenge.title()), "關卡名稱重複：" + challenge.title());
                check(!titles.contains(challenge.title()), "和成就同名：" + challenge.title());
                eq(challenge, Challenge.fromKey(challenge.name()), "讀檔用的名稱");
                eq(Challenge.Status.PLAYING, challenge.check(session(challenge)).status(), challenge + " 還沒開打");
                eq(Challenge.MAX_STARS, new HashSet<>(challenge.starTexts()).size(), challenge + " 三顆星的條件都不一樣");
                eq(Challenge.CLEAR_STAR, challenge.starTexts().get(0), challenge + " 第一顆是過關");
                eq(0, challenge.check(session(challenge)).stars(), challenge + " 還沒過關沒有星");
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
            eq(20_000, profile.startGeneral(100, 20), "全部帶上桌");
            profile.settleGeneral(21_500);
            eq(21_500L, profile.bankroll(), "贏了");
            profile.settleGeneral(21_500);
            eq(21_500L, profile.bankroll(), "同一局再算一次");
            profile.settleGeneral(-300);
            eq(-300L, profile.bankroll(), "輸光可以是負的");
            eq(21_500L, profile.peak(), "最高");
            fails(IllegalStateException.class, () -> profile.startGeneral(50, 10), "打到一半不能再開");
            profile.endGame();
            eq(CareerProfile.Game.NONE, profile.game(), "結束");
            fails(IllegalStateException.class, () -> profile.settleGeneral(100), "沒在打就不能結算");
        });

        test("一般對戰：帶上桌最多一億，其餘留在帳上", () -> {
            CareerProfile rich = profile(10_000_000);
            for (int i = 0; i < 12; i++) {
                rich.startGeneral(100, 20);
                rich.settleGeneral(rich.tableChips() * 2);
                rich.endGame();
            }
            check(rich.bankroll() > CareerProfile.MAX_TABLE_CHIPS, "滾到一億以上 " + rich.bankroll());
            long before = rich.bankroll();
            eq(CareerProfile.MAX_TABLE_CHIPS, rich.startGeneral(200_000, 100_000), "上桌一億");
            rich.settleGeneral(CareerProfile.MAX_TABLE_CHIPS - 5_000);
            eq(before - 5_000, rich.bankroll(), "只扣桌上輸的");
        });

        test("救濟金：籌碼不到門檻、沒有打到一半的牌局才能領，記一次破產", () -> {
            CareerProfile profile = profile(10_000);
            check(!profile.canTakeRelief(), "還有錢");
            profile.startGeneral(100, 20);
            profile.settleGeneral(CareerProfile.RELIEF_BELOW);
            profile.endGame();
            check(!profile.canTakeRelief(), "剛好上得了最小的桌");
            check(profile.stakesAllowed(50, 10), "最小的桌");
            profile.startGeneral(50, 10);
            profile.settleGeneral(CareerProfile.RELIEF_BELOW - 1);
            check(!profile.canTakeRelief(), "牌局打到一半");
            profile.endGame();
            check(profile.canTakeRelief(), "可以領");
            profile.takeRelief();
            eq((long) CareerProfile.RELIEF_TO, profile.bankroll(), "補到");
            eq(1, profile.bankruptcies(), "破產次數");
            fails(IllegalStateException.class, profile::takeRelief, "補完就不能再領");
            check(CareerProfile.RELIEF_TO <= CareerProfile.STARTING_CHIPS.get(0), "救濟金不比最小的起始籌碼多");
            check(!profile.baseOptions().isEmpty(), "領完上得了桌");
        });

        test("專家模式：起始只有 100，底台不大於帳上就能選，付不起最小的底才能領救濟金，補回 100", () -> {
            fails(IllegalArgumentException.class, () -> new CareerProfile("專家", 20_000, DATE, true), "起始只能是 100");
            CareerProfile expert = new CareerProfile("專家", CareerProfile.EXPERT_STARTING_CHIPS, DATE, true);
            check(expert.expert(), "專家模式");
            check(!profile(1_000).expert(), "一般模式");
            eq(List.of(50, 100), expert.baseOptions(), "100 能選的底");
            eq(List.of(10, 20, 50, 100), expert.taiOptions(), "100 能選的每台");
            check(!expert.canTakeRelief(), "100 還上得了最小的桌");
            eq(100, expert.startGeneral(100, 100), "全部帶上桌");
            expert.settleGeneral(CareerProfile.EXPERT_RELIEF_BELOW);
            expert.endGame();
            check(!expert.canTakeRelief(), "剛好付得起最小的底");
            check(expert.stakesAllowed(50, 50), "最小的底、每台 50");
            check(!expert.stakesAllowed(100, 10), "底付不起");
            expert.startGeneral(50, 10);
            expert.settleGeneral(CareerProfile.EXPERT_RELIEF_BELOW - 1);
            expert.endGame();
            check(expert.baseOptions().isEmpty(), "連最小的底都付不起");
            check(expert.canTakeRelief(), "可以領");
            expert.takeRelief();
            eq((long) CareerProfile.EXPERT_STARTING_CHIPS, expert.bankroll(), "補回起始的 100");
            eq(1, expert.bankruptcies(), "破產次數");
        });

        test("關卡星等：過關一顆，加星條件各一顆，再打可以補齊，新拿到的星各給 1/5 獎金", () -> {
            Challenge stage = Challenge.FIRST_WIN;
            int bonus = Challenge.FIRST_WIN.reward() / CareerProfile.STAR_BONUS_SHARE;
            CareerProfile profile = profile(10_000);
            eq(bonus, profile.starBonus(stage), "加星獎金");
            fails(IllegalArgumentException.class, () -> profile.clearChallenge(stage, DATE, 0b10), "一定要有過關那顆");
            eq(stage.reward() + bonus, profile.clearChallenge(stage, DATE, 0b011), "過關加第二顆");
            eq(2, profile.stars(stage), "兩顆");
            eq(bonus, profile.clearChallenge(stage, DATE, 0b101), "再打拿到第三顆，只給新的那顆");
            eq(3, profile.stars(stage), "合起來三顆");
            eq(0, profile.clearChallenge(stage, DATE, 0b111), "都拿過了沒有獎金");
            eq(3, profile.totalStars(), "總星數");
            eq(10_000L + stage.reward() + 2 * bonus, profile.bankroll(), "入帳");
            CareerProfile expert = new CareerProfile("專家", CareerProfile.EXPERT_STARTING_CHIPS, DATE, true);
            eq(20, expert.starBonus(stage), "專家：專家獎金 100 的 1/5");
        });

        test("關卡星等：過關當下判定加星條件", () -> {
            Challenge stage = Challenge.FIRST_WIN;
            GameSession clean = session(stage);
            clean.settle(tsumo(0));
            eq(0b111, stage.check(clean).stars(), "自摸過關、沒放槍：三顆");
            eq(List.of("過關", "沒有放槍", "自摸過關"), stage.starTexts(stage.check(clean).stars()), "說明");
            GameSession dealtIn = session(stage);
            dealtIn.settle(ron(1, 0));
            dealtIn.settle(ron(0, 2));
            eq(0b001, stage.check(dealtIn).stars(), "放過槍、胡別家：只有過關");
            Challenge early = Challenge.FIRST_SELF_DRAW;
            GameSession quick = session(early);
            quick.settle(tsumo(0));
            eq(0b111, early.check(quick).stars(), "東風圈內自摸：三顆");
            GameSession late = session(early);
            endCircles(late, 1);
            late.settle(tsumo(0));
            eq(0b011, early.check(late).stars(), "南風圈才自摸：沒有東風圈內那顆");
        });

        test("專家模式的挑戰獎金是原本的 1/20，一般模式照舊", () -> {
            CareerProfile expert = new CareerProfile("專家", CareerProfile.EXPERT_STARTING_CHIPS, DATE, true);
            eq(100, expert.rewardFor(Challenge.FIRST_WIN), "第一關 2000 的 1/20");
            eq(2_500, expert.rewardFor(Challenge.GRADUATE), "出師 50000 的 1/20");
            expert.startChallenge(Challenge.FIRST_WIN);
            eq(100, expert.clearChallenge(Challenge.FIRST_WIN, DATE), "拿到的獎金");
            eq(200L, expert.bankroll(), "100 加 100");
            eq(0, expert.clearChallenge(Challenge.FIRST_WIN, DATE), "再過一次沒有獎金");
            eq(Challenge.FIRST_WIN.reward(), profile(1_000).rewardFor(Challenge.FIRST_WIN), "一般模式照舊");
        });

        test("起始籌碼有 1000、20000、100000 三種", () -> {
            eq(List.of(1_000, 20_000, 100_000), CareerProfile.STARTING_CHIPS, "選項");
            check(CareerProfile.STARTING_CHIPS.contains(CareerProfile.DEFAULT_STARTING_CHIPS), "預設在選項裡");
            CareerProfile small = profile(1_000);
            eq(List.of(50, 100), small.baseOptions(), "1000 能選的底");
            eq(List.of(10, 20, 50), small.taiOptions(), "1000 能選的每台");
        });

        test("底台照帳上籌碼開放：底最多 1/10、每台最多 1/20，最大底 200000、每台 100000", () -> {
            CareerProfile profile = profile(100_000);
            eq(10_000, profile.baseOptions().get(profile.baseOptions().size() - 1), "10 萬：底最多 1 萬");
            eq(5_000, profile.taiOptions().get(profile.taiOptions().size() - 1), "10 萬：每台最多 5000");
            check(profile.stakesAllowed(10_000, 5_000), "剛好可以");
            check(!profile.stakesAllowed(20_000, 5_000), "底太大");
            check(!profile.stakesAllowed(10_000, 10_000), "每台太大");
            check(!profile.stakesAllowed(150, 20), "不是選項的數字");
            fails(IllegalArgumentException.class, () -> profile.startGeneral(20_000, 5_000), "選不了的底台不能開打");
            eq(CareerProfile.Game.NONE, profile.game(), "沒開成");
            CareerProfile rich = profile(10_000_000);
            eq(200_000, rich.baseOptions().get(rich.baseOptions().size() - 1), "最大底");
            eq(100_000, rich.taiOptions().get(rich.taiOptions().size() - 1), "最大每台");
            CareerProfile broke = profile(1_000);
            broke.startGeneral(50, 10);
            broke.settleGeneral(CareerProfile.RELIEF_BELOW - 1);
            broke.endGame();
            check(broke.baseOptions().isEmpty(), "連最小的底都選不了");
        });

        test("稱號：帳上到過 20 萬、100 萬、500 萬、2000 萬、1 億，輸掉也不會拿走", () -> {
            check(CareerTitle.of(199_999) == null, "還沒有");
            eq(CareerTitle.RISING, CareerTitle.of(200_000), "20 萬");
            eq(CareerTitle.MILLION, CareerTitle.of(1_000_000), "100 萬");
            eq(CareerTitle.TYCOON, CareerTitle.of(5_000_000), "500 萬");
            eq(CareerTitle.MAGNATE, CareerTitle.of(20_000_000), "2000 萬");
            eq(CareerTitle.LEGEND, CareerTitle.of(100_000_000), "1 億");
            eq(CareerTitle.RISING, CareerTitle.next(0), "第一個");
            check(CareerTitle.next(100_000_000) == null, "全部到手");
            CareerProfile profile = profile(100_000);
            check(profile.title() == null, "10 萬還沒有");
            profile.startGeneral(100, 20);
            profile.settleGeneral(1_200_000);
            eq(CareerTitle.MILLION, profile.title(), "贏到 120 萬");
            eq(CareerTitle.TYCOON, profile.nextTitle(), "下一個");
            profile.settleGeneral(5_000);
            eq(CareerTitle.MILLION, profile.title(), "輸回去也還在");
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
            profile.startGeneral(100, 20);
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
            check(!challenge.expert(), "一般模式讀回來還是一般模式");

            new CareerProfile("專家", CareerProfile.EXPERT_STARTING_CHIPS, DATE, true).save(file);
            check(CareerProfile.load(file).expert(), "專家模式讀得回來");

            CareerProfile starred = profile(10_000);
            starred.clearChallenge(Challenge.FIRST_WIN, DATE, 0b101);
            starred.clearChallenge(Challenge.FIRST_SELF_DRAW, DATE);
            starred.save(file);
            CareerProfile reloaded = CareerProfile.load(file);
            eq(0b101, reloaded.starMask(Challenge.FIRST_WIN), "拿過哪幾顆星讀得回來");
            eq(3, reloaded.totalStars(), "總星數讀得回來");
            Files.writeString(file, "name=amin\nstartingChips=10000\ncleared.FIRST_WIN=2026-11-01\n");
            eq(1, CareerProfile.load(file).stars(Challenge.FIRST_WIN), "以前的檔案過關就是一顆星");

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
