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
import java.util.ArrayList;
import java.util.List;
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

    private static final int DISCARD_COLUMNS = 8;
    private static final double DISCARD_W = 25;
    private static final double DISCARD_H = 33;
    private static final double DISCARD_TOP = 104;
    private static final int FRAME_MS = 16;
    private static final int RING_MS = 170;
    private static final int CLAIM_BANNER_MS = 800;

    private record HandHit(Rectangle bounds, Tile tile) {
    }

    /** 一家手牌列的位置，都是該家視角的座標。 */
    private record RowLayout(double x, double y, double tileW, double tileH, double gap, double total) {
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
    private int hover = -1;
    private long frameNow;

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
                if (discardEnabled && hover >= 0 && hover < hits.size()) {
                    onDiscard.accept(hits.get(hover).tile());
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
        repaint();
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
        flights.removeIf(flight -> flight.finished(now));
        banners.removeIf(banner -> banner.progress(now) >= 1);
        if (flights.isEmpty() && banners.isEmpty()) {
            frameTimer.stop();
        }
        repaint();
    }

    private double unit() {
        return Math.min(getWidth(), getHeight()) / 720.0;
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
        double unit = unit();
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
        double w = (DISCARD_W - 1) * unit();
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
        double meldX = row.x() + row.total() - meld.tiles().size() * row.tileW();

        Point2D.Double slot = discardCenter(round.player(event.from()).discards().size());
        Point2D from = toTable(event.from(), slot.x, slot.y);
        Point2D to = toTable(seat, meldX + (tileIndex + .5) * row.tileW(), row.y() + row.tileH() / 2);
        double turn = Math.IEEEremainder(seatAngle(seat) - seatAngle(event.from()), Math.PI * 2);
        flights.add(new Flight(event.tile(), from.getX(), from.getY(), seatAngle(event.from()),
                (DISCARD_W - 1) * unit(), to.getX(), to.getY(), seatAngle(event.from()) + turn, row.tileW(),
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

    void setDiscardEnabled(boolean enabled) {
        discardEnabled = enabled;
        hover = -1;
        repaint();
    }

    private void updateHover(MouseEvent e) {
        int next = -1;
        if (discardEnabled) {
            for (int i = 0; i < hits.size(); i++) {
                if (hits.get(i).bounds().contains(e.getPoint())) {
                    next = i;
                    break;
                }
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
        float radius = Math.max(1, Math.max(width, height) * .7f);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(width / 2f, height / 2f), radius,
                new float[] {0f, 1f}, new Color[] {Theme.FELT, Theme.FELT_DARK}));
        g.fillRect(0, 0, width, height);

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
            paintCenterText(g, unit);
            for (Flight flight : flights) {
                paintFlight(g, flight);
            }
            for (Banner banner : banners) {
                paintBanner(g, banner);
            }
        }
        g.dispose();
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
        paintSeatLabel(g, seat, player, unit);
        paintDiscards(g, seat, player, unit);
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
        double unit = unit();
        Player player = round.player(seat);
        double halfLength = (seat % 2 == 0 ? getWidth() : getHeight()) / 2.0;
        double preferredWidth = (seat == 0 ? 54 : 32) * unit;
        int meldTiles = player.melds().stream().mapToInt(m -> m.tiles().size()).sum();
        double gapSlots = (separated != null ? .4 : 0) + player.melds().size() * .25;
        double slots = player.concealed().size() + meldTiles + gapSlots;
        double tileW = Math.min(preferredWidth, halfLength * 2 * .96 / Math.max(slots, 18.6));
        double tileH = tileW * 1.38;
        double total = slots * tileW;
        return new RowLayout(-total / 2, halfDepth(seat) - tileH - 8 * unit, tileW, tileH, tileW * .25, total);
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
        double gap = row.gap();
        double total = row.total();
        double x = row.x();
        double y = row.y();

        for (Tile tile : hand) {
            paintHandTile(g, seat, tile, x, y, tileW, tileH, faceUp);
            x += tileW;
        }
        if (separated != null) {
            x += tileW * .4;
            paintHandTile(g, seat, separated, x, y, tileW, tileH, faceUp);
            x += tileW;
        }
        for (int m = 0; m < player.melds().size(); m++) {
            Meld meld = player.melds().get(m);
            boolean newest = m == player.melds().size() - 1;
            x += gap;
            for (int i = 0; i < meld.tiles().size(); i++) {
                // 暗槓只亮中間兩張給自己看，其他家全蓋
                boolean hidden = meld.isConcealed() && !finished && (seat != 0 || i == 0 || i == 3);
                if (newest && meldTileHidden(seat, i)) {
                    x += tileW;
                    continue;
                }
                if (hidden) {
                    TilePainter.paintBack(g, x, y, tileW, tileH);
                } else {
                    TilePainter.paintFace(g, meld.tiles().get(i), x, y, tileW, tileH);
                }
                x += tileW;
            }
        }
        // 面子多時手牌變寬，花牌要避開隔壁家的手牌
        paintFlowers(g, player, Math.max(-total / 2, -halfLength + 66 * unit), y, unit);
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
                               boolean faceUp) {
        double drawY = y;
        if (seat == 0) {
            int index = hits.size();
            hits.add(new HandHit(new Rectangle((int) (x + getWidth() / 2.0), (int) (y + getHeight() / 2.0),
                    (int) Math.ceil(w), (int) Math.ceil(h)), tile));
            if (discardEnabled && index == hover) {
                drawY -= h * .16;
            }
        }
        if (faceUp) {
            TilePainter.paintFace(g, tile, x, drawY, w, h);
        } else {
            TilePainter.paintBack(g, x, drawY, w, h);
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
        drawCenteredLine(g, "剩餘 " + round.wall().liveCount() + " 張", 22 * unit);
    }

    private static void drawCenteredLine(Graphics2D g, String text, double y) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (-metrics.stringWidth(text) / 2.0), (float) y);
    }
}
