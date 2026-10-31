package ezmj.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
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
    static final int WIN_BANNER_MS = 1100;
    /** 發牌每四張一批輪流發，最後一批是莊家的第 17 張。 */
    static final int DEAL_PACKETS = 17;
    static final int DEAL_FLIGHT_MS = 200;
    static final int DEAL_INTERVAL_MS = 70;
    static final int DEAL_MS = (DEAL_PACKETS - 1) * DEAL_INTERVAL_MS + DEAL_FLIGHT_MS;

    private static final int DISCARD_COLUMNS = 8;
    private static final double DISCARD_W = 25;
    private static final double DISCARD_H = 33;
    private static final double DISCARD_TOP = 104;
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

    /**
     * 飛行中的一張牌。位置以牌桌中心為原點，指牌的中心點。
     * 飛行時對應的那張牌不畫出來，落地後才出現。
     */
    private record Flight(Tile tile, double fromX, double fromY, double fromAngle, double fromW,
                          double toX, double toY, double toAngle, double toW, long start, int durationMs,
                          int hiddenDiscardSeat, int hiddenMeldSeat, int hiddenMeldIndex) {
        double progress(long now) {
            return (now - start) / 1e6 / durationMs;
        }

        boolean finished(long now) {
            return (now - start) / 1e6 >= durationMs + RING_MS;
        }
    }

    /** 跳出來的大字。位置以牌桌中心為原點。 */
    private record Banner(String text, double x, double y, double size, Color color, boolean burst,
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
    private Round round;
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

    void setRound(Round round) {
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
        flights.clear();
        switch (event.type()) {
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
                addCenterBanner("胡", Theme.GOLD, true);
                break;
            case SELF_WIN:
                addCenterBanner("自摸", Theme.GOLD, true);
                break;
            default:
                addCenterBanner("流局", Theme.MUTED, false);
                break;
        }
        frameNow = System.nanoTime();
        frameTimer.start();
        repaint();
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
        flights.add(new Flight(event.tile(), from.getX(), from.getY(), seatAngle(seat), fromW,
                target.getX(), target.getY(), seatAngle(seat), w, System.nanoTime(), DISCARD_FLIGHT_MS,
                seat, -1, -1));
    }

    /** 被吃碰槓的牌從棄牌區飛進新面子，同時跳出大字。 */
    private void addClaim(RoundEvent event, String text) {
        int seat = event.seat();
        Player player = round.player(seat);
        Meld meld = player.melds().get(player.melds().size() - 1);
        int tileIndex = meld.tiles().indexOf(event.tile());
        RowLayout row = layoutRow(seat, separatedTile(seat, false));
        double meldX = row.x() + row.total() - meld.tiles().size() * row.step();

        Point2D.Double slot = discardCenter(round.player(event.from()).discards().size());
        Point2D from = toTable(event.from(), slot.x, slot.y);
        Point2D to = toTable(seat, meldX + tileIndex * row.step() + row.tileW() / 2, row.y() + row.tileH() / 2);
        double turn = Math.IEEEremainder(seatAngle(seat) - seatAngle(event.from()), Math.PI * 2);
        flights.add(new Flight(event.tile(), from.getX(), from.getY(), seatAngle(event.from()),
                (DISCARD_W - 1) * tableUnit(), to.getX(), to.getY(), seatAngle(event.from()) + turn, row.tileW(),
                System.nanoTime(), CLAIM_FLIGHT_MS, -1, seat, tileIndex));
        addSeatBanner(seat, text);
    }

    private void addSeatBanner(int seat, String text) {
        Point2D at = toTable(seat, 0, halfDepth(seat) * .62);
        banners.add(new Banner(text, at.getX(), at.getY(), 58 * unit(), Theme.GOLD, false,
                System.nanoTime(), CLAIM_BANNER_MS));
    }

    private void addCenterBanner(String text, Color color, boolean burst) {
        banners.clear();
        banners.add(new Banner(text, 0, 0, 120 * unit(), color, burst, System.nanoTime(), WIN_BANNER_MS));
    }

    private boolean discardHidden(int seat) {
        for (Flight flight : flights) {
            if (flight.hiddenDiscardSeat() == seat && flight.progress(frameNow) < 1) {
                return true;
            }
        }
        return false;
    }

    private boolean meldTileHidden(int seat, int tileIndex) {
        for (Flight flight : flights) {
            if (flight.hiddenMeldSeat() == seat && flight.hiddenMeldIndex() == tileIndex
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
            double unit = Math.min(width, height) / 720.0;
            double cx = width / 2.0;
            double cy = height / 2.0;
            hits.clear();
            AffineTransform base = g.getTransform();
            for (int seat = 0; seat < 4; seat++) {
                g.setTransform(base);
                g.translate(cx, cy);
                g.rotate(-Math.PI / 2 * seat);
                boolean horizontal = seat % 2 == 0;
                paintSeat(g, seat, (horizontal ? width : height) / 2.0, (horizontal ? height : width) / 2.0, unit);
            }
            g.setTransform(base);
            g.translate(cx, cy);
            paintCenterText(g, tableUnit());
            for (Flight flight : flights) {
                paintFlight(g, flight);
            }
            for (Banner banner : banners) {
                paintBanner(g, banner);
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
            paintLandingRing(g, flight, t);
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
        TilePainter.paintFace(g, flight.tile(), -w / 2, -h / 2, w, h);
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

    /** 大字先放大再彈回原本大小，最後淡出。 */
    private void paintBanner(Graphics2D g, Banner banner) {
        double t = Math.min(1, Math.max(0, banner.progress(frameNow)));
        double pop = t < .14 ? 1.7 - .7 * easeOut(t / .14) : 1;
        float alpha = (float) (t > .7 ? (1 - t) / .3 : 1);
        AffineTransform savedTransform = g.getTransform();
        Composite savedComposite = g.getComposite();
        g.translate(banner.x(), banner.y());
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        if (banner.burst()) {
            paintBurst(g, banner.size(), t);
        }
        g.scale(pop, pop);
        g.setFont(Theme.fixedFont(Font.BOLD, (float) banner.size()));
        FontMetrics metrics = g.getFontMetrics();
        double textW = metrics.stringWidth(banner.text());
        double padX = banner.size() * .35;
        double padY = banner.size() * .14;
        double boxH = metrics.getHeight() + padY * 2;
        RoundRectangle2D box = new RoundRectangle2D.Double(-textW / 2 - padX, -boxH / 2, textW + padX * 2, boxH,
                banner.size() * .3, banner.size() * .3);
        g.setColor(new Color(0x0B, 0x1E, 0x18, 215));
        g.fill(box);
        g.setColor(banner.color());
        g.setStroke(new BasicStroke((float) Math.max(1.5, banner.size() * .04)));
        g.draw(box);
        g.drawString(banner.text(), (float) (-textW / 2),
                (float) (metrics.getAscent() - metrics.getHeight() / 2.0));
        g.setTransform(savedTransform);
        g.setComposite(savedComposite);
    }

    /** 胡牌時從中央往外放射的光芒。 */
    private void paintBurst(Graphics2D g, double size, double t) {
        double spread = easeOut(Math.min(1, t * 1.6));
        double inner = size * (.6 + 1.2 * spread);
        double outer = size * (.9 + 2.4 * spread);
        g.setStroke(new BasicStroke((float) Math.max(2, size * .05), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2 * i / 16;
            double reach = i % 2 == 0 ? outer : inner + (outer - inner) * .55;
            g.setColor(i % 2 == 0 ? Theme.GOLD : Theme.RED);
            g.draw(new Line2D.Double(Math.cos(angle) * inner, Math.sin(angle) * inner,
                    Math.cos(angle) * reach, Math.sin(angle) * reach));
        }
    }

    private static double easeOut(double t) {
        double rest = 1 - t;
        return 1 - rest * rest * rest;
    }

    private void paintSeat(Graphics2D g, int seat, double halfLength, double halfDepth, double unit) {
        Player player = round.player(seat);
        double tableUnit = tableUnit();
        paintSeatLabel(g, seat, player, tableUnit);
        paintDiscards(g, seat, player, tableUnit);
        paintRow(g, player, seat, halfLength, unit);
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
        boolean lastHidden = discardHidden(seat);
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

    private void paintRow(Graphics2D g, Player player, int seat, double halfLength, double unit) {
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

        int index = 0;
        for (Tile tile : hand) {
            // 被下一張疊住的部分點不到，點擊範圍只算露出來的寬度
            double hitW = index == hand.size() - 1 ? tileW : step;
            paintDealtTile(g, seat, tile, index++, x, y, tileW, tileH, hitW, faceUp);
            x += step;
        }
        if (separated != null) {
            x += step * .4;
            paintDealtTile(g, seat, separated, index, x, y, tileW, tileH, tileW, faceUp);
            x += step;
        }
        for (int m = 0; m < player.melds().size(); m++) {
            Meld meld = player.melds().get(m);
            boolean newest = m == player.melds().size() - 1;
            x += gap;
            for (int i = 0; i < meld.tiles().size(); i++) {
                // 暗槓只亮中間兩張給自己看，其他家全蓋
                boolean hidden = meld.isConcealed() && !finished && (seat != 0 || i == 0 || i == 3);
                if (newest && meldTileHidden(seat, i)) {
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
        }
        // 面子多時手牌變寬，花牌要避開隔壁家的手牌
        if (!dealing()) {
            double inset = Math.max(66 * unit, botRowDepth() + 6 * unit);
            paintFlowers(g, player, Math.max(row.x(), -halfLength + inset), y, tableUnit());
        }
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
            return !result.isDrawnGame() && result.winner() == seat ? result.winTile() : null;
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
            hits.add(new HandHit(new Rectangle((int) (x + getWidth() / 2.0), (int) (y + getHeight() / 2.0),
                    (int) Math.ceil(hitW), (int) Math.ceil(h)), tile));
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
        double w = 22 * unit;
        double h = 30 * unit;
        double y = rowY - h - 6 * unit;
        for (Tile flower : player.flowers()) {
            TilePainter.paintFace(g, flower, x, y, w - 1, h);
            x += w;
        }
    }

    private void paintCenterText(Graphics2D g, double unit) {
        double half = 70 * unit;
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new RoundRectangle2D.Double(-half, -half, half * 2, half * 2, 12 * unit, 12 * unit));
        g.setColor(Theme.GOLD);
        g.setFont(Theme.font(Font.BOLD, (float) (22 * unit)));
        drawCenteredLine(g, Tile.windName(round.roundWind()) + "風圈", -14 * unit);
        g.setColor(Theme.TEXT);
        g.setFont(Theme.font(Font.PLAIN, (float) (16 * unit)));
        drawCenteredLine(g, "剩餘 " + (round.wall().liveCount() + undealtTiles()) + " 張", 22 * unit);
    }

    private static void drawCenteredLine(Graphics2D g, String text, double y) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (-metrics.stringWidth(text) / 2.0), (float) y);
    }
}
