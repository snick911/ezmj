package ezmj.ui;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 要分享的圖的預覽：看得到那張圖，可以複製到剪貼簿貼進 LINE、Messenger 這類 App，或存成 PNG。
 * 電腦版沒有手機那種「分享」選單，複製再貼上是最少步驟的做法。牌局結束的結果圖用這個。
 */
final class ShareDialog extends JDialog {

    private static final int PREVIEW_HEIGHT = 560;
    private static final int MIN_PREVIEW_HEIGHT = 240;
    private static final int SCREEN_MARGIN = 280;
    static final String HINT = "按「複製圖片」，再到 LINE、Messenger 的對話框按 Ctrl+V 貼上；也可以存成圖檔再傳。";

    ShareDialog(Window owner, String title, BufferedImage image, String fileName) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));
        content.add(ResultDialog.line(title, Font.BOLD, 24f, Theme.GOLD));
        content.add(Box.createVerticalStrut(12));

        int screenLimit = Toolkit.getDefaultToolkit().getScreenSize().height - SCREEN_MARGIN;
        int height = Math.max(MIN_PREVIEW_HEIGHT, Math.min(Theme.scaled(PREVIEW_HEIGHT), screenLimit));
        Preview preview = new Preview(image, height * image.getWidth() / image.getHeight(), height);
        preview.setAlignmentX(LEFT_ALIGNMENT);
        content.add(preview);
        content.add(Box.createVerticalStrut(10));
        JLabel hint = ResultDialog.line(HINT, Font.PLAIN, 14f, Theme.MUTED);
        content.add(hint);
        content.add(Box.createVerticalStrut(14));

        JButton copy = copyButton(image, hint);
        JButton close = Theme.button("關閉", Theme.PANEL_LIGHT);
        close.addActionListener(e -> dispose());
        content.add(buttonRow(copy, saveButton(image, fileName), close));
        getRootPane().setDefaultButton(copy);

        setContentPane(content);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** 「複製圖片」：複製好了就在 hint 上說一聲。 */
    static JButton copyButton(BufferedImage image, JLabel hint) {
        JButton copy = Theme.button("複製圖片", Theme.RED);
        copy.addActionListener(e -> {
            boolean copied = ExportFiles.copyImage(image);
            hint.setText(copied ? "已經複製，到 LINE、Messenger 的對話框按 Ctrl+V 貼上。" : "剪貼簿正被別的程式使用，請再按一次，或存成圖檔。");
            hint.setForeground(copied ? Theme.GOLD : Theme.TEXT);
        });
        return copy;
    }

    /** 「存成圖片…」。 */
    static JButton saveButton(BufferedImage image, String fileName) {
        JButton save = Theme.button("存成圖片…", Theme.BUTTON);
        save.addActionListener(e -> ExportFiles.savePng(save, image, fileName));
        return save;
    }

    /** 靠左排成一列，中間隔 10。 */
    static JPanel buttonRow(JComponent... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) {
                row.add(Box.createHorizontalStrut(10));
            }
            row.add(buttons[i]);
        }
        return row;
    }

    /**
     * 把大圖縮小顯示。照螢幕實際的像素縮，Windows 放大 150% 時也清楚。
     */
    static final class Preview extends JComponent {
        private BufferedImage image;
        private BufferedImage cached;

        Preview(BufferedImage image, int width, int height) {
            this.image = image;
            Dimension size = new Dimension(width, height);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
        }

        /** 換一張一樣比例的圖。 */
        void setImage(BufferedImage next) {
            image = next;
            cached = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            AffineTransform transform = g2.getTransform();
            int width = (int) Math.round(getWidth() * transform.getScaleX());
            int height = (int) Math.round(getHeight() * transform.getScaleY());
            if (width <= 0 || height <= 0) {
                g2.dispose();
                return;
            }
            if (cached == null || cached.getWidth() != width || cached.getHeight() != height) {
                cached = ImageKit.smooth(image, width, height);
            }
            g2.scale(1 / transform.getScaleX(), 1 / transform.getScaleY());
            g2.drawImage(cached, 0, 0, null);
            g2.dispose();
        }
    }
}
