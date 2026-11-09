package ezmj.ui;

import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

import ezmj.engine.Achievement;
import ezmj.engine.GameView;
import ezmj.model.GameRecord;
import ezmj.model.Player;

/**
 * 牌局結束的視窗：結束原因與四家的最終排名和整場成績。
 * 可以把結果畫成一張圖分享出去，也可以把每一局的輸贏存成 PDF，單機與連線都有。
 */
final class FinalDialog extends JDialog {

    private static final String PDF_TITLE = "存成輸贏 PDF";

    private final GameReport report;

    /**
     * headline 是 null 時照結束原因寫，例如「四圈打完，牌局結束」。note 是表格下方要告訴玩家的話，沒有就傳空字串，
     * 可以用換行分成幾行。closeLabel 是關閉按鈕的文字。roomName 是連線的房間名稱，寫在結果圖與 PDF 上；單機是空字串。
     */
    FinalDialog(Frame owner, GameView game, String headline, String note, List<Achievement> earned, String closeLabel,
                String roomName) {
        super(owner, "牌局結束", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));

        Player[] ranking = ranking(game);
        String title = headline == null ? headline(ranking) : headline;
        report = new GameReport(game, title, roomName, LocalDateTime.now());

        content.add(ResultDialog.line(title, Font.BOLD, 24f, Theme.GOLD));
        content.add(Box.createVerticalStrut(6));
        content.add(ResultDialog.line("共打 " + game.roundsPlayed() + " 局，流局 " + game.drawnGames() + " 次",
                Font.PLAIN, 14f, Theme.MUTED));
        content.add(Box.createVerticalStrut(12));
        content.add(table(game, ranking));
        content.add(Box.createVerticalStrut(8));
        content.add(ResultDialog.line("胡牌次數包含自摸", Font.PLAIN, 14f, Theme.MUTED));
        if (!note.isEmpty()) {
            content.add(Box.createVerticalStrut(8));
            for (String line : note.split("\n")) {
                content.add(ResultDialog.line(line, Font.BOLD, 16f, Theme.GOLD));
            }
        }
        ResultDialog.addEarned(content, earned);
        content.add(Box.createVerticalStrut(16));

        JButton close = Theme.button(closeLabel, Theme.RED);
        close.addActionListener(e -> dispose());
        JButton history = Theme.button("輸贏歷程", Theme.BUTTON);
        history.addActionListener(e ->
                new HistoryDialog(this, ModalityType.APPLICATION_MODAL, game).setVisible(true));
        JButton share = Theme.button("分享結果圖", Theme.BUTTON);
        share.addActionListener(e ->
                new ShareDialog(this, "分享結果圖", report.shareImage(), report.imageFileName()).setVisible(true));
        JButton pdf = Theme.button(PDF_TITLE + "…", Theme.BUTTON);
        pdf.addActionListener(e -> savePdf());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        buttons.add(close);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(history);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(share);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(pdf);
        content.add(buttons);
        getRootPane().setDefaultButton(close);

        setContentPane(content);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void savePdf() {
        Path target = ExportFiles.chooseTarget(this, PDF_TITLE, report.pdfFileName(), "pdf", "PDF 文件");
        if (target == null) {
            return;
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            Files.write(target, report.pdf());
        } catch (IOException | RuntimeException e) {
            ExportFiles.failed(this, PDF_TITLE, e);
            return;
        } finally {
            setCursor(Cursor.getDefaultCursor());
        }
        ExportFiles.saved(this, PDF_TITLE, target);
    }

    /** 依籌碼由多到少。 */
    static Player[] ranking(GameView game) {
        Player[] ranking = {game.player(0), game.player(1), game.player(2), game.player(3)};
        Arrays.sort(ranking, Comparator.comparingInt(Player::chips).reversed());
        return ranking;
    }

    /** 和 ranking 同樣順序的名次，籌碼相同就是同一名。 */
    static int[] ranks(Player[] ranking) {
        int[] ranks = new int[ranking.length];
        for (int i = 0; i < ranking.length; i++) {
            ranks[i] = i == 0 || ranking[i].chips() != ranking[i - 1].chips() ? i + 1 : ranks[i - 1];
        }
        return ranks;
    }

    static String headline(Player[] ranking) {
        String broke = Arrays.stream(ranking)
                .filter(player -> player.chips() <= 0)
                .map(Player::name)
                .collect(Collectors.joining("、"));
        return broke.isEmpty() ? "四圈打完，牌局結束" : broke + " 輸光籌碼，牌局提早結束";
    }

    private static ResultDialog.GridTable table(GameView game, Player[] ranking) {
        ResultDialog.GridTable table = new ResultDialog.GridTable(1);
        table.header("名次　玩家", "胡牌", "自摸", "放槍", "輸贏", "籌碼");
        String date = LocalDate.now().toString();
        int[] ranks = ranks(ranking);
        for (int i = 0; i < ranking.length; i++) {
            Player player = ranking[i];
            GameRecord record = game.record(player.seat(), date);
            JLabel[] cells = table.row(player.seat() == 0 ? Font.BOLD : Font.PLAIN, ranks[i] + "　" + player.name(),
                    String.valueOf(record.wins()), String.valueOf(record.selfDraws()), String.valueOf(record.dealIns()),
                    ResultDialog.money(record.winnings()), String.valueOf(player.chips()));
            cells[4].setForeground(ResultDialog.moneyColor(record.winnings()));
        }
        return table;
    }
}
