package ezmj.ui;

import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.time.LocalDate;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;

import ezmj.career.CareerProfile;
import ezmj.model.BadWords;

/**
 * 建立修業檔案：名字、起始籌碼與要不要專家模式，和牌桌一樣的深綠底金字。
 * 專家模式有哪些不一樣寫在「說明 → 遊戲玩法」，這裡不寫。勾了專家模式，起始籌碼固定 EXPERT_STARTING_CHIPS。
 * 名字是空的或有不雅字眼就在視窗裡提示，不另外跳視窗；Enter 建立、Esc 取消。
 */
final class CreateCareerDialog extends JDialog {

    private final JTextField nameField = new JTextField(12);
    private final Integer[] normalChips = CareerProfile.STARTING_CHIPS.toArray(new Integer[0]);
    private final JComboBox<Integer> chipBox = StartPanel.combo(normalChips, CareerProfile.DEFAULT_STARTING_CHIPS);
    private final JCheckBox expertBox = new JCheckBox("專家模式");
    private final JLabel nameHint = ResultDialog.line(" ", Font.PLAIN, 13f, Theme.MUTED);
    private CareerProfile created;

    private CreateCareerDialog(Window owner, String slotLabel) {
        super(owner, slotLabel + "：建立新檔案", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        nameField.setFont(Theme.font(Font.PLAIN, 17f));
        nameField.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.MUTED),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        ((AbstractDocument) nameField.getDocument()).setDocumentFilter(new LengthLimit(CareerProfile.MAX_NAME_LENGTH));
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                showNameHint(null);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                showNameHint(null);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                showNameHint(null);
            }
        });
        expertBox.setFont(Theme.font(Font.PLAIN, 16f));
        expertBox.setForeground(Theme.TEXT);
        expertBox.setOpaque(false);
        expertBox.setFocusPainted(false);
        expertBox.addActionListener(e -> syncExpert());

        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(3, 0, 0, 0, Theme.GOLD),
                BorderFactory.createEmptyBorder(20, 28, 22, 28)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        content.add(ResultDialog.line("建立新檔案", Font.BOLD, 22f, Theme.GOLD), c);
        c.gridy++;
        c.insets = new Insets(4, 0, 18, 0);
        content.add(ResultDialog.line(slotLabel + "・建立之後名字、起始籌碼與專家模式都不能改", Font.PLAIN, 14f,
                Theme.MUTED), c);

        addRow(content, c, "名字", nameField);
        c.gridx = 1;
        c.gridy++;
        c.gridwidth = 1;
        c.insets = new Insets(2, 0, 0, 0);
        content.add(nameHint, c);
        addRow(content, c, "起始籌碼", chipBox);
        c.gridx = 1;
        c.gridy++;
        c.insets = new Insets(10, 0, 0, 0);
        c.fill = GridBagConstraints.NONE;
        content.add(expertBox, c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(18, 0, 16, 0);
        JSeparator line = new JSeparator();
        line.setForeground(Theme.PANEL_LIGHT);
        line.setBackground(Theme.PANEL_LIGHT);
        content.add(line, c);

        JButton create = Theme.button("建立檔案", Theme.RED);
        create.addActionListener(e -> submit());
        JButton cancel = Theme.button("取消", Theme.PANEL_LIGHT);
        cancel.addActionListener(e -> dispose());
        JPanel buttons = ShareDialog.buttonRow(create, cancel);
        c.gridy++;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(0, 0, 0, 0);
        content.add(buttons, c);
        getRootPane().setDefaultButton(create);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        setContentPane(content);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(nameField::requestFocusInWindow);
    }

    /** 問名字、起始籌碼與要不要專家模式；按取消或關掉是 null。 */
    static CareerProfile ask(Component parent, String slotLabel) {
        CreateCareerDialog dialog = new CreateCareerDialog(SwingUtilities.getWindowAncestor(parent), slotLabel);
        dialog.setVisible(true);
        return dialog.created;
    }

    /** 左邊是標籤，右邊是輸入，一列。 */
    private static void addRow(JPanel content, GridBagConstraints c, String label, JComponent field) {
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(8, 0, 0, 18);
        content.add(ResultDialog.line(label, Font.PLAIN, 17f, Theme.TEXT), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(8, 0, 0, 0);
        content.add(field, c);
    }

    /** 勾了專家模式，起始籌碼只能是 EXPERT_STARTING_CHIPS。 */
    private void syncExpert() {
        boolean expert = expertBox.isSelected();
        chipBox.setModel(new DefaultComboBoxModel<>(expert
                ? new Integer[] {CareerProfile.EXPERT_STARTING_CHIPS} : normalChips));
        if (!expert) {
            chipBox.setSelectedItem(CareerProfile.DEFAULT_STARTING_CHIPS);
        }
        chipBox.setEnabled(!expert);
    }

    /** problem 是 null 時寫字數上限，不然用金色寫出名字哪裡不行，提示比原本寬就把視窗撐開。 */
    private void showNameHint(String problem) {
        nameHint.setText(problem != null ? problem : "最多 " + CareerProfile.MAX_NAME_LENGTH + " 字");
        nameHint.setForeground(problem != null ? Theme.GOLD : Theme.MUTED);
        if (problem != null && nameHint.getPreferredSize().width > nameHint.getWidth()) {
            pack();
        }
    }

    private void submit() {
        String name = nameField.getText().strip();
        String problem = name.isEmpty() ? "請輸入名字" : BadWords.contains(name) ? "名字不能有不雅的字眼" : null;
        if (problem != null) {
            showNameHint(problem);
            nameField.requestFocusInWindow();
            return;
        }
        created = new CareerProfile(name, (Integer) chipBox.getSelectedItem(), LocalDate.now().toString(),
                expertBox.isSelected());
        dispose();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        showNameHint(null);
    }
}
