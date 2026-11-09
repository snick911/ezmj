package ezmj.ui;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
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

import ezmj.career.Dan;
import ezmj.career.DanCertificate;
import ezmj.career.Records;
import ezmj.engine.PlayerStats;

/**
 * 段位證照：證書的預覽、下一段還差多少；可以複製或存成圖片、換個人圖像、改證書上的名字。
 * 打開時先照目前的成績看能不能升段，以前就打夠的人一打開就拿得到。不擋住牌桌，和說明視窗一樣可以邊看邊打。
 */
final class CertificateDialog extends JDialog {

    private static final String TITLE = "段位證照";
    private static final int PREVIEW_WIDTH = 640;
    private static final int MIN_PREVIEW_WIDTH = 360;
    /** 預覽以外的東西大約要這麼高，螢幕矮時預覽縮小。 */
    private static final int OTHER_HEIGHT = 470;
    private static final int AVATAR_PREVIEW = 120;

    private final Records records;
    private final String ownerLabel;
    private final Runnable showRules;

    /** fallbackName 是證照還沒有名字時用的，例如修業檔案的名字；ownerLabel 例如「練習牌局」「修業：小明」。 */
    CertificateDialog(Frame owner, Records records, String ownerLabel, String fallbackName, Runnable showRules) {
        super(owner, TITLE, ModalityType.MODELESS);
        this.records = records;
        this.ownerLabel = ownerLabel;
        this.showRules = showRules;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        DanCertificate certificate = records.certificate();
        boolean named = certificate.fillName(fallbackName);
        boolean promoted = certificate.promote(records.stats(), LocalDate.now().toString()) != null;
        if (named || promoted) {
            save();
        }
        refresh();
        setLocationRelativeTo(owner);
    }

    /** 看的是不是這套紀錄。 */
    boolean shows(Records other) {
        return records == other;
    }

