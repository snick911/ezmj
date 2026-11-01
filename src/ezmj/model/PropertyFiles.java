package ezmj.model;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** 存文字檔的共用做法。 */
public final class PropertyFiles {

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
}
