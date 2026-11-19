package ezmj.engine;

import ezmj.model.Dice;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Wall;

/**
 * 畫面上的四面牌牆。每家面前一面 18 墩，每墩上下兩張，共 144 張。
 * 墩的編號照順時針排：從自己面前的右端往左數，接著是上家、對家、下家，和真的牌桌一樣是圍成一圈。
 * <p>
 * 開門照台灣的習慣：莊家擲三顆骰子，從莊家起照行牌順序數點數，數到的那家從他牌牆的右端數過點數那麼多墩，
 * 牌頭從下一墩開始往順時針拿，牌尾是開門處的另一邊，往逆時針拿。每墩先拿上面那張。
 * 引擎的 {@link Wall} 就是照這樣從牌牆上的位置拿牌，這裡用同一套算法，把局面畫成牌牆。
 * 連線時客戶端看不到牌牆，用服務器送的骰子算開門處，再從局面推回牌頭與牌尾各拿了幾張。
 */
public final class WallRing {

    public static final int SEATS = Wall.SEATS;
    public static final int STACKS_PER_SIDE = Wall.STACKS_PER_SIDE;
    public static final int STACKS = Wall.STACKS;
    public static final int TILES = Wall.TILES;

    private final int openSeat;
    private final int start;

    public WallRing(int dealer, Dice dice) {
        openSeat = Wall.openSeat(dealer, dice.total());
        start = Wall.openingStack(dealer, dice);
    }

    /** 從莊家起照行牌順序數點數，莊家是 1，數到的那家開門。 */
    public static int openSeat(int dealer, int total) {
        return Wall.openSeat(dealer, total);
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
        return Wall.sideStart(seat) + column;
    }

    /** 牌頭第一墩的編號。 */
    public int start() {
        return start;
    }

    /** 牌頭第 index 張（從 0 起）在哪一墩。 */
    public int headStack(int index) {
        return Wall.headStack(start, index);
    }

    /** 牌尾第 index 張（從 0 起）在哪一墩。 */
    public int tailStack(int index) {
        return Wall.tailStack(start, index);
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
     * 連線時客戶端只看得到這些，單機也用同一套，兩邊畫出來才一樣。
     * 搶槓、八張花亮完不必補這些少見的情況差一兩張也只是畫面，所以都夾在合理的範圍內。
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
