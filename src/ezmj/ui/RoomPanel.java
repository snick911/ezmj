package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import ezmj.online.RoomInfo;
import ezmj.online.RoomSettings;

/** 等人開局的房間：房號、房間設定、玩家與連線狀態。房主可以開局，不到四人由電腦補滿。 */
final class RoomPanel extends JPanel {

    private static final int SEATS = 4;

    private final JLabel title = ResultDialog.line(" ", Font.BOLD, 30f, Theme.GOLD);
    private final JLabel settingsLine = ResultDialog.line(" ", Font.PLAIN, 15f, Theme.MUTED);
    private final JPanel players = new JPanel(new BorderLayout());
    private final JLabel notice = ResultDialog.line(" ", Font.PLAIN, 15f, Theme.GOLD);
    private final JButton startButton = Theme.button("開始牌局", Theme.RED);
    private String code = "";

    RoomPanel(Runnable onStart, Runnable onLeave) {
        super(new GridBagLayout());
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.GOLD, 1),
                BorderFactory.createEmptyBorder(24, 36, 24, 36)));

        JButton copy = Theme.button("複製房號", Theme.BUTTON);
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(code), null));
        JPanel heading = new JPanel(new BorderLayout(24, 0));
        heading.setOpaque(false);
        heading.setAlignmentX(LEFT_ALIGNMENT);
        heading.add(title, BorderLayout.WEST);
        heading.add(copy, BorderLayout.EAST);
        card.add(heading);
        card.add(Box.createVerticalStrut(6));
        card.add(settingsLine);
        card.add(Box.createVerticalStrut(14));
        players.setOpaque(false);
        players.setAlignmentX(LEFT_ALIGNMENT);
        card.add(players);
        card.add(Box.createVerticalStrut(10));
        card.add(notice);
        card.add(Box.createVerticalStrut(14));

        startButton.addActionListener(e -> onStart.run());
        JButton leave = Theme.button("離開房間", Theme.PANEL_LIGHT);
        leave.addActionListener(e -> onLeave.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        buttons.add(startButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(leave);
        card.add(buttons);
        add(card);
    }

    void setRoom(RoomInfo room) {
        code = room.code();
        title.setText("房號 " + code);
        RoomSettings settings = room.settings();
        settingsLine.setText("籌碼 " + settings.chips() + "　底 " + settings.basePay() + "　每台 " + settings.taiPay()
                + "　每次等待 " + settings.waitSeconds() + " 秒　電腦速度" + settings.speed().label());

        ResultDialog.GridTable table = new ResultDialog.GridTable(3, 1);
        table.header("座位", "玩家", "狀態");
        for (int seat = 0; seat < SEATS; seat++) {
            addSeat(table, room, seat);
        }
        table.setPreferredSize(new Dimension(Math.max(Theme.scaled(520), table.getPreferredSize().width),
                table.getPreferredSize().height));
        players.removeAll();
        players.add(table, BorderLayout.CENTER);
        players.revalidate();
        players.repaint();

        startButton.setEnabled(room.isHost());
        startButton.setText(room.isHost() ? "開始牌局" : "等房主開局");
        notice.setText(room.isHost() ? "不到四人時由電腦補滿。" : " ");
    }

    private static void addSeat(ResultDialog.GridTable table, RoomInfo room, int seat) {
        RoomInfo.Seat player = room.seats().stream().filter(item -> item.seat() == seat).findFirst().orElse(null);
        if (player == null) {
            // 沒有人坐的座位只有 1、2、3，開局時才由電腦補上
            JLabel[] cells = table.row(Font.PLAIN, String.valueOf(seat + 1), "（空位）",
                    "開局時由" + room.settings().botLevels().get(seat - 1).label() + "電腦補上");
            for (JLabel cell : cells) {
                cell.setForeground(Theme.MUTED);
            }
            return;
        }
        boolean mine = seat == room.yourSeat();
        String state = (seat == 0 ? "房主・" : "") + (player.connected() ? "連線中" : "斷線");
        JLabel[] cells = table.row(mine ? Font.BOLD : Font.PLAIN, String.valueOf(seat + 1),
                player.name() + (mine ? "（你）" : ""), state);
        if (mine) {
            for (JLabel cell : cells) {
                cell.setForeground(Theme.GOLD);
            }
        }
    }

    /** 連線狀態的提示。 */
    void setNotice(String text) {
        notice.setText(text);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        g.setPaint(new GradientPaint(0, 0, Theme.FELT, 0, getHeight(), Theme.FELT_DARK));
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}
