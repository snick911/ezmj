package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Color;
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

/** 等人開局的房間：房間名稱、房號、房間設定、玩家與連線狀態。房主可以開局，不到四人由電腦補滿。 */
final class RoomPanel extends JPanel {

    private static final int SEATS = 4;
    private static final int LONG_NAME = 14;

    private final JLabel title = ResultDialog.line(" ", Font.BOLD, 30f, Theme.GOLD);
    private final JLabel privateBadge = ResultDialog.line("私人", Font.BOLD, 15f, Color.WHITE);
    private final JLabel expertBadge = ResultDialog.line("專家模式", Font.BOLD, 15f, Color.WHITE);
    private final JLabel substituteBadge = ResultDialog.line("隨時補位", Font.BOLD, 15f, Color.WHITE);
    private final JPanel codeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JLabel codeLine = ResultDialog.line(" ", Font.BOLD, 18f, Theme.TEXT);
    private final JLabel listedLine = ResultDialog.line(" ", Font.PLAIN, 15f, Theme.MUTED);
    private final JLabel settingsLine = ResultDialog.line(" ", Font.PLAIN, 15f, Theme.MUTED);
    /** 專家模式的房間才有，說明哪些不一樣，房間裡每個人都照這個打。 */
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
        privateBadge.setOpaque(true);
        privateBadge.setBackground(Theme.RED);
        privateBadge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        privateBadge.setToolTipText("不列在大廳，朋友要用房號加入");
        expertBadge.setOpaque(true);
        expertBadge.setBackground(Theme.BUTTON);
        expertBadge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        expertBadge.setToolTipText("這間照專家模式打，有哪些不一樣見「說明」→「遊戲玩法」");
        substituteBadge.setOpaque(true);
        substituteBadge.setBackground(Theme.BUTTON);
        substituteBadge.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        substituteBadge.setToolTipText("開打之後，電腦在打的座位別人也能中途加入替換，中途加入的不列入榮譽榜。"
                + "詳見「說明」→「連線對戰」");
        JPanel name = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        name.setOpaque(false);
        name.add(title);
        name.add(Box.createHorizontalStrut(12));
        name.add(privateBadge);
        name.add(Box.createHorizontalStrut(8));
        name.add(expertBadge);
        name.add(Box.createHorizontalStrut(8));
        name.add(substituteBadge);
        JPanel heading = new JPanel(new BorderLayout(24, 0));
        heading.setOpaque(false);
        heading.setAlignmentX(LEFT_ALIGNMENT);
        heading.add(name, BorderLayout.WEST);
        heading.add(copy, BorderLayout.EAST);
        card.add(heading);
        card.add(Box.createVerticalStrut(6));
        codeRow.setOpaque(false);
        codeRow.setAlignmentX(LEFT_ALIGNMENT);
        codeRow.add(codeLine);
        codeRow.add(Box.createHorizontalStrut(14));
        codeRow.add(listedLine);
        card.add(codeRow);
        card.add(Box.createVerticalStrut(4));
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
        RoomSettings settings = room.settings();
        // 舊服務器沒有房間名稱，就和以前一樣用房號當標題。不能 strip：<html> 前面墊的空白會被拿掉
        String name = settings.name();
        boolean named = !name.isBlank();
        title.setText(named ? name : "房號 " + code);
        // 名稱最長 30 字，用原本的大小會把房間撐得比視窗還寬
        title.setFont(Theme.font(Font.BOLD, name.codePointCount(0, name.length()) > LONG_NAME ? 22f : 30f));
        privateBadge.setVisible(settings.privateRoom());
        // 標題旁的「專家模式」標籤就夠了，有哪些不一樣寫在說明
        expertBadge.setVisible(settings.expert());
        substituteBadge.setVisible(settings.substitute());
        codeRow.setVisible(named);
        codeLine.setText("房號 " + code);
        listedLine.setText(settings.privateRoom() ? "不列在大廳，把房號告訴朋友就能加入。" : "列在大廳，也可以把房號告訴朋友。");
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
        notice.setText(!room.isHost() ? " " : settings.substitute()
                ? "不到四人時由電腦補滿，開打後別人還能加入替換電腦。" : "不到四人時由電腦補滿。");
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
