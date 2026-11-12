package ezmj.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.Timer;

import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundResult;
import ezmj.engine.RoundView;
import ezmj.engine.WallRing;
import ezmj.model.Dice;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;

/**
 * 牌桌畫面。四家共用同一套繪製程式：先把座標旋轉到該家視角，
 * 再以「自己坐在下方」的區域座標繪製，所以棄牌會自然排成風車狀。
 */
final class TableCanvas extends JComponent {

    static final int DISCARD_FLIGHT_MS = 190;
    static final int CLAIM_FLIGHT_MS = 240;
    static final int DRAW_FLIGHT_MS = 170;
    private static final int FLOWER_FLIGHT_MS = 260;
    private static final int FLOWER_BANNER_MS = 650;
    /** 胡別家打出的牌，流局也是這麼久。 */
    static final int WIN_BANNER_MS = 1100;
    static final int SELF_DRAW_BANNER_MS = 1400;
    /** 大胡，見 RoundResult.isBigWin。 */
    static final int BIG_WIN_BANNER_MS = 2400;
    /** 連莊時擲骰子前「連莊」大字演多久，演完才擲骰子。 */
    static final int STREAK_BANNER_MS = 1500;
    /** 胡牌時贏家的手牌從左到右一張一張推倒翻開：每張隔這麼久開始，每張翻這麼久。 */
    private static final int PUSH_INTERVAL_MS = 35;
    private static final int PUSH_TILE_MS = 240;
    /** 胡的那張翻開之後，外面的金光擴散多久。 */
    private static final int WIN_GLOW_MS = 650;
    /** 結算時籌碼從付錢的人飛向贏家、各家跳出輸贏，整段演這麼久。 */
    static final int CHIPS_MS = 1200;
    /** 一顆籌碼飛多久；第一顆落地時贏家的數字跳出來，計分表開始滾。 */
    static final int CHIP_FLIGHT_MS = 450;
    /** 計分表的籌碼從舊的數目滾到新的數目要多久。 */
    static final int CHIP_ROLL_MS = 550;
    /** 每一家付錢的人飛幾顆籌碼，一顆隔多久。 */
    static final int CHIPS_PER_PAYER = 5;
    static final int CHIP_GAP_MS = 60;
    /** 不同付錢的人的籌碼錯開這麼久起飛。 */
    private static final int PAYER_GAP_MS = 40;
    private static final Color CHIP_DARK = new Color(0xB0, 0x84, 0x2A);
    private static final Color CHIP_RIM = new Color(0x7A, 0x55, 0x12);
    private static final int SPARKS = 40;
    private static final double GOLDEN_ANGLE = 2.39996323;
    private static final Color BIG_WIN_TOP = new Color(0xC9, 0x3A, 0x32, 235);
    private static final Color BIG_WIN_BOTTOM = new Color(0x6E, 0x12, 0x0E, 235);
    /** 報聽時「聽」標記彈出來、外面兩個圈擴散的時間。 */
    static final int LISTEN_BADGE_MS = 900;
    /** 吃碰槓後要打牌時，手牌外框一明一暗的週期。 */
    static final int PROMPT_PULSE_MS = 1000;
    /** 「聽」標記：紅底、金色圓框、白字。 */
    static final Color LISTEN_FILL = new Color(0xC9, 0x3A, 0x32);
    /**
     * 開局先擲骰子：三顆骰子從莊家面前滾到中間，DICE_ROLL_MS 停住，亮出點數與開門的位置，
     * DICE_MS 之後才從牌牆發牌。DICE_BOUNCE_MS 是骰子落地出聲的時間。連線時服務器留 3 秒，骰子加發牌要在這之內。
     */
    static final int DICE_ROLL_MS = 650;
    static final int DICE_MS = 1100;
    static final int[] DICE_BOUNCE_MS = {300, 480, 620};
    /** 發牌開始後骰子淡出、中央的字淡入的時間。 */
    private static final int DICE_FADE_MS = 300;
    /** 開門位置的金線，發牌開始後再留這麼久。 */
    private static final int OPEN_MARK_MS = 600;
    private static final double DICE_SIZE = 26;
    private static final Color DIE_FACE = new Color(0xF8, 0xF3, 0xE6);
    private static final Color DIE_EDGE = new Color(0x8A, 0x7F, 0x6A);
    private static final Color DIE_PIP = new Color(0x1E, 0x23, 0x2B);
    /** 骰子各點的位置，以一面的中心為原點，單位是點距。 */
    private static final double[][][] PIPS = {
        {},
        {{0, 0}},
        {{-1, -1}, {1, 1}},
        {{-1, -1}, {0, 0}, {1, 1}},
        {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}},
        {{-1, -1}, {1, -1}, {0, 0}, {-1, 1}, {1, 1}},
        {{-1, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {1, 1}},
    };
    /** 發牌每四張一批輪流發，最後一批是莊家的第 17 張。 */
    static final int DEAL_PACKETS = 17;
    static final int DEAL_FLIGHT_MS = 200;
    static final int DEAL_INTERVAL_MS = 70;
    static final int DEAL_MS = (DEAL_PACKETS - 1) * DEAL_INTERVAL_MS + DEAL_FLIGHT_MS;
    /** 發牌從牌頭拿走的張數：每家 16 張，莊家多一張。 */
    private static final int DEALT_TILES = (DEAL_PACKETS - 1) * 4 + 1;

    /** 牌牆在中央方框四邊名字的外面，深度裡上下兩張錯開，上面那張往中間偏。 */
    private static final double WALL_TOP = 103;
    private static final double WALL_DEPTH = 17;
    private static final int DISCARD_COLUMNS = 8;
    /**
     * 棄牌的大小。一排的一半寬（DISCARD_COLUMNS × DISCARD_W / 2）要小於 DISCARD_TOP，
     * 相鄰兩家的棄牌在角落才不會疊在一起，所以 8 張一排時寬最多到 30。
     */
    private static final double DISCARD_W = 30;
    private static final double DISCARD_H = 40;
    /** 棄牌排在牌牆外面。 */
    private static final double DISCARD_TOP = 123;
    private static final double FLOWER_W = 22;
    private static final double FLOWER_H = 30;
    private static final int FRAME_MS = 16;
    private static final int RING_MS = 170;
    private static final int CLAIM_BANNER_MS = 800;
    /** 牌桌上方的公告停多久、淡入淡出多久，外框發光幾次、每次多久。和網頁版的 .table-announce 一樣。 */
    private static final int ANNOUNCE_MS = 8_000;
    private static final int ANNOUNCE_IN_MS = 350;
    private static final int ANNOUNCE_OUT_MS = 600;
    private static final int ANNOUNCE_GLOWS = 3;
    private static final int ANNOUNCE_GLOW_MS = 1_200;
    private static final double TILE_RATIO = 1.38;
    /** 寬度不夠時手牌可以互相疊住，牌最多畫成間距的這個倍數，牌面的字還看得清楚。 */
    private static final double MAX_OVERLAP = 1.22;
    /** 手牌列至少要留這麼多格，張數少的時候牌也不會忽大忽小。 */
    private static final double MIN_ROW_SLOTS = 18;
    private static final double MAX_TABLE_SCALE = 1.25;
    /** 麻將放到很大、又留不下三排棄牌時，中央最多縮到這麼小，棄牌才不會壓到自己的手牌。 */
    private static final double MIN_TABLE_SCALE = .9;
    private static final int DISCARD_ROWS_TO_FIT = 3;

    private record HandHit(Rectangle bounds, Tile tile) {
    }

    /** 一家手牌列的位置，都是該家視角的座標。step 是相鄰兩張的間距，比 tileW 小時牌會互相疊住。 */
    private record RowLayout(double x, double y, double tileW, double tileH, double step, double gap, double total) {
    }

    /**
     * 一面牌牆的位置，該家視角。牌牆從 top 往外 depth 那麼深，左右從 -top 到 top + depth，
     * 四面像風車一樣各佔一個角，彼此不重疊。每墩寬 stackW，牌寬 tileW、深 tileH，上面那張往中間錯開 lift。
     */
    private record WallLayout(double top, double depth, double stackW, double tileW, double tileH, double lift) {
        /** 從右端數過來第 column 墩的左邊。 */
        double stackX(int column) {
            return top + depth - (column + 1) * stackW + (stackW - tileW) / 2;
        }
    }

    /** 飛行中的牌要去的地方。 */
    private enum Slot { DISCARD, MELD, DRAWN, FLOWER }

    /**
     * 飛行中的一張牌。位置以牌桌中心為原點，指牌的中心點。
     * 飛行時對應的那張牌不畫出來，落地後才出現。slotIndex 是面子或花牌裡的第幾張，其他地方用不到。
     */
    private record Flight(Tile tile, boolean faceDown, double fromX, double fromY, double fromAngle, double fromW,
                          double toX, double toY, double toAngle, double toW, long start, int durationMs,
                          Slot slot, int slotSeat, int slotIndex) {
        double progress(long now) {
            return (now - start) / 1e6 / durationMs;
        }

        boolean finished(long now) {
            return (now - start) / 1e6 >= durationMs + RING_MS;
        }

        /** 落在牌桌上的牌才有落地的光圈，摸牌與補花沒有。 */
        boolean ringsOnLanding() {
            return slot == Slot.DISCARD || slot == Slot.MELD;
        }
    }

    /** 大字的樣子：吃碰槓、補花與流局只有字，三種胡牌在字的後面各有不同的效果，連莊和胡一樣有往外擴散的金圈。 */
    enum Style { PLAIN, WIN, SELF_DRAW, BIG_WIN, STREAK }

    /** 牌桌上方的公告，例如有人補位上場。title 是金色粗體，detail 是下面一行白字，可以是 null。 */
    private record Announcement(String title, String detail, long start) {
        double elapsedMs(long now) {
            return (now - start) / 1e6;
        }
    }

    /** 跳出來的大字。位置以牌桌中心為原點。detail 是大胡時寫在字下面的台數，其他時候是 null。 */
    record Banner(String text, String detail, double x, double y, double size, Color color, Style style,
                  long start, int durationMs) {
        double progress(long now) {
            return (now - start) / 1e6 / durationMs;
        }
    }

