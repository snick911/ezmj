package ezmj.ui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import ezmj.AppVersion;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Suit;
import ezmj.model.Tile;

/** 開始畫面：輸入姓名、選擇籌碼本金、底與台、電腦等級，按下按鈕即開始；有存檔時也能繼續上次的牌局。 */
public final class StartPanel extends JPanel {

    private static final int MAX_NAME_LENGTH = 8;
    private static final Integer[] CHIP_OPTIONS = {10_000, 20_000, 50_000, 100_000, 500_000};
    private static final Integer[] BASE_OPTIONS = {50, 100, 300, 500, 1_000};
    private static final Integer[] TAI_OPTIONS = {10, 20, 50, 100, 200};

    private final JTextField nameField = new JTextField(14);
    private final JComboBox<Integer> chipBox = combo(CHIP_OPTIONS, 20_000);
    private final JComboBox<Integer> baseBox = combo(BASE_OPTIONS, 100);
    private final JComboBox<Integer> taiBox = combo(TAI_OPTIONS, 20);
    /** 依序是座位 1、2、3，也就是右家、對家、左家。 */
    private final List<JComboBox<BotLevel>> levelBoxes = List.of(combo(BotLevel.values(), BotLevel.NORMAL),
            combo(BotLevel.values(), BotLevel.NORMAL), combo(BotLevel.values(), BotLevel.NORMAL));
    private final JButton startButton = Theme.button("開始遊戲", Theme.RED);
    private final JButton resumeButton = Theme.button("繼續上次的牌局", Theme.BUTTON);
    private final JLabel resumeInfo = new JLabel(" ", SwingConstants.CENTER);

    public StartPanel(Consumer<GameConfig> onStart, Runnable onResume) {
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

        styleName();
        addRow(card, c, "姓名（最多 " + MAX_NAME_LENGTH + " 字）", nameField);
        addRow(card, c, "籌碼本金", chipBox);
        addRow(card, c, "底", baseBox);
        addRow(card, c, "每台", taiBox);
        addRow(card, c, "電腦等級", buildLevelRow());

        startButton.setFont(Theme.font(Font.BOLD, 20f));
        syncSizes();
        startButton.setEnabled(false);
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(22, 0, 0, 0);
        card.add(startButton, c);

        resumeButton.setFont(Theme.font(Font.BOLD, 17f));
        resumeButton.addActionListener(e -> onResume.run());
        c.gridy++;
        c.insets = new Insets(10, 0, 0, 0);
        card.add(resumeButton, c);
        resumeInfo.setFont(Theme.font(Font.PLAIN, 14f));
        resumeInfo.setForeground(Theme.MUTED);
        c.gridy++;
        c.insets = new Insets(4, 0, 0, 0);
        card.add(resumeInfo, c);
        setResume(null);

        JLabel version = new JLabel("版本 " + AppVersion.get(), SwingConstants.CENTER);
        version.setFont(Theme.font(Font.PLAIN, 13f));
        version.setForeground(Theme.MUTED);
        c.gridy++;
        c.insets = new Insets(12, 0, 0, 0);
        card.add(version, c);

        add(card);

        Runnable submit = () -> {
            String name = nameField.getText().trim();
            if (startButton.isEnabled()) {
                onStart.accept(new GameConfig(name, (Integer) chipBox.getSelectedItem(),
                        (Integer) baseBox.getSelectedItem(), (Integer) taiBox.getSelectedItem(),
                        levelBoxes.stream().map(box -> (BotLevel) box.getSelectedItem()).collect(Collectors.toList())));
            }
        };
        startButton.addActionListener(e -> submit.run());
        nameField.addActionListener(e -> submit.run());
    }

    /** 三家排成一列，開始畫面才不會變高，字體調大時還放得下。 */
    private JPanel buildLevelRow() {
        JPanel row = new JPanel(new GridLayout(2, 3, 6, 2));
        row.setOpaque(false);
        for (String seat : List.of("右家", "對家", "左家")) {
            JLabel label = new JLabel(seat, SwingConstants.CENTER);
            label.setFont(Theme.font(Font.PLAIN, 14f));
            label.setForeground(Theme.MUTED);
            row.add(label);
        }
        levelBoxes.forEach(row::add);
        return row;
    }

    /** 有存檔時顯示繼續的按鈕與牌局摘要；summary 是 null 表示沒有存檔。 */
    void setResume(String summary) {
        resumeButton.setVisible(summary != null);
        resumeInfo.setVisible(summary != null);
        resumeInfo.setText(summary == null ? " " : summary);
    }

    /** 字體大小改變後，讓開始按鈕跟著調整。 */
    void syncSizes() {
        startButton.setPreferredSize(new Dimension(Theme.scaled(300), Theme.scaled(50)));
        resumeButton.setPreferredSize(new Dimension(Theme.scaled(300), Theme.scaled(40)));
    }

    private void styleName() {
        nameField.setFont(Theme.font(Font.PLAIN, 17f));
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                syncStartButton();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                syncStartButton();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                syncStartButton();
            }
        });
    }

    private void syncStartButton() {
        String name = nameField.getText().trim();
        startButton.setEnabled(!name.isEmpty() && name.length() <= MAX_NAME_LENGTH);
    }

    /** 回到開始畫面時保留上次的設定，並把游標放回姓名欄。 */
    public void focusName() {
        nameField.requestFocusInWindow();
    }

    private static void addRow(JPanel card, GridBagConstraints c, String label, JComponent field) {
        JLabel text = new JLabel(label);
        text.setFont(Theme.font(Font.PLAIN, 17f));
        text.setForeground(Theme.TEXT);
        c.gridy++;
        c.gridwidth = 1;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(7, 0, 7, 22);
        card.add(text, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(7, 0, 7, 0);
        card.add(field, c);
    }

    private static <T> JComboBox<T> combo(T[] options, T selected) {
        JComboBox<T> box = new JComboBox<>(options);
        box.setSelectedItem(selected);
        box.setFont(Theme.font(Font.PLAIN, 17f));
        return box;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        g.setPaint(new GradientPaint(0, 0, Theme.FELT, 0, getHeight(), Theme.FELT_DARK));
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    /** 標題上方的裝飾牌。 */
    private static final class TileBanner extends JComponent {
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
