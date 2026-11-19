package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.hand;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ezmj.engine.WallRing;
import ezmj.model.Dice;
import ezmj.model.Meld;
import ezmj.model.Suit;
import ezmj.model.Tile;
import ezmj.model.Wall;

/** 牌組、牌牆與版號。 */
final class TilesTest {

    private TilesTest() {
    }

    static void run() {
        T.section("牌組與牌牆");

        test("一副牌 144 張：數牌與字牌各 4 張、花牌各 1 張", () -> {
            List<Tile> all = Tile.fullSet();
            eq(144, all.size(), "總張數");
            for (int i = 0; i < Tile.KINDS; i++) {
                eq(4, Collections.frequency(all, Tile.fromIndex(i)), Tile.fromIndex(i) + " 張數");
            }
            for (int rank = 1; rank <= 8; rank++) {
                eq(1, Collections.frequency(all, Tile.of(Suit.FLOWER, rank)), "花牌 " + rank);
            }
        });

        test("牌種索引可以來回轉換且不重複", () -> {
            for (int i = 0; i < Tile.KINDS; i++) {
                eq(i, Tile.fromIndex(i).index(), "索引");
            }
            eq(0, tile("1w").index(), "一萬");
            eq(9, tile("1p").index(), "一筒");
            eq(18, tile("1s").index(), "一條");
            eq(27, tile("1z").index(), "東");
            eq(31, tile("5z").index(), "中");
            eq(33, tile("7z").index(), "白");
        });

        test("牌的分類與名稱", () -> {
            check(tile("5w").isNumber() && !tile("5w").isHonor() && !tile("5w").isFlower(), "五萬是數牌");
            check(tile("1z").isHonor() && !tile("1z").isNumber(), "東是字牌");
            check(tile("6z").isHonor(), "發是字牌");
            check(tile("1f").isFlower() && !tile("1f").isHonor(), "春是花牌");
            eq("五萬", tile("5w").label(), "名稱");
            eq("九筒", tile("9p").label(), "名稱");
            eq("一條", tile("1s").label(), "名稱");
            eq("北", tile("4z").label(), "名稱");
            eq("發", tile("6z").label(), "名稱");
            eq("菊", tile("8f").label(), "名稱");
        });

        test("手牌依 萬、筒、條、風、三元 排序", () -> {
            List<Tile> tiles = new ArrayList<>(hand("5z1z9s1p2w1w"));
            Collections.sort(tiles);
            eq(hand("1w2w1p9s1z5z"), tiles, "排序");
        });

        test("牌牆洗牌不增減牌，同種子結果相同", () -> {
            Wall a = Wall.shuffled(new Random(7), 0);
            Wall b = Wall.shuffled(new Random(7), 0);
            eq(a.dice(), b.dice(), "同種子擲出一樣的骰子");
            List<Tile> drawn = new ArrayList<>();
            while (a.size() > 0) {
                Tile tile = a.drawFront();
                eq(tile, b.drawFront(), "同種子");
                drawn.add(tile);
            }
            Collections.sort(drawn);
            List<Tile> expected = new ArrayList<>(Tile.fullSet());
            Collections.sort(expected);
            eq(expected, drawn, "洗牌後內容");
        });

        test("不同種子洗出不同順序", () -> {
            Wall a = Wall.shuffled(new Random(1), 0);
            Wall b = Wall.shuffled(new Random(2), 0);
            boolean differs = false;
            while (a.size() > 0) {
                differs |= !a.drawFront().equals(b.drawFront());
            }
            check(differs, "洗牌應該打亂順序");
        });

        test("牌頭與牌尾各自摸牌，留 16 張不可摸", () -> {
            Wall wall = new Wall(hand("1w2w3w"));
            eq(tile("1w"), wall.drawFront(), "牌頭");
            eq(tile("3w"), wall.drawBack(), "牌尾");
            eq(0, wall.liveCount(), "不足留牌時可摸張數為 0");
            eq(128, Wall.shuffled(new Random(1), 0).liveCount(), "整副牌可摸張數");
            eq(16, Wall.RESERVED, "留牌張數");
        });

        test("牌牆照骰子開門：牌頭從開門處往順時針，牌尾往逆時針，每墩先拿上面", () -> {
            List<Tile> stacked = new ArrayList<>(Tile.fullSet());
            Collections.shuffle(stacked, new Random(5));
            // 莊家是座位 0，11 點數到對家（座位 2），對家的牌牆從第 36 墩開始，右端數過 11 墩，牌頭是第 47 墩
            Wall wall = new Wall(stacked, 0, new Dice(4, 5, 2));
            eq(47, wall.start(), "開門處");
            eq(stacked.get(94), wall.drawFront(), "牌頭第 1 張：第 47 墩上面");
            eq(stacked.get(95), wall.drawFront(), "牌頭第 2 張：第 47 墩下面");
            eq(stacked.get(96), wall.drawFront(), "牌頭第 3 張：第 48 墩上面");
            eq(stacked.get(92), wall.drawBack(), "牌尾第 1 張：第 46 墩上面");
            eq(stacked.get(93), wall.drawBack(), "牌尾第 2 張：第 46 墩下面");
            eq(stacked.get(90), wall.drawBack(), "牌尾第 3 張：第 45 墩上面");
            eq(3, wall.headTaken(), "牌頭拿了 3 張");
            eq(3, wall.tailTaken(), "牌尾拿了 3 張");
            eq(138, wall.size(), "剩下的張數");
            List<Tile> left = wall.order();
            eq(stacked.get(97), left.get(0), "剩下的從牌頭那一墩的下面開始");
            eq(stacked.get(91), left.get(left.size() - 1), "到牌尾那一墩的下面結束");
            eq(stacked.get(0), left.get(47), "繞過第 71 墩接回第 0 墩的上面");
            eq(stacked.get(1), left.get(48), "再來是第 0 墩的下面");
        });

        test("同一面牌牆，骰子不同就從不同的地方開門，拿到不同的牌", () -> {
            List<Tile> stacked = new ArrayList<>(Tile.fullSet());
            Collections.shuffle(stacked, new Random(8));
            for (int dealer = 0; dealer < 4; dealer++) {
                Set<Integer> starts = new HashSet<>();
                for (int total = 3; total <= 18; total++) {
                    Dice dice = new Dice(Math.min(6, total - 2), Math.max(1, Math.min(6, total - 7)), Math.max(1, total - 12));
                    eq(total, dice.total(), "擲出 " + total + " 點");
                    Wall wall = new Wall(stacked, dealer, dice);
                    int start = Wall.openingStack(dealer, dice);
                    eq(new WallRing(dealer, dice).start(), start, "畫面和引擎從同一墩開門");
                    eq(stacked.get(start * 2), wall.drawFront(), dealer + " 當莊、" + total + " 點：牌頭第一張");
                    eq(stacked.get(Math.floorMod(start - 1, 72) * 2), wall.drawBack(), dealer + " 當莊、" + total + " 點：牌尾第一張");
                    starts.add(start);
                }
                eq(16, starts.size(), dealer + " 當莊：3 到 18 點各從不同的墩開門");
            }
        });

        test("牌牆接回：照存的兩頭張數接著拿，牌頭與牌尾不能重疊", () -> {
            List<Tile> stacked = new ArrayList<>(Tile.fullSet());
            Collections.shuffle(stacked, new Random(9));
            Wall played = new Wall(stacked, 2, new Dice(3, 3, 6));
            for (int i = 0; i < 70; i++) {
                played.drawFront();
            }
            for (int i = 0; i < 9; i++) {
                played.drawBack();
            }
            Wall resumed = Wall.resumed(stacked, 2, new Dice(3, 3, 6), 70, 9);
            eq(played.order(), resumed.order(), "剩下的牌一樣");
            eq(played.drawFront(), resumed.drawFront(), "接著從牌頭拿");
            eq(played.drawBack(), resumed.drawBack(), "接著從牌尾拿");
            T.fails(IllegalArgumentException.class, () -> Wall.resumed(stacked, 2, new Dice(3, 3, 6), 141, 3), "重疊");
            T.fails(IllegalArgumentException.class, () -> new Wall(stacked.subList(0, 143), 2, new Dice(3, 3, 6)), "少一張");
        });

        test("碰可以升級成明槓", () -> {
            Meld kong = new Meld(Meld.Type.PONG, hand("5w5w5w")).upgradedToKong();
            eq(Meld.Type.OPEN_KONG, kong.type(), "類型");
            eq(4, kong.tiles().size(), "張數");
        });

        test("版號是 x.y.z 格式且不是預設值", () -> {
            String version = AppVersion.get();
            check(version.matches("\\d+\\.\\d+\\.\\d+"), "版號格式: " + version);
        });
    }
}
