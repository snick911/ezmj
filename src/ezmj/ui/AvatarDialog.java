package ezmj.ui;

import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import ezmj.career.DanCertificate;
import ezmj.career.Records;

/**
 * 選證照的個人圖像：預設圖庫（兩張人像插畫與八張程式畫的圖），或上傳自己的圖。
 * 上傳的圖從中間裁成正方形，存在那套紀錄的資料夾裡。選了就關掉，changed 負責存檔與重畫。
 */
final class AvatarDialog extends JDialog {

    private static final int ICON = 88;
    private static final int COLUMNS = 5;
    private static final String TITLE = "換個人圖像";

    private final Records records;
    private final Runnable changed;

    AvatarDialog(Window owner, Records records, Runnable changed) {
        super(owner, TITLE, ModalityType.APPLICATION_MODAL);
        this.records = records;
        this.changed = changed;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        DanCertificate certificate = records.certificate();
        String key = certificate.avatar();
        boolean custom = DanCertificate.CUSTOM_AVATAR.equals(key);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));
        content.add(ResultDialog.line(TITLE, Font.BOLD, 22f, Theme.GOLD));
        content.add(Box.createVerticalStrut(6));
        content.add(ResultDialog.line("選一張預設的圖，或上傳自己的照片，照片會從中間裁成正方形。", Font.PLAIN, 14f, Theme.MUTED));
        content.add(Box.createVerticalStrut(14));

        JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 10, 10));
        grid.setOpaque(false);
        grid.setAlignmentX(LEFT_ALIGNMENT);
        int size = Theme.scaled(ICON);
        for (CertificateArt.Preset preset : CertificateArt.Preset.values()) {
            BufferedImage image = CertificateArt.drawPreset(preset, certificate.name(), size * 2);
            boolean selected = !custom && CertificateArt.preset(key) == preset;
            grid.add(choice(roundIcon(image, size), preset.label(), selected, () -> choose(preset.name())));
        }
        BufferedImage uploaded = CertificateArt.readCustom(records.avatarFile());
        if (uploaded != null) {
            grid.add(choice(roundIcon(uploaded, size), "我的圖片", custom, () -> choose(DanCertificate.CUSTOM_AVATAR)));
        }
        content.add(grid);
        content.add(Box.createVerticalStrut(16));

        JButton upload = Theme.button("上傳圖片…", Theme.RED);
        upload.addActionListener(e -> upload());
        JButton cancel = Theme.button("取消", Theme.PANEL_LIGHT);
        cancel.addActionListener(e -> dispose());
        content.add(ShareDialog.buttonRow(upload, cancel));

        setContentPane(content);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** 圓形的圖示，Windows 放大顯示時用兩倍大的那張，才不會糊。 */
    static Icon roundIcon(BufferedImage source, int diameter) {
        return new ImageIcon(new BaseMultiResolutionImage(CertificateArt.circular(source, diameter),
                CertificateArt.circular(source, diameter * 2)));
    }

    private JButton choice(Icon icon, String label, boolean selected, Runnable action) {
        JButton button = new JButton(label, icon);
        button.setFont(Theme.font(Font.PLAIN, 14f));
        button.setForeground(selected ? Theme.GOLD : Theme.TEXT);
        button.setBackground(Theme.PANEL_LIGHT);
        button.setFocusPainted(false);
        button.setVerticalTextPosition(SwingConstants.BOTTOM);
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setBorder(selected
                ? BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.GOLD, 3),
                        BorderFactory.createEmptyBorder(6, 8, 6, 8))
                : BorderFactory.createEmptyBorder(9, 11, 9, 11));
        button.setToolTipText(selected ? "現在用的圖像" : "用這張");
        button.addActionListener(e -> action.run());
        return button;
    }

    private void choose(String key) {
        records.certificate().setAvatar(key);
        dispose();
        changed.run();
    }

    private void upload() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("上傳個人圖像");
        chooser.setFileFilter(new FileNameExtensionFilter("圖片（PNG、JPG、GIF、BMP）", "png", "jpg", "jpeg", "gif", "bmp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        BufferedImage image;
        try {
            image = ImageIO.read(file);
        } catch (IOException | RuntimeException e) {
            ErrorReporter.note("讀取上傳的個人圖像", e);
            image = null;
        }
        if (image == null || image.getWidth() == 0 || image.getHeight() == 0) {
            JOptionPane.showMessageDialog(this, "看不懂這個圖檔，請換成 PNG 或 JPG。", TITLE, JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path target = records.avatarFile();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            if (!ImageIO.write(CertificateArt.squareCrop(image), "png", temp.toFile())) {
                throw new IOException("這台電腦沒有 PNG 的寫入程式");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            ExportFiles.failed(this, "上傳個人圖像", e);
            return;
        }
        choose(DanCertificate.CUSTOM_AVATAR);
    }
}
