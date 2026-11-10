package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.text.AbstractDocument;

import ezmj.model.BadWords;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.GameSpeed;
import ezmj.online.OnlineProfile;
import ezmj.online.RoomListing;
import ezmj.online.RoomSettings;

/**
 * 連線對戰的入口，也就是大廳：輸入名字，從公開的房間挑一間加入、用房號加入（私人房間要用），或開一間新房間；
 * 牌局中斷過的話也能接回去。服務器是固定的。看得到這個畫面時才去要房間列表。
 */
final class OnlinePanel extends JPanel implements LobbyBrowser.View {

    private static final int MAX_NAME_LENGTH = 16;
    private static final int ROOM_CODE_LENGTH = 6;
    private static final int DEFAULT_WAIT_SECONDS = 30;
    private static final int SEATS = 4;
    private static final int VISIBLE_ROOMS = 7;
    private static final String[] COLUMNS = {"房間名稱", "房主", "人數", "籌碼／底／台", "等待"};
    /** 數字欄從這一欄開始，靠右對齊。 */
    private static final int FIRST_NUMBER_COLUMN = 2;
    private static final int[] COLUMN_WIDTHS = {124, 80, 46, 140, 64};
    private static final Border CELL_PADDING = BorderFactory.createEmptyBorder(0, 8, 0, 8);
    private static final Border HEADER_PADDING = BorderFactory.createEmptyBorder(5, 8, 5, 8);
    private static final String LOADING = "正在讀取房間列表，服務器閒置後第一次可能要等一分鐘…";
    private static final String TITLE = "連線對戰";

    /** 按鈕送出的要求。name 檢查過了。 */
    interface Actions {
        void create(String name, RoomSettings settings);

        void join(String name, String code);

        void resume(OnlineProfile profile);

        void back();
    }

