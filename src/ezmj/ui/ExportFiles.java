package ezmj.ui;

import java.awt.Component;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;

/**
 * 把產生的圖或 PDF 交給玩家：存到選的地方、存好可以直接打開，或把圖放進剪貼簿。
 * 上次選的資料夾記到程式關掉為止，第一次是桌面。
 */
final class ExportFiles {

    private static File lastDirectory;

    private ExportFiles() {
    }

    /** 問要存到哪裡，取消是 null。沒打副檔名就補上，檔案已經有了先問要不要蓋掉。 */
    static Path chooseTarget(Component parent, String title, String fileName, String extension, String description) {
        File start = lastDirectory != null ? lastDirectory : FileSystemView.getFileSystemView().getHomeDirectory();
        JFileChooser chooser = new JFileChooser(start);
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter(description, extension));
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), fileName));
        while (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith("." + extension)) {
                file = new File(file.getParentFile(), file.getName() + "." + extension);
            }
            if (file.exists() && JOptionPane.showConfirmDialog(parent, file.getName() + " 已經有了，要蓋掉嗎？", title,
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                continue;
            }
            lastDirectory = file.getParentFile();
            return file.toPath();
        }
        return null;
    }

    /** 存好了，告訴玩家存在哪裡，可以直接打開。 */
    static void saved(Component parent, String title, Path file) {
        boolean canOpen = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN);
        if (!canOpen) {
            JOptionPane.showMessageDialog(parent, "已經存到\n" + file, title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] options = {"開啟", "確定"};
        int choice = JOptionPane.showOptionDialog(parent, "已經存到\n" + file, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, options, options[1]);
        if (choice == 0) {
            try {
                Desktop.getDesktop().open(file.toFile());
            } catch (IOException | RuntimeException e) {
                JOptionPane.showMessageDialog(parent, "打不開這個檔案：" + e.getMessage(), title,
                        JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    /** 問要存到哪裡，存成 PNG，存好可以直接打開。 */
    static void savePng(Component parent, BufferedImage image, String fileName) {
        String title = "存成圖片";
        Path target = chooseTarget(parent, title, fileName, "png", "PNG 圖片");
        if (target == null) {
            return;
        }
        try {
            if (!ImageIO.write(image, "png", target.toFile())) {
                throw new IOException("這台電腦沒有 PNG 的寫入程式");
            }
        } catch (IOException e) {
            failed(parent, title, e);
            return;
        }
        saved(parent, title, target);
    }

    /** 存檔失敗，原因記在 error.log。 */
    static void failed(Component parent, String title, Exception error) {
        ErrorReporter.note(title + "失敗", error);
        JOptionPane.showMessageDialog(parent, title + "失敗：" + error.getMessage(), title, JOptionPane.WARNING_MESSAGE);
    }

    /** 把圖放進剪貼簿，LINE、Messenger 這類 App 按 Ctrl+V 就能貼上。剪貼簿被別的程式佔著時回傳 false。 */
    static boolean copyImage(Image image) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(image), null);
            return true;
        } catch (IllegalStateException e) {
            ErrorReporter.note("複製圖片到剪貼簿失敗", e);
            return false;
        }
    }

    private static final class ImageSelection implements Transferable {
        private final Image image;

        ImageSelection(Image image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }
}
