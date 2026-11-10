package ezmj.ui;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import ezmj.career.CareerProfile;
import ezmj.career.CareerSlot;
import ezmj.career.Challenge;

/** 修業模式的檔案列表：固定幾格，每格可以建立新檔案，或進入、刪除已經有的檔案。 */
final class CareerListPanel extends JPanel {

    /** 按鈕送出的要求。 */
    interface Actions {
        void enter(CareerSlot slot);

        void create(CareerSlot slot, CareerProfile profile);

        void delete(CareerSlot slot);

        void back();
    }

    private final Actions actions;
    private final JPanel rows = new JPanel(new GridBagLayout());

    CareerListPanel(Actions actions) {
        super(new GridBagLayout());
        this.actions = actions;

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.GOLD, 1),
                BorderFactory.createEmptyBorder(24, 40, 28, 40)));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(0, 0, 6, 0);
        JLabel title = new JLabel("修業模式", SwingConstants.CENTER);
        title.setFont(Theme.font(Font.BOLD, 30f));
        title.setForeground(Theme.GOLD);
        card.add(title, c);

        // 開場白；規則寫在「說明」→「遊戲玩法」
        String intro = "<div align='center'>這是你自己的麻將修練。從初出茅廬的雀友練起，<br>"
                + "一場一場累積籌碼、一關一關挑戰高手，在麻將江湖中混出一番名堂。</div>";
        JLabel introLabel = new JLabel();
        introLabel.setFont(Theme.font(Font.PLAIN, 16f));
        introLabel.setForeground(Theme.TEXT);
        c.gridy++;
        c.insets = new Insets(0, 0, 16, 0);
        card.add(HomePanel.wrapped(introLabel, intro, 720), c);

        rows.setOpaque(false);
        c.gridy++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(rows, c);

        JButton back = Theme.button("返回", Theme.PANEL_LIGHT);
        back.addActionListener(e -> actions.back());
        c.gridy++;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(18, 0, 0, 0);
        card.add(back, c);

        add(card);
    }

    /** 重新讀一次每一格，回到這個畫面時呼叫。 */
    void refresh(List<CareerSlot> slots) {
        rows.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        for (CareerSlot slot : slots) {
            addRow(c, slot);
            c.gridy++;
        }
        rows.revalidate();
        rows.repaint();
    }

    private void addRow(GridBagConstraints c, CareerSlot slot) {
        JLabel label = new JLabel(slot.label());
        label.setFont(Theme.font(Font.BOLD, 18f));
        label.setForeground(Theme.GOLD);

        JPanel text = new JPanel(new GridBagLayout());
        text.setOpaque(false);
        GridBagConstraints line = new GridBagConstraints();
        line.gridx = 0;
        line.weightx = 1;
        line.anchor = GridBagConstraints.WEST;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);

        if (!slot.exists()) {
            text.add(muted("空的檔案"), line);
            buttons.add(small("建立新檔案", Theme.RED, () -> askCreate(slot)));
        } else {
            try {
                CareerProfile profile = slot.loadProfile();
                String title = profile.title() == null ? "" : "【" + profile.title().label() + "】";
                JLabel name = new JLabel(profile.name() + title + "　籌碼 " + profile.bankroll());
                name.setFont(Theme.font(Font.BOLD, 17f));
                name.setForeground(Theme.TEXT);
                text.add(name, line);
                text.add(muted((profile.expert() ? "專家模式・" : "") + "挑戰 " + profile.clearedCount() + " / " + Challenge.values().length
                        + "（★ " + profile.totalStars() + "）"
                        + "・起始 " + profile.startingChips() + "・破產 " + profile.bankruptcies() + " 次・"
                        + profile.created() + " 建立"
                        + (profile.game() == CareerProfile.Game.NONE ? "" : "・有一場沒打完")), line);
                buttons.add(small("進入", Theme.RED, () -> actions.enter(slot)));
            } catch (IOException e) {
                ErrorReporter.note("讀取修業檔案", e);
                text.add(muted("這個檔案讀不出來，只能刪除"), line);
            }
            buttons.add(small("刪除", Theme.PANEL, () -> askDelete(slot)));
        }

        JPanel row = new JPanel(new GridBagLayout());
        row.setBackground(Theme.PANEL_LIGHT);
        row.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        GridBagConstraints cell = new GridBagConstraints();
        cell.anchor = GridBagConstraints.WEST;
        cell.insets = new Insets(0, 0, 0, 18);
        row.add(label, cell);
        cell.weightx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        row.add(text, cell);
        cell.weightx = 0;
        cell.fill = GridBagConstraints.NONE;
        cell.insets = new Insets(0, 0, 0, 0);
        row.add(buttons, cell);

        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 8, 0);
        rows.add(row, c);
    }

    private static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(Font.PLAIN, 14f));
        label.setForeground(Theme.MUTED);
        return label;
    }

    static JButton small(String text, Color color, Runnable action) {
        JButton button = Theme.button(text, color);
        button.setFont(Theme.font(Font.BOLD, 15f));
        button.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        button.addActionListener(e -> action.run());
        return button;
    }

    /** 問名字、起始籌碼與要不要專家模式（CreateCareerDialog），按取消就算了。 */
    private void askCreate(CareerSlot slot) {
        CareerProfile profile = CreateCareerDialog.ask(this, slot.label());
        if (profile != null) {
            actions.create(slot, profile);
        }
    }

    private void askDelete(CareerSlot slot) {
        String name = "";
        try {
            name = "「" + slot.loadProfile().name() + "」";
        } catch (IOException e) {
            // 讀不出來的檔案也能刪
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "要刪除" + slot.label() + name + "嗎？\n籌碼、挑戰、成就、統計、榮譽榜與沒打完的牌局都會一起刪掉，不能復原。",
                "刪除檔案", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            actions.delete(slot);
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        StartPanel.paintFelt(this, graphics);
    }
}
