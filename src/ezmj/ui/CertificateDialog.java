package ezmj.ui;

import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.LocalDate;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import ezmj.career.CertificateNumber;
import ezmj.career.Dan;
import ezmj.career.DanCertificate;
import ezmj.career.Records;
import ezmj.engine.PlayerStats;

/**
 * 段位證照：左邊是證書的預覽，可以複製或存成圖片，點一下放大看；右邊是證書上的名字、下一段還差多少與換圖像。
 * 預覽照螢幕可以放視窗的範圍放到最大，各種解析度與字體大小都放得進螢幕。
 * 打開時先照目前的成績看能不能升段，以前就打夠的人一打開就拿得到。不擋住牌桌，和說明視窗一樣可以邊看邊打。
 * 證書上的名字不能改：修業檔案是檔案的名字，練習牌局是第一次記進統計那一局的名字。
 */
final class CertificateDialog extends JDialog {

    private static final String TITLE = "段位證照";
    /** 證書預覽最寬這麼多（字體 100% 時），螢幕放不下就再縮。 */
    private static final int PREVIEW_MAX_WIDTH = 1000;
    private static final int AVATAR_PREVIEW = 120;
    private static final int COLUMN_GAP = 24;
    /** 右邊說明折行的寬度（字體 100% 時）。 */
    private static final int INFO_WIDTH = 330;

    private final Records records;
    private final String ownerLabel;
    private final boolean career;
    private final Runnable showRules;
    private final Rectangle screen;

    /** profileName 是修業檔案的名字，練習牌局是 null；ownerLabel 例如「練習牌局」「修業：小明」。 */
    CertificateDialog(Frame owner, Records records, String ownerLabel, String profileName, Runnable showRules) {
        this(owner, records, ownerLabel, profileName, showRules, ShareDialog.usableScreen(owner));
    }

    /** screen 是螢幕上可以放視窗的範圍，預覽的大小照它算；檢查各種螢幕時可以直接給。 */
    CertificateDialog(Frame owner, Records records, String ownerLabel, String profileName, Runnable showRules,
                      Rectangle screen) {
        super(owner, TITLE, ModalityType.MODELESS);
        this.records = records;
        this.ownerLabel = ownerLabel;
        this.career = profileName != null;
        this.showRules = showRules;
        this.screen = screen;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        DanCertificate certificate = records.certificate();
        boolean named = career && certificate.matchProfile(profileName);
        boolean promoted = certificate.promote(records.stats(), LocalDate.now().toString()) != null;
        if (named || promoted) {
            save();
        }
        refresh();
        ShareDialog.placeOnScreen(this, owner, screen);
    }

    /** 看的是不是這套紀錄。 */
    boolean shows(Records other) {
        return records == other;
    }

    /**
     * 照證照目前的內容整個重畫，預覽重新放到螢幕放得下的大小。
     * 左邊只有證書，右邊的說明照固定寬度折行，按鈕都排在最下面一列，字體放大時視窗才不會被一行長字撐寬。
     */
    void refresh() {
        DanCertificate certificate = records.certificate();
        DanCertificate.Grant grant = certificate.grant();
        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(18, 24, 20, 24));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        content.add(ResultDialog.line(TITLE, Font.BOLD, 24f, Theme.GOLD), c);
        c.gridy = 1;
        c.insets = new Insets(4, 0, 12, 0);
        content.add(ResultDialog.line("「" + ownerLabel + "」的證照，段位只升不降。", Font.PLAIN, 14f, Theme.MUTED), c);

