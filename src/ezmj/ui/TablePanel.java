package ezmj.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.text.AbstractDocument;

import ezmj.engine.GameView;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundView;
import ezmj.model.Dice;
import ezmj.model.Player;
import ezmj.model.Tile;

/** 牌桌主畫面：中間是牌桌，下方是動作列，右側是計分板與紀錄。 */
public final class TablePanel extends JPanel {

    /** 聊天一則最多幾個字，和服務器一樣。 */
    static final int MAX_CHAT_LENGTH = 100;
    private static final int PROMPT_BLINK_MS = 450;

    /** 動作列上的一個按鈕。translucent 的按鈕半透明，表示可以用但不一定要按。 */
    record ActionItem(String label, Color color, Runnable run, boolean translucent) {
        ActionItem(String label, Color color, Runnable run) {
            this(label, color, run, false);
        }
    }

    private final TableCanvas canvas;
    private final JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
    private final JLabel hint = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel roundInfo = new JLabel(" ");
    private final JLabel stakesInfo = new JLabel(" ");
    private final JLabel thinkInfo = new JLabel(" ");
    /** 挑戰關卡的任務，平常藏起來。 */
    private final JPanel missionBox = new JPanel() {
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    };
    private final JLabel missionTitle = new JLabel(" ");
    private final JLabel missionGoal = new JLabel(" ");
    private final JLabel missionProgress = new JLabel(" ");
    private String missionGoalText;
    /** 每家一列，欄位依序是門風、玩家、籌碼。 */
    private final JLabel[][] scoreCells = new JLabel[4][];
    private final JTextArea logArea = new JTextArea();
    private final JScrollPane logScroll = new JScrollPane(logArea);
    /** 紀錄收起來時只顯示最新一則。 */
    private final JLabel latestLog = new JLabel(" ");
    private final JButton logToggle = Theme.button("", Theme.PANEL_LIGHT);
    private final ChevronIcon logToggleIcon = new ChevronIcon();
    private final JPanel chatBox = new JPanel();
    private final JTextArea chatArea = new JTextArea();
    private final JTextField chatField = new JTextField();
    private final JLabel chatNotice = new JLabel(" ");
    private final JPanel actionBar = new JPanel();
    private final JPanel sidebar = new JPanel();
    private final JButton autoPlayButton = Theme.button("電腦代打：關", Theme.PANEL_LIGHT);
    /** 吃碰槓後要打牌，提示一閃一閃。 */
    private final Timer promptBlink = new Timer(PROMPT_BLINK_MS, e -> blinkHint());
    private boolean blinkOn;
    private boolean logExpanded;
    /** 專家模式：紀錄一直只顯示最新一則，不能展開。 */
    private boolean logLocked;
    /** 結算時計分表的籌碼從舊的數目滾到新的數目。rollTo 不是 null 時正在滾，refresh 不改籌碼那一欄。 */
    private final Timer chipRoll = new Timer(30, e -> stepChipRoll());
    private int[] rollFrom;
    private int[] rollTo;
    private long rollBegin;
    /** 聊天送出的地方；沒有聊天時是 null。 */
    private Consumer<String> chatSender;

    /** onToggleAutoPlay 是 null 時沒有電腦代打的按鈕，多人遊戲不能用。 */
    TablePanel(Consumer<Tile> onDiscard, Runnable onLeave, Runnable onToggleAutoPlay, Runnable onShowHistory) {
        super(new BorderLayout());
        canvas = new TableCanvas(onDiscard);
        add(canvas, BorderLayout.CENTER);
        add(buildActionBar(), BorderLayout.SOUTH);
        add(buildSidebar(onLeave, onToggleAutoPlay, onShowHistory), BorderLayout.EAST);
        syncSizes();
        showThinkTime(0, 0);
        setLogExpanded(false);
    }

    /** 真人的平均思考時間；還沒有紀錄時 count 是 0。 */
    void showThinkTime(double seconds, int count) {
        thinkInfo.setText(count == 0 ? "平均思考：—"
                : String.format(Locale.ROOT, "平均思考：%.1f 秒（%d 次）", seconds, count));
    }

    /** 連線牌局沒有思考時間的紀錄，這一行改顯示房號。 */
    void showRoomCode(String code) {
        thinkInfo.setText("房號 " + code);
        thinkInfo.setToolTipText(null);
    }