    private final Consumer<Tile> onDiscard;
    private final List<HandHit> hits = new ArrayList<>();
    private final List<Flight> flights = new ArrayList<>();
    private final List<Banner> banners = new ArrayList<>();
    /** 牌桌上方的公告，同時有好幾則就往下疊。換一局也不清掉，補位的人上場時常常剛好開新的一局。 */
    private final List<Announcement> announcements = new ArrayList<>();
    private final Timer frameTimer = new Timer(FRAME_MS, e -> nextFrame());
    private RoundView round;
    private boolean discardEnabled;
    /** 選牌中可以點的牌；null 表示沒有在選牌。 */
    private Set<Tile> pickable;
    /** 已經選好的第一張牌；吃牌還要再選一張時才有。 */
    private Tile picked;
    /** 選牌中能點的牌加上金框。 */
    private boolean markPickable;
    private Consumer<Tile> onPick;
    private int hover = -1;
    private long frameNow;
    /** 擲骰子開始的時間，發牌在 DICE_MS 之後。0 表示沒有在演。 */
    private long dealStart;
    /** 這局開門的骰子，牌牆照它畫。 */
    private Dice dice;
    /** 上一個演過的事件。摸牌緊接在補花或槓之後就是從牌尾補的。 */
    private RoundEvent lastEvent;
    private BufferedImage felt;
    /** 各座位報聽動畫開始的時間，0 表示沒有在演。 */
    private final long[] listenStart = new long[4];
    /** 專家模式：棄牌每家只畫最後一張，中央不寫剩幾張。 */
    private boolean expert;
    /** 正在推倒手牌的贏家，沒有是 -1；pushStart 是胡牌的時間。 */
    private int pushSeat = -1;
    private long pushStart;
    /** 結算籌碼開始飛的時間，0 表示沒有在演；chipDeltas 是各家的輸贏。 */
    private long chipStart;
    private int[] chipDeltas;
    /** 每家剛打出牌時牌河有幾張，-1 表示這局還沒看到他打牌。牌河張數不一樣了，就是最後那張被吃碰槓胡走了。 */
    private final int[] shownDiscards = new int[4];
    /** 吃碰槓後要打牌，手牌外框亮起。 */
    private boolean discardPrompt;
    private long promptStart;

