package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.hand;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
            Wall a = new Wall(new Random(7));
            Wall b = new Wall(new Random(7));
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
            Wall a = new Wall(new Random(1));
            Wall b = new Wall(new Random(2));
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
            eq(128, new Wall(new Random(1)).liveCount(), "整副牌可摸張數");
            eq(16, Wall.RESERVED, "留牌張數");
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
