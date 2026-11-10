package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;

/**
 * 主視窗裡的畫面（開始畫面、練習牌局、修業模式、連線對戰）包一層捲軸：
 * 放得下時畫面照舊撐滿視窗、捲軸不出現；視窗太小或字體太大、連畫面的最小大小都放不下時，
 * 照最小大小排版，多出來的捲著看，按鈕不會被切掉。牌桌自己會縮放，不用包。
 */
final class FitScroll {

    private static final int SCROLL_STEP = 24;

    private FitScroll() {
    }

    static JScrollPane wrap(JComponent panel) {
        JScrollPane scroll = new JScrollPane(new View(panel), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.FELT);
        scroll.getVerticalScrollBar().setUnitIncrement(SCROLL_STEP);
        scroll.getHorizontalScrollBar().setUnitIncrement(SCROLL_STEP);
        return scroll;
    }

    /** 視窗比畫面的最小大小寬（高）就跟著視窗，不然照最小大小，讓捲軸出來。 */
    private static final class View extends JPanel implements Scrollable {
        private final JComponent panel;

        View(JComponent panel) {
            super(new BorderLayout());
            this.panel = panel;
            setOpaque(false);
            add(panel, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredSize() {
            return panel.getMinimumSize();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return panel.getPreferredSize();
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            Container parent = getParent();
            return parent instanceof JViewport && parent.getWidth() >= panel.getMinimumSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Container parent = getParent();
            return parent instanceof JViewport && parent.getHeight() >= panel.getMinimumSize().height;
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return SCROLL_STEP;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL ? visible.height - SCROLL_STEP
                    : visible.width - SCROLL_STEP;
        }
    }
}
