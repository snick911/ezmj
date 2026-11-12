package ezmj.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

import javax.swing.JComponent;

import ezmj.model.Tile;

/**
 * 一排牌：修練的手牌（可以蓋起來）、照順序點的答案（點不滿的格子畫虛框）與正解。牌的大小跟著字體大小，
 * 放不下 maxWidth 時整排縮小。
 */
final class TileRow extends JComponent {

    private static final int GAP = 4;

    private final int baseWidth;
    private final int maxWidth;
    private List<Tile> tiles = List.of();
    private int slots;
    private boolean covered;

    /** tileWidth 是字體 100% 時一張牌的寬度；maxWidth 是整排最寬多少（已經照字體算好），0 是不限。 */
    TileRow(int tileWidth, int maxWidth) {
        this.baseWidth = Theme.scaled(tileWidth);
        this.maxWidth = maxWidth;
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
    }

    /** 顯示這幾張牌；slots 比牌多時後面畫空格；covered 時全部蓋起來。 */
    void show(List<Tile> next, int slotCount, boolean cover) {
        tiles = List.copyOf(next);
        slots = Math.max(slotCount, next.size());
        covered = cover;
        Dimension size = new Dimension(width(), tileWidth() * 4 / 3 + Theme.scaled(6));
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        revalidate();
        repaint();
    }

    private int tileWidth() {
        int count = Math.max(1, slots);
        int gap = Theme.scaled(GAP);
        if (maxWidth > 0 && count * (baseWidth + gap) > maxWidth) {
            return Math.max(Theme.scaled(18), maxWidth / count - gap);
        }
        return baseWidth;
    }

    private int width() {
        return Math.max(1, slots) * (tileWidth() + Theme.scaled(GAP));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = tileWidth();
        int h = w * 4 / 3;
        int gap = Theme.scaled(GAP);
        for (int i = 0; i < slots; i++) {
            int x = i * (w + gap);
            if (i < tiles.size()) {
                if (covered) {
                    TilePainter.paintBack(g, x, 0, w, h);
                } else {
                    TilePainter.paintFace(g, tiles.get(i), x, 0, w, h);
                }
            } else {
                g.setColor(Theme.MUTED);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                        new float[] {5f, 4f}, 0f));
                g.draw(new RoundRectangle2D.Double(x + 1, 1, w - 2, h - 2, 6, 6));
            }
        }
        g.dispose();
    }
}
