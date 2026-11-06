package ezmj.ui;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
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

import ezmj.model.BotLevel;
import ezmj.model.GameConfig;

/**
 * 練習牌局：輸入姓名、選擇籌碼本金、底與台、電腦等級，按下按鈕即開始；有存檔時也能繼續上次的練習牌局。
 * 每場的籌碼都重新選，成就、統計與榮譽榜和修業檔案分開記。
 */
public final class StartPanel extends JPanel {

    static final int MAX_NAME_LENGTH = 8;
    static final Integer[] CHIP_OPTIONS = {10_000, 20_000, 50_000, 100_000, 500_000, 1_000_000, 5_000_000, 10_000_000};
    static final Integer[] BASE_OPTIONS = {50, 100, 300, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000};
    static final Integer[] TAI_OPTIONS = {10, 20, 50, 100, 200, 5_000, 10_000, 50_000, 100_000, 200_000, 500_000, 1_000_000};

    private final JTextField nameField = new JTextField(14);
    private final JComboBox<Integer> chipBox = combo(CHIP_OPTIONS, 20_000);
    private final JComboBox<Integer> baseBox = combo(BASE_OPTIONS, 100);
    private final JComboBox<Integer> taiBox = combo(TAI_OPTIONS, 20);
    /** 依序是座位 1、2、3，也就是右家、對家、左家。 */
    private final List<JComboBox<BotLevel>> levelBoxes = List.of(combo(BotLevel.values(), BotLevel.NORMAL),
            combo(BotLevel.values(), BotLevel.NORMAL), combo(BotLevel.values(), BotLevel.NORMAL));
    private final JButton startButton = Theme.button("開始練習牌局", Theme.RED);
    private final JButton resumeButton = Theme.button("繼續上次的練習牌局", Theme.BUTTON);
    private final JButton backButton = Theme.button("返回", Theme.PANEL_LIGHT);
    private final JLabel resumeInfo = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel payWarning = warningLabel();

    public StartPanel(Consumer<GameConfig> onStart, Runnable onResume, Runnable onBack) {
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
        card.add(new HomePanel.TileBanner(), c);

        JLabel title = new JLabel("練習牌局", SwingConstants.CENTER);
        title.setFont(Theme.font(Font.BOLD, 34f));
        title.setForeground(Theme.GOLD);
        c.gridy++;
        c.insets = new Insets(0, 0, 4, 0);
        card.add(title, c);
        JLabel subtitle = new JLabel("每場自訂籌碼、底台與電腦等級，成就、統計與榮譽榜和修業檔案分開記", SwingConstants.CENTER);
        subtitle.setFont(Theme.font(Font.PLAIN, 14f));
        subtitle.setForeground(Theme.MUTED);
        c.gridy++;
        c.insets = new Insets(0, 0, 18, 0);
        card.add(subtitle, c);

        styleName();
        addRow(card, c, "姓名（最多 " + MAX_NAME_LENGTH + " 字）", nameField);
        addRow(card, c, "籌碼本金", chipBox);
        addRow(card, c, "底", baseBox);
        addRow(card, c, "每台", taiBox);
        addRow(card, c, "電腦等級", levelRow(levelBoxes));

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(8, 0, 0, 0);
        card.add(payWarning, c);
        for (JComboBox<Integer> box : List.of(chipBox, baseBox, taiBox)) {
            box.addActionListener(e -> syncStartButton());
        }

        startButton.setFont(Theme.font(Font.BOLD, 20f));
        syncSizes();
        startButton.setEnabled(false);
        c.gridy++;
        c.insets = new Insets(8, 0, 0, 0);
        card.add(startButton, c);

        resumeButton.setFont(Theme.font(Font.BOLD, 17f));
        resumeButton.addActionListener(e -> onResume.run());
        backButton.setFont(Theme.font(Font.BOLD, 17f));
        backButton.addActionListener(e -> onBack.run());
        // 排在同一列，視窗最小的時候畫面才放得下
        JPanel moreButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        moreButtons.setOpaque(false);
        moreButtons.add(resumeButton);
        moreButtons.add(backButton);
        c.gridy++;
        c.insets = new Insets(10, 0, 0, 0);
        card.add(moreButtons, c);
        resumeInfo.setFont(Theme.font(Font.PLAIN, 14f));
        resumeInfo.setForeground(Theme.MUTED);
        c.gridy++;
        c.insets = new Insets(4, 0, 0, 0);
        card.add(resumeInfo, c);
        setResume(null);

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
    static JPanel levelRow(List<JComboBox<BotLevel>> boxes) {
        JPanel row = new JPanel(new GridLayout(2, 3, 6, 2));
        row.setOpaque(false);
        for (String seat : List.of("右家", "對家", "左家")) {
            JLabel label = new JLabel(seat, SwingConstants.CENTER);
            label.setFont(Theme.font(Font.PLAIN, 14f));
            label.setForeground(Theme.MUTED);
            row.add(label);
        }
        boxes.forEach(row::add);
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
        resumeButton.setPreferredSize(new Dimension(Theme.scaled(220), Theme.scaled(40)));
        backButton.setPreferredSize(new Dimension(Theme.scaled(120), Theme.scaled(40)));
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
        boolean paysFit = paysFit(chipBox, baseBox, taiBox, payWarning);
        startButton.setEnabled(!name.isEmpty() && name.length() <= MAX_NAME_LENGTH && paysFit);
    }

    /** 底和每台不能大於籌碼本金的提示。沒事時是空白，位置先留著，選項一改畫面才不會跳。 */
    static JLabel warningLabel() {
        JLabel label = new JLabel(" ", SwingConstants.CENTER);
        label.setFont(Theme.font(Font.PLAIN, 14f));
        label.setForeground(Theme.GOLD);
        return label;
    }

    /** 檢查三個下拉選單，順便更新提示。連線開房也用這個。 */
    static boolean paysFit(JComboBox<Integer> chips, JComboBox<Integer> base, JComboBox<Integer> tai, JLabel warning) {
        boolean fit = GameConfig.paysFit((Integer) chips.getSelectedItem(), (Integer) base.getSelectedItem(),
                (Integer) tai.getSelectedItem());
        warning.setText(fit ? " " : GameConfig.PAYS_TOO_HIGH);
        return fit;
    }

    /** 回到開始畫面時保留上次的設定，並把游標放回姓名欄。 */
    public void focusName() {
        nameField.requestFocusInWindow();
    }

    static void addRow(JPanel card, GridBagConstraints c, String label, JComponent field) {
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

    static <T> JComboBox<T> combo(T[] options, T selected) {
        JComboBox<T> box = new JComboBox<>(options);
        box.setSelectedItem(selected);
        box.setFont(Theme.font(Font.PLAIN, 17f));
        return box;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        paintFelt(this, graphics);
    }

    /** 開始畫面、練習牌局與修業模式共用的綠色漸層背景。 */
    static void paintFelt(JComponent panel, Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        g.setPaint(new GradientPaint(0, 0, Theme.FELT, 0, panel.getHeight(), Theme.FELT_DARK));
        g.fillRect(0, 0, panel.getWidth(), panel.getHeight());
    }
}
