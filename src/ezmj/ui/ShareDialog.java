package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 要分享的圖的預覽：看得到那張圖，可以複製到剪貼簿貼進 LINE、Messenger 這類 App，或存成 PNG。
 * 電腦版沒有手機那種「分享」選單，複製再貼上是最少步驟的做法。牌局結束的結果圖用這個。
 * 預覽的大小照螢幕可以放視窗的範圍算，各種解析度與字體大小都放得進螢幕。
 */
final class ShareDialog extends JDialog {

    private static final int PREVIEW_HEIGHT = 560;
    private static final int MIN_PREVIEW_HEIGHT = 240;
    /** 預覽最窄這麼寬，再窄字就看不清楚了。 */
    static final int MIN_PREVIEW_WIDTH = 360;
    /** 視窗最多佔螢幕可用範圍的這麼多。 */
    private static final double SCREEN_SHARE = .95;
    static final String HINT = "按「複製圖片」，再到 LINE、Messenger 的對話框按 Ctrl+V 貼上；也可以存成圖檔再傳。";

    ShareDialog(Window owner, String title, BufferedImage image, String fileName) {
        this(owner, title, image, fileName, usableScreen(owner));
    }

    /** screen 是螢幕上可以放視窗的範圍，預覽的大小照它算；檢查各種螢幕時可以直接給。 */
    ShareDialog(Window owner, String title, BufferedImage image, String fileName, Rectangle screen) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));
        content.add(ResultDialog.line(title, Font.BOLD, 24f, Theme.GOLD));
        content.add(Box.createVerticalStrut(12));

        Preview preview = new Preview(image, MIN_PREVIEW_HEIGHT * image.getWidth() / image.getHeight(),
                MIN_PREVIEW_HEIGHT);
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
        fitPreview(this, preview, Theme.scaled(PREVIEW_HEIGHT) * image.getWidth() / image.getHeight(), screen);
        setResizable(false);
        placeOnScreen(this, owner, screen);
    }

    /** 螢幕上可以放視窗的範圍：扣掉工作列。Windows 放大顯示時是放大後的大小。 */
    static Rectangle usableScreen(Component owner) {
        GraphicsConfiguration config = owner == null ? null : owner.getGraphicsConfiguration();
        if (config == null) {
            config = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle bounds = config.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top, bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom);
    }

    /**
     * 把預覽放到整個視窗放得進 screen 九成五的最大，最寬 maxWidth、最窄 MIN_PREVIEW_WIDTH。
     * 先照 maxWidth 排一次，這時預覽比旁邊的說明高，量出來「預覽以外」佔多少是準的，照它算出預覽多大；
     * 排好還超出（例如旁邊的說明比預覽高）就照超出的量再縮。螢幕真的太小時，縮到最窄為止。
     */
    static void fitPreview(Window window, Preview preview, int maxWidth, Rectangle screen) {
        double ratio = preview.ratio();
        int limitWidth = (int) (screen.width * SCREEN_SHARE);
        int limitHeight = (int) (screen.height * SCREEN_SHARE);
        int width = maxWidth;
        applyPreview(window, preview, width, ratio);
        Dimension size = window.getSize();
        int byWidth = limitWidth - (size.width - width);
        int byHeight = (int) ((limitHeight - (size.height - previewHeight(width, ratio))) * ratio);
        width = Math.max(MIN_PREVIEW_WIDTH, Math.min(maxWidth, Math.min(byWidth, byHeight)));
        applyPreview(window, preview, width, ratio);
        for (int pass = 0; pass < 3 && width > MIN_PREVIEW_WIDTH; pass++) {
            size = window.getSize();
            int over = Math.max(size.width - limitWidth, (int) Math.ceil((size.height - limitHeight) * ratio));
            if (over <= 0) {
                return;
            }
            width = Math.max(MIN_PREVIEW_WIDTH, width - over);
            applyPreview(window, preview, width, ratio);
        }
    }

    private static int previewHeight(int width, double ratio) {
        return (int) Math.round(width / ratio);
    }

    private static void applyPreview(Window window, Preview preview, int width, double ratio) {
        preview.setPreviewSize(width, previewHeight(width, ratio));
        window.pack();
    }

    /** 放在 owner 正中間，超出 screen 的部分推回來。 */
    static void placeOnScreen(Window window, Component owner, Rectangle screen) {
        window.setLocationRelativeTo(owner);
        Point at = window.getLocation();
        int x = Math.max(screen.x, Math.min(at.x, screen.x + screen.width - window.getWidth()));
        int y = Math.max(screen.y, Math.min(at.y, screen.y + screen.height - window.getHeight()));
        window.setLocation(x, y);
    }

    /** 整張圖放到螢幕放得下的最大，點一下或按 Esc 關掉。 */
    static void zoom(Window owner, BufferedImage image, String title) {
        JDialog dialog = new JDialog(owner, title, ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Rectangle screen = usableScreen(owner);
        Preview preview = new Preview(image, 100, 100);
        preview.setToolTipText("點一下關閉");
        preview.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dialog.dispose();
            }
        });
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(preview, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        fitPreview(dialog, preview, image.getWidth(), screen);
        dialog.setResizable(false);
        placeOnScreen(dialog, owner, screen);
        dialog.setVisible(true);
    }

    /** 讓預覽可以點開放大，滑鼠移上去變成手指。 */
    static void zoomOnClick(Preview preview, BufferedImage image, String title) {
        preview.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        preview.setToolTipText("點一下放大看");
        preview.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                zoom(SwingUtilities.getWindowAncestor(preview), image, title);
            }
        });
    }

    /** 「複製圖片」：複製好了就在 hint 上說一聲。 */
    static JButton copyButton(BufferedImage image, JLabel hint) {
        return copyButton(image, hint, 0);
    }

    /** wrapWidth 大於 0 時，hint 照這個寬度折行（畫面上的寬度，已經照字體大小算好）。 */
    static JButton copyButton(BufferedImage image, JLabel hint, int wrapWidth) {
        JButton copy = Theme.button("複製圖片", Theme.RED);
        copy.addActionListener(e -> {
            boolean copied = ExportFiles.copyImage(image);
            String text = copied ? "已經複製，到 LINE、Messenger 的對話框按 Ctrl+V 貼上。" : "剪貼簿正被別的程式使用，請再按一次，或存成圖檔。";
            hint.setText(wrapWidth > 0 ? HomePanel.wrapHtml(HomePanel.escape(text), wrapWidth) : text);
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
            setPreviewSize(width, height);
        }

        /** 在版面上佔這麼大，圖照這個大小縮放。 */
        void setPreviewSize(int width, int height) {
            Dimension size = new Dimension(width, height);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
            revalidate();
        }

        /** 圖的寬高比。 */
        double ratio() {
            return image.getWidth() / (double) image.getHeight();
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
