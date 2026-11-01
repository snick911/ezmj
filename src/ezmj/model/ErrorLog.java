package ezmj.model;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 程式錯誤的記錄檔。檔案太大就換成 .old 再重新開始，不會一直長大。寫不進去就算了，不能因為記錄失敗又出錯。 */
public final class ErrorLog {

    private static final long MAX_BYTES = 512 * 1024;
    /** 畫面每次重畫都出錯時會有一連串一樣的錯誤，一次執行最多記這麼多筆。 */
    private static final int MAX_ENTRIES = 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path file;
    private int entries;

    public ErrorLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** context 是出錯時的局面，例如亂數種子與第幾局。 */
    public synchronized void write(String threadName, String context, Throwable error) {
        if (entries >= MAX_ENTRIES) {
            return;
        }
        entries++;
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.println("==== " + LocalDateTime.now().format(TIME) + " 執行緒 " + threadName);
        out.println(context);
        error.printStackTrace(out);
        out.flush();
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException e) {
            System.err.print(text);
        }
    }
}
