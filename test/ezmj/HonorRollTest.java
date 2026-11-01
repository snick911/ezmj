package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ezmj.model.BotLevel;
import ezmj.model.GameRecord;
import ezmj.model.HonorRoll;
import ezmj.model.HonorRoll.Category;

/** 榮譽榜：排序、名額、各類別獨立與存檔。 */
final class HonorRollTest {

    private HonorRollTest() {
    }

    private static GameRecord record(String name, int wins, int selfDraws, int winnings, int dealIns) {
        return new GameRecord(name, "2026-10-31", wins, selfDraws, winnings, dealIns, BotLevel.PRO, 100, 20);
    }

    static void run() {
        T.section("榮譽榜");

        test("由高到低排列，回傳拿到的名次", () -> {
            HonorRoll roll = new HonorRoll();
            eq(Map.of(Category.WINS, 1), roll.add(record("甲", 3, 0, 0, 0)), "第一筆");
            eq(Map.of(Category.WINS, 1), roll.add(record("乙", 5, 0, 0, 0)), "比較高");
            eq(Map.of(Category.WINS, 2), roll.add(record("丙", 4, 0, 0, 0)), "中間");
            eq("乙", roll.top(Category.WINS).get(0).name(), "第一名");
            eq("丙", roll.top(Category.WINS).get(1).name(), "第二名");
            eq("甲", roll.top(Category.WINS).get(2).name(), "第三名");
        });

        test("每個類別只留十筆，擠不進去就沒有名次", () -> {
            HonorRoll roll = new HonorRoll();
            for (int i = 1; i <= 12; i++) {
                roll.add(record("P" + i, i, 0, 0, 0));
            }
            eq(HonorRoll.SIZE, roll.top(Category.WINS).size(), "筆數");
            eq(12, roll.top(Category.WINS).get(0).wins(), "最高");
            eq(3, roll.top(Category.WINS).get(9).wins(), "第十名");
            check(roll.add(record("太低", 1, 0, 0, 0)).isEmpty(), "太低不上榜");
        });

        test("數值相同時先達成的排前面，名額滿了後來的不擠掉", () -> {
            HonorRoll roll = new HonorRoll();
            for (int i = 0; i < 10; i++) {
                roll.add(record("早" + i, 5, 0, 0, 0));
            }
            check(roll.add(record("晚", 5, 0, 0, 0)).isEmpty(), "同分不擠掉");
            eq("早0", roll.top(Category.WINS).get(0).name(), "最早的在最前面");
        });

        test("各類別獨立排名，0 以下不列入", () -> {
            HonorRoll roll = new HonorRoll();
            Map<Category, Integer> ranks = roll.add(record("甲", 2, 1, -300, 4));
            eq(Map.of(Category.WINS, 1, Category.SELF_DRAWS, 1, Category.DEAL_INS, 1), ranks, "輸錢不上贏錢榜");
            check(roll.top(Category.WINNINGS).isEmpty(), "贏錢榜是空的");

            roll.add(record("乙", 0, 0, 900, 0));
            eq("乙", roll.top(Category.WINNINGS).get(0).name(), "贏錢榜");
            eq(1, roll.top(Category.WINS).size(), "胡牌榜不受影響");
        });

        test("存檔後可以讀回來，包含特殊字元的姓名", () -> {
            Path dir = Files.createTempDirectory("ezmj-honor");
            Path nested = dir.resolve("ezmj");
            Path file = nested.resolve("honor.properties");
            try {
                HonorRoll roll = new HonorRoll();
                roll.add(record("王<小>明&=:", 7, 2, 1500, 3));
                roll.add(record("歐陽忠義", 9, 1, -100, 1));
                roll.save(file);
                check(!Files.exists(nested.resolve("honor.properties.tmp")), "暫存檔要換掉");

                HonorRoll loaded = HonorRoll.load(file);
                for (Category category : Category.values()) {
                    eq(roll.top(category), loaded.top(category), category.label());
                }
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(nested);
                Files.delete(dir);
            }
        });

        test("重開遊戲：舊紀錄還在，新成績接著排，再重開也都在", () -> {
            Path dir = Files.createTempDirectory("ezmj-honor");
            Path file = dir.resolve("honor.properties");
            try {
                HonorRoll first = HonorRoll.load(file);
                first.add(record("第一次", 5, 1, 300, 2));
                first.save(file);

                HonorRoll second = HonorRoll.load(file);
                eq("第一次", second.top(Category.WINS).get(0).name(), "重開後還在");
                eq(Map.of(Category.WINS, 1, Category.SELF_DRAWS, 1, Category.WINNINGS, 1, Category.DEAL_INS, 1),
                        second.add(record("第二次", 8, 2, 900, 3)), "新成績拿到名次");
                second.save(file);

                List<GameRecord> wins = HonorRoll.load(file).top(Category.WINS);
                eq(2, wins.size(), "兩筆都在");
                eq("第二次", wins.get(0).name(), "第一名");
                eq("第一次", wins.get(1).name(), "第二名");
            } finally {
                Files.deleteIfExists(file);
                Files.delete(dir);
            }
        });

        test("三家等級不同：存檔讀回來順序不變；舊檔只有一個等級就是三家都一樣", () -> {
            Path dir = Files.createTempDirectory("ezmj-honor");
            Path file = dir.resolve("honor.properties");
            try {
                List<BotLevel> mixed = List.of(BotLevel.PRO, BotLevel.BEGINNER, BotLevel.AMATEUR);
                GameRecord record = new GameRecord("甲", "2026-11-01", 3, 1, 100, 1, mixed, 100, 20);
                eq("職業／初學者／業餘", record.botLevelsLabel(), "不同時依座位列出");
                eq("職業", record("乙", 1, 0, 0, 0).botLevelsLabel(), "相同時顯示一個");

                HonorRoll roll = new HonorRoll();
                roll.add(record);
                roll.save(file);
                eq(mixed, HonorRoll.load(file).top(Category.WINS).get(0).botLevels(), "讀回來");

                Files.writeString(file, String.join("\n", List.of(
                        "WINS.0.name=old", "WINS.0.date=2026-10-31", "WINS.0.wins=3", "WINS.0.selfDraws=1",
                        "WINS.0.winnings=10", "WINS.0.dealIns=0", "WINS.0.botLevel=AMATEUR", "WINS.0.basePay=100",
                        "WINS.0.taiPay=20",
                        "WINS.1.name=twoLevels", "WINS.1.date=2026-10-31", "WINS.1.wins=2", "WINS.1.selfDraws=1",
                        "WINS.1.winnings=10", "WINS.1.dealIns=0", "WINS.1.botLevel=PRO,PRO", "WINS.1.basePay=100",
                        "WINS.1.taiPay=20")));
                List<GameRecord> wins = HonorRoll.load(file).top(Category.WINS);
                eq(1, wins.size(), "不是三家的略過");
                eq(List.of(BotLevel.AMATEUR, BotLevel.AMATEUR, BotLevel.AMATEUR), wins.get(0).botLevels(), "舊檔");
            } finally {
                Files.deleteIfExists(file);
                Files.delete(dir);
            }
        });

        test("檔案不存在或內容壞掉時，只略過讀不懂的紀錄", () -> {
            Path dir = Files.createTempDirectory("ezmj-honor");
            Path file = dir.resolve("honor.properties");
            try {
                check(HonorRoll.load(file).top(Category.WINS).isEmpty(), "沒有檔案");

                Files.writeString(file, String.join("\n", List.of(
                        "WINS.0.name=good", "WINS.0.date=2026-10-31", "WINS.0.wins=3", "WINS.0.selfDraws=1",
                        "WINS.0.winnings=10", "WINS.0.dealIns=0", "WINS.0.botLevel=PRO", "WINS.0.basePay=100",
                        "WINS.0.taiPay=20",
                        "WINS.1.name=bad", "WINS.1.date=2026-10-31", "WINS.1.wins=abc",
                        "WINS.2.name=badLevel", "WINS.2.date=2026-10-31", "WINS.2.wins=1", "WINS.2.selfDraws=1",
                        "WINS.2.winnings=1", "WINS.2.dealIns=0", "WINS.2.botLevel=GOD", "WINS.2.basePay=1",
                        "WINS.2.taiPay=1")));
                List<GameRecord> wins = HonorRoll.load(file).top(Category.WINS);
                eq(1, wins.size(), "只留下好的那筆");
                eq("good", wins.get(0).name(), "姓名");

                Files.writeString(file, "WINS.0.name=\\u00zz\n");
                check(HonorRoll.load(file).top(Category.WINS).isEmpty(), "整個檔案壞掉");
            } finally {
                Files.deleteIfExists(file);
                Files.delete(dir);
            }
        });
    }
}
