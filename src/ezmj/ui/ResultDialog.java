package ezmj.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.Border;

import ezmj.engine.Achievement;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundResult;
import ezmj.engine.Settlement;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;

/** 一局結束的結果視窗：胡牌牌型、台數明細、各家輸贏與四家手牌。 */
final class ResultDialog extends JDialog {

    private static final int SCREEN_MARGIN = 80;
    private static final int SCROLLBAR_ROOM = 24;

    /** 時間到自動關閉，電腦代打才能一直打下去。 */
    ResultDialog(Frame owner, GameSession session, Round round, Settlement settlement, List<Achievement> earned,
                 String buttonText, int autoCloseSeconds) {
        super(owner, "本局結果", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));

        RoundResult result = round.result();
        content.add(line(headline(round, result), Font.BOLD, 24f, Theme.GOLD));
        content.add(Box.createVerticalStrut(12));

        if (result.isDrawnGame()) {
            content.add(drawnTable(session));
        } else {
            HandStrip strip = HandStrip.large(round.player(result.winner()), result.winTile());
            strip.setAlignmentX(LEFT_ALIGNMENT);
            content.add(strip);
            content.add(Box.createVerticalStrut(12));
            content.add(tables(session, round, settlement));
            content.add(Box.createVerticalStrut(8));
            content.add(line("每筆付款＝底 " + session.config().basePay() + " ＋ 台數 × " + session.config().taiPay()
                    + (countsDealer(round, settlement) ? "，莊家台只算與莊家相關的付款" : ""),
                    Font.PLAIN, 14f, Theme.MUTED));
        }
        content.add(Box.createVerticalStrut(12));
        content.add(otherHands(round));
        addEarned(content, earned);
        content.add(Box.createVerticalStrut(16));

        JButton next = Theme.button(buttonText, Theme.RED);
        next.addActionListener(e -> dispose());
        next.setAlignmentX(LEFT_ALIGNMENT);
        content.add(next);
        getRootPane().setDefaultButton(next);
        startAutoClose(next, buttonText, autoCloseSeconds);

        setContentPane(scrollable(content));
        pack();
        fitOnScreen();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private static JScrollPane scrollable(JPanel content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }

    /** 牌放大時內容可能比螢幕高，超過就縮到螢幕內，用捲的看。 */
    private void fitOnScreen() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int maxHeight = screen.height - SCREEN_MARGIN;
        if (getHeight() > maxHeight) {
            setSize(Math.min(getWidth() + SCROLLBAR_ROOM, screen.width), maxHeight);
        }
    }

    private void startAutoClose(JButton next, String buttonText, int seconds) {
        int[] left = {seconds};
        next.setText(buttonText + "（" + left[0] + "）");
        Timer timer = new Timer(1000, e -> {
            left[0]--;
            if (left[0] > 0 && isDisplayable()) {
                next.setText(buttonText + "（" + left[0] + "）");
            } else {
                ((Timer) e.getSource()).stop();
                dispose();
            }
        });
        timer.start();
    }

    private static String headline(Round round, RoundResult result) {
        if (result.isDrawnGame()) {
            return "流局";
        }
        String winner = round.player(result.winner()).name();
        if (result.isFlowerWin()) {
            return result.isSelfDraw()
                    ? winner + " 集滿八張花牌，八仙過海"
                    : winner + " 七搶一，搶走 " + round.player(result.from()).name() + " 的 " + result.winTile().label();
        }
        return result.isSelfDraw()
                ? winner + " 自摸 " + result.winTile().label()
                : winner + " 胡牌，" + round.player(result.from()).name() + " 放槍 " + result.winTile().label();
    }

    /** 其他家的手牌，打完才看得到別人在做什麼。胡牌者上面已經有了；靠花牌胡的上面只有花牌，這裡也列手牌。 */
    private static JPanel otherHands(Round round) {
        RoundResult result = round.result();
        JPanel hands = new JPanel(new GridBagLayout());
        hands.setBackground(Theme.PANEL);
        hands.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(2, 0, 2, 12);
        constraints.gridy = 0;
        for (int seat = 0; seat < 4; seat++) {
            if (seat == result.winner() && !result.isFlowerWin()) {
                continue;
            }
            Player player = round.player(seat);
            String mark = !result.isDrawnGame() && !result.isSelfDraw() && seat == result.from() ? "（放槍）" : "";
            constraints.gridx = 0;
            hands.add(line(player.name() + mark, seat == 0 ? Font.BOLD : Font.PLAIN, 15f, Theme.TEXT), constraints);
            constraints.gridx = 1;
            hands.add(HandStrip.small(player), constraints);
            constraints.gridy++;
        }
        return hands;
    }

    /** 列出這次新達成的成就。 */
    static void addEarned(JPanel content, List<Achievement> earned) {
        if (!earned.isEmpty()) {
            content.add(Box.createVerticalStrut(10));
        }
        for (Achievement achievement : earned) {
            content.add(line("★ 達成成就：" + achievement.title() + "（" + achievement.description() + "）",
                    Font.BOLD, 16f, Theme.GOLD));
        }
    }

    static JLabel line(String text, int style, float size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(style, size));
        label.setForeground(color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    /** 左邊牌型台數，右邊各家輸贏。 */
    private static JPanel tables(GameSession session, Round round, Settlement settlement) {
        JPanel tables = new JPanel(new GridBagLayout());
        tables.setBackground(Theme.PANEL);
        tables.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.anchor = GridBagConstraints.NORTH;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 0, 20);
        tables.add(scoreTable(round, settlement), constraints);
        constraints.insets = new Insets(0, 0, 0, 0);
        constraints.weightx = 1;
        tables.add(payTable(session, round, settlement), constraints);
        return tables;
    }

    private static GridTable scoreTable(Round round, Settlement settlement) {
        ScoreResult score = round.result().score();
        GridTable table = new GridTable(1);
        table.header("牌型", "台數");
        for (ScoreItem item : score.items()) {
            table.row(Font.PLAIN, item.name(), String.valueOf(item.tai()));
        }
        if (score.items().isEmpty()) {
            table.row(Font.PLAIN, "屁胡", "0");
        }
        for (JLabel cell : table.row(Font.BOLD, "牌型合計", String.valueOf(score.totalTai()))) {
            cell.setForeground(Theme.GOLD);
        }
        if (countsDealer(round, settlement)) {
            table.row(Font.PLAIN, "＋莊家", "1");
            if (round.streak() > 0) {
                table.row(Font.PLAIN, "＋連" + round.streak() + "拉" + round.streak(), String.valueOf(2 * round.streak()));
            }
        }
        return table;
    }

    /** 有人的付款比牌型台數多，就是這局有算到莊家台。 */
    private static boolean countsDealer(Round round, Settlement settlement) {
        return Arrays.stream(settlement.tais()).anyMatch(tai -> tai > round.result().score().totalTai());
    }

    private static GridTable payTable(GameSession session, Round round, Settlement settlement) {
        GridTable table = new GridTable(2);
        table.header("玩家", "結果", "台數", "輸贏", "籌碼");
        for (int seat = 0; seat < 4; seat++) {
            Player player = session.player(seat);
            int delta = settlement.deltas()[seat];
            String tai = delta < 0 ? String.valueOf(settlement.tais()[seat]) : "—";
            JLabel[] cells = table.row(seat == 0 ? Font.BOLD : Font.PLAIN,
                    player.name() + (seat == round.dealer() ? "（莊）" : ""), outcome(round.result(), seat),
                    tai, money(delta), String.valueOf(player.chips()));
            cells[3].setForeground(moneyColor(delta));
        }
        return table;
    }

    private static GridTable drawnTable(GameSession session) {
        GridTable table = new GridTable(1);
        table.header("玩家", "籌碼");
        for (int seat = 0; seat < 4; seat++) {
            Player player = session.player(seat);
            table.row(seat == 0 ? Font.BOLD : Font.PLAIN, player.name(), String.valueOf(player.chips()));
        }
        return table;
    }

    private static String outcome(RoundResult result, int seat) {
        if (seat == result.winner()) {
            return result.isSelfDraw() ? "自摸" : "胡牌";
        }
        if (result.isSelfDraw()) {
            return "被自摸";
        }
        return seat == result.from() ? "放槍" : "";
    }

    static String money(int delta) {
        return (delta > 0 ? "+" : "") + delta;
    }

    static Color moneyColor(int delta) {
        if (delta == 0) {
            return Theme.MUTED;
        }
        return delta > 0 ? Theme.GOLD : Theme.TEXT;
    }

    /** 欄位對齊的表格。前 leftColumns 欄是文字靠左，其餘是數字靠右。多出來的寬度給 stretchColumn。 */
    static final class GridTable extends JPanel {
        private static final Border HEADER_BORDER = BorderFactory.createEmptyBorder(5, 10, 5, 10);
        private static final Border CELL_BORDER = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.PANEL_LIGHT), HEADER_BORDER);

        private final int leftColumns;
        private final int stretchColumn;
        private int rows;

        GridTable(int leftColumns) {
            this(leftColumns, 0);
        }

        GridTable(int leftColumns, int stretchColumn) {
            super(new GridBagLayout());
            this.leftColumns = leftColumns;
            this.stretchColumn = stretchColumn;
            setBackground(Theme.PANEL);
            setAlignmentX(LEFT_ALIGNMENT);
        }

        /** 放在 BoxLayout 裡時不要被拉高。 */
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        void header(String... texts) {
            for (JLabel cell : add(Font.BOLD, 14f, Theme.MUTED, HEADER_BORDER, texts)) {
                cell.setOpaque(true);
                cell.setBackground(Theme.PANEL_LIGHT);
            }
        }

        JLabel[] row(int style, String... texts) {
            return add(style, 16f, Theme.TEXT, CELL_BORDER, texts);
        }

        private JLabel[] add(int style, float size, Color color, Border border, String[] texts) {
            JLabel[] cells = new JLabel[texts.length];
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = rows++;
            constraints.fill = GridBagConstraints.BOTH;
            for (int column = 0; column < texts.length; column++) {
                JLabel cell = line(texts[column], style, size, color);
                cell.setHorizontalAlignment(column < leftColumns ? SwingConstants.LEFT : SwingConstants.RIGHT);
                cell.setBorder(border);
                constraints.gridx = column;
                constraints.weightx = column == stretchColumn ? 1 : 0;
                add(cell, constraints);
                cells[column] = cell;
            }
            return cells;
        }
    }

    /** 一家的完整牌面。winTile 是 null 表示這家沒有胡，全部排在一起。 */
    private static final class HandStrip extends JComponent {
        private final double tileW;
        private final double tileH;
        private final double gap;
        private final Player player;
        private final Tile winTile;

        static HandStrip large(Player player, Tile winTile) {
            return new HandStrip(player, winTile, 36, 50, 9);
        }

        static HandStrip small(Player player) {
            return new HandStrip(player, null, 24, 33, 6);
        }

        private HandStrip(Player player, Tile winTile, double w, double h, double spacing) {
            this.player = player;
            this.winTile = winTile;
            this.tileW = w * Theme.tileScale();
            this.tileH = h * Theme.tileScale();
            this.gap = spacing * Theme.tileScale();
            int tiles = player.concealed().size();
            for (Meld meld : player.melds()) {
                tiles += meld.tiles().size();
            }
            if (winTile != null && winTile.isFlower()) {
                tiles = player.flowers().size();
            }
            Dimension size = new Dimension(
                    (int) (tiles * tileW + (player.melds().size() + 1) * gap) + 4, (int) tileH + 6);
            setPreferredSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double x = 1;
            // 靠花牌胡的不看手牌，只列花牌
            if (winTile != null && winTile.isFlower()) {
                for (Tile flower : player.flowers()) {
                    TilePainter.paintFace(g, flower, x, 2, tileW - 1, tileH);
                    if (flower.equals(winTile)) {
                        TilePainter.paintHighlight(g, x, 2, tileW - 1, tileH, Theme.RED);
                    }
                    x += tileW;
                }
                g.dispose();
                return;
            }
            for (Meld meld : player.melds()) {
                for (Tile tile : meld.tiles()) {
                    TilePainter.paintFace(g, tile, x, 2, tileW - 1, tileH);
                    x += tileW;
                }
                x += gap;
            }
            List<Tile> hand = new ArrayList<>(player.concealed());
            hand.remove(winTile);
            for (Tile tile : hand) {
                TilePainter.paintFace(g, tile, x, 2, tileW - 1, tileH);
                x += tileW;
            }
            if (winTile != null) {
                x += gap;
                TilePainter.paintFace(g, winTile, x, 2, tileW - 1, tileH);
                TilePainter.paintHighlight(g, x, 2, tileW - 1, tileH, Theme.RED);
            }
            g.dispose();
        }
    }
}
