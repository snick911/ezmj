package ezmj.ui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import ezmj.AppVersion;
import ezmj.career.CareerSlot;
import ezmj.career.Challenge;
import ezmj.model.Suit;
import ezmj.model.Tile;

/** 開始畫面：選修業模式、練習牌局或連線對戰。 */
public final class HomePanel extends JPanel {

    private final JButton careerButton = Theme.button("修業模式", Theme.RED);
    private final JButton practiceButton = Theme.button("練習牌局", Theme.BUTTON);
    private final JButton onlineButton = Theme.button("連線對戰", Theme.BUTTON);

    HomePanel(Runnable onCareer, Runnable onPractice, Runnable onOnline) {
        super(new GridBagLayout());

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.GOLD, 1),
                BorderFactory.createEmptyBorder(28, 44, 32, 44)));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.insets = new Insets(0, 0, 8, 0);
        card.add(new TileBanner(), c);

        JLabel title = new JLabel("台灣十六張麻將", SwingConstants.CENTER);
        title.setFont(Theme.font(Font.BOLD, 34f));
        title.setForeground(Theme.GOLD);
        c.gridy++;
        c.insets = new Insets(0, 0, 22, 0);
        card.add(title, c);

        addMode(card, c, careerButton, "建立自己的檔案，籌碼一路帶著打，輸贏不會重來；還有 "
                + Challenge.values().length + " 道挑戰關卡。最多 " + CareerSlot.COUNT + " 個檔案，紀錄各自分開。", onCareer);
        addMode(card, c, practiceButton, "每場自訂籌碼、底台與電腦等級，打完就重來。", onPractice);
        addMode(card, c, onlineButton, "開房間或加入房間，和其他玩家同桌。", onOnline);
        syncSizes();

        // 完整的授權聲明在「說明」→「關於」
        JLabel version = new JLabel("版本 " + AppVersion.get() + "　" + HelpDialog.COPYRIGHT
                + "　依 AGPL-3.0 授權，不提供任何擔保", SwingConstants.CENTER);
        version.setFont(Theme.font(Font.PLAIN, 13f));
        version.setForeground(Theme.MUTED);
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(18, 0, 0, 0);
        card.add(version, c);

        add(card);
    }

    private static void addMode(JPanel card, GridBagConstraints c, JButton button, String text, Runnable action) {
        button.setFont(Theme.font(Font.BOLD, 20f));
        button.addActionListener(e -> action.run());
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 1;
        c.anchor = GridBagConstraints.CENTER;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(8, 0, 8, 20);
        card.add(button, c);
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(Font.PLAIN, 15f));
        label.setForeground(Theme.MUTED);
        c.gridx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(8, 0, 8, 0);
        card.add(wrapped(label, text, 430), c);
    }

    /** 說明太長時折行，width 是畫面上的寬度，跟著字體大小。text 會當成 HTML，從檔案或使用者來的字要先 escape。 */
    static JComponent wrapped(JLabel label, String text, int width) {
        label.setText(wrapHtml(text, Theme.scaled(width)));
        return label;
    }

    /** Swing 的 HTML 把 CSS 的 px 放大 1.3 倍，要先除回來，折行的寬度才是畫面上的 pixels。 */
    static String wrapHtml(String text, int pixels) {
        return "<html><body style='width:" + Math.round(pixels / 1.3f) + "px'>" + text + "</body></html>";
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 字體大小改變後，讓按鈕跟著調整。 */
    void syncSizes() {
        for (JButton button : List.of(careerButton, practiceButton, onlineButton)) {
            button.setPreferredSize(new Dimension(Theme.scaled(220), Theme.scaled(52)));
        }
    }

    /** 回到這個畫面時，焦點放在第一個按鈕。 */
    void focusFirst() {
        careerButton.requestFocusInWindow();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        StartPanel.paintFelt(this, graphics);
    }

    /** 標題上方的裝飾牌。 */
    static final class TileBanner extends JComponent {
        private static final Tile[] TILES = {
            Tile.of(Suit.WAN, 1), Tile.of(Suit.TONG, 5), Tile.of(Suit.TIAO, 9),
            Tile.of(Suit.WIND, 1), Tile.of(Suit.DRAGON, 1), Tile.of(Suit.DRAGON, 2),
        };

        TileBanner() {
            setPreferredSize(new Dimension(300, 66));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double w = 42;
            double x = (getWidth() - w * TILES.length - 4 * (TILES.length - 1)) / 2;
            for (Tile tile : TILES) {
                TilePainter.paintFace(g, tile, x, 2, w, 58);
                x += w + 4;
            }
            g.dispose();
        }
    }
}