    TableCanvas(Consumer<Tile> onDiscard) {
        this.onDiscard = onDiscard;
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateHover(e);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                updateHover(e);
                if (hover >= 0 && hover < hits.size()) {
                    (pickable != null ? onPick : onDiscard).accept(hits.get(hover).tile());
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void setRound(RoundView round, Dice dice) {
        this.round = round;
        this.dice = dice;
        flights.clear();
        banners.clear();
        Arrays.fill(listenStart, 0);
        Arrays.fill(shownDiscards, -1);
        pushSeat = -1;
        chipStart = 0;
        chipDeltas = null;
        discardPrompt = false;
        dealStart = 0;
        lastEvent = null;
        repaint();
    }

    /** 專家模式：棄牌每家只畫最後一張，畫在牌河的第一格，看不出打了幾張；中央不寫剩幾張。 */
    void setExpert(boolean on) {
        expert = on;
        repaint();
    }

    /** 有沒有動畫都會收到。打出牌時記下牌河的張數，之後少了就是被拿走了，專家模式就不再畫它。 */
    void noteEvent(RoundEvent event) {
        if (event.type() == RoundEvent.Type.DISCARD && round != null) {
            shownDiscards[event.seat()] = round.player(event.seat()).discards().size();
        }
    }

    /** 吃碰槓之後要打一張牌：自己的手牌外框一明一暗，直到打出牌。 */
    void setDiscardPrompt(boolean on) {
        if (on == discardPrompt) {
            return;
        }
        discardPrompt = on;
        promptStart = System.nanoTime();
        frameNow = promptStart;
        if (on) {
            frameTimer.start();
        }
        repaint();
    }

    /**
     * 先擲骰子，再從牌牆一批一批發牌。牌局其實已經發好了，這裡只是讓牌一批一批出現。
     * streak 大於 0 是連莊：先跳出「連莊」與「連 N 拉 N」的大字，演完才擲骰子，這段時間手牌還沒發。
     */
    void animateDeal(int streak) {
        long now = System.nanoTime();
        if (streak > 0) {
            addCenterBanner("連莊", "連" + streak + "拉" + streak, Theme.GOLD, Style.STREAK, STREAK_BANNER_MS);
        }
        dealStart = now + dealLeadMs(streak) * 1_000_000L;
        frameNow = now;
        frameTimer.start();
        repaint();
    }

    /** 擲骰子前要先演多久：連莊時是「連莊」大字的時間，其他時候是 0。 */
    static int dealLeadMs(int streak) {
        return streak > 0 ? STREAK_BANNER_MS : 0;
    }

    /** 從擲骰子開始過了多久。 */
    private double diceElapsedMs() {
        return (frameNow - dealStart) / 1e6;
    }

    /** 發牌開始了多久，還在擲骰子時是負的。 */
    private double dealElapsedMs() {
        return diceElapsedMs() - DICE_MS;
    }

    /** 擲骰子與發牌都算。 */
    private boolean dealing() {
        return dealStart != 0 && dealElapsedMs() < DEAL_MS;
    }

    /** 手牌第 index 張是第幾批發的。每家每批四張，莊家先拿，最後一批是莊家的第 17 張。 */
    private int dealPacket(int seat, int index) {
        return index >= 16 ? DEAL_PACKETS - 1 : index / 4 * 4 + Math.floorMod(seat - round.dealer(), 4);
    }

    /** 手牌第 index 張是牌頭的第幾張。 */
    private int dealtFrom(int seat, int index) {
        return index >= 16 ? DEALT_TILES - 1 : dealPacket(seat, index) * 4 + index % 4;
    }

    /** 手牌第 index 張的發牌進度：小於 0 還沒發，0 到 1 飛行中，1 以上已落地。 */
    private double dealProgress(int seat, int index) {
        if (!dealing()) {
            return 1;
        }
        return (dealElapsedMs() - dealPacket(seat, index) * DEAL_INTERVAL_MS) / DEAL_FLIGHT_MS;
    }

    /** 還沒發出去的張數，讓中央的剩餘張數與牌牆跟著往下數。 */
    private int undealtTiles() {
        if (!dealing()) {
            return 0;
        }
        if (dealElapsedMs() < 0) {
            return DEALT_TILES;
        }
        int started = (int) (dealElapsedMs() / DEAL_INTERVAL_MS) + 1;
        return started >= DEAL_PACKETS ? 0 : (DEAL_PACKETS - 1 - started) * 4 + 1;
    }

    /** 這局的牌牆。沒有骰子時是 null，只有測試會這樣。 */
    private WallRing ring() {
        return dice == null ? null : new WallRing(round.dealer(), dice);
    }

    /** 牌牆上看得到的：發牌時牌頭一批一批少下去，發完才補花，所以牌尾等發完才少。 */
    private WallRing.Taken wallTaken() {
        return dealing() ? new WallRing.Taken(DEALT_TILES - undealtTiles(), 0) : WallRing.taken(round);
    }

    private WallLayout wallLayout() {
        double unit = tableUnit();
        double top = WALL_TOP * unit;
        double depth = WALL_DEPTH * unit;
        double stackW = (top * 2 + depth) / WallRing.STACKS_PER_SIDE;
        double lift = depth * .24;
        return new WallLayout(top, depth, stackW, stackW * .9, depth - lift, lift);
    }

    /** 第 stack 墩的中心點，以牌桌中心為原點。 */
    private Point2D stackCenter(int stack) {
        WallLayout wall = wallLayout();
        return toTable(WallRing.side(stack), wall.stackX(WallRing.column(stack)) + wall.tileW() / 2,
                wall.top() + wall.depth() / 2);
    }

    /** 播放事件的動畫。只影響畫面，牌局狀態已經更新好了。 */
    void animate(RoundEvent event) {
        if (round == null || getWidth() == 0) {
            return;
        }
        // 摸牌與補花緊接在打牌之後，不能把還在飛的牌清掉
        if (event.type() != RoundEvent.Type.DRAW && event.type() != RoundEvent.Type.FLOWER) {
            flights.clear();
        }
        switch (event.type()) {
            case DRAW:
                addDrawFlight(event);
                break;
            case FLOWER:
                addFlowerFlight(event);
                break;
            case DISCARD:
                addDiscardFlight(event);
                break;
            case CHOW:
                addClaim(event, "吃");
                break;
            case PONG:
                addClaim(event, "碰");
                break;
            case KONG:
                addClaim(event, "槓");
                break;
            case CONCEALED_KONG:
                addSeatBanner(event.seat(), "暗槓");
                break;
            case ADD_KONG:
                addSeatBanner(event.seat(), "加槓");
                break;
            case WIN:
            case SELF_WIN:
                addWinBanner(event.type() == RoundEvent.Type.SELF_WIN);
                pushSeat = event.seat();
                pushStart = System.nanoTime();
                break;
            case LISTEN:
                addSeatBanner(event.seat(), "聽牌");
                listenStart[event.seat()] = System.nanoTime();
                break;
            default:
                addCenterBanner("流局", null, Theme.MUTED, Style.PLAIN, WIN_BANNER_MS);
                break;
        }
        lastEvent = event;
        frameNow = System.nanoTime();
        frameTimer.start();
        repaint();
    }

    /** 這次摸牌是從牌尾補的：同一家剛補花或開槓。 */
    private boolean fromTail(RoundEvent draw) {
        if (lastEvent == null || lastEvent.seat() != draw.seat()) {
            return false;
        }
        switch (lastEvent.type()) {
            case FLOWER:
            case KONG:
            case CONCEALED_KONG:
            case ADD_KONG:
                return true;
            default:
                return false;
        }
    }

    /** 結果在胡牌的事件之前就有了，單機與連線都是，所以這時候已經知道台數。靠花牌胡的直接顯示八仙過海或七搶一。 */
    private void addWinBanner(boolean selfDraw) {
        RoundResult result = round.result();
        Style style = result == null ? (selfDraw ? Style.SELF_DRAW : Style.WIN) : endStyle(result);
        String text = result != null && result.isFlowerWin() ? result.score().items().get(0).name() : selfDraw ? "自摸" : "胡";
        String detail = style == Style.BIG_WIN ? result.score().totalTai() + " 台" : null;
        addCenterBanner(text, detail, Theme.GOLD, style, durationMs(style));
    }

    /** 一局結束的大字演多久，結果視窗等它演完才跳出來。 */
    static int endBannerMs(RoundResult result) {
        return durationMs(endStyle(result));
    }

    private static Style endStyle(RoundResult result) {
        if (result == null || result.isDrawnGame()) {
            return Style.PLAIN;
        }
        if (result.isBigWin()) {
            return Style.BIG_WIN;
        }
        return result.isSelfDraw() ? Style.SELF_DRAW : Style.WIN;
    }

    private static int durationMs(Style style) {
        switch (style) {
            case SELF_DRAW: return SELF_DRAW_BANNER_MS;
            case BIG_WIN:   return BIG_WIN_BANNER_MS;
            default:        return WIN_BANNER_MS;
        }
    }

    private void nextFrame() {
        long now = System.nanoTime();
        frameNow = now;
        flights.removeIf(flight -> flight.finished(now));
        banners.removeIf(banner -> banner.progress(now) >= 1);
        announcements.removeIf(announcement -> announcement.elapsedMs(now) >= ANNOUNCE_MS);
        boolean listenAnimating = false;
        for (int seat = 0; seat < listenStart.length; seat++) {
            if (listenStart[seat] != 0 && listenProgress(seat) >= 1) {
                listenStart[seat] = 0;
            }
            listenAnimating |= listenStart[seat] != 0;
        }
        if (flights.isEmpty() && banners.isEmpty() && announcements.isEmpty() && !dealing() && !listenAnimating
                && !discardPrompt && !pushing() && !settling()) {
            frameTimer.stop();
        }
        repaint();
    }

    /** 從胡牌起過了幾毫秒。 */
    private double pushElapsedMs() {
        return (frameNow - pushStart) / 1e6;
    }

    /** 推倒手牌與胡的那張的金光還在演。最多 17 張加上一張分開的。 */
    private boolean pushing() {
        return pushSeat >= 0 && pushElapsedMs() < 18 * PUSH_INTERVAL_MS + PUSH_TILE_MS + WIN_GLOW_MS;
    }

    /** 結算的籌碼還在飛，或數字還沒淡掉；還沒開始飛也算。 */
    private boolean settling() {
        return chipStart != 0 && (frameNow - chipStart) / 1e6 < CHIPS_MS;
    }

    /** 報聽動畫的進度，0 到 1；沒有在演時是 1。 */
    private double listenProgress(int seat) {
        return listenStart[seat] == 0 ? 1 : (frameNow - listenStart[seat]) / 1e6 / LISTEN_BADGE_MS;
    }

    private double unit() {
        return Math.min(getWidth(), getHeight()) / 720.0;
    }

    /**
     * 中央方框與棄牌區跟著牌放大，但至少要留得下三排棄牌，不能撞到自己的手牌。
     * 留不下時可以比原本小一點，最小到 MIN_TABLE_SCALE。
     */
    private double tableUnit() {
        double unit = unit();
        double handH = rowTileW(0, MIN_ROW_SLOTS) * TILE_RATIO;
        double room = (getHeight() / 2.0 - handH - 8 * unit) / unit;
        double fit = room / (DISCARD_TOP + DISCARD_ROWS_TO_FIT * DISCARD_H);
        double grow = Math.min(Theme.tileScale(), MAX_TABLE_SCALE);
        return unit * Math.max(MIN_TABLE_SCALE, Math.min(Math.max(1, grow), fit));
    }

    private double preferredTileW(int seat) {
        return (seat == 0 ? 54 : 32) * unit() * Theme.tileScale();
    }

    private double rowStep(int seat, double slots) {
        return Math.min(preferredTileW(seat), rowLength(seat) * .98 / Math.max(slots, MIN_ROW_SLOTS));
    }

    /** 自己的手牌佔滿整個寬度，其他三家排在剩下的空間裡，牌放大時才不會互相蓋住。 */
    private double rowLength(int seat) {
        if (seat == 0) {
            return getWidth();
        }
        if (seat == 2) {
            return getWidth() - 2 * botRowDepth();
        }
        return getHeight() - humanRowDepth() - botRowDepth();
    }

    private double humanRowDepth() {
        return rowTileW(0, MIN_ROW_SLOTS) * TILE_RATIO + 8 * unit();
    }

    private double botRowDepth() {
        return preferredTileW(1) * TILE_RATIO + 8 * unit();
    }

    /** 左右兩家的牌往上挪，避開比較厚的自家手牌。座標方向相反，所以兩家的正負號也相反。 */
    private double rowShift(int seat) {
        if (seat % 2 == 0) {
            return 0;
        }
        double shift = (humanRowDepth() - botRowDepth()) / 2;
        return seat == 1 ? shift : -shift;
    }

    private double rowTileW(int seat, double slots) {
        return Math.min(preferredTileW(seat), rowStep(seat, slots) * MAX_OVERLAP);
    }

    private double halfDepth(int seat) {
        return (seat % 2 == 0 ? getHeight() : getWidth()) / 2.0;
    }

    private static double seatAngle(int seat) {
        return -Math.PI / 2 * seat;
    }

    /** 把該家視角的座標轉成以牌桌中心為原點的座標。 */
    private static Point2D toTable(int seat, double x, double y) {
        return AffineTransform.getRotateInstance(seatAngle(seat)).transform(new Point2D.Double(x, y), null);
    }

    /** 第 index 張棄牌的中心點，該家視角。 */
    private Point2D.Double discardCenter(int index) {
        double unit = tableUnit();
        double w = DISCARD_W * unit;
        double h = DISCARD_H * unit;
        return new Point2D.Double(-w * DISCARD_COLUMNS / 2 + (index % DISCARD_COLUMNS) * w + w / 2,
                DISCARD_TOP * unit + (index / DISCARD_COLUMNS) * h + h / 2);
    }

    /** 打出的牌從手牌飛到棄牌區。真人從點的那張牌起飛，電腦從手牌中間起飛。 */
    private void addDiscardFlight(RoundEvent event) {
        int seat = event.seat();
        RowLayout row = layoutRow(seat, null);
        double fromX = 0;
        double fromY = row.y() + row.tileH() / 2;
        double fromW = row.tileW();
        if (seat == 0) {
            for (HandHit hit : hits) {
                if (hit.tile().equals(event.tile())) {
                    fromX = hit.bounds().getCenterX() - getWidth() / 2.0;
                }
            }
        }
        // 專家模式只畫最後一張，放在第一格
        int index = expert ? 0 : round.player(seat).discards().size() - 1;
        Point2D.Double to = discardCenter(index);
        Point2D from = toTable(seat, fromX, fromY);
        Point2D target = toTable(seat, to.x, to.y);
        double w = (DISCARD_W - 1) * tableUnit();
        flights.add(new Flight(event.tile(), false, from.getX(), from.getY(), seatAngle(seat), fromW,
                target.getX(), target.getY(), seatAngle(seat), w, System.nanoTime(), DISCARD_FLIGHT_MS,
                Slot.DISCARD, seat, -1));
    }

    /** 被吃碰槓的牌從棄牌區飛進新面子，同時跳出大字。 */
    private void addClaim(RoundEvent event, String text) {
        int seat = event.seat();
        Player player = round.player(seat);
        Meld meld = player.melds().get(player.melds().size() - 1);
        int tileIndex = meld.tiles().indexOf(event.tile());
        RowLayout row = layoutRow(seat, separatedTile(seat, false));
        // 面子從左邊排起，新的排在最後面
        double meldX = row.x();
        for (Meld earlier : player.melds().subList(0, player.melds().size() - 1)) {
            meldX += earlier.tiles().size() * row.step() + row.gap();
        }

        Point2D.Double slot = discardCenter(expert ? 0 : round.player(event.from()).discards().size());
        Point2D from = toTable(event.from(), slot.x, slot.y);
        Point2D to = toTable(seat, meldX + tileIndex * row.step() + row.tileW() / 2, row.y() + row.tileH() / 2);
        double turn = Math.IEEEremainder(seatAngle(seat) - seatAngle(event.from()), Math.PI * 2);
        flights.add(new Flight(event.tile(), false, from.getX(), from.getY(), seatAngle(event.from()),
                (DISCARD_W - 1) * tableUnit(), to.getX(), to.getY(), seatAngle(event.from()) + turn, row.tileW(),
                System.nanoTime(), CLAIM_FLIGHT_MS, Slot.MELD, seat, tileIndex));
        addSeatBanner(seat, text);
    }

    /**
     * 摸到的牌從牌牆飛到手牌右邊：一般從牌頭，補花與開槓之後從牌尾。別家的牌背朝上。
     * 局面已經更新，剛拿走的就是牌頭或牌尾的最後一張。
     */
    private void addDrawFlight(RoundEvent event) {
        int seat = event.seat();
        Player player = round.player(seat);
        RowLayout row = layoutRow(seat, event.tile());
        double x = row.x();
        for (Meld meld : player.melds()) {
            x += meld.tiles().size() * row.step() + row.gap();
        }
        x += (player.concealed().size() - 1) * row.step() + row.step() * .4;
        Point2D to = toTable(seat, x + row.tileW() / 2, row.y() + row.tileH() / 2);
        Point2D from = new Point2D.Double(0, 0);
        double fromAngle = seatAngle(seat);
        double fromW = row.tileW() * .6;
        WallRing ring = ring();
        WallRing.Taken taken = WallRing.taken(round);
        boolean tail = fromTail(event);
        int index = (tail ? taken.tail() : taken.head()) - 1;
        if (ring != null && index >= 0) {
            int stack = tail ? ring.tailStack(index) : ring.headStack(index);
            from = stackCenter(stack);
            fromAngle = seatAngle(WallRing.side(stack));
            fromW = wallLayout().tileW();
        }
        double toAngle = fromAngle + Math.IEEEremainder(seatAngle(seat) - fromAngle, Math.PI * 2);
        flights.add(new Flight(event.tile(), seat != 0, from.getX(), from.getY(), fromAngle, fromW,
                to.getX(), to.getY(), toAngle, row.tileW(), System.nanoTime(), DRAW_FLIGHT_MS,
                Slot.DRAWN, seat, -1));
    }

    /** 花牌從手牌亮出來，飛到該家的花牌列，旁邊跳出小一點的字。補的牌接著從牌尾飛過來。 */
    private void addFlowerFlight(RoundEvent event) {
        int seat = event.seat();
        int index = round.player(seat).flowers().size() - 1;
        RowLayout row = layoutRow(seat, separatedTile(seat, false));
        double unit = tableUnit();
        double w = FLOWER_W * unit;
        double h = FLOWER_H * unit;
        Point2D from = toTable(seat, row.x() + row.total() / 2, row.y() + row.tileH() / 2);
        Point2D to = toTable(seat, flowersX(seat, row) + index * w + w / 2, row.y() - 6 * unit - h / 2);
        flights.add(new Flight(event.tile(), false, from.getX(), from.getY(), seatAngle(seat), row.tileW(),
                to.getX(), to.getY(), seatAngle(seat), w - 1, System.nanoTime(), FLOWER_FLIGHT_MS,
                Slot.FLOWER, seat, index));
        addSeatBanner(seat, "補花", 34 * unit(), FLOWER_BANNER_MS);
    }

    private void addSeatBanner(int seat, String text) {
        addSeatBanner(seat, text, 58 * unit(), CLAIM_BANNER_MS);
    }

    /** 不是牌局事件的大字，例如有人補位上場。畫面座位，0 是自己。還沒有局面時位置算不出來，就不跳。 */
    void showSeatBanner(int seat, String text) {
        if (round == null) {
            return;
        }
        addSeatBanner(seat, text);
        frameTimer.start();
        repaint();
    }

    /** 牌桌上方跳出公告，停 ANNOUNCE_MS 再淡出。還沒有局面時也畫，補位的人上場時可能還沒發牌。 */
    void announce(String title, String detail) {
        announcements.add(new Announcement(title, detail, System.nanoTime()));
        frameTimer.start();
        repaint();
    }

    /**
     * 結算：delayMs 之後籌碼從付錢的人飛向贏的人，每家付錢的人 CHIPS_PER_PAYER 顆，各家跳出「+1200」「−400」。
     * 付錢的人的數字一開始就出現，贏的人的數字等第一顆籌碼落地才跳出來。
     */
    void animateSettlement(int[] deltas, int delayMs) {
        long now = System.nanoTime();
        chipDeltas = deltas.clone();
        chipStart = now + delayMs * 1_000_000L;
        frameNow = now;
        frameTimer.start();
        repaint();
    }

    /** 有沒有人的籌碼變了；流局或沒有輸贏時不用演籌碼。 */
    static boolean moneyMoved(int[] deltas) {
        return Arrays.stream(deltas).anyMatch(delta -> delta != 0);
    }

    /** 胡牌大字演到快完的時候籌碼開始飛。 */
    static int chipsStartMs(RoundResult result) {
        return endBannerMs(result) * 4 / 5;
    }

    /** 一局結束要演多久才跳出結果：胡牌大字；有人輸贏的話一直到籌碼飛完、數字跳完。 */
    static int endAnimationMs(RoundResult result, int[] deltas) {
        return moneyMoved(deltas) ? chipsStartMs(result) + CHIPS_MS : endBannerMs(result);
    }

    /** 每家跳出大字、籌碼起降的地方：中央方框和這家手牌之間，以牌桌中心為原點。 */
    private Point2D seatAnchor(int seat) {
        return toTable(seat, 0, halfDepth(seat) * .62);
    }

    private void paintSettlement(Graphics2D g) {
        if (chipStart == 0 || chipDeltas == null) {
            return;
        }
        double ms = (frameNow - chipStart) / 1e6;
        if (ms < 0 || ms >= CHIPS_MS) {
            return;
        }
        double unit = tableUnit();
        int pair = 0;
        for (int payer = 0; payer < 4; payer++) {
            if (chipDeltas[payer] >= 0) {
                continue;
            }
            for (int gainer = 0; gainer < 4; gainer++) {
                if (chipDeltas[gainer] <= 0) {
                    continue;
                }
                Point2D from = seatAnchor(payer);
                Point2D to = seatAnchor(gainer);
                for (int chip = 0; chip < CHIPS_PER_PAYER; chip++) {
                    double t = (ms - pair * PAYER_GAP_MS - chip * CHIP_GAP_MS) / CHIP_FLIGHT_MS;
                    if (t < 0 || t >= 1) {
                        continue;
                    }
                    double eased = t < .5 ? 2 * t * t : 1 - 2 * (1 - t) * (1 - t);
                    double x = from.getX() + (to.getX() - from.getX()) * eased;
                    double y = from.getY() + (to.getY() - from.getY()) * eased - Math.sin(Math.PI * t) * 70 * unit;
                    paintChip(g, x, y, 11 * unit);
                }
                pair++;
            }
        }
        for (int seat = 0; seat < 4; seat++) {
            int delta = chipDeltas[seat];
            if (delta != 0) {
                paintAmount(g, seat, delta, ms - (delta > 0 ? CHIP_FLIGHT_MS : 0), unit);
            }
        }
    }

    /** 一顆金色籌碼：底下有影子，邊緣深色，裡面一圈白色虛線。 */
    private static void paintChip(Graphics2D g, double x, double y, double r) {
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new Ellipse2D.Double(x - r + r * .15, y - r + r * .3, r * 2, r * 2));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x - r * .35, y - r * .35), (float) (r * 1.6),
                new float[] {0f, 1f}, new Color[] {Theme.GOLD.brighter(), CHIP_DARK}));
        g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        g.setColor(CHIP_RIM);
        g.setStroke(new BasicStroke((float) Math.max(1, r * .14)));
        g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        double inner = r * .62;
        g.setColor(new Color(255, 255, 255, 170));
        g.setStroke(new BasicStroke((float) Math.max(1, r * .16), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10,
                new float[] {(float) (r * .5), (float) (r * .4)}, 0));
        g.draw(new Ellipse2D.Double(x - inner, y - inner, inner * 2, inner * 2));
    }

    /**
     * 這家的輸贏數字，贏是金色「+」、輸是紅色「−」，黑邊才看得清楚。shownMs 是跳出來之後過了多久，
     * 還沒到就不畫；贏的人跳出來時先放大再彈回。之後慢慢往上飄，最後一小段淡掉。
     */
    private void paintAmount(Graphics2D g, int seat, int delta, double shownMs, double unit) {
        if (shownMs < 0) {
            return;
        }
        double life = CHIPS_MS - (delta > 0 ? CHIP_FLIGHT_MS : 0);
        double t = Math.min(1, shownMs / life);
        double fadeIn = Math.min(1, shownMs / 120);
        double fadeOut = t > .85 ? (1 - t) / .15 : 1;
        double pop = delta > 0 && t < .2 ? 1.6 - .6 * easeOut(t / .2) : 1;
        Point2D at = seatAnchor(seat);
        double rise = 26 * unit * easeOut(t);
        String text = (delta > 0 ? "+" : "−") + Math.abs(delta);
        Font font = Theme.fixedFont(Font.BOLD, (float) (30 * unit * pop));
        FontMetrics metrics = g.getFontMetrics(font);
        float x = (float) (at.getX() - metrics.stringWidth(text) / 2.0);
        float y = (float) (at.getY() - rise + metrics.getAscent() / 2.0);
        Composite saved = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, fadeIn * fadeOut)));
        g.setFont(font);
        g.setColor(new Color(0, 0, 0, 200));
        float edge = (float) Math.max(1.5, 2 * unit);
        for (float[] offset : new float[][] {{-edge, 0}, {edge, 0}, {0, -edge}, {0, edge}}) {
            g.drawString(text, x + offset[0], y + offset[1]);
        }
        g.setColor(delta > 0 ? Theme.GOLD : TablePanel.DealerBadge.RED);
        g.drawString(text, x, y);
        g.setComposite(saved);
    }

    private void addSeatBanner(int seat, String text, double size, int durationMs) {
        Point2D at = seatAnchor(seat);
        banners.add(new Banner(text, null, at.getX(), at.getY(), size, Theme.GOLD, Style.PLAIN, System.nanoTime(),
                durationMs));
    }

    private void addCenterBanner(String text, String detail, Color color, Style style, int durationMs) {
        banners.clear();
        double size = (style == Style.BIG_WIN ? 140 : 120) * unit();
        banners.add(new Banner(text, detail, 0, 0, size, color, style, System.nanoTime(), durationMs));
    }

    /** 這個位置的牌是不是還在飛。 */
    private boolean flying(Slot slot, int seat, int index) {
        for (Flight flight : flights) {
            if (flight.slot() == slot && flight.slotSeat() == seat && flight.slotIndex() == index
                    && flight.progress(frameNow) < 1) {
                return true;
            }
        }
        return false;
    }

    /** 回到一般狀態，同時結束選牌。 */
    void setDiscardEnabled(boolean enabled) {
        discardEnabled = enabled;
        pickable = null;
        picked = null;
        markPickable = false;
        hover = -1;
        repaint();
    }

    /**
     * 開始選牌：只有 tiles 裡的牌能點，其他手牌蓋上遮罩。first 是已經選好、要留著不蓋的牌。
     * marked 時能點的牌另外加上金框。
     */
    void startPick(Set<Tile> tiles, Tile first, Consumer<Tile> onPick, boolean marked) {
        discardEnabled = false;
        pickable = tiles;
        picked = first;
        markPickable = marked;
        this.onPick = onPick;
        hover = -1;
        repaint();
    }

    private boolean clickable(Tile tile) {
        return pickable != null ? pickable.contains(tile) : discardEnabled;
    }

    private void updateHover(MouseEvent e) {
        int next = -1;
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).bounds().contains(e.getPoint())) {
                next = clickable(hits.get(i).tile()) ? i : -1;
                break;
            }
        }
        if (next != hover) {
            hover = next;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        int width = getWidth();
        int height = getHeight();
        g.drawImage(felt(width, height), 0, 0, null);
        AffineTransform origin = g.getTransform();

        if (round != null) {
            frameNow = System.nanoTime();
            double cx = width / 2.0;
            double cy = height / 2.0;
            hits.clear();
            AffineTransform base = g.getTransform();
            for (int seat = 0; seat < 4; seat++) {
                g.setTransform(base);
                g.translate(cx, cy);
                g.rotate(-Math.PI / 2 * seat);
                paintSeat(g, seat);
            }
            g.setTransform(base);
            g.translate(cx, cy);
            paintCenterText(g, tableUnit());
            paintDice(g, tableUnit());
            for (Flight flight : flights) {
                paintFlight(g, flight);
            }
            for (Banner banner : banners) {
                paintBanner(g, banner, Math.min(1, Math.max(0, banner.progress(frameNow))));
            }
            paintSettlement(g);
        }
        g.setTransform(origin);
        paintAnnouncements(g, origin, width, height);
        g.dispose();
    }

    /**
     * 公告畫在牌桌上方正中間，深色底、金框、金色粗體字，下面一行白字；同時有好幾則就往下疊。
     * 先從小放大淡入，外框發光 ANNOUNCE_GLOWS 次，最後淡出。太寬就把字縮小，放得進牌桌。
     * origin 是元件原本的座標，Windows 的顯示比例也在裡面，所以位移與縮放要疊在它上面。
     */
    private void paintAnnouncements(Graphics2D g, AffineTransform origin, int width, int height) {
        if (announcements.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        double unit = Math.max(.8, unit());
        double y = height * .16;
        Composite saved = g.getComposite();
        for (Announcement announcement : announcements) {
            double ms = announcement.elapsedMs(now);
            double alpha = ms < ANNOUNCE_IN_MS ? ms / ANNOUNCE_IN_MS
                    : ms > ANNOUNCE_MS - ANNOUNCE_OUT_MS ? (ANNOUNCE_MS - ms) / ANNOUNCE_OUT_MS : 1;
            alpha = Math.min(1, Math.max(0, alpha));
            double pop = ms < ANNOUNCE_IN_MS ? .8 + .2 * easeOut(ms / ANNOUNCE_IN_MS) : 1;

            double padX = 18 * unit;
            double padY = 9 * unit;
            float titleSize = (float) (24 * unit);
            float detailSize = (float) (14 * unit);
            Font titleFont = Theme.fixedFont(Font.BOLD, titleSize);
            Font detailFont = Theme.fixedFont(Font.PLAIN, detailSize);
            double textW = Math.max(g.getFontMetrics(titleFont).stringWidth(announcement.title()),
                    announcement.detail() == null ? 0 : g.getFontMetrics(detailFont).stringWidth(announcement.detail()));
            double room = width * .92 - padX * 2;
            if (textW > room && textW > 0) {
                double shrink = room / textW;
                titleFont = titleFont.deriveFont((float) (titleSize * shrink));
                detailFont = detailFont.deriveFont((float) (detailSize * shrink));
                textW = room;
            }
            FontMetrics titleMetrics = g.getFontMetrics(titleFont);
            FontMetrics detailMetrics = announcement.detail() == null ? null : g.getFontMetrics(detailFont);
            double boxW = textW + padX * 2;
            double boxH = titleMetrics.getHeight() + (detailMetrics == null ? 0 : detailMetrics.getHeight()) + padY * 2;

            AffineTransform at = new AffineTransform(origin);
            at.translate(width / 2.0, y + boxH / 2);
            at.scale(pop, pop);
            g.setTransform(at);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
            RoundRectangle2D box = new RoundRectangle2D.Double(-boxW / 2, -boxH / 2, boxW, boxH, 14 * unit, 14 * unit);
            double glowing = ms - ANNOUNCE_IN_MS;
            if (glowing > 0 && glowing < ANNOUNCE_GLOWS * ANNOUNCE_GLOW_MS) {
                double glow = Math.sin(Math.PI * (glowing % ANNOUNCE_GLOW_MS) / ANNOUNCE_GLOW_MS);
                for (int ring = 3; ring >= 1; ring--) {
                    g.setColor(new Color(Theme.GOLD.getRed(), Theme.GOLD.getGreen(), Theme.GOLD.getBlue(),
                            (int) (70 * glow / ring)));
                    g.setStroke(new BasicStroke((float) (ring * 5 * unit)));
                    g.draw(box);
                }
            }
            g.setColor(new Color(0x0A, 0x1A, 0x14, 240));
            g.fill(box);
            g.setColor(Theme.GOLD);
            g.setStroke(new BasicStroke((float) Math.max(2, 2 * unit)));
            g.draw(box);
            double top = -boxH / 2 + padY;
            g.setFont(titleFont);
            g.drawString(announcement.title(), (float) (-titleMetrics.stringWidth(announcement.title()) / 2.0),
                    (float) (top + titleMetrics.getAscent()));
            if (detailMetrics != null) {
                g.setFont(detailFont);
                g.setColor(Theme.TEXT);
                g.drawString(announcement.detail(), (float) (-detailMetrics.stringWidth(announcement.detail()) / 2.0),
                        (float) (top + titleMetrics.getHeight() + detailMetrics.getAscent()));
            }
            y += boxH + 8 * unit;
        }
        g.setComposite(saved);
        g.setTransform(origin);
    }

    /** 整片漸層很花時間，大小沒變就重複用同一張。 */
    private BufferedImage felt(int width, int height) {
        if (felt == null || felt.getWidth() != width || felt.getHeight() != height) {
            felt = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = felt.createGraphics();
            float radius = Math.max(1, Math.max(width, height) * .7f);
            g.setPaint(new RadialGradientPaint(new Point2D.Float(width / 2f, height / 2f), radius,
                    new float[] {0f, 1f}, new Color[] {Theme.FELT, Theme.FELT_DARK}));
            g.fillRect(0, 0, width, height);
            g.dispose();
        }
        return felt;
    }

    private void paintFlight(Graphics2D g, Flight flight) {
        double t = flight.progress(frameNow);
        if (t >= 1) {
            if (flight.ringsOnLanding()) {
                paintLandingRing(g, flight, t);
            }
            return;
        }
        double eased = easeOut(Math.max(0, t));
        double x = flight.fromX() + (flight.toX() - flight.fromX()) * eased;
        double y = flight.fromY() + (flight.toY() - flight.fromY()) * eased;
        double w = (flight.fromW() + (flight.toW() - flight.fromW()) * eased) * (1 + .2 * Math.sin(Math.PI * t));
        double h = w * DISCARD_H / DISCARD_W;
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.rotate(flight.fromAngle() + (flight.toAngle() - flight.fromAngle()) * eased);
        if (flight.faceDown()) {
            TilePainter.paintBack(g, -w / 2, -h / 2, w, h);
        } else {
            TilePainter.paintFace(g, flight.tile(), -w / 2, -h / 2, w, h);
        }
        g.setTransform(saved);
    }

    private void paintLandingRing(Graphics2D g, Flight flight, double t) {
        double ring = Math.min(1, (t - 1) * flight.durationMs() / RING_MS);
        double radius = flight.toW() * (.7 + .8 * ring);
        Composite saved = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (.7 * (1 - ring))));
        g.setColor(Theme.GOLD);
        g.setStroke(new BasicStroke((float) Math.max(1, flight.toW() * .08)));
        g.draw(new Ellipse2D.Double(flight.toX() - radius, flight.toY() - radius, radius * 2, radius * 2));
        g.setComposite(saved);
    }

    /**
     * 大字先放大再彈回原本大小，最後淡出。t 是 0 到 1 的進度。
     * 胡：金色的圈往外擴散；自摸：金光加上慢慢轉的金紅光芒；大胡：紅金閃光、更多更長的光芒、往外噴再落下的彩片，
     * 紅底的框裡多一行台數，字放大後會跳動。網頁版照著畫，所以只用形狀、漸層、透明度、旋轉與文字。
     */
    static void paintBanner(Graphics2D g, Banner banner, double t) {
        boolean big = banner.style() == Style.BIG_WIN;
        double size = banner.size();
        double pop;
        if (big) {
            pop = t < .12 ? 2 - easeOut(t / .12) : 1 + .04 * Math.sin((t - .12) * Math.PI * 8) * (1 - t);
        } else {
            pop = t < .14 ? 1.7 - .7 * easeOut(t / .14) : 1;
        }
        double fadeFrom = big ? .8 : .7;
        float alpha = (float) (t > fadeFrom ? (1 - t) / (1 - fadeFrom) : 1);
        AffineTransform savedTransform = g.getTransform();
        Composite savedComposite = g.getComposite();
        g.translate(banner.x(), banner.y());
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        switch (banner.style()) {
            case WIN:
            case STREAK:
                paintRings(g, size, t);
                break;
            case SELF_DRAW:
                paintGlow(g, size, t);
                paintRays(g, size, t, 16, Math.PI / 3, Theme.RED);
                break;
            case BIG_WIN:
                paintFlash(g, size, t);
                paintRays(g, size, t, 24, Math.PI / 2, Color.WHITE);
                paintSparks(g, size, t);
                break;
            default:
                break;
        }
        g.scale(pop, pop);
        paintBox(g, banner, big);
        g.setTransform(savedTransform);
        g.setComposite(savedComposite);
    }

    /** 字和外框。大胡是紅色漸層底，字下面再寫台數。 */
    private static void paintBox(Graphics2D g, Banner banner, boolean big) {
        double size = banner.size();
        Font font = Theme.fixedFont(Font.BOLD, (float) size);
        FontMetrics metrics = g.getFontMetrics(font);
        FontMetrics detailMetrics = banner.detail() == null ? null : g.getFontMetrics(font.deriveFont((float) (size * .36)));
        double textW = metrics.stringWidth(banner.text());
        double detailH = 0;
        if (detailMetrics != null) {
            textW = Math.max(textW, detailMetrics.stringWidth(banner.detail()));
            detailH = detailMetrics.getHeight();
        }
        double padX = size * .35;
        double padY = size * .14;
        double boxH = metrics.getHeight() + detailH + padY * 2;
        double top = -boxH / 2;
        RoundRectangle2D box = new RoundRectangle2D.Double(-textW / 2 - padX, top, textW + padX * 2, boxH,
                size * .3, size * .3);
        if (big) {
            g.setPaint(new GradientPaint(0, (float) top, BIG_WIN_TOP, 0, (float) -top, BIG_WIN_BOTTOM));
        } else {
            g.setColor(new Color(0x0B, 0x1E, 0x18, 215));
        }
        g.fill(box);
        g.setColor(banner.color());
        g.setStroke(new BasicStroke((float) Math.max(big ? 2 : 1.5, size * (big ? .06 : .04))));
        g.draw(box);
        g.setFont(font);
        g.drawString(banner.text(), (float) (-metrics.stringWidth(banner.text()) / 2.0),
                (float) (top + padY + metrics.getAscent()));
        if (detailMetrics != null) {
            g.setFont(detailMetrics.getFont());
            g.setColor(Color.WHITE);
            g.drawString(banner.detail(), (float) (-detailMetrics.stringWidth(banner.detail()) / 2.0),
                    (float) (top + padY + metrics.getHeight() + detailMetrics.getAscent()));
        }
    }

    /** 胡：三個金色的圈接連往外擴散，越外面越細越淡。 */
    private static void paintRings(Graphics2D g, double size, double t) {
        for (int ring = 0; ring < 3; ring++) {
            double r = (t - ring * .12) / .55;
            if (r <= 0 || r >= 1) {
                continue;
            }
            double radius = size * (.55 + 2.0 * easeOut(r));
            g.setColor(withAlpha(Theme.GOLD, .85 * (1 - r)));
            g.setStroke(new BasicStroke((float) Math.max(1.5, size * .07 * (1 - r))));
            g.draw(new Ellipse2D.Double(-radius, -radius, radius * 2, radius * 2));
        }
    }

    /** 自摸：中央一團往外變大的金光。 */
    private static void paintGlow(Graphics2D g, double size, double t) {
        double radius = size * (1 + 1.6 * easeOut(Math.min(1, t * 1.6)));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), (float) radius, new float[] {0f, 1f},
                new Color[] {withAlpha(Theme.GOLD, .55), withAlpha(Theme.GOLD, 0)}));
        g.fill(new Ellipse2D.Double(-radius, -radius, radius * 2, radius * 2));
    }

    /** 大胡：一開始整片紅金閃一下。 */
    private static void paintFlash(Graphics2D g, double size, double t) {
        if (t >= .3) {
            return;
        }
        double strength = .6 * (1 - t / .3);
        double radius = size * 4;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), (float) radius, new float[] {0f, .5f, 1f},
                new Color[] {withAlpha(Theme.GOLD, strength), withAlpha(Theme.RED, strength / 2), withAlpha(Theme.RED, 0)}));
        g.fill(new Ellipse2D.Double(-radius, -radius, radius * 2, radius * 2));
    }

    /**
     * 從中央往外放射、慢慢轉的光芒。單數的光芒短一點，顏色用 second。turn 是整段時間轉幾弧度。
     * 自摸 16 道、大胡 24 道，大胡的更長。
     */
    private static void paintRays(Graphics2D g, double size, double t, int count, double turn, Color second) {
        boolean big = count > 16;
        double spread = easeOut(Math.min(1, t * (big ? 1.4 : 1.6)));
        double inner = size * (big ? .7 + 1.0 * spread : .6 + 1.2 * spread);
        double outer = size * (big ? 1.1 + 3.0 * spread : .9 + 2.4 * spread);
        g.setStroke(new BasicStroke((float) Math.max(2, size * (big ? .06 : .05)), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count + t * turn;
            double reach = i % 2 == 0 ? outer : inner + (outer - inner) * (big ? .6 : .55);
            g.setColor(i % 2 == 0 ? Theme.GOLD : second);
            g.draw(new Line2D.Double(Math.cos(angle) * inner, Math.sin(angle) * inner,
                    Math.cos(angle) * reach, Math.sin(angle) * reach));
        }
    }

    /** 大胡：彩片照黃金角往四面噴出去，遠近不一，再慢慢往下掉、邊轉邊淡。 */
    private static void paintSparks(Graphics2D g, double size, double t) {
        Color[] colors = {Theme.GOLD, Theme.RED, Color.WHITE};
        double travel = easeOut(Math.min(1, t * 1.3));
        double drop = size * 1.2 * t * t;
        double side = size * .11 * (1 - .4 * t);
        AffineTransform saved = g.getTransform();
        for (int i = 0; i < SPARKS; i++) {
            double angle = i * GOLDEN_ANGLE;
            double reach = .55 + .45 * ((i * .618034) % 1);
            double distance = size * 3.2 * reach * travel;
            g.translate(Math.cos(angle) * distance, Math.sin(angle) * distance + drop);
            g.rotate(angle + t * 8);
            g.setColor(withAlpha(colors[i % colors.length], 1 - .8 * t));
            g.fill(new Rectangle2D.Double(-side / 2, -side / 2, side, side));
            g.setTransform(saved);
        }
    }

    private static Color withAlpha(Color color, double alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                (int) Math.round(255 * Math.max(0, Math.min(1, alpha))));
    }

    private static double easeOut(double t) {
        double rest = 1 - t;
        return 1 - rest * rest * rest;
    }

    private void paintSeat(Graphics2D g, int seat) {
        Player player = round.player(seat);
        double tableUnit = tableUnit();
        paintSeatLabel(g, seat, player, tableUnit);
        paintWall(g, seat);
        paintDiscards(g, seat, player, tableUnit);
        paintRow(g, player, seat);
    }

    /**
     * 這家面前的牌牆，該家視角。拿走的墩空出來，只剩下面那張的墩比較暗。
     * 擲完骰子到發牌開始後一下子，開門的地方有一條金線。
     */
    private void paintWall(Graphics2D g, int seat) {
        WallRing ring = ring();
        if (ring == null) {
            return;
        }
        WallLayout wall = wallLayout();
        WallRing.Taken taken = wallTaken();
        for (int column = 0; column < WallRing.STACKS_PER_SIDE; column++) {
            int left = ring.remaining(WallRing.stack(seat, column), taken.head(), taken.tail());
            if (left == 0) {
                continue;
            }
            double x = wall.stackX(column);
            double y = wall.top() + wall.lift();
            TilePainter.paintBack(g, x, y, wall.tileW(), wall.tileH());
            g.setColor(new Color(0, 0, 0, 80));
            g.fill(new RoundRectangle2D.Double(x, y, wall.tileW(), wall.tileH(), wall.tileW() * .3, wall.tileW() * .3));
            if (left == 2) {
                TilePainter.paintBack(g, x, wall.top(), wall.tileW(), wall.tileH());
            }
        }
        double mark = openMarkAlpha();
        if (mark > 0 && WallRing.side(ring.start()) == seat) {
            double unit = tableUnit();
            double x = wall.stackX(WallRing.column(ring.start())) + wall.tileW() + (wall.stackW() - wall.tileW()) / 2;
            Composite saved = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) mark));
            g.setColor(Theme.GOLD);
            g.setStroke(new BasicStroke((float) Math.max(2, 3 * unit), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x, wall.top() - 5 * unit, x, wall.top() + wall.depth() + 5 * unit));
            g.setComposite(saved);
        }
    }

    /** 開門金線的透明度：骰子停下來才出現，發牌開始後慢慢淡掉。 */
    private double openMarkAlpha() {
        if (dealStart == 0) {
            return 0;
        }
        double t = diceElapsedMs();
        if (t < DICE_ROLL_MS) {
            return 0;
        }
        double fadeFrom = DICE_MS + OPEN_MARK_MS / 2.0;
        return t < fadeFrom ? 1 : Math.max(0, 1 - (t - fadeFrom) / (OPEN_MARK_MS / 2.0));
    }

    /** 骰子還看得到的時候是 1，發牌開始後淡出，之後是 0。 */
    private double diceAlpha() {
        if (dealStart == 0 || dice == null) {
            return 0;
        }
        double t = diceElapsedMs();
        if (t < 0) {
            // 連莊的大字還在演，骰子還沒丟出來
            return 0;
        }
        return t <= DICE_MS ? 1 : Math.max(0, 1 - (t - DICE_MS) / DICE_FADE_MS);
    }

    /**
     * 三顆骰子從莊家面前邊轉邊跳到中央，滾動時點數一直換，停下來就是這局的點數，下面跳出總點數。
     * 1 點與 4 點是紅色的，和真的骰子一樣。
     */
    private void paintDice(Graphics2D g, double unit) {
        double alpha = diceAlpha();
        if (alpha <= 0) {
            return;
        }
        double t = diceElapsedMs();
        double size = DICE_SIZE * unit;
        double roll = Math.min(1, t / DICE_ROLL_MS);
        double eased = easeOut(roll);
        int[] faces = {dice.first(), dice.second(), dice.third()};
        Composite savedComposite = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
        for (int i = 0; i < faces.length; i++) {
            double toX = (i - 1) * size * 1.3;
            double toY = -8 * unit;
            Point2D from = toTable(round.dealer(), toX * .8, (WALL_TOP - 20) * unit);
            double x = from.getX() + (toX - from.getX()) * eased;
            double y = from.getY() + (toY - from.getY()) * eased;
            double hop = roll < 1 ? Math.abs(Math.sin(Math.PI * (2 + i) * eased)) * (1 - eased) : 0;
            double scale = 1 + .5 * hop;
            double angle = (1 - eased) * Math.PI * (3 + i);
            int face = roll < 1 ? 1 + Math.floorMod((int) (t / 70) * (i + 2) + faces[i] + i * 3, 6) : faces[i];
            AffineTransform saved = g.getTransform();
            g.translate(x, y);
            g.rotate(angle);
            g.scale(scale, scale);
            paintDie(g, face, size);
            g.setTransform(saved);
        }
        if (t >= DICE_ROLL_MS) {
            double p = Math.min(1, (t - DICE_ROLL_MS) / 180.0);
            double pop = 1.5 - .5 * easeOut(p);
            g.setColor(Theme.GOLD);
            g.setFont(Theme.fixedFont(Font.BOLD, (float) (20 * unit * pop)));
            drawCenteredLine(g, dice.total() + " 點", 38 * unit);
        }
        g.setComposite(savedComposite);
    }

    /** 一顆骰子，中心在原點：米白圓角方塊加陰影，點是深色，1 與 4 點是紅色。 */
    private static void paintDie(Graphics2D g, int face, double size) {
        double corner = size * .28;
        double shadow = size * .07;
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Double(-size / 2 + shadow, -size / 2 + shadow, size, size, corner, corner));
        RoundRectangle2D body = new RoundRectangle2D.Double(-size / 2, -size / 2, size, size, corner, corner);
        g.setColor(DIE_FACE);
        g.fill(body);
        g.setColor(DIE_EDGE);
        g.setStroke(new BasicStroke((float) Math.max(1, size * .05)));
        g.draw(body);
        g.setColor(face == 1 || face == 4 ? Theme.RED : DIE_PIP);
        double r = size * (face == 1 ? .14 : .085);
        double step = size * .26;
        for (double[] pip : PIPS[face]) {
            g.fill(new Ellipse2D.Double(pip[0] * step - r, pip[1] * step - r, r * 2, r * 2));
        }
    }

    /** 中央方框四邊標示各家門風與姓名，輪到的一家亮起。 */
    private void paintSeatLabel(Graphics2D g, int seat, Player player, double unit) {
        double half = 100 * unit;
        boolean active = round.phase() != Round.Phase.FINISHED && round.current() == seat
                && round.phase() == Round.Phase.TURN;
        g.setColor(active ? Theme.GOLD : new Color(0, 0, 0, 90));
        g.fill(new Rectangle2D.Double(-half, half - 30 * unit, half * 2, 30 * unit));

        String wind = Tile.windName(round.seatWind(seat));
        String dealerMark = round.dealer() == seat ? "莊 " : "";
        String text = dealerMark + wind + "  " + player.name();
        g.setFont(Theme.canvasFont(Font.BOLD, (float) (14 * unit)));
        g.setColor(active ? Color.BLACK : Theme.TEXT);
        // 報聽的標記放在右端、轉角的方格內側，名字往左讓開
        boolean listening = round.isListening(seat);
        double badgeRoom = listening ? 32 * unit : 0;
        // 字體調大時，長名字要縮回方框內
        int textWidth = g.getFontMetrics().stringWidth(text);
        double maxWidth = half * 2 - 8 * unit - badgeRoom * 2;
        if (textWidth > maxWidth) {
            g.setFont(g.getFont().deriveFont((float) (g.getFont().getSize2D() * maxWidth / textWidth)));
        }
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (-metrics.stringWidth(text) / 2.0 - badgeRoom / 2),
                (float) (half - 15 * unit + metrics.getAscent() / 2.0 - metrics.getDescent() / 2.0));
        if (listening) {
            paintListenBadge(g, half - 46 * unit, half - 15 * unit, 12.5 * unit, listenProgress(seat));
        }
    }

    /**
     * 報聽的人方框邊上的「聽」：紅底圓形、金色圓框、白色粗體字。t 是報聽動畫的進度：
     * 一開始從 2.4 倍大彈回原本大小，同時有兩個金圈往外擴散、變淡，t 到 1 之後就是靜止的標記。
     */
    static void paintListenBadge(Graphics2D g, double cx, double cy, double radius, double t) {
        if (t < 1) {
            for (int ring = 0; ring < 2; ring++) {
                double r = (t - ring * .2) / .8;
                if (r <= 0 || r >= 1) {
                    continue;
                }
                double reach = radius * (1 + 3 * easeOut(r));
                g.setColor(withAlpha(Theme.GOLD, .8 * (1 - r)));
                g.setStroke(new BasicStroke((float) Math.max(1.5, radius * .25 * (1 - r))));
                g.draw(new Ellipse2D.Double(cx - reach, cy - reach, reach * 2, reach * 2));
            }
        }
        double scale = t < .35 ? 2.4 - 1.4 * easeOut(t / .35) : 1;
        double r = radius * scale;
        g.setColor(LISTEN_FILL);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.setColor(Theme.GOLD);
        g.setStroke(new BasicStroke((float) Math.max(1.5, r * .16)));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        g.setColor(Color.WHITE);
        g.setFont(Theme.fixedFont(Font.BOLD, (float) (r * 1.2)));
        FontMetrics metrics = g.getFontMetrics();
        g.drawString("聽", (float) (cx - metrics.stringWidth("聽") / 2.0),
                (float) (cy - metrics.getHeight() / 2.0 + metrics.getAscent()));
    }

    private void paintDiscards(Graphics2D g, int seat, Player player, double unit) {
        double tileW = DISCARD_W * unit;
        double tileH = DISCARD_H * unit;
        double startX = -tileW * DISCARD_COLUMNS / 2;
        double startY = DISCARD_TOP * unit;
        List<Tile> discards = player.discards();
        boolean lastHidden = flying(Slot.DISCARD, seat, -1);
        if (expert) {
            // 只畫最後打出、還沒被拿走的那張，放在第一格
            int count = discards.size();
            if (count == 0 || count != shownDiscards[seat] || lastHidden) {
                return;
            }
            TilePainter.paintFace(g, discards.get(count - 1), startX, startY, tileW - 1, tileH - 1);
            if (round.phase() == Round.Phase.CLAIMS && !round.isRobbing() && round.discarder() == seat) {
                TilePainter.paintHighlight(g, startX, startY, tileW - 1, tileH - 1, Theme.RED);
            }
            return;
        }
        for (int i = 0; i < discards.size(); i++) {
            if (lastHidden && i == discards.size() - 1) {
                break;
            }
            double x = startX + (i % DISCARD_COLUMNS) * tileW;
            double y = startY + (i / DISCARD_COLUMNS) * tileH;
            TilePainter.paintFace(g, discards.get(i), x, y, tileW - 1, tileH - 1);
            boolean justDiscarded = round.phase() == Round.Phase.CLAIMS && !round.isRobbing()
                    && round.discarder() == seat && i == discards.size() - 1;
            if (justDiscarded) {
                TilePainter.paintHighlight(g, x, y, tileW - 1, tileH - 1, Theme.RED);
            }
        }
    }

    /** 手牌列的位置。separated 是與手牌分開顯示的那張牌，沒有就傳 null。 */
    private RowLayout layoutRow(int seat, Tile separated) {
        Player player = round.player(seat);
        int meldTiles = player.melds().stream().mapToInt(m -> m.tiles().size()).sum();
        double gapSlots = (separated != null ? .4 : 0) + player.melds().size() * .25;
        double slots = player.concealed().size() + meldTiles + gapSlots;
        double step = rowStep(seat, slots);
        double tileW = rowTileW(seat, slots);
        double tileH = tileW * TILE_RATIO;
        double total = slots * step;
        return new RowLayout(-total / 2 + rowShift(seat), halfDepth(seat) - tileH - 8 * unit(), tileW, tileH, step,
                step * .25, total);
    }

    private void paintRow(Graphics2D g, Player player, int seat) {
        boolean finished = round.phase() == Round.Phase.FINISHED;
        boolean faceUp = seat == 0 || finished;

        List<Tile> hand = new ArrayList<>(player.concealed());
        Tile separated = separatedTile(seat, finished);
        if (separated != null) {
            hand.remove(separated);
        }

        RowLayout row = layoutRow(seat, separated);
        double tileW = row.tileW();
        double tileH = row.tileH();
        double step = row.step();
        double gap = row.gap();
        double x = row.x();
        double y = row.y();

        for (int m = 0; m < player.melds().size(); m++) {
            Meld meld = player.melds().get(m);
            boolean newest = m == player.melds().size() - 1;
            for (int i = 0; i < meld.tiles().size(); i++) {
                // 暗槓只亮中間兩張給自己看，其他家全蓋
                boolean hidden = meld.isConcealed() && !finished && (seat != 0 || i == 0 || i == 3);
                if (newest && flying(Slot.MELD, seat, i)) {
                    x += step;
                    continue;
                }
                if (hidden) {
                    TilePainter.paintBack(g, x, y, tileW, tileH);
                } else {
                    TilePainter.paintFace(g, meld.tiles().get(i), x, y, tileW, tileH);
                }
                x += step;
            }
            x += gap;
        }
        // 有動畫時贏家的手牌在胡牌時推倒翻開，沒有動畫就直接翻開
        boolean pushed = finished && seat == pushSeat && !dealing();
        int index = 0;
        for (Tile tile : hand) {
            // 被下一張疊住的部分點不到，點擊範圍只算露出來的寬度
            double hitW = index == hand.size() - 1 ? tileW : step;
            if (pushed) {
                paintPushedTile(g, seat, tile, index++, x, y, tileW, tileH, false);
            } else {
                paintDealtTile(g, seat, tile, index++, x, y, tileW, tileH, hitW, faceUp);
            }
            x += step;
        }
        if (separated != null) {
            x += step * .4;
            if (pushed) {
                paintPushedTile(g, seat, separated, index, x, y, tileW, tileH, true);
            } else if (!flying(Slot.DRAWN, seat, -1)) {
                paintDealtTile(g, seat, separated, index, x, y, tileW, tileH, tileW, faceUp);
                if (finished) {
                    // 一局結束時分開的那張就是胡的牌
                    TilePainter.paintHighlight(g, x, y, tileW, tileH, Theme.GOLD);
                }
            } else if (seat == 0) {
                // 還在飛也要點得到，真人可以馬上打掉
                addHit(x, y, tileW, tileH, separated);
            }
        }
        if (seat == 0 && discardPrompt && !finished) {
            paintPromptGlow(g, row);
        }
        if (!dealing()) {
            paintFlowers(g, player, flowersX(seat, row), y, tableUnit());
        }
    }

    /**
     * 吃碰槓後要打牌：自己整列牌外面一圈金色圓角框，一明一暗，週期 PROMPT_PULSE_MS。
     * 外面再疊兩圈比較寬、比較淡的框當作光暈。
     */
    private void paintPromptGlow(Graphics2D g, RowLayout row) {
        double phase = (frameNow - promptStart) / 1e6 / PROMPT_PULSE_MS;
        double strength = .55 + .45 * Math.sin(Math.PI * 2 * phase);
        double pad = 6 * unit();
        RoundRectangle2D frame = new RoundRectangle2D.Double(row.x() - pad, row.y() - pad, row.total() + pad * 2,
                row.tileH() + pad * 2, 14 * unit(), 14 * unit());
        for (int layer = 2; layer >= 0; layer--) {
            g.setColor(withAlpha(Theme.GOLD, strength * (layer == 0 ? .95 : .25 / layer)));
            g.setStroke(new BasicStroke((float) ((3 + layer * 5) * unit())));
            g.draw(frame);
        }
    }

    /** 花牌列的起點，該家視角。面子多時手牌變寬，花牌要避開隔壁家的手牌。 */
    private double flowersX(int seat, RowLayout row) {
        double halfLength = (seat % 2 == 0 ? getWidth() : getHeight()) / 2.0;
        double inset = Math.max(66 * unit(), botRowDepth() + 6 * unit());
        return Math.max(row.x(), -halfLength + inset);
    }

    private void addHit(double x, double y, double w, double h, Tile tile) {
        hits.add(new HandHit(new Rectangle((int) (x + getWidth() / 2.0), (int) (y + getHeight() / 2.0),
                (int) Math.ceil(w), (int) Math.ceil(h)), tile));
    }

    /**
     * 發牌時，牌背朝上從牌牆上拿的那一墩轉著飛過來，落地後才是一般的手牌。原點是牌桌中央，座標是這家的視角。
     * 起飛時和牌牆上的牌一樣大、一樣的方向。
     */
    private void paintDealtTile(Graphics2D g, int seat, Tile tile, int index, double x, double y, double w, double h,
                                double hitW, boolean faceUp) {
        double progress = dealProgress(seat, index);
        if (progress >= 1) {
            paintHandTile(g, seat, tile, x, y, w, h, hitW, faceUp);
        } else if (progress >= 0) {
            double eased = easeOut(progress);
            double fromX = 0;
            double fromY = 0;
            double turn = .9;
            double from = .6;
            WallRing ring = ring();
            if (ring != null) {
                int stack = ring.headStack(dealtFrom(seat, index));
                Point2D center = AffineTransform.getRotateInstance(-seatAngle(seat)).transform(stackCenter(stack), null);
                fromX = center.getX();
                fromY = center.getY();
                turn = Math.IEEEremainder(seatAngle(WallRing.side(stack)) - seatAngle(seat), Math.PI * 2);
                from = Math.min(1, wallLayout().tileW() / w);
            }
            double scale = from + (1 - from) * eased;
            AffineTransform saved = g.getTransform();
            g.translate(fromX + (x + w / 2 - fromX) * eased, fromY + (y + h / 2 - fromY) * eased);
            g.rotate((1 - eased) * turn);
            TilePainter.paintBack(g, -w * scale / 2, -h * scale / 2, w * scale, h * scale);
            g.setTransform(saved);
        }
    }

    /**
     * 胡牌時贏家的手牌從左到右一張一張推倒：先往上抬一點、壓扁，再翻成牌面落回原位。
     * 別家原本是牌背，翻過來才看得到；自己的本來就是牌面，看起來是整排往前倒。
     * 胡的那張最後翻，翻開後有金框，外面一圈金光擴散。
     */
    private void paintPushedTile(Graphics2D g, int seat, Tile tile, int index, double x, double y, double w, double h,
                                 boolean winTile) {
        double t = (pushElapsedMs() - index * PUSH_INTERVAL_MS) / PUSH_TILE_MS;
        if (t >= 1) {
            TilePainter.paintFace(g, tile, x, y, w, h);
            if (winTile) {
                TilePainter.paintHighlight(g, x, y, w, h, Theme.GOLD);
                double glow = (pushElapsedMs() - index * PUSH_INTERVAL_MS - PUSH_TILE_MS) / WIN_GLOW_MS;
                if (glow < 1) {
                    paintWinGlow(g, x + w / 2, y + h / 2, w, glow);
                }
            }
            return;
        }
        boolean faceBefore = seat == 0;
        if (t <= 0) {
            if (faceBefore) {
                TilePainter.paintFace(g, tile, x, y, w, h);
            } else {
                TilePainter.paintBack(g, x, y, w, h);
            }
            return;
        }
        // 前半壓扁到看不見，後半從牌面撐開；整段抬起再落下
        boolean secondHalf = t >= .5;
        double squash = Math.max(.04, secondHalf ? (t - .5) / .5 : 1 - t / .5);
        double lift = Math.sin(Math.PI * t) * h * .18;
        AffineTransform saved = g.getTransform();
        g.translate(x + w / 2, y + h / 2 - lift);
        g.scale(1, squash);
        g.translate(-(x + w / 2), -(y + h / 2));
        if (secondHalf || faceBefore) {
            TilePainter.paintFace(g, tile, x, y, w, h);
        } else {
            TilePainter.paintBack(g, x, y, w, h);
        }
        g.setTransform(saved);
    }

    /** 胡的那張翻開後，一圈金光往外擴散、變淡。t 是 0 到 1。 */
    private static void paintWinGlow(Graphics2D g, double cx, double cy, double w, double t) {
        double radius = w * (.6 + 1.1 * easeOut(t));
        Composite saved = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (.85 * (1 - t))));
        g.setColor(Theme.GOLD);
        g.setStroke(new BasicStroke((float) Math.max(1.5, w * .1 * (1 - t))));
        g.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
        g.setComposite(saved);
    }

    /** 剛摸到的牌（或胡的那張）與手牌分開顯示。 */
    private Tile separatedTile(int seat, boolean finished) {
        if (finished) {
            RoundResult result = round.result();
            return !result.isDrawnGame() && !result.isFlowerWin() && result.winner() == seat ? result.winTile() : null;
        }
        return round.phase() == Round.Phase.TURN && round.current() == seat ? round.drawn() : null;
    }

    private void paintHandTile(Graphics2D g, int seat, Tile tile, double x, double y, double w, double h,
                               double hitW, boolean faceUp) {
        double drawY = y;
        boolean masked = false;
        boolean marked = false;
        if (seat == 0) {
            int index = hits.size();
            // 相同的牌只抬起第一張，看起來才是選了一張
            boolean chosen = tile.equals(picked) && hits.stream().noneMatch(hit -> hit.tile().equals(picked));
            addHit(x, y, hitW, h, tile);
            masked = pickable != null && !pickable.contains(tile) && !chosen;
            marked = markPickable && pickable != null && pickable.contains(tile);
            if (chosen || index == hover) {
                drawY -= h * .16;
            }
        }
        if (faceUp) {
            TilePainter.paintFace(g, tile, x, drawY, w, h);
        } else {
            TilePainter.paintBack(g, x, drawY, w, h);
        }
        if (masked) {
            TilePainter.paintMask(g, x, drawY, w, h);
        }
        if (marked) {
            TilePainter.paintHighlight(g, x, drawY, w, h, Theme.GOLD);
        }
    }

    private void paintFlowers(Graphics2D g, Player player, double x, double rowY, double unit) {
        double w = FLOWER_W * unit;
        double h = FLOWER_H * unit;
        double y = rowY - h - 6 * unit;
        List<Tile> flowers = player.flowers();
        for (int i = 0; i < flowers.size(); i++) {
            if (!flying(Slot.FLOWER, player.seat(), i)) {
                TilePainter.paintFace(g, flowers.get(i), x, y, w - 1, h);
            }
            x += w;
        }
    }

    /** 中央方框的圈風與剩餘張數。擲骰子時骰子在方框上，字先藏起來，骰子淡出時再淡入。 */
    private void paintCenterText(Graphics2D g, double unit) {
        double half = 70 * unit;
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new RoundRectangle2D.Double(-half, -half, half * 2, half * 2, 12 * unit, 12 * unit));
        double textAlpha = 1 - diceAlpha();
        if (textAlpha <= 0) {
            return;
        }
        Composite saved = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) textAlpha));
        paintCenterLines(g, unit, half);
        g.setComposite(saved);
    }

    /**
     * 圈風局風與剩餘張數；連莊時三行一起往上挪，最下面用紅字寫「連 N 拉 N」，和計分表的「莊」同一個紅。
     * 專家模式不寫剩幾張，其他行往中間靠。
     */
    private void paintCenterLines(Graphics2D g, double unit, double half) {
        int streak = round.streak();
        if (expert) {
            g.setColor(Theme.GOLD);
            g.setFont(Theme.canvasFont(Font.BOLD, (float) (22 * unit)));
            fitWidth(g, roundLabel(round), half * 2 - 12 * unit);
            drawCenteredLine(g, roundLabel(round), (streak > 0 ? -10 : 8) * unit);
            if (streak > 0) {
                g.setColor(TablePanel.DealerBadge.RED);
                g.setFont(Theme.canvasFont(Font.BOLD, (float) (16 * unit)));
                drawCenteredLine(g, "連" + streak + "拉" + streak, 24 * unit);
            }
            return;
        }
        g.setColor(Theme.GOLD);
        g.setFont(Theme.canvasFont(Font.BOLD, (float) (22 * unit)));
        String label = roundLabel(round);
        fitWidth(g, label, half * 2 - 12 * unit);
        drawCenteredLine(g, label, (streak > 0 ? -26 : -14) * unit);
        g.setColor(Theme.TEXT);
        g.setFont(Theme.canvasFont(Font.PLAIN, (float) (16 * unit)));
        drawCenteredLine(g, "剩餘 " + (round.liveTiles() + undealtTiles()) + " 張", (streak > 0 ? 8 : 22) * unit);
        if (streak > 0) {
            g.setColor(TablePanel.DealerBadge.RED);
            g.setFont(Theme.canvasFont(Font.BOLD, (float) (16 * unit)));
            drawCenteredLine(g, "連" + streak + "拉" + streak, 40 * unit);
        }
    }

    /** 圈風加局風，例如「東風東局」「南風西局」。舊服務器沒有送局風時是原本的「東風圈」。牌桌中間與右側都用這個。 */
    static String roundLabel(RoundView round) {
        int hand = round.handWind();
        return Tile.windName(round.roundWind()) + "風" + (hand >= 1 && hand <= 4 ? Tile.windName(hand) + "局" : "圈");
    }

    /** 字體調大時圈風局風四個字會超出方框，字型縮到放得下。 */
    private static void fitWidth(Graphics2D g, String text, double maxWidth) {
        int width = g.getFontMetrics().stringWidth(text);
        if (width > maxWidth) {
            g.setFont(g.getFont().deriveFont((float) (g.getFont().getSize2D() * maxWidth / width)));
        }
    }

    private static void drawCenteredLine(Graphics2D g, String text, double y) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (-metrics.stringWidth(text) / 2.0), (float) y);
    }
}
