package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ezmj.career.CareerProfile;
import ezmj.career.Challenge;
import ezmj.career.Drill;
import ezmj.career.MemoryQuiz;
import ezmj.career.MemoryQuiz.Discard;
import ezmj.career.MemoryQuiz.Question;
import ezmj.career.WaitsQuiz;
import ezmj.engine.GameSession;
import ezmj.model.Tile;
import ezmj.rules.HandAnalyzer;

/** 修練關卡：設定、開放順序、星等與獎金、存讀，以及看聽與記牌的出題和判定。 */
final class DrillTest {

    private static final String DATE = "2026-11-10";
    private static final List<Challenge> DRILLS = List.of(Challenge.MEMORY_1, Challenge.WAITS_1, Challenge.MEMORY_2,
            Challenge.WAITS_2, Challenge.MEMORY_3, Challenge.WAITS_3);

    private DrillTest() {
    }

    static void run() {
        T.section("修練關卡");

        test("修練自成一條線：六關照順序，和完整牌局的十二關分開編號", () -> {
            eq(DRILLS, Challenge.line(true), "修練的順序");
            eq(12, Challenge.line(false).size(), "完整牌局還是十二關");
            for (int i = 0; i < DRILLS.size(); i++) {
                Challenge drill = DRILLS.get(i);
                check(drill.isDrill(), drill + " 是修練");
                eq(i + 1, drill.number(), drill + " 是修練第幾關");
                eq("修練 " + (i + 1), drill.label(), drill + " 的名稱");
                eq(Challenge.DRILL_CHAPTER, drill.chapter(), drill + " 的章");
                check(java.util.Objects.equals(i == 0 ? null : DRILLS.get(i - 1), drill.previous()), drill + " 的前一關");
                check(java.util.Objects.equals(i + 1 < DRILLS.size() ? DRILLS.get(i + 1) : null, drill.next()),
                        drill + " 的下一關");
            }
            check(Challenge.FIRST_WIN.previous() == null, "完整牌局的第一關前面沒有關");
            check(Challenge.GRADUATE.next() == null, "出師之後不接修練");
            eq(12, Challenge.GRADUATE.number(), "出師還是第 12 關");
            eq("第 12 關", Challenge.GRADUATE.label(), "完整牌局的名稱");
        });

        test("修練的設定：記牌三關、看聽三關，題數、秒數、過關門檻與獎金", () -> {
            int[] rewards = {600, 600, 1_000, 1_000, 1_500, 1_500};
            for (int i = 0; i < DRILLS.size(); i++) {
                Challenge stage = DRILLS.get(i);
                Drill drill = stage.drill();
                eq(rewards[i], stage.reward(), stage + " 獎金");
                eq(0, stage.reward() % CareerProfile.EXPERT_REWARD_SHARE, stage + " 專家獎金除得盡");
                eq(List.of(Challenge.CLEAR_STAR, "全部答對", "平均 " + drill.fastSeconds() + " 秒內作答"), stage.starTexts(),
                        stage + " 三顆星");
                check(!stage.setup().isEmpty() && !stage.goalText().isEmpty(), stage + " 說明");
                fails(IllegalStateException.class, () -> stage.check(new GameSession(stage.config("測試"), new Random(1))),
                        stage + " 不是牌局，不能用 check");
            }
            Drill memory1 = Challenge.MEMORY_1.drill();
            check(memory1.isMemory(), "記牌一是記牌");
            eq(List.of(2, 3, 3, 15, 2), List.of(memory1.minRounds(), memory1.maxRounds(), memory1.questions(),
                    memory1.answerSeconds(), memory1.passCount()), "記牌一：第 2～3 輪、3 題、15 秒、對 2 題");
            eq(List.of(3, 5, 4, 12, 3), numbers(Challenge.MEMORY_2.drill()), "記牌二");
            eq(List.of(5, 7, 5, 10, 4), numbers(Challenge.MEMORY_3.drill()), "記牌三");
            check(Challenge.MEMORY_1.config("測試").expert(), "記牌照專家模式顯示");
            eq(List.of(1, 2, 15, 15, 5, 4), waitsNumbers(Challenge.WAITS_1.drill()), "看聽一：聽 1～2、看 15、答 15、5 手對 4");
            eq(List.of(2, 4, 10, 12, 5, 4), waitsNumbers(Challenge.WAITS_2.drill()), "看聽二");
            eq(List.of(3, 6, 8, 10, 5, 4), waitsNumbers(Challenge.WAITS_3.drill()), "看聽三");
            check(Challenge.WAITS_3.drill().oneSuit(), "看聽三多為一色牌");
            eq("三家一般・第 2～3 輪停下來問・3 題・每題 15 秒・答對 2 題過關", Challenge.MEMORY_1.setup(), "記牌一的說明");
        });

        test("修練的開放順序：第一關一開始就開放，和完整牌局互不擋", () -> {
            CareerProfile profile = new CareerProfile("阿明", 20_000, DATE);
            check(profile.isUnlocked(Challenge.MEMORY_1), "修練第一關一開始就開放");
            check(!profile.isUnlocked(Challenge.WAITS_1), "修練第二關還沒開放");
            check(profile.isUnlocked(Challenge.FIRST_WIN), "完整牌局第一關照舊開放");
            profile.clearChallenge(Challenge.FIRST_WIN, DATE);
            check(!profile.isUnlocked(Challenge.WAITS_1), "過完整牌局不會開修練");
            profile.clearChallenge(Challenge.MEMORY_1, DATE);
            check(profile.isUnlocked(Challenge.WAITS_1), "過了記牌一開看聽一");
            check(!profile.isUnlocked(Challenge.CLEAN_CIRCLE), "過修練不會開完整牌局");
            eq(1, profile.clearedCount(false), "完整牌局過了一關");
            eq(1, profile.clearedCount(true), "修練過了一關");
            eq(2, profile.clearedCount(), "全部過了兩關");
        });

        test("修練的星等：答對到門檻過關，全部答對與作答夠快各一顆", () -> {
            Drill drill = Challenge.MEMORY_3.drill();
            eq(0, drill.stars(3, 2.0), "答對 3 題不到 4 題，沒過關");
            eq(0b001, drill.stars(4, 9.5), "過關，沒全對、也不夠快");
            eq(0b011, drill.stars(5, 9.5), "全部答對");
            eq(0b101, drill.stars(4, 6.0), "剛好 6 秒算夠快");
            eq(0b111, drill.stars(5, 3.2), "三顆星");
            eq(0b001, drill.stars(4, 6.01), "多一點點就不算快");
        });

        test("修練的獎金：過關獎金與第一次拿到的加星獎金，專家檔案是 1/20", () -> {
            CareerProfile profile = new CareerProfile("阿明", 20_000, DATE);
            int bonus = 600 / CareerProfile.STAR_BONUS_SHARE;
            eq(600 + bonus, profile.clearChallenge(Challenge.MEMORY_1, DATE, 0b011), "過關加全部答對");
            eq(bonus, profile.clearChallenge(Challenge.MEMORY_1, DATE, 0b101), "再過一次拿到作答夠快");
            eq(0, profile.clearChallenge(Challenge.MEMORY_1, DATE, 0b111), "星都拿過了");
            eq(20_000L + 600 + 2 * bonus, profile.bankroll(), "入帳");
            eq(CareerProfile.Game.NONE, profile.game(), "修練不留打到一半的牌局");
            CareerProfile expert = new CareerProfile("阿明", CareerProfile.EXPERT_STARTING_CHIPS, DATE, true);
            eq(List.of(30, 30, 50, 50, 75, 75), DRILLS.stream().map(expert::rewardFor).toList(), "專家獎金");
            eq(30 + 6, expert.clearChallenge(Challenge.MEMORY_1, DATE, 0b011), "專家的加星獎金");
        });

        test("修練的過關與星存讀得回來", () -> {
            CareerProfile profile = new CareerProfile("阿明", 20_000, DATE);
            profile.clearChallenge(Challenge.MEMORY_1, DATE, 0b101);
            profile.clearChallenge(Challenge.WAITS_1, DATE, 0b011);
            Path file = Files.createTempDirectory("ezmj-drill").resolve("profile.properties");
            profile.save(file);
            CareerProfile loaded = CareerProfile.load(file);
            eq(0b101, loaded.starMask(Challenge.MEMORY_1), "記牌一的星");
            eq(0b011, loaded.starMask(Challenge.WAITS_1), "看聽一的星");
            eq(DATE, loaded.clearedDate(Challenge.WAITS_1), "過關日期");
            check(loaded.isUnlocked(Challenge.MEMORY_2), "開到記牌二");
            eq(4, loaded.totalStars(), "總星數");
        });

        test("看聽出題：每手 16 張、同一種最多 4 張、聽的種數在範圍內，而且真的聽那幾種", () -> {
            for (Challenge stage : List.of(Challenge.WAITS_1, Challenge.WAITS_2, Challenge.WAITS_3)) {
                Drill drill = stage.drill();
                for (int seed = 0; seed < 30; seed++) {
                    List<WaitsQuiz.Hand> hands = WaitsQuiz.hands(drill, new Random(seed));
                    eq(drill.questions(), hands.size(), stage + " 題數");
                    for (WaitsQuiz.Hand hand : hands) {
                        eq(16, hand.tiles().size(), "16 張");
                        int[] counts = new int[Tile.KINDS];
                        for (Tile tile : hand.tiles()) {
                            check(!tile.isFlower(), "沒有花牌");
                            check(++counts[tile.index()] <= 4, "同一種最多 4 張：" + hand.tiles());
                        }
                        int waits = hand.waits().size();
                        check(waits >= drill.minWaits() && waits <= drill.maxWaits(), stage + " 聽 " + waits + " 種");
                        eq(HandAnalyzer.waits(hand.tiles()), hand.waits(), "聽的牌照 HandAnalyzer");
                        for (Tile wait : hand.waits()) {
                            List<Tile> won = new ArrayList<>(hand.tiles());
                            won.add(wait);
                            check(HandAnalyzer.canWin(won), "摸到 " + wait + " 就胡");
                        }
                        List<Tile> sorted = new ArrayList<>(hand.tiles());
                        sorted.sort(null);
                        eq(sorted, hand.tiles(), "手牌排好了");
                    }
                }
            }
        });

        test("看聽出題：同一個種子出一樣的題目；一色牌那關大多是清一色", () -> {
            eq(WaitsQuiz.hands(Challenge.WAITS_2.drill(), new Random(42)), WaitsQuiz.hands(Challenge.WAITS_2.drill(),
                    new Random(42)), "可重現");
            int oneSuit = 0;
            int total = 0;
            for (int seed = 0; seed < 20; seed++) {
                for (WaitsQuiz.Hand hand : WaitsQuiz.hands(Challenge.WAITS_3.drill(), new Random(seed))) {
                    total++;
                    Set<Object> suits = new HashSet<>();
                    hand.tiles().forEach(tile -> suits.add(tile.suit()));
                    if (suits.size() == 1) {
                        oneSuit++;
                    }
                }
            }
            check(oneSuit * 2 > total, "看聽三一半以上是清一色：" + oneSuit + " / " + total);
        });

        test("看聽判定：剛好選對才算，少選、多選、沒選都不算", () -> {
            WaitsQuiz.Hand hand = WaitsQuiz.hands(Challenge.WAITS_2.drill(), new Random(7)).get(0);
            List<Tile> waits = hand.waits();
            check(hand.correct(waits), "選對");
            List<Tile> reversed = new ArrayList<>(waits);
            java.util.Collections.reverse(reversed);
            check(hand.correct(reversed), "順序不管");
            check(!hand.correct(waits.subList(0, waits.size() - 1)), "少選一種");
            List<Tile> extra = new ArrayList<>(waits);
            for (int index = 0; index < Tile.KINDS; index++) {
                Tile tile = Tile.fromIndex(index);
                if (!waits.contains(tile)) {
                    extra.add(tile);
                    break;
                }
            }
            check(!hand.correct(extra), "多選一種");
            check(!hand.correct(List.of()), "沒選");
            check(!hand.correct(null), "逾時");
        });

        test("記牌出題：四種題目的正解都照剛才打出的牌", () -> {
            List<Discard> discards = sampleDiscards();
            String[] labels = {"南家（我）", "西家（甲）", "北家（乙）", "東家（丙）"};
            List<Integer> order = List.of(3, 0, 1, 2);
            Set<String> kinds = new HashSet<>();
            for (int seed = 0; seed < 40; seed++) {
                List<Question> questions = MemoryQuiz.questions(discards, labels, order, 5, new Random(seed));
                eq(5, questions.size(), "題數");
                for (Question question : questions) {
                    String prompt = question.prompt();
                    if (prompt.startsWith("最後打出的")) {
                        kinds.add("最後幾張");
                        eq(List.of(T.tile("5s"), T.tile("2p"), T.tile("7w"), T.tile("1z")), question.tiles(),
                                "最後四張照順序");
                    } else if (prompt.contains("最後兩張")) {
                        kinds.add("某家最後兩張");
                        int seat = List.of(labels).indexOf(prompt.substring(0, prompt.indexOf("最後兩張")));
                        List<Tile> own = new ArrayList<>();
                        discards.stream().filter(d -> d.seat() == seat).forEach(d -> own.add(d.tile()));
                        eq(own.subList(own.size() - 2, own.size()), question.tiles(), labels[seat] + "最後兩張");
                    } else if (prompt.contains("被打出了幾張")) {
                        kinds.add("幾張");
                        long count = discards.stream().filter(d -> prompt.contains(MemoryQuiz.name(d.tile()))).count();
                        eq((int) count, question.choice(), prompt);
                        eq(5, question.choices().size(), "0～4 張");
                    } else {
                        kinds.add("誰打的");
                        eq(List.of("東家（丙）", "南家（我）", "西家（甲）", "北家（乙）"), question.choices(), "照門風排");
                        String asked = prompt.substring(0, prompt.indexOf("是誰打的"));
                        Discard discard = discards.stream().filter(d -> MemoryQuiz.name(d.tile()).equals(asked))
                                .findFirst().orElseThrow();
                        eq(order.indexOf(discard.seat()), question.choice(), prompt);
                    }
                }
            }
            eq(4, kinds.size(), "四種題目都出現過：" + kinds);
        });

        test("記牌判定：點牌要照順序，選擇題要選對；打出的牌很少也出得了題", () -> {
            List<Question> questions = MemoryQuiz.questions(sampleDiscards(), new String[] {"甲", "乙", "丙", "丁"},
                    List.of(0, 1, 2, 3), 6, new Random(3));
            for (Question question : questions) {
                if (question.answer() == MemoryQuiz.Answer.TILES) {
                    check(question.correctTiles(question.tiles()), "照順序點對");
                    List<Tile> reversed = new ArrayList<>(question.tiles());
                    java.util.Collections.reverse(reversed);
                    check(reversed.equals(question.tiles()) || !question.correctTiles(reversed), "順序錯了不算");
                    check(!question.correctTiles(null), "逾時不算");
                    check(!question.correctChoice(0), "點牌題沒有選項");
                } else {
                    check(question.correctChoice(question.choice()), "選對");
                    check(!question.correctChoice((question.choice() + 1) % question.choices().size()), "選錯");
                    check(!question.correctChoice(-1), "逾時不算");
                }
                check(!question.solution().isEmpty(), "看得到正解");
            }
            List<Question> few = MemoryQuiz.questions(List.of(new Discard(1, T.tile("3s"))), new String[] {"甲", "乙",
                "丙", "丁"}, List.of(0, 1, 2, 3), 3, new Random(1));
            eq(3, few.size(), "只打出一張也出得了三題");
            fails(IllegalArgumentException.class, () -> MemoryQuiz.questions(List.of(), new String[4], List.of(0, 1, 2, 3),
                    3, new Random(1)), "一張都沒打不能問");
            eq(MemoryQuiz.questions(sampleDiscards(), new String[] {"甲", "乙", "丙", "丁"}, List.of(0, 1, 2, 3), 4,
                    new Random(9)), MemoryQuiz.questions(sampleDiscards(), new String[] {"甲", "乙", "丙", "丁"},
                    List.of(0, 1, 2, 3), 4, new Random(9)), "同一個種子出一樣的題目");
            eq("東風", MemoryQuiz.name(T.tile("1z")), "東風");
            eq("紅中", MemoryQuiz.name(T.tile("5z")), "紅中");
            eq("七萬", MemoryQuiz.name(T.tile("7w")), "七萬");
        });
    }

    private static List<Integer> numbers(Drill drill) {
        return List.of(drill.minRounds(), drill.maxRounds(), drill.questions(), drill.answerSeconds(), drill.passCount());
    }

    private static List<Integer> waitsNumbers(Drill drill) {
        return List.of(drill.minWaits(), drill.maxWaits(), drill.viewSeconds(), drill.answerSeconds(), drill.questions(),
                drill.passCount());
    }

    /** 三輪多一點：座位 0 到 3 輪流打，最後四張是五條、二筒、七萬、東風。 */
    private static List<Discard> sampleDiscards() {
        String[] tiles = {"9w", "1z", "3p", "7z", "2s", "9w", "4p", "6s", "8p", "3w", "5z", "5s", "2p", "7w", "1z"};
        List<Discard> discards = new ArrayList<>();
        for (int i = 0; i < tiles.length; i++) {
            discards.add(new Discard(i % 4, T.tile(tiles[i])));
        }
        return discards;
    }
}