    void setAutoPlay(boolean on) {
        autoPlayButton.setText(on ? "電腦代打：開" : "電腦代打：關");
        autoPlayButton.setBackground(on ? Theme.RED : Theme.PANEL_LIGHT);
    }

    /** 字體大小改變後，讓側欄與動作列跟著調整。 */
    void syncSizes() {
        actionBar.setPreferredSize(new Dimension(0, Theme.scaled(84)));
        sidebar.setPreferredSize(new Dimension(Theme.scaled(262), 0));
        wrapMissionGoal();
    }

    /** 挑戰關卡的任務與進度，顯示在平均思考那一行下面。title 是 null 時藏起來。文字都是程式自己的。 */
    void showMission(String title, String goal, String progress) {
        missionBox.setVisible(title != null);
        if (title != null) {
            missionTitle.setText(title);
            missionGoalText = goal;
            wrapMissionGoal();
            missionProgress.setText(progress);
        }
        sidebar.revalidate();
        sidebar.repaint();
    }

    /** 任務說明照側欄的寬度折行：側欄扣掉兩邊各 14 與任務框兩邊各 8，再留一點餘裕。 */
    private void wrapMissionGoal() {
        if (missionGoalText != null) {
            missionGoal.setText(HomePanel.wrapHtml(missionGoalText, Theme.scaled(262) - 48));
        }
    }

    /** 下面的間距放在框外面，藏起來時連間距一起不見，其他牌桌的排版不變。 */
    private JPanel buildMissionBox() {
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBackground(Theme.PANEL_LIGHT);
        inner.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        missionTitle.setFont(Theme.font(Font.BOLD, 14f));
        missionTitle.setForeground(Theme.GOLD);
        missionGoal.setFont(Theme.font(Font.PLAIN, 13f));
        missionGoal.setForeground(Theme.TEXT);
        missionProgress.setFont(Theme.font(Font.BOLD, 13f));
        missionProgress.setForeground(Theme.GOLD);
        inner.add(missionTitle);
        inner.add(Box.createVerticalStrut(3));
        inner.add(missionGoal);
        inner.add(Box.createVerticalStrut(3));
        inner.add(missionProgress);
        missionBox.setLayout(new BorderLayout());
        missionBox.setOpaque(false);
        missionBox.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        missionBox.setAlignmentX(LEFT_ALIGNMENT);
        missionBox.add(inner, BorderLayout.CENTER);
        missionBox.setVisible(false);
        return missionBox;
    }

    private JPanel buildActionBar() {
        JPanel bar = actionBar;
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBackground(Theme.PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 8, 10, 8));

