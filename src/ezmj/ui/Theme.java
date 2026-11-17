package ezmj.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.UIManager;

/** 共用的顏色、字型與按鈕樣式。 */
final class Theme {

    static final Color FELT = new Color(0x14, 0x5A, 0x43);
    static final Color FELT_DARK = new Color(0x0B, 0x3B, 0x2C);
    static final Color PANEL = new Color(0x12, 0x26, 0x20);
    static final Color PANEL_LIGHT = new Color(0x1E, 0x38, 0x30);
    static final Color TEXT = new Color(0xF2, 0xEF, 0xE4);
    static final Color MUTED = new Color(0xA9, 0xBD, 0xB3);
    static final Color GOLD = new Color(0xE8, 0xC0, 0x5A);
    static final Color RED = new Color(0xC9, 0x3A, 0x32);
    static final Color BUTTON = new Color(0x2C, 0x6E, 0x58);
    /** 半透明按鈕平常的不透明度。 */
    private static final float TRANSLUCENT_ALPHA = .5f;

    private static final String FAMILY = pickFamily(Font.DIALOG,
            "Microsoft JhengHei", "PingFang TC", "Noto Sans CJK TC", "Noto Sans TC");
    private static final String BRUSH_FAMILY = pickFamily(FAMILY,
            "DFKai-SB", "標楷體", "BiauKai", "Kaiti TC", "AR PL UKai TW", "KaiTi");

    /** 「字體大小」設定的比例。 */
    private static float fontPercentScale = 1f;
    /** 視窗比預設解析度大時，整個畫面跟著放大的比例，最小是 1，見 Resolution.zoomFor。 */
    private static float zoom = 1f;
    /** 字體大小乘上視窗放大的比例，font 與 scaled 用這個。 */
    private static float fontScale = 1f;
    private static double tileScale = 1;

    private Theme() {
    }

