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
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.Timer;

import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundResult;
import ezmj.engine.RoundView;
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
    private static final int SPARKS = 40;
    private static final double GOLDEN_ANGLE = 2.39996323;
    private static final Color BIG_WIN_TOP = new Color(0xC9, 0x3A, 0x32, 235);
    private static final Color BIG_WIN_BOTTOM = new Color(0x6E, 0x12, 0x0E, 235);
    /** 發牌每四張一批輪流發，最後一批是莊家的第 17 張。 */
    static final int DEAL_PACKETS = 17;
    static final int DEAL_FLIGHT_MS = 200;
    static final int DEAL_INTERVAL_MS = 70;
    static final int DEAL_MS = (DEAL_PACKETS - 1) * DEAL_INTERVAL_MS + DEAL_FLIGHT_MS;

    private static final int DISCARD_COLUMNS = 8;
    private static final double DISCARD_W = 25;
    private static final double DISCARD_H = 33;
    private static final double DISCARD_TOP = 104;
    private static final double FLOWER_W = 22;
    private static final double FLOWER_H = 30;
    private static final int FRAME_MS = 16;
    private static final int RING_MS = 170;
    private static final int CLAIM_BANNER_MS = 800;
    private static final double TILE_RATIO = 1.38;
    /** 寬度不夠時手牌可以互相疊住，牌最多畫成間距的這個倍數，牌面的字還看得清楚。 */
    private static final double MAX_OVERLAP = 1.22;
    /** 手牌列至少要留這麼多格，張數少的時候牌也不會忽大忽小。 */
    private static final double MIN_ROW_SLOTS = 18;
    private static final double MAX_TABLE_SCALE = 1.25;
    private static final int DISCARD_ROWS_TO_FIT = 3;

    private record HandHit(Rectangle bounds, Tile tile) {
    }

    /** 一家手牌列的位置，都是該家視角的座標。step 是相鄰兩張的間距，比 tileW 小時牌會互相疊住。 */
    private record RowLayout(double x, double y, double tileW, double tileH, double step, double gap, double total) {
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

    /** 大字的樣子：吃碰槓、補花與流局只有字，三種胡牌在字的後面各有不同的效果。 */
    enum Style { PLAIN, WIN, SELF_DRAW, BIG_WIN }

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
    private final Timer frameTimer = new Timer(FRAME_MS, e -> nextFrame());
    private RoundView round;
    private boolean discardEnabled;
    /** 選牌中可以點的牌；null 表示沒有在選牌。 */
    private Set<Tile> pickable;
    /** 已經選好的第一張牌；吃牌還要再選一張時才有。 */
    private Tile picked;
    private Consumer<Tile> onPick;
    private int hover = -1;
    private long frameNow;
    private long dealStart;
    private BufferedImage felt;

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

    void setRound(RoundView round) {
        this.round = round;
        flights.clear();
        banners.clear();
        dealStart = 0;
        repaint();
    }

    /** 開始發牌動畫。牌局其實已經發好了，這裡只是讓牌一批一批出現。 */
    void animateDeal() {
        dealStart = System.nanoTime();
        frameNow = dealStart;
        frameTimer.start();
        repaint();
    }

    private double dealElapsedMs() {
        return (frameNow - dealStart) / 1e6;
    }

    private boolean dealing() {
        return dealStart != 0 && dealElapsedMs() < DEAL_MS;
    }

    /** 手牌第 index 張的發牌進度：小於 0 還沒發，0 到 1 飛行中，1 以上已落地。 */
    private double dealProgress(int seat, int index) {
        if (!dealing()) {
            return 1;
        }
        int packet = index >= 16 ? DEAL_PACKETS - 1 : index / 4 * 4 + Math.floorMod(seat - round.dealer(), 4);
        return (dealElapsedMs() - packet * DEAL_INTERVAL_MS) / DEAL_FLIGHT_MS;
    }

    /** 還沒發出去的張數，讓中央的剩餘張數跟著往下數。 */
    private int undealtTiles() {
        if (!dealing()) {
            return 0;
        }
        int started = (int) (dealElapsedMs() / DEAL_INTERVAL_MS) + 1;
        return started >= DEAL_PACKETS ? 0 : (DEAL_PACKETS - 1 - started) * 4 + 1;
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
                break;
            default:
                addCenterBanner("流局", null, Theme.MUTED, Style.PLAIN, WIN_BANNER_MS);
                break;
        }
        frameNow = System.nanoTime();
        frameTimer.start();
        repaint();
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
        if (flights.isEmpty() && banners.isEmpty() && !dealing()) {
            frameTimer.stop();
        }
        repaint();
    }

    private double unit() {
        return Math.min(getWidth(), getHeight()) / 720.0;
    }

    /** 中央方框與棄牌區跟著牌放大，但至少要留得下三排棄牌，不能撞到自己的手牌。 */
    private double tableUnit() {
        double unit = unit();
        double handH = rowTileW(0, MIN_ROW_SLOTS) * TILE_RATIO;
        double room = (getHeight() / 2.0 - handH - 8 * unit) / unit;
        double fit = room / (DISCARD_TOP + DISCARD_ROWS_TO_FIT * DISCARD_H);
        return unit * Math.max(1, Math.min(Math.min(Theme.tileScale(), MAX_TABLE_SCALE), fit));
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
        int index = round.player(seat).discards().size() - 1;
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

        Point2D.Double slot = discardCenter(round.player(event.from()).discards().size());
        Point2D from = toTable(event.from(), slot.x, slot.y);
        Point2D to = toTable(seat, meldX + tileIndex * row.step() + row.tileW() / 2, row.y() + row.tileH() / 2);
        double turn = Math.IEEEremainder(seatAngle(seat) - seatAngle(event.from()), Math.PI * 2);
        flights.add(new Flight(event.tile(), false, from.getX(), from.getY(), seatAngle(event.from()),
                (DISCARD_W - 1) * tableUnit(), to.getX(), to.getY(), seatAngle(event.from()) + turn, row.tileW(),
                System.nanoTime(), CLAIM_FLIGHT_MS, Slot.MELD, seat, tileIndex));
        addSeatBanner(seat, text);
    }

    /** 摸到的牌從牌桌中央飛到手牌右邊。別家的牌背朝上。 */
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
        flights.add(new Flight(event.tile(), seat != 0, 0, 0, seatAngle(seat), row.tileW() * .6,
                to.getX(), to.getY(), seatAngle(seat), row.tileW(), System.nanoTime(), DRAW_FLIGHT_MS,
                Slot.DRAWN, seat, -1));
    }

    /** 花牌從牌桌中央飛到該家的花牌列，旁邊跳出小一點的字。 */
    private void addFlowerFlight(RoundEvent event) {
        int seat = event.seat();
        int index = round.player(seat).flowers().size() - 1;
        RowLayout row = layoutRow(seat, separatedTile(seat, false));
        double unit = tableUnit();
        double w = FLOWER_W * unit;
        double h = FLOWER_H * unit;
        Point2D to = toTable(seat, flowersX(seat, row) + index * w + w / 2, row.y() - 6 * unit - h / 2);
        flights.add(new Flight(event.tile(), false, 0, 0, seatAngle(seat), w * .7,
                to.getX(), to.getY(), seatAngle(seat), w - 1, System.nanoTime(), FLOWER_FLIGHT_MS,
                Slot.FLOWER, seat, index));
        addSeatBanner(seat, "補花", 34 * unit(), FLOWER_BANNER_MS);
    }

    private void addSeatBanner(int seat, String text) {
        addSeatBanner(seat, text, 58 * unit(), CLAIM_BANNER_MS);
    }

    private void addSeatBanner(int seat, String text, double size, int durationMs) {
        Point2D at = toTable(seat, 0, halfDepth(seat) * .62);
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
        hover = -1;
        repaint();
    }

    /** 開始選牌：只有 tiles 裡的牌能點，其他手牌蓋上遮罩。first 是已經選好、要留著不蓋的牌。 */
    void startPick(Set<Tile> tiles, Tile first, Consumer<Tile> onPick) {
        discardEnabled = false;
        pickable = tiles;
        picked = first;
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
            for (Flight flight : flights) {
                paintFlight(g, flight);
            }
            for (Banner banner : banners) {
                paintBanner(g, banner, Math.min(1, Math.max(0, banner.progress(frameNow))));
            }
        }
        g.dispose();
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
        paintDiscards(g, seat, player, tableUnit);
        paintRow(g, player, seat);
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
        g.setFont(Theme.font(Font.BOLD, (float) (14 * unit)));
        g.setColor(active ? Color.BLACK : Theme.TEXT);
        // 字體調大時，長名字要縮回方框內
        int textWidth = g.getFontMetrics().stringWidth(text);
        double maxWidth = half * 2 - 8 * unit;
        if (textWidth > maxWidth) {
            g.setFont(g.getFont().deriveFont((float) (g.getFont().getSize2D() * maxWidth / textWidth)));
        }
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (-metrics.stringWidth(text) / 2.0),
                (float) (half - 15 * unit + metrics.getAscent() / 2.0 - metrics.getDescent() / 2.0));
    }

    private void paintDiscards(Graphics2D g, int seat, Player player, double unit) {
        double tileW = DISCARD_W * unit;
        double tileH = DISCARD_H * unit;
        double startX = -tileW * DISCARD_COLUMNS / 2;
        double startY = DISCARD_TOP * unit;
        List<Tile> discards = player.discards();
        boolean lastHidden = flying(Slot.DISCARD, seat, -1);
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
        int index = 0;
        for (Tile tile : hand) {
            // 被下一張疊住的部分點不到，點擊範圍只算露出來的寬度
            double hitW = index == hand.size() - 1 ? tileW : step;
            paintDealtTile(g, seat, tile, index++, x, y, tileW, tileH, hitW, faceUp);
            x += step;
        }
        if (separated != null) {
            x += step * .4;
            if (!flying(Slot.DRAWN, seat, -1)) {
                paintDealtTile(g, seat, separated, index, x, y, tileW, tileH, tileW, faceUp);
            } else if (seat == 0) {
                // 還在飛也要點得到，真人可以馬上打掉
                addHit(x, y, tileW, tileH, separated);
            }
        }
        if (!dealing()) {
            paintFlowers(g, player, flowersX(seat, row), y, tableUnit());
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

    /** 發牌時，牌背朝上從牌桌中央轉著飛過來，落地後才是一般的手牌。原點就是牌桌中央。 */
    private void paintDealtTile(Graphics2D g, int seat, Tile tile, int index, double x, double y, double w, double h,
                                double hitW, boolean faceUp) {
        double progress = dealProgress(seat, index);
        if (progress >= 1) {
            paintHandTile(g, seat, tile, x, y, w, h, hitW, faceUp);
        } else if (progress >= 0) {
            double eased = easeOut(progress);
            double scale = .6 + .4 * eased;
            AffineTransform saved = g.getTransform();
            g.translate((x + w / 2) * eased, (y + h / 2) * eased);
            g.rotate((1 - eased) * .9);
            TilePainter.paintBack(g, -w * scale / 2, -h * scale / 2, w * scale, h * scale);
            g.setTransform(saved);
        }
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
        if (seat == 0) {
            int index = hits.size();
            // 相同的牌只抬起第一張，看起來才是選了一張
            boolean chosen = tile.equals(picked) && hits.stream().noneMatch(hit -> hit.tile().equals(picked));
            addHit(x, y, hitW, h, tile);
            masked = pickable != null && !pickable.contains(tile) && !chosen;
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

    private void paintCenterText(Graphics2D g, double unit) {
        double half = 70 * unit;
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new RoundRectangle2D.Double(-half, -half, half * 2, half * 2, 12 * unit, 12 * unit));
        g.setColor(Theme.GOLD);
        g.setFont(Theme.font(Font.BOLD, (float) (22 * unit)));
        String label = roundLabel(round);
        // 字體調大時四個字會超出方框，縮回框內
        int labelWidth = g.getFontMetrics().stringWidth(label);
        double maxWidth = half * 2 - 12 * unit;
        if (labelWidth > maxWidth) {
            g.setFont(g.getFont().deriveFont((float) (g.getFont().getSize2D() * maxWidth / labelWidth)));
        }
        drawCenteredLine(g, label, -14 * unit);
        g.setColor(Theme.TEXT);
        g.setFont(Theme.font(Font.PLAIN, (float) (16 * unit)));
        drawCenteredLine(g, "剩餘 " + (round.liveTiles() + undealtTiles()) + " 張", 22 * unit);
    }

    /** 圈風加局風，例如「東風東局」「南風西局」。舊服務器沒有送局風時是原本的「東風圈」。牌桌中間與右側都用這個。 */
    static String roundLabel(RoundView round) {
        int hand = round.handWind();
        return Tile.windName(round.roundWind()) + "風" + (hand >= 1 && hand <= 4 ? Tile.windName(hand) + "局" : "圈");
    }

    private static void drawCenteredLine(Graphics2D g, String text, double y) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (-metrics.stringWidth(text) / 2.0), (float) y);
    }
}
