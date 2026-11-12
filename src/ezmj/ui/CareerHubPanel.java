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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
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
import ezmj.career.CareerTitle;
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

        void showCertificate();

        void back();
    }

    /** 還不知道列表多寬時，關卡說明先用這個寬度。 */
    private static final int GOAL_WIDTH = 330;
    /** 每一關左邊「已過關」那一欄的寬度。 */
    private static final int STATE_WIDTH = 56;

    private final Actions actions;
    private final JLabel title = label(" ", Font.BOLD, 28f, Theme.GOLD);
    private final JLabel subtitle = label(" ", Font.PLAIN, 14f, Theme.MUTED);
    private final JLabel bankroll = label(" ", Font.BOLD, 28f, Theme.GOLD);
    private final JLabel bankrollNote = label(" ", Font.PLAIN, 14f, Theme.MUTED);
    private final JPanel pendingBox = box();
    private final JLabel pendingInfo = label(" ", Font.PLAIN, 15f, Theme.TEXT);
    private final JPanel reliefBox = box();
    /** 救濟金補到多少，專家模式不一樣，換檔案時重寫。 */
    private final JLabel reliefText = new JLabel();
    /** 一般對戰的規則，專家模式不一樣，換檔案時重寫。 */
    private final JLabel generalText = new JLabel();
    private final JLabel titleNote = label(" ", Font.BOLD, 14f, Theme.GOLD);
    /** 選項跟著帳上的籌碼變，見 fillStakes。 */
    private final JComboBox<Integer> baseBox = StartPanel.combo(new Integer[] {100}, 100);
    private final JComboBox<Integer> taiBox = StartPanel.combo(new Integer[] {20}, 20);
    /** 正在換底台的選項，這時候的選取事件不算數。 */
    private boolean filling;
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
    /** 挑戰列表目前的寬度，還沒排版時是 0。 */
    private int rowsWidth;

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

        // 左欄照自己的高度靠上，右邊的挑戰列表吃掉剩下的高度，關卡多的時候在裡面捲
        c.gridy = 1;
        c.gridwidth = 1;
        c.weighty = 1;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTH;
        c.insets = new Insets(0, 0, 0, 30);
        card.add(leftColumn(), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(rightColumn(), c);

        // 卡片撐滿視窗的高度：字體放大或視窗小時，列表跟著變矮，不會因為放不下被擠成一條線
        GridBagConstraints outer = new GridBagConstraints();
        outer.fill = GridBagConstraints.VERTICAL;
        outer.weighty = 1;
        outer.insets = new Insets(12, 12, 12, 12);
        add(card, outer);

        // 列表變寬變窄時，關卡說明照新的寬度重新折行
        challengeScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int width = challengeScroll.getViewport().getWidth();
                if (profile != null && width > 0 && width != rowsWidth) {
                    rowsWidth = width;
                    fillChallenges(false);
                }
            }
        });

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
        buttons.add(CareerListPanel.small("證照", Theme.BUTTON, actions::showCertificate));
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
        addWide(left, c, titleNote, 2);

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
        reliefText.setFont(Theme.font(Font.PLAIN, 14f));
        reliefText.setForeground(Theme.TEXT);
        reliefText.setAlignmentX(LEFT_ALIGNMENT);
        reliefBox.add(reliefText);
        JPanel reliefButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        reliefButtons.setOpaque(false);
        reliefButtons.add(CareerListPanel.small("領救濟金", Theme.RED, actions::takeRelief));
        reliefButtons.setAlignmentX(LEFT_ALIGNMENT);
        reliefBox.add(reliefButtons);
        addWide(left, c, reliefBox, 12);

        addWide(left, c, sectionLabel("一般對戰"), 14);
        generalText.setFont(Theme.font(Font.PLAIN, 14f));
        generalText.setForeground(Theme.MUTED);
        addWide(left, c, generalText, 2);
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
        if (profile == null || !profile.name().equals(shown.name()) || profile.expert() != shown.expert()) {
            // 換了一個檔案，上一個檔案選的底台不留，照這個檔案的預設重選
            baseBox.setSelectedItem(null);
            taiBox.setSelectedItem(null);
        }
        profile = shown;
        title.setText(shown.name() + " 的修業");
        subtitle.setText(slot.label() + "・" + (shown.expert() ? "專家模式・" : "") + shown.created() + " 建立");
        HomePanel.wrapped(reliefText, "補到 " + shown.reliefTo() + "，記一次破產。", 400);
        // 一句開場白；底台怎麼開放、專家模式的等待時間等規則寫在「說明」→「遊戲玩法」
        HomePanel.wrapped(generalText, "帶著帳上的籌碼上桌，和三家電腦拚四圈，贏來的就是你闖蕩江湖的本錢。", 400);
        bankroll.setText(String.valueOf(shown.bankroll()));
        bankrollNote.setText("起始 " + shown.startingChips() + "・最高 " + shown.peak() + "・破產 "
                + shown.bankruptcies() + " 次");
        titleNote.setText(titleText(shown));
        pendingBox.setVisible(pendingSummary != null);
        pendingInfo.setText(pendingSummary == null ? " " : pendingSummary);
        reliefBox.setVisible(shown.canTakeRelief());
        fillStakes();
        syncGeneral();
        fillChallenges(true);
        revalidate();
        repaint();
    }

    /** 例如「稱號：百萬雀士・下一個「麻將大亨」要帳上到 5000000」。 */
    static String titleText(CareerProfile shown) {
        CareerTitle current = shown.title();
        CareerTitle next = shown.nextTitle();
        String now = current == null ? "還沒有稱號" : "稱號：" + current.label();
        return next == null ? now + "・全部到手" : now + "・帳上到 " + next.threshold() + " 得到「" + next.label() + "」";
    }

    /**
     * 底與每台只列帳上的籌碼選得了的：底最多帳上的 1/10、每台最多 1/20，專家模式不大於帳上就好。
     * 原本選的還能選就留著，不能選就換成能選的裡面最接近 100／20 的；專家模式籌碼少，先選最小的 50／10。
     */
    private void fillStakes() {
        refill(baseBox, profile.baseOptions(), profile.expert() ? CareerProfile.BASE_OPTIONS.get(0) : 100);
        refill(taiBox, profile.taiOptions(), profile.expert() ? CareerProfile.TAI_OPTIONS.get(0) : 20);
    }

    private void refill(JComboBox<Integer> box, List<Integer> options, int preferred) {
        Integer previous = (Integer) box.getSelectedItem();
        filling = true;
        box.setModel(new DefaultComboBoxModel<>(options.toArray(new Integer[0])));
        if (options.contains(previous)) {
            box.setSelectedItem(previous);
        } else if (!options.isEmpty()) {
            Integer pick = options.get(0);
            for (Integer option : options) {
                if (option <= preferred) {
                    pick = option;
                }
            }
            box.setSelectedItem(pick);
        }
        filling = false;
    }

    private void syncGeneral() {
        if (profile == null || filling) {
            return;
        }
        int chips = profile.tableChips();
        tableNote.setText(chips > 0 ? "帶上桌 " + chips + (chips < profile.bankroll() ? "，其餘留在帳上" : "") : " ");
        Integer basePay = (Integer) baseBox.getSelectedItem();
        Integer taiPay = (Integer) taiBox.getSelectedItem();
        boolean fit = basePay != null && taiPay != null && profile.stakesAllowed(basePay, taiPay)
                && GameConfig.paysFit(chips, basePay, taiPay);
        String warning;
        if (profile.game() != CareerProfile.Game.NONE) {
            warning = "先把沒打完的牌局打完或放棄";
        } else if (profile.baseOptions().isEmpty() || profile.taiOptions().isEmpty()) {
            warning = "籌碼不到 " + profile.reliefBelow() + "，連最小的桌都上不了，領救濟金才能再打";
        } else if (!fit) {
            warning = "這個底台現在選不了";
        } else {
            warning = " ";
        }
        payWarning.setText(warning);
        generalButton.setEnabled(profile.game() == CareerProfile.Game.NONE && chips > 0 && fit);
    }

    /** 例如兩顆星是「★★☆」。 */
    static String starMarks(int stars) {
        return "★".repeat(stars) + "☆".repeat(Challenge.MAX_STARS - stars);
    }

    /** 重新排挑戰列表。scrollToCurrent 時捲到第一個還沒過的關；只是視窗變寬變窄就不捲。 */
    private void fillChallenges(boolean scrollToCurrent) {
        challengeTitle.setText("挑戰關卡　已過 " + profile.clearedCount(false) + " / " + Challenge.line(false).size()
                + "　修練 " + profile.clearedCount(true) + " / " + Challenge.line(true).size()
                + "　★ " + profile.totalStars() + " / " + Challenge.values().length * Challenge.MAX_STARS);
        int goalPixels = goalPixels();
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
                JLabel heading = label(challenge.chapterHeading(), Font.BOLD, 15f, Theme.GOLD);
                heading.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
                c.insets = new Insets(0, 0, 0, 0);
                challengeRows.add(heading, c);
                c.gridy++;
            }
            JComponent row = challengeRow(challenge, goalPixels);
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
        if (scrollToCurrent) {
            SwingUtilities.invokeLater(() -> {
                if (target != null) {
                    challengeRows.scrollRectToVisible(target.getBounds());
                }
            });
        }
    }

    /**
     * 關卡說明折行的寬度：列表的寬度扣掉兩邊的間距與邊框、左邊的狀態、中間的空隙與右邊的按鈕。
     * 列表還沒排版時先用 GOAL_WIDTH。
     */
    private int goalPixels() {
        if (rowsWidth <= 0) {
            return Theme.scaled(GOAL_WIDTH);
        }
        int button = CareerListPanel.small("再挑戰", Theme.BUTTON, () -> { }).getPreferredSize().width;
        int fixed = 6 + 6 + 10 + 10 + Theme.scaled(STATE_WIDTH) + 12 + 12 + button + 8;
        return Math.max(Theme.scaled(140), rowsWidth - fixed);
    }

    private JComponent challengeRow(Challenge challenge, int goalPixels) {
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
        int stars = profile.stars(challenge);
        JLabel heading = label("", Font.BOLD, 16f, unlocked ? Theme.TEXT : Theme.MUTED);
        String title = HomePanel.escape(challenge.label() + "　" + challenge.title());
        // 拿到的星是金色，還沒拿到的是灰色；列表窄的時候標題和說明一樣折行，不會被截掉
        heading.setText(HomePanel.wrapHtml(!unlocked ? title : title + "　<font color='" + hex(Theme.GOLD) + "'>"
                + "★".repeat(stars) + "</font><font color='" + hex(Theme.MUTED) + "'>"
                + "☆".repeat(Challenge.MAX_STARS - stars) + "</font>", goalPixels));
        text.add(heading, line);
        JLabel goal = new JLabel();
        goal.setFont(Theme.font(Font.PLAIN, 14f));
        goal.setForeground(unlocked ? Theme.TEXT : Theme.MUTED);
        goal.setText(HomePanel.wrapHtml(challenge.goalText(), goalPixels));
        text.add(goal, line);
        JLabel starLine = new JLabel();
        starLine.setFont(Theme.font(Font.PLAIN, 13f));
        starLine.setForeground(Theme.MUTED);
        starLine.setText(HomePanel.wrapHtml(starConditions(challenge, profile.starMask(challenge)), goalPixels));
        text.add(starLine, line);
        String reward = cleared ? "（" + HomePanel.escape(profile.clearedDate(challenge)) + " 過關，獎金已領）" : "";
        JLabel setup = new JLabel();
        setup.setFont(Theme.font(Font.PLAIN, 13f));
        setup.setForeground(Theme.MUTED);
        setup.setText(HomePanel.wrapHtml(challenge.setup() + "・獎金 " + profile.rewardFor(challenge) + reward
                + "・每多一顆星 +" + profile.starBonus(challenge), goalPixels));
        text.add(setup, line);

        boolean enabled = unlocked && profile.game() == CareerProfile.Game.NONE;
        Color color = !enabled ? Theme.PANEL : cleared ? Theme.BUTTON : Theme.RED;
        JButton start = CareerListPanel.small(cleared ? "再挑戰" : "挑戰", color, () -> actions.startChallenge(challenge));
        start.setEnabled(enabled);
        if (!unlocked) {
            Challenge previous = challenge.previous();
            start.setToolTipText(previous.label() + "「" + previous.title() + "」過了才開放");
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
        stateLabel.setPreferredSize(new Dimension(Theme.scaled(STATE_WIDTH), stateLabel.getPreferredSize().height));
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

    /** 三顆星各要做到什麼，例如「★ 過關　★ 沒有放槍　☆ 自摸過關」：拿過的是金色實心，還沒拿過的是灰色空心。 */
    private static String starConditions(Challenge challenge, int mask) {
        StringBuilder html = new StringBuilder();
        List<String> texts = challenge.starTexts();
        for (int i = 0; i < texts.size(); i++) {
            boolean got = (mask & (1 << i)) != 0;
            html.append(i == 0 ? "" : "　").append("<font color='").append(hex(got ? Theme.GOLD : Theme.MUTED))
                    .append("'>").append(got ? "★ " : "☆ ").append(HomePanel.escape(texts.get(i))).append("</font>");
        }
        return html.toString();
    }

    private static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    /** 字體大小改變後，讓挑戰列表與按鈕跟著調整。 */
    void syncSizes() {
        // 高度只是起點，視窗多出來的高度都給列表；放不下時最矮到最小值，不會被擠成一條線
        challengeScroll.setPreferredSize(new Dimension(Theme.scaled(560), Theme.scaled(300)));
        // 最窄 340：字體 150% 時 1280 寬的視窗還放得下整個畫面，不用左右捲
        challengeScroll.setMinimumSize(new Dimension(Theme.scaled(340), Theme.scaled(150)));
        generalButton.setPreferredSize(new Dimension(Theme.scaled(260), Theme.scaled(46)));
        if (profile != null) {
            fillChallenges(false);
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
