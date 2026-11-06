package ezmj.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** 存文字檔的共用做法。 */
public final class PropertyFiles {

    /**
     * 每個存檔都記著自己的格式版本。新增項目不用改版本，讀的地方補預設值就好；
     * 同一個項目的意思變了才加一，讀的地方照版本換算舊的值。沒有這一項的檔案是第 1 版。
     */
    public static final String FORMAT_KEY = "format";

    private PropertyFiles() {
    }

    /** 先寫到暫存檔再換過去，存到一半當機也不會弄壞原本的檔案。 */
    public static void store(Properties values, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            values.store(out, "EZMJ");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 讀一般的文字檔；內容的跳脫字元壞掉也是 IOException。 */
    public static Properties read(Path file) throws IOException {
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (IllegalArgumentException e) {
            throw new IOException("檔案內容不正確", e);
        }
        return values;
    }

    /** 和 store 一樣先寫暫存檔，內容是現成的位元組。 */
    static void writeAtomically(byte[] content, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, content);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 檔案的格式版本，沒寫的是第 1 版，讀不懂的當作第 1 版。 */
    public static int format(Properties values) {
        try {
            return Integer.parseInt(values.getProperty(FORMAT_KEY, "1").trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
