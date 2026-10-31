package ezmj.ui;

import java.awt.Color;
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
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JComponent;

import ezmj.engine.Round;
import ezmj.engine.RoundResult;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;

/**
 * 牌桌畫面。四家共用同一套繪製程式：先把座標旋轉到該家視角，
 * 再以「自己坐在下方」的區域座標繪製，所以棄牌會自然排成風車狀。
 */
final class TableCanvas extends JComponent {

    private static final int DISCARD_COLUMNS = 8;

    private record HandHit(Rectangle bounds, Tile tile) {
    }

    private final Consumer<Tile> onDiscard;
    private final List<HandHit> hits = new ArrayList<>();
    private Round round;
    private boolean discardEnabled;
    private int hover = -1;

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
        repaint();
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
        }
        g.dispose();
    }

    private void paintSeat(Graphics2D g, int seat, double halfLength, double halfDepth, double unit) {
        Player player = round.player(seat);
        paintSeatLabel(g, seat, player, unit);
        paintDiscards(g, seat, player, unit);
        if (seat == 0) {
            paintRow(g, player, seat, halfLength, halfDepth, unit, 54 * unit, 8 * unit);
        } else {
            paintRow(g, player, seat, halfLength, halfDepth, unit, 32 * unit, 8 * unit);
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
        g.setFont(Theme.font(Font.BOLD, (float) (14 * unit)));
        g.setColor(active ? Color.BLACK : Theme.TEXT);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (float) (-metrics.stringWidth(text) / 2.0),
                (float) (half - 15 * unit + metrics.getAscent() / 2.0 - metrics.getDescent() / 2.0));
    }

    private void paintDiscards(Graphics2D g, int seat, Player player, double unit) {
        double tileW = 25 * unit;
        double tileH = 33 * unit;
        double startX = -tileW * DISCARD_COLUMNS / 2;
        double startY = 104 * unit;
        List<Tile> discards = player.discards();
        for (int i = 0; i < discards.size(); i++) {
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

    private void paintRow(Graphics2D g, Player player, int seat, double halfLength, double halfDepth,
                          double unit, double preferredWidth, double margin) {
        boolean finished = round.phase() == Round.Phase.FINISHED;
        boolean faceUp = seat == 0 || finished;

        List<Tile> hand = new ArrayList<>(player.concealed());
        Tile separated = separatedTile(seat, finished);
        if (separated != null) {
            hand.remove(separated);
        }

        int meldTiles = player.melds().stream().mapToInt(m -> m.tiles().size()).sum();
        double gapSlots = (separated != null ? .4 : 0) + player.melds().size() * .25;
        double slots = hand.size() + (separated != null ? 1 : 0) + meldTiles + gapSlots;
        double tileW = Math.min(preferredWidth, halfLength * 2 * .96 / Math.max(slots, 18.6));
        double tileH = tileW * 1.38;
        double gap = tileW * .25;

        double total = slots * tileW;
        double x = -total / 2;
        double y = halfDepth - tileH - margin;

        for (Tile tile : hand) {
            paintHandTile(g, seat, tile, x, y, tileW, tileH, faceUp);
            x += tileW;
        }
        if (separated != null) {
            x += tileW * .4;
            paintHandTile(g, seat, separated, x, y, tileW, tileH, faceUp);
            x += tileW;
        }
        for (Meld meld : player.melds()) {
            x += gap;
            for (int i = 0; i < meld.tiles().size(); i++) {
                // 暗槓只亮中間兩張給自己看，其他家全蓋
                boolean hidden = meld.isConcealed() && !finished && (seat != 0 || i == 0 || i == 3);
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