        hint.setFont(Theme.font(Font.PLAIN, 15f));
        hint.setForeground(Theme.MUTED);
        hint.setAlignmentX(CENTER_ALIGNMENT);
        actionButtons.setOpaque(false);
        actionButtons.setAlignmentX(CENTER_ALIGNMENT);
        bar.add(hint);
        bar.add(Box.createVerticalStrut(6));
        bar.add(actionButtons);
        return bar;
    }

    private JPanel buildSidebar(Runnable onLeave, Runnable onToggleAutoPlay, Runnable onShowHistory) {
        JPanel side = sidebar;
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(Theme.PANEL);
        side.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        roundInfo.setFont(Theme.font(Font.BOLD, 19f));
        roundInfo.setForeground(Theme.GOLD);
        stakesInfo.setFont(Theme.font(Font.PLAIN, 14f));
        stakesInfo.setForeground(Theme.MUTED);
        side.add(leftAligned(roundInfo));
        side.add(Box.createVerticalStrut(2));
        side.add(leftAligned(stakesInfo));
        side.add(Box.createVerticalStrut(12));

        side.add(buildScoreTable());
        side.add(Box.createVerticalStrut(10));
        thinkInfo.setFont(Theme.font(Font.PLAIN, 14f));
        thinkInfo.setForeground(Theme.MUTED);
        thinkInfo.setToolTipText("從摸牌或可以吃碰槓胡開始，到打出牌或決定過為止的平均時間");
        side.add(leftAligned(thinkInfo));
        side.add(Box.createVerticalStrut(8));
        side.add(buildMissionBox());

        side.add(buildLogHeader());
        side.add(Box.createVerticalStrut(4));
        styleTextArea(logArea);
        logScroll.setBorder(null);
        logScroll.setAlignmentX(LEFT_ALIGNMENT);
        side.add(logScroll);
        side.add(Box.createVerticalStrut(10));
        side.add(buildChatBox());
        side.add(Box.createVerticalStrut(10));

        JButton history = Theme.button("輸贏歷程", Theme.PANEL_LIGHT);
        history.setFont(Theme.font(Font.PLAIN, 14f));
        history.addActionListener(e -> onShowHistory.run());
        side.add(history);
        side.add(Box.createVerticalStrut(8));

        if (onToggleAutoPlay != null) {
            autoPlayButton.setFont(Theme.font(Font.PLAIN, 14f));
            autoPlayButton.addActionListener(e -> onToggleAutoPlay.run());
            side.add(autoPlayButton);
            side.add(Box.createVerticalStrut(8));
        }

        JButton leave = Theme.button("離開牌局", Theme.PANEL_LIGHT);
        leave.setFont(Theme.font(Font.PLAIN, 14f));
        leave.addActionListener(e -> onLeave.run());
        side.add(leave);
        return side;
    }

    /** 「紀錄」一行：收起來時顯示最新一則，右邊的箭頭展開或收合，點這一行也可以。 */
    private JPanel buildLogHeader() {
        JLabel title = new JLabel("紀錄");
        title.setFont(Theme.font(Font.BOLD, 13f));
        title.setForeground(Theme.GOLD);
        latestLog.setFont(Theme.font(Font.PLAIN, 13f));
        latestLog.setForeground(Theme.TEXT);
        latestLog.setMinimumSize(new Dimension(0, 0));
        logToggle.setIcon(logToggleIcon);
        logToggle.setBorder(BorderFactory.createEmptyBorder(4, 7, 4, 7));
        logToggle.addActionListener(e -> toggleLog());
        JPanel header = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        header.setOpaque(false);
        header.setAlignmentX(LEFT_ALIGNMENT);
        header.add(title, BorderLayout.WEST);
        header.add(latestLog, BorderLayout.CENTER);
        header.add(logToggle, BorderLayout.EAST);
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleLog();
            }
        });
        latestLog.setVisible(false);
        return header;
    }

    /** 聊天：訊息列表、一行說明、輸入框與送出，按 Enter 也送出。有聊天時才出現。 */
    private JPanel buildChatBox() {
        chatBox.setLayout(new BoxLayout(chatBox, BoxLayout.Y_AXIS));
        chatBox.setOpaque(false);
        chatBox.setAlignmentX(LEFT_ALIGNMENT);
        JLabel title = new JLabel("聊天");
        title.setFont(Theme.font(Font.BOLD, 13f));
        title.setForeground(Theme.GOLD);
        chatBox.add(leftAligned(title));
        chatBox.add(Box.createVerticalStrut(4));
        styleTextArea(chatArea);
        chatArea.setWrapStyleWord(false);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setBorder(null);
        chatScroll.setAlignmentX(LEFT_ALIGNMENT);
        chatBox.add(chatScroll);
        chatNotice.setFont(Theme.font(Font.PLAIN, 12f));
        chatNotice.setForeground(Theme.MUTED);
        chatBox.add(leftAligned(chatNotice));
        chatField.setFont(Theme.font(Font.PLAIN, 13f));
        ((AbstractDocument) chatField.getDocument()).setDocumentFilter(new LengthLimit(MAX_CHAT_LENGTH));
        chatField.addActionListener(e -> sendChat());
        JButton send = Theme.button("送出", Theme.BUTTON);
        send.setFont(Theme.font(Font.PLAIN, 13f));
        send.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        send.addActionListener(e -> sendChat());
        JPanel input = new JPanel(new BorderLayout(6, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        input.setOpaque(false);
        input.setAlignmentX(LEFT_ALIGNMENT);
        input.add(chatField, BorderLayout.CENTER);
        input.add(send, BorderLayout.EAST);
        chatBox.add(input);
        chatBox.setVisible(false);
        return chatBox;
    }

    private static void styleTextArea(JTextArea area) {
        area.setEditable(false);
        area.setLineWrap(true);
        area.setFont(Theme.font(Font.PLAIN, 13f));
        area.setBackground(Theme.PANEL_LIGHT);
        area.setForeground(Theme.TEXT);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }

    private void toggleLog() {
        if (!logLocked) {
            setLogExpanded(!logExpanded);
        }
    }

    /**
     * 專家模式：紀錄收起來只留最新一則，展開的箭頭藏起來；牌桌的棄牌每家只留最後一張，中間不寫剩幾張。
     * 一般模式照舊。
     */
    void setExpert(boolean on) {
        logLocked = on;
        logToggle.setVisible(!on);
        setLogExpanded(false);
        canvas.setExpert(on);
    }

    /** 每個事件都會送來，有沒有動畫都一樣。 */
    void noteEvent(RoundEvent event) {
        canvas.noteEvent(event);
    }

    /**
     * 結算：delayMs 之後牌桌上的籌碼飛向贏家，第一顆落地時計分表的籌碼從舊的數目滾到 chipsNow。
     * 在那之前計分表先顯示結算前的數目。
     */
    void animateSettlement(int[] deltas, int[] chipsNow, int delayMs) {
        canvas.animateSettlement(deltas, delayMs);
        rollTo = chipsNow.clone();
        rollFrom = new int[rollTo.length];
        for (int seat = 0; seat < rollTo.length; seat++) {
            rollFrom[seat] = rollTo[seat] - deltas[seat];
        }
        rollBegin = System.nanoTime() + (long) (delayMs + TableCanvas.CHIP_FLIGHT_MS) * 1_000_000L;
        showChips(rollFrom);
        chipRoll.start();
    }

    private void stepChipRoll() {
        if (rollTo == null) {
            chipRoll.stop();
            return;
        }
        double t = (System.nanoTime() - rollBegin) / 1e6 / TableCanvas.CHIP_ROLL_MS;
        if (t <= 0) {
            return;
        }
        if (t >= 1) {
            int[] done = rollTo;
            stopChipRoll();
            showChips(done);
            return;
        }
        double eased = 1 - Math.pow(1 - t, 3);
        int[] now = new int[rollTo.length];
        for (int seat = 0; seat < now.length; seat++) {
            now[seat] = (int) Math.round(rollFrom[seat] + (rollTo[seat] - rollFrom[seat]) * eased);
        }
        showChips(now);
    }

    private void stopChipRoll() {
        chipRoll.stop();
        rollTo = null;
        rollFrom = null;
    }

    private void showChips(int[] chips) {
        for (int seat = 0; seat < chips.length; seat++) {
            scoreCells[seat][2].setText(String.valueOf(chips[seat]));
        }
    }

    /** 紀錄一律預設收起來，只留「紀錄」那一行，空出來的地方給聊天。箭頭朝下是展開，朝上是收合。 */
    void setLogExpanded(boolean expanded) {
        logExpanded = expanded;
        logScroll.setVisible(expanded);
        latestLog.setVisible(!expanded);
        logToggleIcon.up = expanded;
        String action = expanded ? "收合紀錄" : "展開紀錄";
        logToggle.setToolTipText(action);
        logToggle.getAccessibleContext().setAccessibleName(action);
        logToggle.repaint();
        sidebar.revalidate();
        sidebar.repaint();
    }

    /** 連線的牌桌都有聊天，跟電腦打的一人房間也有，sender 是送出的地方。單機沒有聊天，傳 null 就藏起來。 */
    void setChat(Consumer<String> sender) {
        chatSender = sender;
        chatBox.setVisible(sender != null);
        sidebar.revalidate();
    }

    /** 文字來自網路，用 JTextArea 顯示，不會被當成 HTML。 */
    void appendChat(String name, String text, boolean mine) {
        chatArea.append((mine ? "我" : name) + "：" + text + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
        chatNotice.setText(" ");
    }

    /** 聊天送不出去的原因，例如說太快或舊服務器不能聊天。 */
    void showChatNotice(String text) {
        chatNotice.setText(safeLabel(text));
    }

    private void sendChat() {
        String text = chatField.getText().strip();
        if (chatSender == null || text.isEmpty()) {
            return;
        }
        chatSender.accept(text);
        chatField.setText("");
    }

    /** JLabel 的文字以 <html> 開頭就會當成 HTML，前面墊一個空白。 */
    private static String safeLabel(String text) {
        return text.startsWith("<") ? " " + text : text;
    }

    private ResultDialog.GridTable buildScoreTable() {
        ResultDialog.GridTable table = new ResultDialog.GridTable(2, 1);
        table.header("門風", "玩家", "籌碼");
        for (int seat = 0; seat < 4; seat++) {
            scoreCells[seat] = table.row(seat == 0 ? Font.BOLD : Font.PLAIN, " ", " ", " ");
            for (JLabel cell : scoreCells[seat]) {
                cell.setForeground(seat == 0 ? Theme.GOLD : Theme.TEXT);
            }
            // 名字太長時只縮玩家這欄，後面用刪節號，籌碼才不會被擠掉
            scoreCells[seat][1].setMinimumSize(new Dimension(0, 0));
            // 莊家的圓圈接在門風後面
            scoreCells[seat][0].setHorizontalTextPosition(SwingConstants.LEADING);
            scoreCells[seat][0].setIconTextGap(Theme.scaled(5));
        }
        return table;
    }

    /**
     * 計分表裡莊家的標記：紅色圓圈框住紅字「莊」。直徑跟著字體大小設定，是 22 的 Theme.scaled；
     * 圈線寬是直徑的 0.09（至少 1.5），字是粗體、直徑的 0.64。牌桌上名字旁邊的「莊」不用這個。
     * 連莊時圓圈右邊再寫紅色粗體的連莊次數，字是直徑的 0.8。
     */
    static final class DealerBadge implements Icon {
        static final DealerBadge INSTANCE = new DealerBadge(0);
        static final Color RED = new Color(0xE5, 0x48, 0x3D);
        private static final int DIAMETER = 22;
        /** 用來量連莊次數的寬度，不用畫在哪裡。 */
        private static final JLabel MEASURE = new JLabel();

        /** 連莊次數，0 表示沒有連莊，只畫圓圈。 */
        private final int streak;

        private DealerBadge(int streak) {
            this.streak = streak;
        }

        /** 連莊 streak 次的莊家標記。 */
        static DealerBadge of(int streak) {
            return streak > 0 ? new DealerBadge(streak) : INSTANCE;
        }

        private static Font streakFont(double size) {
            return Theme.fixedFont(Font.BOLD, (float) (size * .8));
        }

        private int streakWidth(double size) {
            return streak > 0 ? Theme.scaled(3) + MEASURE.getFontMetrics(streakFont(size)).stringWidth(String.valueOf(streak)) : 0;
        }

        @Override
        public int getIconWidth() {
            int size = Theme.scaled(DIAMETER);
            return size + streakWidth(size);
        }

        @Override
        public int getIconHeight() {
            return Theme.scaled(DIAMETER);
        }

        @Override
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double size = getIconHeight();
            double stroke = Math.max(1.5, size * .09);
            g.setColor(RED);
            g.setStroke(new BasicStroke((float) stroke));
            g.draw(new Ellipse2D.Double(x + stroke / 2, y + stroke / 2, size - stroke, size - stroke));
            g.setFont(Theme.fixedFont(Font.BOLD, (float) (size * .64)));
            FontMetrics metrics = g.getFontMetrics();
            String text = "莊";
            g.drawString(text, (float) (x + (size - metrics.stringWidth(text)) / 2),
                    (float) (y + (size - metrics.getHeight()) / 2 + metrics.getAscent()));
            if (streak > 0) {
                g.setFont(streakFont(size));
                FontMetrics streakMetrics = g.getFontMetrics();
                g.drawString(String.valueOf(streak), (float) (x + size + Theme.scaled(3)),
                        (float) (y + (size - streakMetrics.getHeight()) / 2 + streakMetrics.getAscent()));
            }
            g.dispose();
        }
    }

    /** 紀錄收合按鈕上的箭頭：一個 V 字，up 時朝上。大小跟著字體大小設定。 */
    static final class ChevronIcon implements Icon {
        private static final int SIZE = 14;
        boolean up;

        @Override
        public int getIconWidth() {
            return Theme.scaled(SIZE);
        }

        @Override
        public int getIconHeight() {
            return Theme.scaled(SIZE);
        }

        @Override
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double size = getIconWidth();
            double half = size * .32;
            double rise = size * .18 * (up ? -1 : 1);
            double cx = x + size / 2;
            double cy = y + size / 2;
            g.setColor(Theme.TEXT);
            g.setStroke(new BasicStroke((float) Math.max(1.5, size * .14), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double path = new Path2D.Double();
            path.moveTo(cx - half, cy - rise);
            path.lineTo(cx, cy + rise);
            path.lineTo(cx + half, cy - rise);
            g.draw(path);
            g.dispose();
        }
    }

    private static JPanel leftAligned(JLabel label) {
        JPanel row = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height + 8);
            }
        };
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(label, BorderLayout.WEST);
        return row;
    }

    /** 換一局。dice 是這局開門的骰子，牌桌照它畫牌牆。 */
    void bindRound(RoundView round, Dice dice) {
        logArea.setText("");
        latestLog.setText(" ");
        setDiscardPrompt(false);
        // 上一局的籌碼還沒滾完就開了新局，接著的 refresh 直接寫上現在的數目
        stopChipRoll();
        canvas.setRound(round, dice);
    }

    /**
     * 吃碰槓之後要打一張牌：動作列的提示放大成金色粗體並閃動，手牌外框也一明一暗，直到打出牌。
     * 一般的提示是灰色、15 點。
     */
    void setDiscardPrompt(boolean on) {
        canvas.setDiscardPrompt(on);
        if (on == promptBlink.isRunning()) {
            return;
        }
        if (on) {
            hint.setFont(Theme.font(Font.BOLD, 18f));
            blinkOn = true;
            hint.setForeground(Theme.GOLD);
            promptBlink.start();
        } else {
            promptBlink.stop();
            hint.setFont(Theme.font(Font.PLAIN, 15f));
            hint.setForeground(Theme.MUTED);
        }
    }

    private void blinkHint() {
        blinkOn = !blinkOn;
        hint.setForeground(blinkOn ? Theme.GOLD : Color.WHITE);
    }

    void animate(RoundEvent event) {
        canvas.animate(event);
    }

    /** streak 大於 0 時先跳出「連莊」的大字再擲骰子，見 TableCanvas.animateDeal。 */
    void animateDeal(int streak) {
        canvas.animateDeal(streak);
    }

    void appendLog(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
        latestLog.setText(safeLabel(message));
        latestLog.setToolTipText(safeLabel(message));
    }

    /** 由目前狀態更新計分板與牌桌。 */
    void refresh(GameView game, RoundView round) {
        // 結算後 session 已經換到下一局，畫面上還是這一局，要看 round
        String streak = round.streak() > 0 ? " 連" + round.streak() : "";
        roundInfo.setText(TableCanvas.roundLabel(round) + streak);
        stakesInfo.setText("底 " + game.basePay() + " / 每台 " + game.taiPay());
        for (int seat = 0; seat < 4; seat++) {
            Player player = game.player(seat);
            scoreCells[seat][0].setText(Tile.windName(round.seatWind(seat)));
            boolean dealer = round.dealer() == seat;
            scoreCells[seat][0].setIcon(dealer ? DealerBadge.of(round.streak()) : null);
            scoreCells[seat][0].setToolTipText(!dealer ? null
                    : round.streak() > 0 ? "莊家，連" + round.streak() + "拉" + round.streak() : "莊家");
            scoreCells[seat][1].setText(player.name());
            String traits = game.traits(seat);
            scoreCells[seat][1].setToolTipText(traits == null ? player.name() : player.name() + "（" + traits + "）");
            if (rollTo == null) {
                scoreCells[seat][2].setText(String.valueOf(player.chips()));
            }
        }
        canvas.repaint();
    }

    /** 只更新提示文字，按鈕不動。 */
    void setHint(String hintText) {
        hint.setText(hintText);
    }

    void showActions(String hintText, boolean discardEnabled, List<ActionItem> actions) {
        hint.setText(hintText);
        canvas.setDiscardEnabled(discardEnabled);
        setButtons(actions);
    }

    /** 進入選牌：只有 tiles 裡的手牌能點，其他蓋上遮罩。first 是已經選好的牌，沒有就是 null。 */
    void showPick(String hintText, Set<Tile> tiles, Tile first, Consumer<Tile> onPick, List<ActionItem> actions) {
        hint.setText(hintText);
        canvas.startPick(tiles, first, onPick, false);
        setButtons(actions);
    }

    /** 和 showPick 一樣只有 tiles 裡的手牌能點，這些牌另外加上金框，例如報聽時打出去會聽牌的牌。 */
    void showMarkedPick(String hintText, Set<Tile> tiles, Consumer<Tile> onPick, List<ActionItem> actions) {
        hint.setText(hintText);
        canvas.startPick(tiles, null, onPick, true);
        setButtons(actions);
    }

    private void setButtons(List<ActionItem> actions) {
        actionButtons.removeAll();
        for (ActionItem action : actions) {
            JButton button = action.translucent()
                    ? Theme.translucentButton(action.label(), action.color())
                    : Theme.button(action.label(), action.color());
            button.addActionListener(e -> action.run().run());
            actionButtons.add(button);
        }
        actionButtons.revalidate();
        actionButtons.repaint();
    }
}
