package ezmj.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;

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

    private static final String FAMILY = pickFamily();

    private Theme() {
    }

    private static String pickFamily() {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String family : new String[] {"Microsoft JhengHei", "PingFang TC", "Noto Sans CJK TC", "Noto Sans TC"}) {
            if (available.contains(family)) {
                return family;
            }
        }
        return Font.DIALOG;
    }

    static Font font(int style, float size) {
        return new Font(FAMILY, style, 1).deriveFont(size);
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
}
