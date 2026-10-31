package ezmj.ui;

import java.awt.Font;
import java.awt.Frame;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

import ezmj.engine.GameSession;
import ezmj.model.GameRecord;
import ezmj.model.Player;

/** 牌局結束的視窗：結束原因與四家的最終排名和整場成績。 */
final class FinalDialog extends JDialog {

    /** note 是表格下方要告訴玩家的話，沒有就傳空字串。 */
    FinalDialog(Frame owner, GameSession session, String note) {
        super(owner, "牌局結束", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));

        Player[] ranking = {session.player(0), session.player(1), session.player(2), session.player(3)};
        Arrays.sort(ranking, Comparator.comparingInt(Player::chips).reversed());

        content.add(ResultDialog.line(headline(ranking), Font.BOLD, 24f, Theme.GOLD));
        content.add(Box.createVerticalStrut(6));
        content.add(ResultDialog.line("共打 " + session.roundsPlayed() + " 局，流局 " + session.drawnGames() + " 次",
                Font.PLAIN, 14f, Theme.MUTED));
        content.add(Box.createVerticalStrut(12));
        content.add(table(session, ranking));
        content.add(Box.createVerticalStrut(8));
        content.add(ResultDialog.line("胡牌次數包含自摸", Font.PLAIN, 14f, Theme.MUTED));
        if (!note.isEmpty()) {
            content.add(Box.createVerticalStrut(8));
            content.add(ResultDialog.line(note, Font.BOLD, 16f, Theme.GOLD));
        }
        content.add(Box.createVerticalStrut(16));

        JButton close = Theme.button("回到開始畫面", Theme.RED);
        close.addActionListener(e -> dispose());
        close.setAlignmentX(LEFT_ALIGNMENT);
        content.add(close);
        getRootPane().setDefaultButton(close);

        setContentPane(content);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private static String headline(Player[] ranking) {
        String broke = Arrays.stream(ranking)
                .filter(player -> player.chips() <= 0)
                .map(Player::name)
                .collect(Collectors.joining("、"));
        return broke.isEmpty() ? "四圈打完，牌局結束" : broke + " 輸光籌碼，牌局提早結束";
    }

    private static ResultDialog.GridTable table(GameSession session, Player[] ranking) {
        ResultDialog.GridTable table = new ResultDialog.GridTable(1);
        table.header("名次　玩家", "胡牌", "自摸", "放槍", "輸贏", "籌碼");
        String date = LocalDate.now().toString();
        int rank = 0;
        for (int i = 0; i < ranking.length; i++) {
            Player player = ranking[i];
            // 籌碼相同就是同一名
            if (i == 0 || player.chips() != ranking[i - 1].chips()) {
                rank = i + 1;
            }
            GameRecord record = session.record(player.seat(), date);
            JLabel[] cells = table.row(player.isHuman() ? Font.BOLD : Font.PLAIN, rank + "　" + player.name(),
                    String.valueOf(record.wins()), String.valueOf(record.selfDraws()), String.valueOf(record.dealIns()),
                    ResultDialog.money(record.winnings()), String.valueOf(player.chips()));
            cells[4].setForeground(ResultDialog.moneyColor(record.winnings()));
        }
        return table;
    }
}
