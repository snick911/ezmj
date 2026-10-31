package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.model.Player;
import ezmj.model.Tile;

/** 牌桌主畫面：中間是牌桌，下方是動作列，右側是計分板與紀錄。 */
public final class TablePanel extends JPanel {

    /** 動作列上的一個按鈕。 */
    record ActionItem(String label, Color color, Runnable run) {
    }

    private final TableCanvas canvas;
    private final JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
    private final JLabel hint = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel roundInfo = new JLabel(" ");
    private final JLabel stakesInfo = new JLabel(" ");
    private final JLabel[] scoreLabels = new JLabel[4];
    private final JTextArea logArea = new JTextArea();
    private final JPanel actionBar = new JPanel();
    private final JPanel sidebar = new JPanel();
    private final JButton autoPlayButton = Theme.button("電腦代打：關", Theme.PANEL_LIGHT);

    TablePanel(Consumer<Tile> onDiscard, Runnable onLeave, Runnable onToggleAutoPlay) {
        super(new BorderLayout());
        canvas = new TableCanvas(onDiscard);
        add(canvas, BorderLayout.CENTER);
        add(buildActionBar(), BorderLayout.SOUTH);
        add(buildSidebar(onLeave, onToggleAutoPlay), BorderLayout.EAST);
        syncSizes();
    }

    void setAutoPlay(boolean on) {
        autoPlayButton.setText(on ? "電腦代打：開" : "電腦代打：關");
        autoPlayButton.setBackground(on ? Theme.RED : Theme.PANEL_LIGHT);
    }

    /** 字體大小改變後，讓側欄與動作列跟著調整。 */
    void syncSizes() {
        actionBar.setPreferredSize(new Dimension(0, Theme.scaled(84)));
        sidebar.setPreferredSize(new Dimension(Theme.scaled(262), 0));
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

    private JPanel buildSidebar(Runnable onLeave, Runnable onToggleAutoPlay) {
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

        for (int seat = 0; seat < 4; seat++) {
            scoreLabels[seat] = new JLabel(" ");
            scoreLabels[seat].setFont(Theme.font(Font.PLAIN, 15f));
            scoreLabels[seat].setForeground(Theme.TEXT);
            side.add(leftAligned(scoreLabels[seat]));
            side.add(Box.createVerticalStrut(5));
        }
        side.add(Box.createVerticalStrut(8));

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setFont(Theme.font(Font.PLAIN, 13f));
        logArea.setBackground(Theme.PANEL_LIGHT);
        logArea.setForeground(Theme.TEXT);
        logArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(null);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        side.add(scroll);
        side.add(Box.createVerticalStrut(10));

        autoPlayButton.setFont(Theme.font(Font.PLAIN, 14f));
        autoPlayButton.addActionListener(e -> onToggleAutoPlay.run());
        side.add(autoPlayButton);
        side.add(Box.createVerticalStrut(8));

        JButton leave = Theme.button("離開牌局", Theme.PANEL_LIGHT);
        leave.setFont(Theme.font(Font.PLAIN, 14f));
        leave.addActionListener(e -> onLeave.run());
        side.add(leave);
        return side;
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

    void bindRound(Round round) {
        logArea.setText("");
        canvas.setRound(round);
    }

    void animate(RoundEvent event) {
        canvas.animate(event);
    }

    void animateDeal() {
        canvas.animateDeal();
    }

    void appendLog(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** 由目前狀態更新計分板與牌桌。 */
    void refresh(GameSession session, Round round) {
        String streak = session.streak() > 0 ? " 連" + session.streak() : "";
        roundInfo.setText(Tile.windName(round.roundWind()) + "風圈" + streak);
        stakesInfo.setText("底 " + session.config().basePay() + " / 每台 " + session.config().taiPay());
        for (int seat = 0; seat < 4; seat++) {
            Player player = session.player(seat);
            String mark = round.dealer() == seat ? "【莊】" : "";
            scoreLabels[seat].setText(player.name() + mark + "：" + player.chips());
            scoreLabels[seat].setForeground(seat == 0 ? Theme.GOLD : Theme.TEXT);
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
        canvas.startPick(tiles, first, onPick);
        setButtons(actions);
    }

    private void setButtons(List<ActionItem> actions) {
        actionButtons.removeAll();
        for (ActionItem action : actions) {
            JButton button = Theme.button(action.label(), action.color());
            button.addActionListener(e -> action.run().run());
            actionButtons.add(button);
        }
        actionButtons.revalidate();
        actionButtons.repaint();
    }
}
