package ezmj.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import ezmj.engine.GameView;
import ezmj.engine.RoundRecord;
import ezmj.engine.RoundResult;
import ezmj.model.Tile;
import ezmj.ui.ResultDialog.GridTable;

/** 這場牌局每一局的輸贏歷程。局數多時可以捲動，表頭固定不動。 */
final class HistoryDialog extends JDialog {

    private static final int SEATS = 4;
    private static final int LEFT_COLUMNS = 3;
    private static final int MAX_TABLE_HEIGHT = 520;
    private static final int SCREEN_MARGIN = 220;
    private static final int SCROLL_STEP = 24;
    private static final int FIRST_MONEY_COLUMN = 4;
    private static final int COLUMNS = FIRST_MONEY_COLUMN + SEATS + 1;

    private final GameView game;

    /** 打牌中開著可以繼續打，所以不擋住牌桌；牌局結束視窗上開的才要 modal。 */
    HistoryDialog(Window owner, ModalityType modality, GameView game) {
        super(owner, "輸贏歷程", modality);
        this.game = game;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        refresh();
        setLocationRelativeTo(owner);
    }

    /** 依目前打完的局數重畫，並捲到最新的一局。 */
    void refresh() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));

        List<RoundRecord> history = game.history();
        content.add(ResultDialog.line("輸贏歷程", Font.BOLD, 24f, Theme.GOLD));
        content.add(Box.createVerticalStrut(6));
        content.add(ResultDialog.line(history.isEmpty() ? "還沒有打完的局" : "共打 " + history.size() + " 局，把滑鼠停在結果上可以看牌型",
                Font.PLAIN, 14f, Theme.MUTED));
        content.add(Box.createVerticalStrut(12));

        JScrollPane scroll = null;
        if (!history.isEmpty()) {
            scroll = scrollingTable();
            content.add(scroll);
            content.add(Box.createVerticalStrut(16));
        }

        JButton close = Theme.button("關閉", Theme.BUTTON);
        close.addActionListener(e -> dispose());
        close.setAlignmentX(LEFT_ALIGNMENT);
        content.add(close);
        getRootPane().setDefaultButton(close);

        setContentPane(content);
        pack();
        setResizable(false);
        if (scroll != null) {
            JScrollBar bar = scroll.getVerticalScrollBar();
            SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
        }
    }

    /**
     * 表頭是同一張表再做一份，只露出最上面一列，欄寬才會和內容完全一樣。
     * 內容那份的表頭列高度歸零，只留著撐開欄寬。
     */
    private JScrollPane scrollingTable() {
        GridTable table = table();
        for (int column = 0; column < COLUMNS; column++) {
            Component cell = table.getComponent(column);
            cell.setPreferredSize(new Dimension(cell.getPreferredSize().width, 0));
        }
        GridTable headerCopy = table();
        int headerHeight = headerCopy.getComponent(0).getPreferredSize().height;
        JViewport header = new JViewport();
        header.setView(headerCopy);
        header.setPreferredSize(new Dimension(table.getPreferredSize().width, headerHeight));

        JScrollPane scroll = new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setColumnHeader(header);
        scroll.setBorder(null);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getVerticalScrollBar().setUnitIncrement(SCROLL_STEP);
        JPanel corner = new JPanel();
        corner.setBackground(Theme.PANEL_LIGHT);
        scroll.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);

        int fullHeight = table.getPreferredSize().height + headerHeight;
        int screenLimit = Toolkit.getDefaultToolkit().getScreenSize().height - SCREEN_MARGIN;
        int height = Math.min(fullHeight, Math.min(Theme.scaled(MAX_TABLE_HEIGHT), screenLimit));
        int scrollBarWidth = height < fullHeight ? scroll.getVerticalScrollBar().getPreferredSize().width : 0;
        Dimension size = new Dimension(table.getPreferredSize().width + scrollBarWidth, height);
        scroll.setPreferredSize(size);
        scroll.setMaximumSize(size);
        return scroll;
    }

    private GridTable table() {
        GridTable table = new GridTable(LEFT_COLUMNS);
        List<String> header = new ArrayList<>(List.of("局", "圈風", "結果", "牌型台"));
        for (int seat = 0; seat < SEATS; seat++) {
            header.add(game.player(seat).name());
        }
        header.add("我的累計");
        table.header(header.toArray(new String[0]));

        int[] totals = new int[SEATS];
        for (RoundRecord record : game.history()) {
            for (int seat = 0; seat < SEATS; seat++) {
                totals[seat] += record.deltas()[seat];
            }
            addRound(table, record, totals[0]);
        }
        addTotals(table, totals);
        return table;
    }

    private void addRound(GridTable table, RoundRecord record, int running) {
        RoundResult result = record.result();
        List<String> cells = new ArrayList<>(List.of(String.valueOf(record.number()), windText(game, record),
                resultText(game, result),
                result.isDrawnGame() ? "—" : String.valueOf(result.score().totalTai())));
        for (int seat = 0; seat < SEATS; seat++) {
            cells.add(ResultDialog.money(record.deltas()[seat]));
        }
        cells.add(ResultDialog.money(running));
        JLabel[] labels = table.row(Font.PLAIN, cells.toArray(new String[0]));
        labels[2].setToolTipText(scoreText(result));
        for (int seat = 0; seat < SEATS; seat++) {
            colorMoney(labels[FIRST_MONEY_COLUMN + seat], record.deltas()[seat]);
        }
        colorMoney(labels[FIRST_MONEY_COLUMN + SEATS], running);
        labels[FIRST_MONEY_COLUMN].setFont(labels[FIRST_MONEY_COLUMN].getFont().deriveFont(Font.BOLD));
    }

    private void addTotals(GridTable table, int[] totals) {
        List<String> cells = new ArrayList<>(List.of("合計", "", "", ""));
        for (int total : totals) {
            cells.add(ResultDialog.money(total));
        }
        cells.add(ResultDialog.money(totals[0]));
        JLabel[] labels = table.row(Font.BOLD, cells.toArray(new String[0]));
        for (int seat = 0; seat < SEATS; seat++) {
            colorMoney(labels[FIRST_MONEY_COLUMN + seat], totals[seat]);
        }
        colorMoney(labels[FIRST_MONEY_COLUMN + SEATS], totals[0]);
    }

    private static void colorMoney(JLabel label, int amount) {
        label.setForeground(ResultDialog.moneyColor(amount));
    }

    /** 例如「東風 莊王小明 連2」；結果圖的 PDF 也用。 */
    static String windText(GameView game, RoundRecord record) {
        return Tile.windName(record.roundWind()) + "風 莊" + game.player(record.dealer()).name()
                + (record.streak() > 0 ? " 連" + record.streak() : "");
    }

    static String resultText(GameView game, RoundResult result) {
        if (result.isDrawnGame()) {
            return "流局";
        }
        String winner = game.player(result.winner()).name();
        if (result.isFlowerWin()) {
            return result.isSelfDraw()
                    ? winner + " 八仙過海"
                    : winner + " 七搶一 " + game.player(result.from()).name() + " 的 " + result.winTile().label();
        }
        return result.isSelfDraw()
                ? winner + " 自摸 " + result.winTile().label()
                : winner + " 胡 " + game.player(result.from()).name() + " 的 " + result.winTile().label();
    }

    /** 滑鼠提示裡的牌型明細；流局沒有。 */
    static String scoreText(RoundResult result) {
        if (result.isDrawnGame()) {
            return null;
        }
        if (result.score().items().isEmpty()) {
            return "屁胡 0 台";
        }
        return result.score().items().stream()
                .map(item -> item.name() + " " + item.tai() + " 台")
                .collect(Collectors.joining("、"));
    }
}