    /** 照證照目前的內容整個重畫。 */
    void refresh() {
        DanCertificate certificate = records.certificate();
        DanCertificate.Grant grant = certificate.grant();
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));
        content.add(ResultDialog.line(TITLE, Font.BOLD, 24f, Theme.GOLD));
        content.add(Box.createVerticalStrut(4));
        content.add(ResultDialog.line("「" + ownerLabel + "」的證照。練習牌局與每個修業檔案各有一張，段位只升不降。",
                Font.PLAIN, 14f, Theme.MUTED));
        content.add(Box.createVerticalStrut(12));

        BufferedImage avatar = CertificateArt.avatar(certificate.avatar(), certificate.name(), records.avatarFile(),
                CertificateArt.AVATAR_SIZE);
        if (grant != null) {
            BufferedImage image = CertificateArt.certificate(grant, certificate.name(), avatar);
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int byHeight = (screen.height - Theme.scaled(OTHER_HEIGHT)) * CertificateArt.WIDTH / CertificateArt.HEIGHT;
            int width = Math.max(MIN_PREVIEW_WIDTH, Math.min(Theme.scaled(PREVIEW_WIDTH), byHeight));
            ShareDialog.Preview preview = new ShareDialog.Preview(image, width,
                    width * CertificateArt.HEIGHT / CertificateArt.WIDTH);
            preview.setAlignmentX(LEFT_ALIGNMENT);
            content.add(preview);
            content.add(Box.createVerticalStrut(10));
            JLabel hint = ResultDialog.line(ShareDialog.HINT, Font.PLAIN, 14f, Theme.MUTED);
            content.add(hint);
            content.add(Box.createVerticalStrut(12));
            content.add(ShareDialog.buttonRow(ShareDialog.copyButton(image, hint),
                    ShareDialog.saveButton(image, CertificateArt.fileName(grant))));
        } else {
            content.add(notYet(avatar, certificate.name()));
        }
        content.add(Box.createVerticalStrut(18));
        content.add(progress(certificate.dan()));
        content.add(Box.createVerticalStrut(18));

        JButton avatarButton = Theme.button("換圖像…", Theme.BUTTON);
        avatarButton.addActionListener(e -> new AvatarDialog(this, records, () -> {
            save();
            refresh();
        }).setVisible(true));
        JButton rename = Theme.button("改名字…", Theme.BUTTON);
        rename.addActionListener(e -> rename());
        JButton rules = Theme.button("段位表", Theme.BUTTON);
        rules.addActionListener(e -> showRules.run());
        JButton close = Theme.button("關閉", Theme.PANEL_LIGHT);
        close.addActionListener(e -> dispose());
        content.add(ShareDialog.buttonRow(avatarButton, rename, rules, close));
        getRootPane().setDefaultButton(close);

        setContentPane(content);
        pack();
        setResizable(false);
    }

    /** 還沒入段：圖像、名字與一句說明。 */
    private JComponent notYet(BufferedImage avatar, String name) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.add(new JLabel(AvatarDialog.roundIcon(avatar, Theme.scaled(AVATAR_PREVIEW))));
        row.add(Box.createHorizontalStrut(18));
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(ResultDialog.line("尚未入段", Font.BOLD, 26f, Theme.GOLD));
        text.add(Box.createVerticalStrut(6));
        text.add(ResultDialog.line("證書上的名字：" + (name.isEmpty() ? "（還沒有，按「改名字…」）" : name),
                Font.PLAIN, 15f, Theme.TEXT));
        text.add(Box.createVerticalStrut(4));
        text.add(ResultDialog.line("打到初段就會發證書，可以複製或存成圖片分享。", Font.PLAIN, 14f, Theme.MUTED));
        row.add(text);
        return row;
    }

    /** 下一段看哪些局、三項各到了沒有。 */
    private JComponent progress(Dan current) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        Dan next = Dan.after(current);
        if (next == null) {
            panel.add(ResultDialog.line("已經是最高的九段了。", Font.BOLD, 16f, Theme.GOLD));
            return panel;
        }
        String scope = next.opponents() == null ? "看全部的局" : "只看" + next.opponentsText() + "的局";
        panel.add(ResultDialog.line("下一段：" + next.label() + "（" + scope + "）", Font.BOLD, 16f, Theme.TEXT));
        panel.add(Box.createVerticalStrut(6));
        PlayerStats.Totals pool = next.pool(records.stats());
        ResultDialog.GridTable table = new ResultDialog.GridTable(1);
        table.header("", "目前", "門檻", "");
        int missing = next.rounds() - pool.rounds();
        addRow(table, "局數", String.valueOf(pool.rounds()), String.valueOf(next.rounds()), next.roundsMet(pool),
                "還差 " + missing + " 局");
        addRow(table, "胡牌率", CertificateArt.percent(pool.winRate()), permille(next.winPermille()),
                next.winRateMet(pool), "還沒到");
        addRow(table, "自摸率", CertificateArt.percent(pool.selfDrawRate()), permille(next.selfDrawPermille()),
                next.selfDrawRateMet(pool), "還沒到");
        panel.add(table);
        if (next.opponents() != null) {
            panel.add(Box.createVerticalStrut(6));
            panel.add(ResultDialog.line("「三家都是某等級以上」的局是有段位證照之後才開始記的。", Font.PLAIN, 13f, Theme.MUTED));
        }
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

    private void rename() {
        DanCertificate certificate = records.certificate();
        Object input = JOptionPane.showInputDialog(this, "證書上的名字（最多 " + DanCertificate.MAX_NAME_LENGTH + " 字）",
                "改名字", JOptionPane.PLAIN_MESSAGE, null, null, certificate.name());
        if (input == null) {
            return;
        }
        try {
            certificate.rename(input.toString());
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "改名字", JOptionPane.WARNING_MESSAGE);
            return;
        }
        save();
        refresh();
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