        BufferedImage avatar = CertificateArt.avatar(certificate.avatar(), certificate.name(), records.avatarFile(),
                CertificateArt.AVATAR_SIZE);
        int infoWidth = Theme.scaled(INFO_WIDTH);
        JPanel info = column();
        JComponent left;
        ShareDialog.Preview preview = null;
        JButton[] shareButtons = {};
        if (grant != null) {
            BufferedImage image = CertificateArt.certificate(grant, certificate.name(), avatar);
            preview = new ShareDialog.Preview(image, ShareDialog.MIN_PREVIEW_WIDTH,
                    ShareDialog.MIN_PREVIEW_WIDTH * CertificateArt.HEIGHT / CertificateArt.WIDTH);
            ShareDialog.zoomOnClick(preview, image, "段位證書");
            left = preview;
            JLabel hint = wrappedLine("點證書可以放大看；按「複製圖片」再到 LINE 按 Ctrl+V 貼上。", 14f, infoWidth);
            shareButtons = new JButton[] {ShareDialog.copyButton(image, hint, infoWidth),
                ShareDialog.saveButton(image, CertificateArt.fileName(grant))};
            fillInfo(info, certificate, infoWidth);
            info.add(Box.createVerticalStrut(16));
            info.add(hint);
        } else {
            left = notYet(avatar);
            fillInfo(info, certificate, infoWidth);
        }
        c.gridy = 2;
        c.gridwidth = 1;
        c.insets = new Insets(0, 0, 0, COLUMN_GAP);
        c.anchor = GridBagConstraints.NORTHWEST;
        content.add(left, c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        content.add(info, c);

        JButton avatarButton = Theme.button("換圖像…", Theme.BUTTON);
        avatarButton.addActionListener(e -> new AvatarDialog(this, records, () -> {
            save();
            refresh();
        }).setVisible(true));
        JButton verify = Theme.button("驗證證號…", Theme.BUTTON);
        verify.addActionListener(e -> verifyNumber());
        JButton rules = Theme.button("段位表", Theme.BUTTON);
        rules.addActionListener(e -> showRules.run());
        JButton close = Theme.button("關閉", Theme.PANEL_LIGHT);
        close.addActionListener(e -> dispose());
        JComponent[] buttons = new JComponent[shareButtons.length + 4];
        System.arraycopy(shareButtons, 0, buttons, 0, shareButtons.length);
        buttons[shareButtons.length] = avatarButton;
        buttons[shareButtons.length + 1] = verify;
        buttons[shareButtons.length + 2] = rules;
        buttons[shareButtons.length + 3] = close;
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.insets = new Insets(16, 0, 0, 0);
        content.add(ShareDialog.buttonRow(buttons), c);
        getRootPane().setDefaultButton(close);

        setContentPane(content);
        if (preview != null) {
            ShareDialog.fitPreview(this, preview, Theme.scaled(PREVIEW_MAX_WIDTH), screen);
        } else {
            pack();
        }
        setResizable(false);
    }

