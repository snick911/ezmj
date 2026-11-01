package ezmj.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** 牌牆。一般摸牌從牌頭、補花與補槓從牌尾，並保留固定張數不摸（留牌）。 */
public final class Wall {

    /** 台灣麻將慣例留 16 張（8 墩）。 */
    public static final int RESERVED = 16;

    private final Deque<Tile> tiles;

    public Wall(Random random) {
        List<Tile> shuffled = new ArrayList<>(Tile.fullSet());
        Collections.shuffle(shuffled, random);
        this.tiles = new ArrayDeque<>(shuffled);
    }

    /** 以指定順序建立牌牆，供測試使用。 */
    public Wall(Collection<Tile> ordered) {
        this.tiles = new ArrayDeque<>(ordered);
    }

    /** 從牌頭到牌尾的順序，存檔用。 */
    public List<Tile> order() {
        return new ArrayList<>(tiles);
    }

    public Tile drawFront() {
        return tiles.removeFirst();
    }

    public Tile drawBack() {
        return tiles.removeLast();
    }

    public int size() {
        return tiles.size();
    }

    /** 還可以摸的張數；歸零即流局。 */
    public int liveCount() {
        return Math.max(0, tiles.size() - RESERVED);
    }
}