    private final JTextField nameField = new JTextField(16);
    private final JTextField codeField = new JTextField(8);
    private final PromptField roomNameField = new PromptField(16);
    private final JCheckBox privateBox = new JCheckBox("不列在大廳，只能用房號加入");
    private final JCheckBox expertBox = StartPanel.expertCheckBox();
    private final JComboBox<Integer> chipBox = StartPanel.combo(StartPanel.CHIP_OPTIONS, 20_000);
    private final JComboBox<Integer> baseBox = StartPanel.combo(StartPanel.BASE_OPTIONS, 100);
    private final JComboBox<Integer> taiBox = StartPanel.combo(StartPanel.TAI_OPTIONS, 20);
    private final List<JComboBox<BotLevel>> levelBoxes = List.of(StartPanel.combo(BotLevel.values(), BotLevel.NORMAL),
            StartPanel.combo(BotLevel.values(), BotLevel.NORMAL), StartPanel.combo(BotLevel.values(), BotLevel.NORMAL));
    private final JSpinner waitSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_WAIT_SECONDS,
            RoomSettings.MIN_WAIT_SECONDS, RoomSettings.MAX_WAIT_SECONDS, 5));
    private final JComboBox<GameSpeed> speedBox = StartPanel.combo(GameSpeed.values(), GameSpeed.NORMAL);
    private final RoomTableModel rooms = new RoomTableModel();
    private final JTable roomTable = new RoomTable(rooms);
    private final JLabel listStatus = new JLabel(LOADING);
    private final JButton refreshButton = Theme.button("重新整理", Theme.BUTTON);
    private final JButton listJoinButton = Theme.button("加入", Theme.RED);
    private final JLabel payWarning = StartPanel.warningLabel();
    private final JButton createButton = Theme.button("開房間", Theme.RED);
    private final JButton joinButton = Theme.button("加入房間", Theme.RED);
    private final JButton resumeButton = Theme.button("接回牌局", Theme.BUTTON);
    private final JButton backButton = Theme.button("返回", Theme.PANEL_LIGHT);
    private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
    private final LobbyBrowser lobby = new LobbyBrowser(this);
    private OnlineProfile profile;
    private boolean busy;

    OnlinePanel(Actions actions) {
        super(new GridBagLayout());
        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.GOLD, 1),
                BorderFactory.createEmptyBorder(20, 36, 20, 36)));

        JLabel title = new JLabel("連線對戰", SwingConstants.CENTER);
        title.setFont(Theme.font(Font.BOLD, 30f));
        title.setForeground(Theme.GOLD);

        for (JTextField field : List.of(nameField, codeField, roomNameField)) {
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
        ((AbstractDocument) roomNameField.getDocument()).setDocumentFilter(new LengthLimit(RoomSettings.MAX_NAME_LENGTH));
        roomNameField.setToolTipText("最多 " + RoomSettings.MAX_NAME_LENGTH + " 字，空白就用預設的名稱");
        privateBox.setFont(Theme.font(Font.PLAIN, 15f));
        privateBox.setForeground(Theme.TEXT);
        privateBox.setOpaque(false);
        privateBox.setFocusPainted(false);
        waitSpinner.setFont(Theme.font(Font.PLAIN, 17f));
        speedBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focused) {
                return super.getListCellRendererComponent(list, ((GameSpeed) value).label(), index, selected, focused);
            }
        });
        for (JButton small : List.of(refreshButton, listJoinButton)) {
            small.setFont(Theme.font(Font.BOLD, 15f));
            small.setBorder(BorderFactory.createEmptyBorder(5, 16, 5, 16));
        }
        listStatus.setFont(Theme.font(Font.PLAIN, 14f));
        listStatus.setForeground(Theme.MUTED);

        // 左右兩欄，視窗最小的時候也放得下：左邊是大廳，右邊開新房間
        JPanel left = new JPanel(new GridBagLayout());
        left.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = -1;
        StartPanel.addRow(left, c, "名字（最多 " + MAX_NAME_LENGTH + " 字）", nameField);
        addWide(left, c, besides(sectionLabel("公開的房間"), refreshButton), 10);
        addWide(left, c, roomList(), 4);
        addWide(left, c, besides(listStatus, listJoinButton), 6);
        addSection(left, c, "用房號加入");
        StartPanel.addRow(left, c, "六碼房號", besides(codeField, joinButton));

        JPanel right = new JPanel(new GridBagLayout());
        right.setOpaque(false);
        c = new GridBagConstraints();
        c.gridy = -1;
        addSection(right, c, "或開一間新房間");
        StartPanel.addRow(right, c, "房間名稱", roomNameField);
        StartPanel.addRow(right, c, "私人房間", privateBox);
        StartPanel.addRow(right, c, "籌碼本金", chipBox);
        StartPanel.addRow(right, c, "底", baseBox);
        StartPanel.addRow(right, c, "每台", taiBox);
        StartPanel.addRow(right, c, "補位電腦等級", StartPanel.levelRow(levelBoxes));
        StartPanel.addRow(right, c, "等待秒數", waitSpinner);
        StartPanel.addRow(right, c, "電腦速度", speedBox);
        expertBox.setToolTipText("房間裡每個人都照專家模式打，有哪些不一樣見「說明」→「遊戲玩法」");
        StartPanel.addRow(right, c, "專家模式", expertBox);
        addWide(right, c, payWarning, 2);
        addWide(right, c, createButton, 4);
        for (JComboBox<Integer> box : List.of(chipBox, baseBox, taiBox)) {
            box.addActionListener(e -> syncButtons());
        }

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        bottom.setOpaque(false);
        bottom.add(resumeButton);
        bottom.add(backButton);
        status.setFont(Theme.font(Font.PLAIN, 14f));
        status.setForeground(Theme.MUTED);

        GridBagConstraints layout = new GridBagConstraints();
        layout.gridwidth = 2;
        layout.insets = new Insets(0, 0, 10, 0);
        card.add(title, layout);
        layout.gridy = 1;
        layout.gridwidth = 1;
        layout.anchor = GridBagConstraints.NORTH;
        layout.insets = new Insets(0, 0, 0, 36);
        card.add(left, layout);
        layout.insets = new Insets(0, 0, 0, 0);
        card.add(right, layout);
        layout.gridy = 2;
        layout.gridwidth = 2;
        layout.anchor = GridBagConstraints.CENTER;
        layout.insets = new Insets(16, 0, 0, 0);
        card.add(bottom, layout);
        layout.gridy = 3;
        layout.insets = new Insets(8, 0, 0, 0);
        card.add(status, layout);
        add(card);

        createButton.addActionListener(e -> create(actions));
        joinButton.addActionListener(e -> joinByCode(actions));
        listJoinButton.addActionListener(e -> joinSelected(actions));
        refreshButton.addActionListener(e -> refresh());
        resumeButton.addActionListener(e -> actions.resume(profile));
        backButton.addActionListener(e -> actions.back());
        codeField.addActionListener(e -> joinButton.doClick());
        roomTable.getSelectionModel().addListSelectionListener(e -> syncButtons());
        roomTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && roomTable.rowAtPoint(e.getPoint()) >= 0) {
                    joinSelected(actions);
                }
            }
        });
        // 列表只在大廳畫面看得到的時候更新，換到房間或牌桌就關掉那條連線
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                rooms.set(List.of());
                refresh();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                lobby.stop();
            }
        });
        syncButtons();
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(Font.BOLD, 17f));
        label.setForeground(Theme.GOLD);
        return label;
    }

    private static void addSection(JPanel card, GridBagConstraints c, String text) {
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(14, 0, 2, 0);
        card.add(sectionLabel(text), c);
    }

    /** 佔滿兩欄的一列。 */
    private static void addWide(JPanel card, GridBagConstraints c, JComponent component, int top) {
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(top, 0, 0, 0);
        card.add(component, c);
    }

    /** main 佔住剩下的寬度，side 靠右。 */
    private static JPanel besides(JComponent main, JComponent side) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.add(main, BorderLayout.CENTER);
        row.add(side, BorderLayout.EAST);
        return row;
    }

    private JScrollPane roomList() {
        roomTable.setFont(Theme.font(Font.PLAIN, 15f));
        roomTable.getTableHeader().setFont(Theme.font(Font.BOLD, 14f));
        roomTable.getTableHeader().setReorderingAllowed(false);
        roomTable.getTableHeader().setResizingAllowed(false);
        roomTable.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        roomTable.setDefaultRenderer(Object.class, new CellRenderer());
        roomTable.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        roomTable.setForeground(Theme.TEXT);
        roomTable.setBackground(Theme.PANEL_LIGHT);
        roomTable.setSelectionForeground(Color.WHITE);
        roomTable.setSelectionBackground(Theme.BUTTON);
        roomTable.setGridColor(Theme.PANEL);
        roomTable.setShowVerticalLines(false);
        roomTable.setIntercellSpacing(new Dimension(0, 1));
        roomTable.setFillsViewportHeight(true);
        roomTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        for (int column = 0; column < COLUMN_WIDTHS.length; column++) {
            TableColumn tableColumn = roomTable.getColumnModel().getColumn(column);
            tableColumn.setPreferredWidth(COLUMN_WIDTHS[column]);
        }
        // 字體大小改了，列高跟著字型變，這裡的寬高也要跟著算。捲軸的寬度先留好，房間多的時候欄位才不會被擠掉
        JScrollPane scroll = new JScrollPane(roomTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public Dimension getPreferredSize() {
                int width = 0;
                for (int columnWidth : COLUMN_WIDTHS) {
                    width += columnWidth;
                }
                Insets insets = getInsets();
                return new Dimension(Theme.scaled(width) + getVerticalScrollBar().getPreferredSize().width
                        + insets.left + insets.right,
                        roomTable.getTableHeader().getPreferredSize().height + VISIBLE_ROOMS * roomTable.getRowHeight()
                                + insets.top + insets.bottom);
            }

            /** 視窗放不下時 GridBagLayout 改用最小的大小，列表不能跟著縮成一條線。 */
            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        scroll.setBorder(BorderFactory.createLineBorder(Theme.PANEL_LIGHT));
        scroll.getViewport().setBackground(Theme.PANEL_LIGHT);
        JPanel corner = new JPanel();
        corner.setBackground(Theme.PANEL_LIGHT);
        scroll.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);
        return scroll;
    }

    /** 回到這個畫面時用存的資料帶入，上次打到一半的話可以接回。 */
    void setProfile(OnlineProfile saved) {
        profile = saved;
        if (nameField.getText().isBlank()) {
            nameField.setText(saved.name());
        }
        resumeButton.setVisible(saved.canResume());
        resumeButton.setText(saved.canResume() ? "接回牌局（房號 " + saved.room() + "）" : "接回牌局");
        setBusy(null);
    }

    /** text 不是 null 時表示正在連線，顯示這段文字，只能按返回。列表照樣更新。 */
    void setBusy(String text) {
        busy = text != null;
        status.setText(busy ? text : " ");
        syncButtons();
    }

    @Override
    public void showRooms(List<RoomListing> list) {
        RoomListing selected = selected();
        rooms.set(list);
        int row = selected == null ? -1 : rooms.indexOf(selected.code());
        if (row >= 0) {
            roomTable.setRowSelectionInterval(row, row);
        }
        listStatus.setText(list.isEmpty() ? "目前沒有等人的公開房間，可以自己開一間。"
                : list.size() + " 間等人中，選一間按「加入」或連點兩下。");
        syncButtons();
    }

    @Override
    public void showListProblem(String text) {
        rooms.set(List.of());
        listStatus.setText(text);
        syncButtons();
    }

    private void refresh() {
        if (rooms.getRowCount() == 0) {
            listStatus.setText(LOADING);
        }
        lobby.refresh();
    }

    /** 按鈕都不因為名字或房號變灰，按下去才檢查，不行就跳出提醒。 */
    private void create(Actions actions) {
        if (busy || !StartPanel.nameAccepted(nameField, MAX_NAME_LENGTH, TITLE)) {
            return;
        }
        if (BadWords.contains(roomNameField.getText())) {
            StartPanel.warn(this, BadWords.ROOM_NAME_REJECTED, TITLE);
            roomNameField.requestFocusInWindow();
            return;
        }
        if (!StartPanel.paysFit(chipBox, baseBox, taiBox, payWarning)) {
            StartPanel.warn(this, GameConfig.PAYS_TOO_HIGH + "。", TITLE);
            return;
        }
        actions.create(name(), settings());
    }

    private void joinByCode(Actions actions) {
        if (busy || !StartPanel.nameAccepted(nameField, MAX_NAME_LENGTH, TITLE)) {
            return;
        }
        if (code().length() != ROOM_CODE_LENGTH) {
            StartPanel.warn(this, "請輸入六碼房號。", TITLE);
            codeField.requestFocusInWindow();
            return;
        }
        actions.join(name(), code());
    }

    private void joinSelected(Actions actions) {
        if (busy) {
            return;
        }
        RoomListing room = selected();
        if (room == null) {
            StartPanel.warn(this, "請先在列表選一間房間。", TITLE);
            return;
        }
        if (StartPanel.nameAccepted(nameField, MAX_NAME_LENGTH, TITLE)) {
            actions.join(name(), room.code());
        }
    }

    private RoomListing selected() {
        int row = roomTable.getSelectedRow();
        return row < 0 ? null : rooms.get(row);
    }

    /** 只有正在連線時按鈕不能按；名字、房號與底台按下去才檢查，底台太大先在下面寫出來。 */
    private void syncButtons() {
        StartPanel.paysFit(chipBox, baseBox, taiBox, payWarning);
        createButton.setEnabled(!busy);
        joinButton.setEnabled(!busy);
        listJoinButton.setEnabled(!busy);
        resumeButton.setEnabled(!busy);
        roomNameField.setPrompt((nameValid() ? name() : "你的名字") + "的房間");
    }

    private boolean nameValid() {
        String name = name();
        int length = name.codePointCount(0, name.length());
        return length > 0 && length <= MAX_NAME_LENGTH;
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
                (Integer) waitSpinner.getValue(), (GameSpeed) speedBox.getSelectedItem(),
                roomNameField.getText().strip(), privateBox.isSelected(), expertBox.isSelected());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics;
        g.setPaint(new GradientPaint(0, 0, Theme.FELT, 0, getHeight(), Theme.FELT_DARK));
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    /** 大廳列表的內容，一列一間房間。 */
    private static final class RoomTableModel extends AbstractTableModel {
        private List<RoomListing> rooms = List.of();

        void set(List<RoomListing> next) {
            rooms = next;
            fireTableDataChanged();
        }

        RoomListing get(int row) {
            return rooms.get(row);
        }

        int indexOf(String code) {
            for (int row = 0; row < rooms.size(); row++) {
                if (rooms.get(row).code().equals(code)) {
                    return row;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return rooms.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            RoomListing room = rooms.get(row);
            RoomSettings settings = room.settings();
            switch (column) {
                case 0:  return room.name();
                case 1:  return room.host();
                case 2:  return room.humans() + "/" + SEATS;
                case 3:  return settings.chips() + "/" + settings.basePay() + "/" + settings.taiPay();
                default: return settings.waitSeconds() + " 秒";
            }
        }
    }

    /** 列高跟著字型走，字體大小改了也放得下。滑鼠停著看得到完整的房間名稱與電腦速度。 */
    private static final class RoomTable extends JTable {
        RoomTable(RoomTableModel model) {
            super(model);
        }

        @Override
        public void setFont(Font font) {
            super.setFont(font);
            if (font != null) {
                setRowHeight(getFontMetrics(font).getHeight() + 10);
            }
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            if (row < 0) {
                return null;
            }
            RoomListing room = ((RoomTableModel) getModel()).get(row);
            return room.name() + "　房主 " + room.host() + "　電腦速度" + room.settings().speed().label()
                    + (room.settings().expert() ? "　專家模式" : "");
        }
    }

    /** 房間名稱那一格：專家模式的房間在名稱右邊標「專家」，名稱太長時只截名稱，標記一直看得到。 */
    private static final class NameRenderer extends JPanel implements TableCellRenderer {
        private final CellRenderer name = new CellRenderer();
        private final JLabel badge = new JLabel("專家");
        private final JPanel badgeHolder = new JPanel(new GridBagLayout());

        NameRenderer() {
            super(new BorderLayout());
            badge.setOpaque(true);
            badge.setForeground(Color.WHITE);
            badge.setBackground(Theme.RED);
            badge.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
            badgeHolder.setOpaque(false);
            badgeHolder.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
            badgeHolder.add(badge);
            add(name, BorderLayout.CENTER);
            add(badgeHolder, BorderLayout.EAST);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            name.getTableCellRendererComponent(table, value, selected, focused, row, column);
            badge.setFont(table.getFont().deriveFont(Font.BOLD, table.getFont().getSize2D() * 0.8f));
            badgeHolder.setVisible(((RoomTableModel) table.getModel()).get(row).settings().expert());
            setBackground(name.getBackground());
            // 同一欄的大小不變，標記出現或消失時要自己重新排
            invalidate();
            return this;
        }
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setBorder(CELL_PADDING);
            setHorizontalAlignment(column < FIRST_NUMBER_COLUMN ? SwingConstants.LEFT : SwingConstants.RIGHT);
            return this;
        }
    }

    /** 和結果視窗的表頭同一個樣子。 */
    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setFont(table.getTableHeader().getFont());
            setForeground(Theme.MUTED);
            setBackground(Theme.PANEL);
            setBorder(HEADER_PADDING);
            setHorizontalAlignment(column < FIRST_NUMBER_COLUMN ? SwingConstants.LEFT : SwingConstants.RIGHT);
            return this;
        }
    }

    /** 空白時淡淡地顯示提示，這裡是不填房間名稱時服務器會用的名稱。 */
    private static final class PromptField extends JTextField {
        private String prompt = "";

        PromptField(int columns) {
            super(columns);
        }

        void setPrompt(String text) {
            if (!text.equals(prompt)) {
                prompt = text;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!getText().isEmpty()) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());
            g.setColor(Color.GRAY);
            FontMetrics metrics = g.getFontMetrics();
            Insets insets = getInsets();
            int y = insets.top + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(prompt, insets.left, y);
            g.dispose();
        }
    }
}
