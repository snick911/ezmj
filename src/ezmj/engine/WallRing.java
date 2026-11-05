package ezmj.engine;

import ezmj.model.Dice;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Wall;

/**
 * 牌桌上的四面牌牆，只給畫面用。每家面前一面 18 墩，每墩上下兩張，共 144 張。
 * 墩的編號照順時針排：從自己面前的右端往左數，接著是上家、對家、下家，和真的牌桌一樣是圍成一圈。
 *
 * 開門照台灣的習慣：莊家擲三顆骰子，從莊家起照行牌順序數點數，數到的那家從他牌牆的右端數過點數那麼多墩，
 * 牌頭從下一墩開始往順時針拿，牌尾是開門處的另一邊，往逆時針拿。每墩先拿上面那張。
 * 引擎的 Wall 只記牌頭與牌尾，洗好的牌從哪裡開門拿到什麼牌的機率都一樣，所以位置由這裡算，不必送給服務器。
 */
public final class WallRing {

    public static final int SEATS = 4;
    public static final int STACKS_PER_SIDE = 18;
    public static final int STACKS = STACKS_PER_SIDE * SEATS;
    public static final int TILES = STACKS * 2;

    private final int openSeat;
    private final int start;

    public WallRing(int dealer, Dice dice) {
        openSeat = openSeat(dealer, dice.total());
        start = Math.floorMod(sideStart(openSeat) + dice.total(), STACKS);
    }

    /** 從莊家起照行牌順序數點數，莊家是 1，數到的那家開門。 */
    public static int openSeat(int dealer, int total) {
        return Math.floorMod(dealer + total - 1, SEATS);
    }

    /** 開門的是哪一家的牌牆。 */
    public int openSeat() {
        return openSeat;
    }

    /** 順時針第 stack 墩在誰面前。行牌順序是逆時針，所以自己之後先接上家。 */
    public static int side(int stack) {
        return Math.floorMod(-(Math.floorMod(stack, STACKS) / STACKS_PER_SIDE), SEATS);
    }

    /** 第 stack 墩是那家牌牆從右端數過來的第幾墩，0 是最右邊。 */
    public static int column(int stack) {
        return Math.floorMod(stack, STACKS) % STACKS_PER_SIDE;
    }

    /** 這家牌牆從右端數過來第 column 墩的編號，和 side、column 相反。 */
    public static int stack(int seat, int column) {
        return sideStart(seat) + column;
    }

    /** 這家牌牆最右邊那一墩的編號。 */
    private static int sideStart(int seat) {
        return Math.floorMod(-seat, SEATS) * STACKS_PER_SIDE;
    }

    /** 牌頭第一墩的編號。 */
    public int start() {
        return start;
    }

    /** 牌頭第 index 張（從 0 起）在哪一墩。 */
    public int headStack(int index) {
        return Math.floorMod(start + index / 2, STACKS);
    }

    /** 牌尾第 index 張（從 0 起）在哪一墩。 */
    public int tailStack(int index) {
        return Math.floorMod(start - 1 - index / 2, STACKS);
    }

    /** 牌頭拿走 head 張、牌尾拿走 tail 張之後，第 stack 墩還剩幾張：2、1 或 0。剩 1 張時剩的是下面那張。 */
    public int remaining(int stack, int head, int tail) {
        int fromHead = Math.floorMod(stack - start, STACKS);
        int fromTail = STACKS - 1 - fromHead;
        int taken = clamp(head - fromHead * 2) + clamp(tail - fromTail * 2);
        return Math.max(0, 2 - taken);
    }

    private static int clamp(int taken) {
        return Math.max(0, Math.min(2, taken));
    }

    /** 紀錄上的一行，例如「阿土 擲出 4、5、2，共 11 點，從小美的牌牆開門」。Swing 與網頁版都用這個。 */
    public static String announce(RoundView round, Dice dice) {
        WallRing ring = new WallRing(round.dealer(), dice);
        return round.player(round.dealer()).name() + " 擲出 " + dice.describe() + "，從"
                + round.player(ring.openSeat()).name() + "的牌牆開門";
    }

    /** 從局面看得出來牌頭與牌尾各拿過幾張。 */
    public record Taken(int head, int tail) {
    }

    /**
     * 剩下的張數看得到，每亮一張花、每開一槓都從牌尾補一張，其他都是從牌頭拿的。
     * 開局補花、搶槓這些少見的情況差一兩張也只是畫面，所以都夾在合理的範圍內。
     */
    public static Taken taken(RoundView round) {
        int total = Math.max(0, Math.min(TILES, TILES - round.liveTiles() - Wall.RESERVED));
        int tail = 0;
        for (int seat = 0; seat < SEATS; seat++) {
            Player player = round.player(seat);
            tail += player.flowers().size();
            for (Meld meld : player.melds()) {
                if (meld.tiles().size() == 4) {
                    tail++;
                }
            }
        }
        tail = Math.min(tail, total);
        return new Taken(total - tail, tail);
    }
}
