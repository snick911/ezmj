package ezmj.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * 牌牆，照真的牌桌來：洗好的 144 張疊成四面牌牆，每家面前 18 墩、每墩上下兩張，圍成一圈；
 * 莊家擲三顆骰子，從莊家起照行牌順序數點數，數到的那家從他牌牆的右端數過點數那麼多墩，這裡就是開門處。
 * 牌頭從開門處的下一墩往順時針一墩一墩拿，牌尾從開門處往逆時針拿，每墩都先拿上面那張。
 * 一般摸牌從牌頭、補花與補槓從牌尾，最後留固定張數不摸（留牌）。
 * <p>
 * 墩的編號照順時針排：從座位 0 面前的右端往左數，接著是座位 3、2、1 的牌牆（行牌順序是逆時針，
 * 所以自己之後先接上家）。第 s 墩上面那張的位置是 2s，下面那張是 2s + 1。
 * <p>
 * 測試可以用 {@link #Wall(Collection)} 直接指定摸牌的順序，這時沒有墩與骰子，牌頭從第一張、牌尾從最後一張拿。
 */
public final class Wall {

    /** 台灣麻將慣例留 16 張（8 墩）。 */
    public static final int RESERVED = 16;
    public static final int SEATS = 4;
    public static final int STACKS_PER_SIDE = 18;
    public static final int STACKS = STACKS_PER_SIDE * SEATS;
    public static final int TILES = STACKS * 2;

    /** 牌牆上每個位置的牌，拿走了也留著，存檔要記整面牌牆。照劇本排的牌牆就是摸牌的順序。 */
    private final Tile[] slots;
    private final boolean[] taken;
    /** 開門的骰子。照劇本排的牌牆沒有骰子。 */
    private final Dice dice;
    /** 牌頭第一墩的編號。 */
    private final int start;
    private int head;
    private int tail;

    /** 洗牌、疊成牌牆，莊家擲骰子開門。和真的牌桌一樣先疊好牌牆才擲骰子。 */
    public static Wall shuffled(Random random, int dealer) {
        List<Tile> tiles = new ArrayList<>(Tile.fullSet());
        Collections.shuffle(tiles, random);
        return new Wall(tiles, dealer, Dice.roll(random));
    }

    /** stacked 是疊好的牌牆，第 i 張在位置 i（見類別說明）；莊家擲出 dice 開門，還沒拿過牌。 */
    public Wall(List<Tile> stacked, int dealer, Dice dice) {
        this(stacked, dealer, dice, 0, 0);
    }

    /** 接回存檔：牌頭已經拿了 head 張、牌尾 tail 張。 */
    public static Wall resumed(List<Tile> stacked, int dealer, Dice dice, int head, int tail) {
        return new Wall(stacked, dealer, dice, head, tail);
    }

    private Wall(List<Tile> stacked, int dealer, Dice dice, int head, int tail) {
        if (stacked.size() != TILES) {
            throw new IllegalArgumentException("牌牆要有 " + TILES + " 張：" + stacked.size());
        }
        if (head < 0 || tail < 0 || head + tail > TILES) {
            throw new IllegalArgumentException("拿走的張數不對：牌頭 " + head + "、牌尾 " + tail);
        }
        this.slots = stacked.toArray(new Tile[0]);
        this.taken = new boolean[TILES];
        this.dice = dice;
        this.start = openingStack(dealer, dice);
        for (int i = 0; i < head; i++) {
            mark(headSlot(i));
        }
        for (int i = 0; i < tail; i++) {
            if (taken[tailSlot(i)]) {
                throw new IllegalArgumentException("牌頭與牌尾拿到同一張：牌頭 " + head + "、牌尾 " + tail);
            }
            mark(tailSlot(i));
        }
        this.head = head;
        this.tail = tail;
    }

    /** 以指定順序建立牌牆，供測試使用：牌頭從第一張拿，牌尾從最後一張拿。 */
    public Wall(Collection<Tile> ordered) {
        this.slots = ordered.toArray(new Tile[0]);
        this.taken = new boolean[slots.length];
        this.dice = null;
        this.start = 0;
    }

    /** 從莊家起照行牌順序數點數，莊家是 1，數到的那家開門。 */
    public static int openSeat(int dealer, int total) {
        return Math.floorMod(dealer + total - 1, SEATS);
    }

    /** 這家牌牆最右邊那一墩的編號。 */
    public static int sideStart(int seat) {
        return Math.floorMod(-seat, SEATS) * STACKS_PER_SIDE;
    }

    /** 牌頭第一墩：開門那家的牌牆從右端數過點數那麼多墩，再下一墩。點數超過 18 就數到順時針的下一面。 */
    public static int openingStack(int dealer, Dice dice) {
        return Math.floorMod(sideStart(openSeat(dealer, dice.total())) + dice.total(), STACKS);
    }

    /** 牌頭第 index 張（從 0 起）在哪一墩：從牌頭第一墩往順時針。 */
    public static int headStack(int start, int index) {
        return Math.floorMod(start + index / 2, STACKS);
    }

    /** 牌尾第 index 張（從 0 起）在哪一墩：從開門處往逆時針。 */
    public static int tailStack(int start, int index) {
        return Math.floorMod(start - 1 - index / 2, STACKS);
    }

    private int headSlot(int index) {
        return dice == null ? index : headStack(start, index) * 2 + index % 2;
    }

    private int tailSlot(int index) {
        return dice == null ? slots.length - 1 - index : tailStack(start, index) * 2 + index % 2;
    }

    private void mark(int slot) {
        taken[slot] = true;
    }

    public Tile drawFront() {
        requireTiles();
        return take(headSlot(head++));
    }

    public Tile drawBack() {
        requireTiles();
        return take(tailSlot(tail++));
    }

    private void requireTiles() {
        if (size() == 0) {
            throw new NoSuchElementException("牌牆已經拿完了");
        }
    }

    /** 牌頭與牌尾在牌牆上相遇之前一定會先流局，這裡只是保險。 */
    private Tile take(int slot) {
        if (taken[slot]) {
            throw new IllegalStateException("這個位置的牌已經拿走了：" + slot);
        }
        mark(slot);
        return slots[slot];
    }

    /** 還沒拿走的牌，從牌頭到牌尾：從牌頭那一墩往順時針到牌尾那一墩，每墩先上後下。存檔與流局翻牌用。 */
    public List<Tile> order() {
        List<Tile> left = new ArrayList<>(size());
        for (int i = head; i < slots.length; i++) {
            int slot = headSlot(i);
            if (!taken[slot]) {
                left.add(slots[slot]);
            }
        }
        return left;
    }

    /** 整面牌牆，拿走的也在，存檔用。照劇本排的牌牆是摸牌的順序。 */
    public List<Tile> stacked() {
        return Collections.unmodifiableList(Arrays.asList(slots.clone()));
    }

    /** 開門的骰子；照劇本排的牌牆是 null。 */
    public Dice dice() {
        return dice;
    }

    /** 牌頭第一墩的編號。 */
    public int start() {
        return start;
    }

    /** 牌頭拿了幾張。 */
    public int headTaken() {
        return head;
    }

    /** 牌尾拿了幾張。 */
    public int tailTaken() {
        return tail;
    }

    public int size() {
        return slots.length - head - tail;
    }

    /** 還可以摸的張數；歸零即流局。 */
    public int liveCount() {
        return Math.max(0, size() - RESERVED);
    }
}
