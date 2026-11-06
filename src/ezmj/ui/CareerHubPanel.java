package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

import ezmj.career.CareerProfile;
import ezmj.career.CareerSlot;
import ezmj.career.Challenge;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;

/**
 * 一個修業檔案的畫面：帳上的籌碼、沒打完的牌局、救濟金、開一般對戰，右邊是挑戰關卡。
 * 這個檔案的成就、統計與榮譽榜也從這裡看。
 */
final class CareerHubPanel extends JPanel {

    /** 按鈕送出的要求。 */
    interface Actions {
        void startGeneral(int basePay, int taiPay, List<BotLevel> levels);

        void startChallenge(Challenge challenge);

        void resume();

        void abandon();

        void takeRelief();

        void showAchievements();

        void showStats();

        void showHonorRoll();

        void back();
    }

    /** 關卡說明的寬度：列表扣掉捲軸、狀態、按鈕與邊框之後剩下的。 */
    private static final int GOAL_WIDTH = 330;

    private final Actions actions;
    private final JLabel title = label(" ", Font.BOLD, 28f, Theme.GOLD);
    private final JLabel subtitle = label(" ", Font.PLAIN, 14f, Theme.MUTED);
    private final JLabel bankroll = label(" ", Font.BOLD, 28f, Theme.GOLD);
    private final JLabel bankrollNote = label(" ", Font.PLAIN, 14f, Theme.MUTED);
    private final JPanel pendingBox = box();
    private final JLabel pendingInfo = label(" ", Font.PLAIN, 15f, Theme.TEXT);
    private final JPanel reliefBox = box();
    private final JComboBox<Integer> baseBox = StartPanel.combo(StartPanel.BASE_OPTIONS, 100);
    private final JComboBox<Integer> taiBox = StartPanel.combo(StartPanel.TAI_OPTIONS, 20);
    /** 依序是座位 1、2、3，也就是右家、對家、左家。 */
    private final List<JComboBox<BotLevel>> levelBoxes = List.of(StartPanel.combo(BotLevel.values(), BotLevel.NORMAL),
            StartPanel.combo(BotLevel.values(), BotLevel.NORMAL), StartPanel.combo(BotLevel.values(), BotLevel.NORMAL));
    private final JLabel tableNote = label(" ", Font.PLAIN, 14f, Theme.MUTED);
    private final JLabel payWarning = StartPanel.warningLabel();
    private final JButton generalButton = Theme.button("開始一般對戰", Theme.RED);
    private final JLabel challengeTitle = sectionLabel(" ");
    private final JPanel challengeRows = new RowsPanel();
    private final JScrollPane challengeScroll = new JScrollPane(challengeRows,
            JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private CareerProfile profile;

    CareerHubPanel(Actions actions) {
        super(new GridBagLayout());
        this.actions = actions;

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.GOLD, 1),
                BorderFactory.createEmptyBorder(18, 30, 22, 30)));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 12, 0);
        card.add(header(), c);

        c.gridy = 1;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTH;
        c.insets = new Insets(0, 0, 0, 30);
        card.add(leftColumn(), c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(rightColumn(), c);
        add(card);

        for (JComboBox<Integer> box : List.of(baseBox, taiBox)) {
            box.addActionListener(e -> syncGeneral());
        }
        generalButton.addActionListener(e -> {
            if (generalButton.isEnabled()) {
                actions.startGeneral((Integer) baseBox.getSelectedItem(), (Integer) taiBox.getSelectedItem(),
                        levelBoxes.stream().map(box -> (BotLevel) box.getSelectedItem()).collect(Collectors.toList()));
            }
        });
        syncSizes();
    }

    private JPanel header() {
        JPanel names = new JPanel(new GridBagLayout());
        names.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        names.add(title, c);
        names.add(subtitle, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(CareerListPanel.small("成就", Theme.BUTTON, actions::showAchievements));
        buttons.add(CareerListPanel.small("統計", Theme.BUTTON, actions::showStats));
        buttons.add(CareerListPanel.small("榮譽榜", Theme.BUTTON, actions::showHonorRoll));
        buttons.add(CareerListPanel.small("返回檔案列表", Theme.PANEL_LIGHT, actions::back));

        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setOpaque(false);
        header.add(names, BorderLayout.WEST);
        header.add(buttons, BorderLayout.EAST);
        return header;
    }

    private JPanel leftColumn() {
        JPanel left = new JPanel(new GridBagLayout());
        left.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = -1;
        addWide(left, c, label("帳上籌碼", Font.PLAIN, 14f, Theme.MUTED), 0);
        addWide(left, c, bankroll, 0);
        addWide(left, c, bankrollNote, 2);

        pendingBox.add(sectionLabel("沒打完的牌局"));
        pendingBox.add(pendingInfo);
        JPanel pendingButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        pendingButtons.setOpaque(false);
        pendingButtons.add(CareerListPanel.small("繼續牌局", Theme.RED, actions::resume));
        pendingButtons.add(Box.createHorizontalStrut(8));
        pendingButtons.add(CareerListPanel.small("放棄這場", Theme.PANEL_LIGHT, actions::abandon));
        pendingButtons.setAlignmentX(LEFT_ALIGNMENT);
        pendingBox.add(pendingButtons);
        addWide(left, c, pendingBox, 12);

        reliefBox.add(sectionLabel("救濟金"));
        JLabel reliefText = new JLabel();
        reliefText.setFont(Theme.font(Font.PLAIN, 14f));
        reliefText.setForeground(Theme.TEXT);
        reliefBox.add(HomePanel.wrapped(reliefText, "籌碼不到 " + CareerProfile.RELIEF_BELOW + "，可以領救濟金補到 "
                + CareerProfile.RELIEF_TO + "，會記一次破產。", 400));
        JPanel reliefButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        reliefButtons.setOpaque(false);
        reliefButtons.add(CareerListPanel.small("領救濟金", Theme.RED, actions::takeRelief));
        reliefButtons.setAlignmentX(LEFT_ALIGNMENT);
        reliefBox.add(reliefButtons);
        addWide(left, c, reliefBox, 12);

        addWide(left, c, sectionLabel("一般對戰"), 14);
        JLabel generalText = new JLabel();
        generalText.setFont(Theme.font(Font.PLAIN, 14f));
        generalText.setForeground(Theme.MUTED);
        addWide(left, c, HomePanel.wrapped(generalText, "打四圈，帶帳上的籌碼上桌，每局結算完就入帳；三家電腦也帶一樣多。",
                400), 2);
        StartPanel.addRow(left, c, "底", baseBox);
        StartPanel.addRow(left, c, "每台", taiBox);
        StartPanel.addRow(left, c, "電腦等級", StartPanel.levelRow(levelBoxes));
        addWide(left, c, tableNote, 2);
        addWide(left, c, payWarning, 2);
        generalButton.setFont(Theme.font(Font.BOLD, 18f));
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(6, 0, 0, 0);
        left.add(generalButton, c);
        return left;
    }

    private JPanel rightColumn() {
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setOpaque(false);
        right.add(challengeTitle, BorderLayout.NORTH);
        challengeRows.setBackground(Theme.PANEL);
        challengeScroll.setBorder(BorderFactory.createLineBorder(Theme.PANEL_LIGHT));
        challengeScroll.getViewport().setBackground(Theme.PANEL);
        challengeScroll.getVerticalScrollBar().setUnitIncrement(24);
        right.add(challengeScroll, BorderLayout.CENTER);
        return right;
    }

    /** 換一個檔案或檔案有變動時呼叫。pendingSummary 是沒打完的牌局的說明，沒有就是 null。 */
    void show(CareerSlot slot, CareerProfile shown, String pendingSummary) {
        profile = shown;
        title.setText(shown.name() + " 的修業");
        subtitle.setText(slot.label() + "・" + shown.created() + " 建立");
        bankroll.setText(String.valueOf(shown.bankroll()));
        bankrollNote.setText("起始 " + shown.startingChips() + "・最高 " + shown.peak() + "・破產 "
                + shown.bankruptcies() + " 次");
        pendingBox.setVisible(pendingSummary != null);
        pendingInfo.setText(pendingSummary == null ? " " : pendingSummary);
        reliefBox.setVisible(shown.canTakeRelief());
        syncGeneral();
        fillChallenges();
        revalidate();
        repaint();
    }

    private void syncGeneral() {
        if (profile == null) {
            return;
        }
        int chips = profile.tableChips();
        tableNote.setText(chips > 0 ? "帶上桌 " + chips + (chips < profile.bankroll() ? "，其餘留在帳上" : "") : " ");
        boolean fit = GameConfig.paysFit(chips, (Integer) baseBox.getSelectedItem(), (Integer) taiBox.getSelectedItem());
        String warning;
        if (profile.game() != CareerProfile.Game.NONE) {
            warning = "先把沒打完的牌局打完或放棄";
        } else if (chips <= 0) {
            warning = "籌碼輸光了，領救濟金才能再打";
        } else if (!fit) {
            warning = "底和每台不能大於帶上桌的籌碼";
        } else {
            warning = " ";
        }
        payWarning.setText(warning);
        generalButton.setEnabled(profile.game() == CareerProfile.Game.NONE && chips > 0 && fit);
    }

    private void fillChallenges() {
        challengeTitle.setText("挑戰關卡　已過 " + profile.clearedCount() + " / " + Challenge.values().length);
        challengeRows.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        String chapter = null;
        JComponent focus = null;
        for (Challenge challenge : Challenge.values()) {
            if (!challenge.chapter().equals(chapter)) {
                chapter = challenge.chapter();
                JLabel heading = label(chapter + "・三家" + challenge.level().label(), Font.BOLD, 15f, Theme.GOLD);
                heading.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
                c.insets = new Insets(0, 0, 0, 0);
                challengeRows.add(heading, c);
                c.gridy++;
            }
            JComponent row = challengeRow(challenge);
            if (focus == null && !profile.isCleared(challenge)) {
                focus = row;
            }
            c.insets = new Insets(0, 6, 6, 6);
            challengeRows.add(row, c);
            c.gridy++;
        }
        // 下面墊一塊，關卡少的時候不會擠在中間
        c.weighty = 1;
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        challengeRows.add(filler, c);
        challengeRows.revalidate();
        challengeRows.repaint();
        JComponent target = focus;
        SwingUtilities.invokeLater(() -> {
            if (target != null) {
                challengeRows.scrollRectToVisible(target.getBounds());
            }
        });
    }

    private JComponent challengeRow(Challenge challenge) {
        boolean cleared = profile.isCleared(challenge);
        boolean unlocked = profile.isUnlocked(challenge);
        boolean playing = profile.game() == CareerProfile.Game.CHALLENGE && profile.challenge() == challenge;
        String state = playing ? "挑戰中" : cleared ? "已過關" : unlocked ? "可挑戰" : "未開放";
        Color stateColor = cleared ? Theme.GOLD : unlocked || playing ? Theme.TEXT : Theme.MUTED;

        JPanel text = new JPanel(new GridBagLayout());
        text.setOpaque(false);
        GridBagConstraints line = new GridBagConstraints();
        line.gridx = 0;
        line.anchor = GridBagConstraints.WEST;
        text.add(label("第 " + challenge.number() + " 關　" + challenge.title(), Font.BOLD, 16f,
                unlocked ? Theme.TEXT : Theme.MUTED), line);
        JLabel goal = new JLabel();
        goal.setFont(Theme.font(Font.PLAIN, 14f));
        goal.setForeground(unlocked ? Theme.TEXT : Theme.MUTED);
        text.add(HomePanel.wrapped(goal, challenge.goalText(), GOAL_WIDTH), line);
        String reward = cleared ? "（" + HomePanel.escape(profile.clearedDate(challenge)) + " 過關，獎金已領）" : "";
        JLabel setup = new JLabel();
        setup.setFont(Theme.font(Font.PLAIN, 13f));
        setup.setForeground(Theme.MUTED);
        text.add(HomePanel.wrapped(setup, challenge.setup() + "・獎金 " + challenge.reward() + reward, GOAL_WIDTH), line);

        boolean enabled = unlocked && profile.game() == CareerProfile.Game.NONE;
        Color color = !enabled ? Theme.PANEL : cleared ? Theme.BUTTON : Theme.RED;
        JButton start = CareerListPanel.small(cleared ? "再挑戰" : "挑戰", color, () -> actions.startChallenge(challenge));
        start.setEnabled(enabled);
        if (!unlocked) {
            start.setToolTipText("過了第 " + (challenge.number() - 1) + " 關才開放");
        } else if (profile.game() != CareerProfile.Game.NONE) {
            start.setToolTipText("先把沒打完的牌局打完或放棄");
        }

        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(Theme.PANEL_LIGHT);
        row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = new Insets(0, 0, 0, 12);
        JLabel stateLabel = label(state, Font.BOLD, 14f, stateColor);
        stateLabel.setPreferredSize(new Dimension(Theme.scaled(56), stateLabel.getPreferredSize().height));
        row.add(stateLabel, cell);
        cell.weightx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        row.add(text, cell);
        cell.weightx = 0;
        cell.fill = GridBagConstraints.NONE;
        cell.anchor = GridBagConstraints.CENTER;
        cell.insets = new Insets(0, 0, 0, 0);
        row.add(start, cell);
        return row;
    }

    /** 字體大小改變後，讓挑戰列表與按鈕跟著調整。 */
    void syncSizes() {
        challengeScroll.setPreferredSize(new Dimension(Theme.scaled(560), Theme.scaled(450)));
        generalButton.setPreferredSize(new Dimension(Theme.scaled(260), Theme.scaled(46)));
        if (profile != null) {
            fillChallenges();
        }
    }

    private static JLabel label(String text, int style, float size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(style, size));
        label.setForeground(color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel sectionLabel(String text) {
        return label(text, Font.BOLD, 17f, Theme.GOLD);
    }

    /** 直排、靠左的一小塊，沒打完的牌局與救濟金用。 */
    private static JPanel box() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        return box;
    }

    /** 佔滿兩欄的一列。 */
    private static void addWide(JPanel panel, GridBagConstraints c, JComponent component, int top) {
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(top, 0, 0, 0);
        panel.add(component, c);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        StartPanel.paintFelt(this, graphics);
    }

    /** 挑戰列表跟著捲動區的寬度，只往下捲，右邊的按鈕才不會被切掉。 */
    private static final class RowsPanel extends JPanel implements Scrollable {
        RowsPanel() {
            super(new GridBagLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return Theme.scaled(24);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.height - Theme.scaled(24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
