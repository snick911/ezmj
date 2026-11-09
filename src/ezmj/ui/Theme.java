package ezmj.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.swing.BorderFactory;
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

    /** 會隨字體大小設定縮放的字型。 */
    static Font font(int style, float size) {
        return fixedFont(style, size * fontScale);
    }

    /** 不縮放的字型，用在要剛好塞進牌面的文字。 */
    static Font fixedFont(int style, float size) {
        return new Font(FAMILY, style, 1).deriveFont(size);
    }

    /** 依字體大小設定縮放的尺寸，用在要容納文字的固定寬高。 */
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
        float next = percent / 100f;
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
        JButton button = new JButton(text);
        button.setFont(font(Font.BOLD, 16f));
        button.setForeground(Color.WHITE);
        button.setBackground(background);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        return button;
    }

    /** 半透明的按鈕：可以用、但不一定要按的選項。滑鼠移上去時變成不透明，按下時顏色變深。 */
    static JButton translucentButton(String text, Color background) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                float alpha = getModel().isRollover() || getModel().isPressed() ? 1f : TRANSLUCENT_ALPHA;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(getModel().isPressed() ? getBackground().darker() : getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g2);
                g2.dispose();
            }
        };
        button.setFont(font(Font.BOLD, 16f));
        button.setForeground(Color.WHITE);
        button.setBackground(background);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        // 底色自己畫，才能半透明
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setRolloverEnabled(true);
        return button;
    }
}