    private static String pickFamily(String fallback, String... families) {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String family : families) {
            if (available.contains(family)) {
                return family;
            }
        }
        return fallback;
    }

    /** 牌面上的毛筆字；電腦沒有楷書字型時用一般字型。 */
    static Font brushFont(float size) {
        return new Font(BRUSH_FAMILY, Font.BOLD, 1).deriveFont(size);
    }

    /** 會隨字體大小設定與視窗大小縮放的字型。 */
    static Font font(int style, float size) {
        return fixedFont(style, size * fontScale);
    }

    /** 牌桌畫布上的字：畫布自己照視窗大小縮放，這裡只跟字體大小設定，不然視窗變大時會放大兩次。 */
    static Font canvasFont(int style, float size) {
        return fixedFont(style, size * fontPercentScale);
    }

    /** 不縮放的字型，用在要剛好塞進牌面的文字。 */
    static Font fixedFont(int style, float size) {
        return new Font(FAMILY, style, 1).deriveFont(size);
    }

    /** 依字體大小設定與視窗大小縮放的尺寸，用在要容納文字的固定寬高。 */
    static int scaled(int size) {
        return Math.round(size * fontScale);
    }

    /** 麻將大小的比例。牌桌每次重畫都會讀這個值，結果視窗在建立時讀。 */
    static double tileScale() {
        return tileScale;
    }

    static void setTilePercent(int percent) {
        tileScale = percent / 100.0;
    }

    /** 改變字體大小，並套用到 roots 底下已經建立的元件。 */
    static void setFontPercent(int percent, Component... roots) {
        fontPercentScale = percent / 100f;
        applyScale(roots);
    }

    static float zoom() {
        return zoom;
    }

    /** 視窗大小改變時，整個畫面跟著放大縮小，套用到 roots 底下已經建立的元件。 */
    static void setZoom(float next, Component... roots) {
        zoom = next;
        applyScale(roots);
    }

    private static void applyScale(Component... roots) {
        float next = fontPercentScale * zoom;
        float ratio = next / fontScale;
        fontScale = next;
        UIManager.put("OptionPane.messageFont", font(Font.PLAIN, 15f));
        UIManager.put("OptionPane.buttonFont", font(Font.PLAIN, 15f));
        for (Component root : roots) {
            rescale(root, ratio);
            root.revalidate();
            root.repaint();
        }
    }

    private static void rescale(Component component, float ratio) {
        // 繼承自上層的字型會跟著上層變，再縮放一次就重複了
        if (component.isFontSet()) {
            Font font = component.getFont();
            component.setFont(font.deriveFont(font.getSize2D() * ratio));
        }
        if (component instanceof JMenu) {
            for (Component item : ((JMenu) component).getMenuComponents()) {
                rescale(item, ratio);
            }
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                rescale(child, ratio);
            }
        }
    }

    static JButton button(String text, Color background) {
        return new ThemeButton(text, background, false);
    }

    /** 半透明的按鈕：可以用、但不一定要按的選項。滑鼠移上去時變成不透明，按下時顏色變深。 */
    static JButton translucentButton(String text, Color background) {
        return new ThemeButton(text, background, true);
    }

    /** 直角的按鈕，例如和列表連在一起的頁籤。不是 button 做的按鈕不變。 */
    static void squareCorners(JButton button) {
        if (button instanceof ThemeButton) {
            ((ThemeButton) button).corner = 0;
            button.repaint();
        }
    }

    /** 按鈕圓角的直徑，跟著字體大小縮放；0 是直角。 */
    static int buttonArc(JButton button) {
        return button instanceof ThemeButton ? scaled(((ThemeButton) button).corner) : 0;
    }

    /** a 往 b 調 amount（0 到 1），透明度照 a。 */
    static Color mix(Color a, Color b, double amount) {
        double keep = 1 - amount;
        return new Color((int) Math.round(a.getRed() * keep + b.getRed() * amount),
                (int) Math.round(a.getGreen() * keep + b.getGreen() * amount),
                (int) Math.round(a.getBlue() * keep + b.getBlue() * amount), a.getAlpha());
    }

    /**
     * 所有色塊按鈕：圓角，上面亮一點、下面暗一點，頂端一道細的亮線。滑鼠移上去變亮，按下變深、字往下沉一點，
     * 不能按時底色淡掉；游標是手指，不能按時換回箭頭。用 Tab 移過來時外面一圈金框，看得出空白鍵會按到哪一個；
     * 滑鼠點的或程式放的焦點不畫。translucent 的平常連字一起半透明，滑鼠移上去或按下才不透明。
     */
    private static final class ThemeButton extends JButton {
        private static final Cursor HAND = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        private static final int CORNER = 10;

        private final boolean translucent;
        /** 圓角的直徑，還沒縮放。 */
        private int corner = CORNER;
        /**
         * 焦點是用 Tab 移過來的。畫面一打開程式自己把焦點放在第一個按鈕（例如開始畫面的「修業模式」、
         * 視窗的第一個按鈕）時不算，不畫金框，看起來和以前一樣。
         */
        private boolean keyboardFocus;

        ThemeButton(String text, Color background, boolean translucent) {
            super(text);
            this.translucent = translucent;
            setFont(font(Font.BOLD, 16f));
            setForeground(Color.WHITE);
            setBackground(background);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
            // 底色自己畫，圓角外面才透得出後面
            setOpaque(false);
            setContentAreaFilled(false);
            setRolloverEnabled(true);
            // 滑鼠點了照舊拿到焦點（例如等待秒數打到一半直接按開房間，要先收下打的數字），只是不畫金框
            setCursor(HAND);
            addPropertyChangeListener("enabled", e -> setCursor(isEnabled() ? HAND : Cursor.getDefaultCursor()));
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    keyboardFocus = e.getCause().name().startsWith("TRAVERSAL");
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    keyboardFocus = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel model = getModel();
            boolean pressed = model.isArmed() && model.isPressed();
            boolean hover = isEnabled() && model.isRollover();
            Color base = getBackground();
            Color fill = !isEnabled() ? mix(base, PANEL, .55)
                    : pressed ? mix(base, Color.BLACK, .22)
                    : hover ? mix(base, Color.WHITE, .14) : base;
            if (translucent && !hover && !pressed) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, TRANSLUCENT_ALPHA));
            }
            int w = getWidth();
            int h = getHeight();
            double arc = scaled(corner);
            Shape shape = arc > 0 ? new RoundRectangle2D.Double(0, 0, w, h, arc, arc) : new Rectangle2D.Double(0, 0, w, h);
            g.setPaint(new GradientPaint(0, 0, mix(fill, Color.WHITE, pressed ? 0 : .09), 0, h,
                    mix(fill, Color.BLACK, pressed ? .02 : .1)));
            g.fill(shape);
            if (isEnabled()) {
                // 按下時亮線換成上緣的一道陰影，看起來是壓下去
                Graphics2D edge = (Graphics2D) g.create();
                edge.clip(shape);
                edge.setColor(pressed ? new Color(0, 0, 0, 70) : new Color(255, 255, 255, hover ? 70 : 45));
                edge.fill(new Rectangle2D.Double(0, 0, w, Math.max(1, scaled(pressed ? 2 : 1))));
                edge.dispose();
            }
            if (keyboardFocus && isFocusOwner()) {
                float stroke = Math.max(2f, scaled(2));
                g.setComposite(AlphaComposite.SrcOver);
                g.setColor(GOLD);
                g.setStroke(new BasicStroke(stroke));
                double inset = stroke / 2;
                g.draw(arc > 0 ? new RoundRectangle2D.Double(inset, inset, w - stroke, h - stroke, arc - inset, arc - inset)
                        : new Rectangle2D.Double(inset, inset, w - stroke, h - stroke));
            }
            g.dispose();
            Graphics2D text = (Graphics2D) graphics.create();
            if (translucent && !hover && !pressed) {
                // 和以前一樣，平常連字也是半透明
                text.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, TRANSLUCENT_ALPHA));
            }
            if (pressed) {
                text.translate(0, 1);
            }
            super.paintComponent(text);
            text.dispose();
        }
    }
}
