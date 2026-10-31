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
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.Border;

import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundResult;
import ezmj.engine.Settlement;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;

/** 一局結束的結果視窗：胡牌牌型、台數明細與各家輸贏。 */
final class ResultDialog extends JDialog {

    /** autoCloseSeconds 大於 0 時，時間到自動關閉，電腦代打才能一直打下去。 */
    ResultDialog(Frame owner, GameSession session, Round round, Settlement settlement, String buttonText,
                 int autoCloseSeconds) {
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
            HandStrip strip = new HandStrip(round.player(result.winner()), result.winTile());
            strip.setAlignmentX(LEFT_ALIGNMENT);
            content.add(strip);
            content.add(Box.createVerticalStrut(12));
            content.add(tables(session, round, settlement));
            content.add(Box.createVerticalStrut(8));
            content.add(line("每筆付款＝底 " + session.config().basePay() + " ＋ 台數 × " + session.config().taiPay()
                    + (countsDealer(round, settlement) ? "，莊家台只算與莊家相關的付款" : ""),
                    Font.PLAIN, 14f, Theme.MUTED));
        }
        content.add(Box.createVerticalStrut(16));

        JButton next = Theme.button(buttonText, Theme.RED);
        next.addActionListener(e -> dispose());
        next.setAlignmentX(LEFT_ALIGNMENT);
        content.add(next);
        getRootPane().setDefaultButton(next);
        if (autoCloseSeconds > 0) {
            startAutoClose(next, buttonText, autoCloseSeconds);
        }

        setContentPane(content);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
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
        return result.isSelfDraw()
                ? winner + " 自摸 " + result.winTile().label()
                : winner + " 胡牌，" + round.player(result.from()).name() + " 放槍 " + result.winTile().label();
    }

    private static JLabel line(String text, int style, float size, Color color) {
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

    private static String money(int delta) {
        return (delta > 0 ? "+" : "") + delta;
    }

    private static Color moneyColor(int delta) {
        if (delta == 0) {
            return Theme.MUTED;
        }
        return delta > 0 ? Theme.GOLD : Theme.TEXT;
    }

    /** 欄位對齊的表格。前 leftColumns 欄是文字靠左，其餘是數字靠右。 */
    private static final class GridTable extends JPanel {
        private static final Border HEADER_BORDER = BorderFactory.createEmptyBorder(5, 10, 5, 10);
        private static final Border CELL_BORDER = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.PANEL_LIGHT), HEADER_BORDER);

        private final int leftColumns;
        private int rows;

        GridTable(int leftColumns) {
            super(new GridBagLayout());
            this.leftColumns = leftColumns;
            setBackground(Theme.PANEL);
            setAlignmentX(LEFT_ALIGNMENT);
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
                constraints.weightx = column == 0 ? 1 : 0;
                add(cell, constraints);
                cells[column] = cell;
            }
            return cells;
        }
    }

    /** 胡牌者的完整牌面。 */
    private static final class HandStrip extends JComponent {
        private final double TILE_W = 36 * Theme.tileScale();
        private final double TILE_H = 50 * Theme.tileScale();
        private final double GAP = 9 * Theme.tileScale();

        private final Player player;
        private final Tile winTile;

        HandStrip(Player player, Tile winTile) {
            this.player = player;
            this.winTile = winTile;
            int tiles = player.concealed().size();
            for (Meld meld : player.melds()) {
                tiles += meld.tiles().size();
            }
            Dimension size = new Dimension(
                    (int) (tiles * TILE_W + (player.melds().size() + 1) * GAP) + 4, (int) TILE_H + 6);
            setPreferredSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double x = 1;
            List<Tile> hand = new ArrayList<>(player.concealed());
            hand.remove(winTile);
            for (Tile tile : hand) {
                TilePainter.paintFace(g, tile, x, 2, TILE_W - 1, TILE_H);
                x += TILE_W;
            }
            x += GAP;
            TilePainter.paintFace(g, winTile, x, 2, TILE_W - 1, TILE_H);
            TilePainter.paintHighlight(g, x, 2, TILE_W - 1, TILE_H, Theme.RED);
            x += TILE_W;
            for (Meld meld : player.melds()) {
                x += GAP;
                for (Tile tile : meld.tiles()) {
                    TilePainter.paintFace(g, tile, x, 2, TILE_W - 1, TILE_H);
                    x += TILE_W;
                }
            }
            g.dispose();
        }
    }
}
