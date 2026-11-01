package ezmj.ui;

import java.awt.Component;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import ezmj.model.BotLevel;
import ezmj.model.GameSpeed;
import ezmj.online.OnlineProfile;
import ezmj.online.RoomSettings;
import ezmj.online.ServerAddress;

/** 連線對戰的入口：輸入服務器位址與名字，開房間或用房號加入；牌局中斷過的話也能接回去。 */
final class OnlinePanel extends JPanel {

    private static final int MAX_NAME_LENGTH = 16;
    private static final int ROOM_CODE_LENGTH = 6;
    private static final int DEFAULT_WAIT_SECONDS = 30;

    /** 按鈕送出的要求。server 與 name 都檢查過了。 */
    interface Actions {
        void create(String server, String name, RoomSettings settings);

        void join(String server, String name, String code);

        void resume(OnlineProfile profile);

        void back();
    }

    private final JTextField serverField = new JTextField(16);
    private final JTextField nameField = new JTextField(16);
    private final JTextField codeField = new JTextField(8);
    private final JComboBox<Integer> chipBox = StartPanel.combo(StartPanel.CHIP_OPTIONS, 20_000);
    private final JComboBox<Integer> baseBox = StartPanel.combo(StartPanel.BASE_OPTIONS, 100);
    private final JComboBox<Integer> taiBox = StartPanel.combo(StartPanel.TAI_OPTIONS, 20);
    private final List<JComboBox<BotLevel>> levelBoxes = List.of(StartPanel.combo(BotLevel.values(), BotLevel.NORMAL),
            StartPanel.combo(BotLevel.values(), BotLevel.NORMAL), StartPanel.combo(BotLevel.values(), BotLevel.NORMAL));
    private final JSpinner waitSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_WAIT_SECONDS,
            RoomSettings.MIN_WAIT_SECONDS, RoomSettings.MAX_WAIT_SECONDS, 5));
    private final JComboBox<GameSpeed> speedBox = StartPanel.combo(GameSpeed.values(), GameSpeed.NORMAL);
    private final JButton createButton = Theme.button("開房間", Theme.RED);
    private final JButton joinButton = Theme.button("加入房間", Theme.RED);
    private final JButton resumeButton = Theme.button("接回牌局", Theme.BUTTON);
    private final JButton backButton = Theme.button("返回", Theme.PANEL_LIGHT);
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private OnlineProfile profile;
    private boolean busy;

    OnlinePanel(Actions actions) {
        super(new GridBagLayout());
        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.GOLD, 1),
                BorderFactory.createEmptyBorder(24, 40, 24, 40)));

        JLabel title = new JLabel("連線對戰", SwingConstants.CENTER);
        title.setFont(Theme.font(Font.BOLD, 30f));
        title.setForeground(Theme.GOLD);

        for (JTextField field : List.of(serverField, nameField, codeField)) {
            field.setFont(Theme.font(Font.PLAIN, 17f));
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    syncButtons();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    syncButtons();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    syncButtons();
                }
            });
        }
        waitSpinner.setFont(Theme.font(Font.PLAIN, 17f));
        speedBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                return super.getListCellRendererComponent(list, ((GameSpeed) value).label(), index, selected, focused);
            }
        });

        // 左右兩欄，視窗最小的時候也放得下
        JPanel left = new JPanel(new GridBagLayout());
        left.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = -1;
        StartPanel.addRow(left, c, "服務器位址", serverField);
        StartPanel.addRow(left, c, "名字（最多 " + MAX_NAME_LENGTH + " 字）", nameField);
        addSection(left, c, "用房號加入");
        StartPanel.addRow(left, c, "六碼房號", codeField);
        addButton(left, c, joinButton, 6);
        addButton(left, c, resumeButton, 24);
        addButton(left, c, backButton, 8);
        status.setFont(Theme.font(Font.PLAIN, 14f));
        status.setForeground(Theme.MUTED);
        c.gridy++;
        c.insets = new Insets(10, 0, 0, 0);
        left.add(status, c);

        JPanel right = new JPanel(new GridBagLayout());
        right.setOpaque(false);
        c = new GridBagConstraints();
        c.gridy = -1;
        addSection(right, c, "或開一間新房間");
        StartPanel.addRow(right, c, "籌碼本金", chipBox);
        StartPanel.addRow(right, c, "底", baseBox);
        StartPanel.addRow(right, c, "每台", taiBox);
        StartPanel.addRow(right, c, "補位電腦等級", StartPanel.levelRow(levelBoxes));
        StartPanel.addRow(right, c, "等待秒數", waitSpinner);
        StartPanel.addRow(right, c, "電腦速度", speedBox);
        addButton(right, c, createButton, 6);

        GridBagConstraints layout = new GridBagConstraints();
        layout.gridwidth = 2;
        layout.insets = new Insets(0, 0, 14, 0);
        card.add(title, layout);
        layout.gridy = 1;
        layout.gridwidth = 1;
        layout.anchor = GridBagConstraints.NORTH;
        layout.insets = new Insets(0, 0, 0, 40);
        card.add(left, layout);
        layout.insets = new Insets(0, 0, 0, 0);
        card.add(right, layout);
        add(card);

        createButton.addActionListener(e -> actions.create(server(), name(), settings()));
        joinButton.addActionListener(e -> actions.join(server(), name(), code()));
        resumeButton.addActionListener(e -> actions.resume(profile));
        backButton.addActionListener(e -> actions.back());
        codeField.addActionListener(e -> joinButton.doClick());
        syncButtons();
    }

    private static void addSection(JPanel card, GridBagConstraints c, String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(Font.BOLD, 17f));
        label.setForeground(Theme.GOLD);
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(14, 0, 2, 0);
        card.add(label, c);
    }

    private static void addButton(JPanel card, GridBagConstraints c, JButton button, int top) {
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(top, 0, 0, 0);
        card.add(button, c);
    }

    /** 回到這個畫面時用存的資料帶入，上次打到一半的話可以接回。 */
    void setProfile(OnlineProfile saved) {
        profile = saved;
        if (serverField.getText().isBlank()) {
            serverField.setText(saved.server());
        }
        if (nameField.getText().isBlank()) {
            nameField.setText(saved.name());
        }
        resumeButton.setVisible(saved.canResume());
        resumeButton.setText(saved.canResume() ? "接回牌局（房號 " + saved.room() + "）" : "接回牌局");
        setBusy(null);
    }

    /** text 不是 null 時表示正在連線，顯示這段文字，只能按返回。 */
    void setBusy(String text) {
        busy = text != null;
        status.setText(busy ? text : " ");
        syncButtons();
    }

    private void syncButtons() {
        boolean ready = !busy && nameValid() && serverValid();
        createButton.setEnabled(ready);
        joinButton.setEnabled(ready && code().length() == ROOM_CODE_LENGTH);
        resumeButton.setEnabled(!busy);
    }

    private boolean nameValid() {
        String name = name();
        int length = name.codePointCount(0, name.length());
        return length > 0 && length <= MAX_NAME_LENGTH;
    }

    private boolean serverValid() {
        try {
            ServerAddress.parse(server());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String server() {
        return serverField.getText().strip();
    }

    private String name() {
        return nameField.getText().strip();
    }

    private String code() {
        return codeField.getText().strip().toUpperCase();
    }

    private RoomSettings settings() {
        return new RoomSettings((Integer) chipBox.getSelectedItem(), (Integer) baseBox.getSelectedItem(),
                (Integer) taiBox.getSelectedItem(),
                levelBoxes.stream().map(box -> (BotLevel) box.getSelectedItem()).collect(Collectors.toList()),
                (Integer) waitSpinner.getValue(), (GameSpeed) speedBox.getSelectedItem());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        g.setPaint(new GradientPaint(0, 0, Theme.FELT, 0, getHeight(), Theme.FELT_DARK));
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}