    private static JPanel column() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        return column;
    }

    /** 照 width 折行的一段說明。 */
    private static JLabel wrappedLine(String text, float size, int width) {
        JLabel label = ResultDialog.line("", Font.PLAIN, size, Theme.MUTED);
        label.setText(HomePanel.wrapHtml(HomePanel.escape(text), width));
        return label;
    }

    /** 右邊：證書上的名字與下一段的進度。 */
    private void fillInfo(JPanel info, DanCertificate certificate, int width) {
        String name = certificate.name();
        info.add(ResultDialog.line("證書上的名字：" + (name.isEmpty() ? "（還沒有）" : name), Font.BOLD, 16f, Theme.TEXT));
        info.add(Box.createVerticalStrut(4));
        String rule = career ? "用修業檔案的名字，不能改。"
                : name.isEmpty() ? "打完一局就用那一局的名字，之後不能改。" : "用第一次記進統計那一局的名字，不能改。";
        info.add(wrappedLine(rule, 13f, width));
        String number = certificate.number();
        if (number != null && !name.isEmpty()) {
            info.add(Box.createVerticalStrut(8));
            info.add(ResultDialog.line("證號：" + CertificateNumber.grouped(number), Font.BOLD, 16f, Theme.TEXT));
        }
        info.add(Box.createVerticalStrut(18));
        info.add(progress(certificate.dan()));
    }

    /** 輸入證號與證書上的名字，看對不對得上；對得上就說是哪一段、哪一天認定的。 */
    private void verifyNumber() {
        JTextField numberField = new JTextField(22);
        JTextField nameField = new JTextField(12);
        for (JTextField field : new JTextField[] {numberField, nameField}) {
            field.setFont(Theme.font(Font.PLAIN, 16f));
        }
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel("證號（16 碼，空格可有可無）"));
        form.add(numberField);
        form.add(new JLabel("證書上的名字"));
        form.add(nameField);
        String title = "驗證證號";
        if (JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        CertificateNumber.Check check = CertificateNumber.verify(numberField.getText(), nameField.getText(),
                LocalDate.now().getYear());
        if (check.valid()) {
            JOptionPane.showMessageDialog(this, "證號有效：「" + nameField.getText().trim() + "」的" + check.dan().label()
                    + "，認定日期 " + CertificateArt.longDate(check.date()) + "。", title, JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "證號和名字對不上：可能打錯字，或不是 EZMJ 發的證書。", title,
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 還沒入段：圖像與一句說明。 */
    private JComponent notYet(BufferedImage avatar) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(new JLabel(AvatarDialog.roundIcon(avatar, Theme.scaled(AVATAR_PREVIEW))));
        row.add(Box.createHorizontalStrut(18));
        JPanel text = column();
        text.add(ResultDialog.line("尚未入段", Font.BOLD, 26f, Theme.GOLD));
        text.add(Box.createVerticalStrut(6));
        text.add(ResultDialog.line("打到初段就會發證書，", Font.PLAIN, 14f, Theme.MUTED));
        text.add(ResultDialog.line("可以複製或存成圖片分享。", Font.PLAIN, 14f, Theme.MUTED));
        row.add(text);
        return row;
    }

    /** 下一段看哪些局、三項各到了沒有。表格照內容的寬度，不撐開。 */
    private JComponent progress(Dan current) {
        JPanel panel = column();
        panel.setAlignmentX(LEFT_ALIGNMENT);
        Dan next = Dan.after(current);
        if (next == null) {
            panel.add(ResultDialog.line("已經是最高的九段了。", Font.BOLD, 16f, Theme.GOLD));
            return panel;
        }
        panel.add(ResultDialog.line("下一段：" + next.label(), Font.BOLD, 16f, Theme.TEXT));
        panel.add(Box.createVerticalStrut(2));
        panel.add(ResultDialog.line(next.opponents() == null ? "看全部的局" : "只看" + next.opponentsText() + "的局",
                Font.PLAIN, 14f, Theme.MUTED));
        panel.add(Box.createVerticalStrut(6));
        PlayerStats.Totals pool = next.pool(records.stats());
        ResultDialog.GridTable table = new ResultDialog.GridTable(1, -1);
        table.header("", "目前", "門檻", "");
        addRow(table, "局數", String.valueOf(pool.rounds()), String.valueOf(next.rounds()), next.roundsMet(pool),
                "還差 " + (next.rounds() - pool.rounds()) + " 局");
        addRow(table, "胡牌率", CertificateArt.percent(pool.winRate()), permille(next.winPermille()),
                next.winRateMet(pool), "還沒到");
        addRow(table, "自摸率", CertificateArt.percent(pool.selfDrawRate()), permille(next.selfDrawPermille()),
                next.selfDrawRateMet(pool), "還沒到");
        // 放槍率是上限，不能超過
        addRow(table, "放槍率", CertificateArt.percent(pool.dealInRate()), "≤ " + permille(next.dealInPermille()),
                next.dealInRateMet(pool), pool.rounds() == 0 ? "還沒打" : "太高");
        JPanel fixed = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fixed.setOpaque(false);
        fixed.setAlignmentX(LEFT_ALIGNMENT);
        fixed.add(table);
        panel.add(fixed);
        return panel;
    }

    private static void addRow(ResultDialog.GridTable table, String label, String now, String needed, boolean met,
                               String shortfall) {
        JLabel[] cells = table.row(Font.PLAIN, label, now, needed, met ? "✓ 達到" : shortfall);
        cells[3].setForeground(met ? Theme.GOLD : Theme.MUTED);
    }

    /** 千分之幾寫成百分比，例如 55 是 5.5%。 */
    static String permille(int value) {
        return value % 10 == 0 ? value / 10 + "%" : value / 10 + "." + value % 10 + "%";
    }

    private void save() {
        try {
            records.saveCertificate();
        } catch (IOException e) {
            ErrorReporter.note("儲存段位證照", e);
            JOptionPane.showMessageDialog(this, "證照無法儲存，下次開啟時會不見。\n" + e.getMessage(), TITLE,
                    JOptionPane.WARNING_MESSAGE);
        }
    }
}
