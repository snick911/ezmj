package ezmj.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.JComponent;

import ezmj.model.Tile;

/**
 * 修練作答用的 34 種牌：萬、筒、條各一排，最後一排是東南西北中發白。
 * 兩種用法：SET 是點一下選、再點一下取消（看聽選聽哪幾種）；SEQUENCE 是照順序點，點滿 length 張就不能再點（記牌點打過的牌）。
 * 牌的大小跟著字體大小。
 */
final class TilePicker extends JComponent {

    enum Mode {
        SET, SEQUENCE
    }

    private static final int COLUMNS = 9;
    private static final int ROWS = 4;
    private static final int TILE_WIDTH = 34;
    private static final int MAX_TILE_WIDTH = 46;
    private static final int GAP = 6;
    private static final Color HOVER = new Color(255, 255, 255, 60);

    /** 字體放大時牌也放大，但最多 MAX_TILE_WIDTH，字體 150% 時才放得進最小的視窗。 */
    private final int tileWidth = Math.min(Theme.scaled(TILE_WIDTH), MAX_TILE_WIDTH);
    private final int tileHeight = tileWidth * 4 / 3;
    private final int gap = Theme.scaled(GAP);
    private final List<Tile> picked = new ArrayList<>();
    private Mode mode = Mode.SET;
    private int length;
    private int hover = -1;
    private Runnable onChange = () -> { };

    TilePicker() {
        setOpaque(false);
        setPreferredSize(new Dimension(COLUMNS * (tileWidth + gap) + gap, ROWS * (tileHeight + gap) + gap));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = indexAt(e.getX(), e.getY());
                if (index >= 0 && isEnabled()) {
                    toggle(Tile.fromIndex(index));
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int index = indexAt(e.getX(), e.getY());
                if (index != hover) {
                    hover = index;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** 換一題：清掉選的牌。SEQUENCE 時 length 是要點幾張。 */
    void reset(Mode next, int count) {
        mode = next;
        length = count;
        picked.clear();
        repaint();
        onChange.run();
    }

    /** 選的牌一有變動就通知，例如更新「確定」按鈕。 */
    void onChange(Runnable listener) {
        onChange = listener;
    }

    /** SET 是選了哪幾種（照點的順序），SEQUENCE 是照順序點的牌。 */
    List<Tile> picked() {
        return List.copyOf(picked);
    }

    /** SEQUENCE 點滿了。 */
    boolean full() {
        return mode == Mode.SEQUENCE && picked.size() >= length;
    }

    void clear() {
        picked.clear();
        repaint();
        onChange.run();
    }

    /** 拿掉最後點的那張。 */
    void undo() {
        if (!picked.isEmpty()) {
            picked.remove(picked.size() - 1);
            repaint();
            onChange.run();
        }
    }

    private void toggle(Tile tile) {
        if (mode == Mode.SET) {
            if (!picked.remove(tile)) {
                picked.add(tile);
            }
        } else if (picked.size() < length) {
            picked.add(tile);
        }
        repaint();
        onChange.run();
    }

    private int indexAt(int x, int y) {
        int column = (x - gap) / (tileWidth + gap);
        int row = (y - gap) / (tileHeight + gap);
        if (x < gap || y < gap || column >= COLUMNS || row >= ROWS) {
            return -1;
        }
        int inX = (x - gap) % (tileWidth + gap);
        int inY = (y - gap) % (tileHeight + gap);
        if (inX >= tileWidth || inY >= tileHeight) {
            return -1;
        }
        int index = row * COLUMNS + column;
        return index < Tile.KINDS ? index : -1;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Set<Tile> chosen = new LinkedHashSet<>(picked);
        for (int index = 0; index < Tile.KINDS; index++) {
            Tile tile = Tile.fromIndex(index);
            int x = gap + (index % COLUMNS) * (tileWidth + gap);
            int y = gap + (index / COLUMNS) * (tileHeight + gap);
            TilePainter.paintFace(g, tile, x, y, tileWidth, tileHeight);
            if (!isEnabled() || mode == Mode.SEQUENCE && full() && !chosen.contains(tile)) {
                TilePainter.paintMask(g, x, y, tileWidth, tileHeight);
            } else if (index == hover) {
                g.setColor(HOVER);
                g.fillRect(x, y, tileWidth, tileHeight);
            }
            if (chosen.contains(tile)) {
                TilePainter.paintHighlight(g, x, y, tileWidth, tileHeight, Theme.GOLD);
            }
        }
        g.dispose();
    }
}
