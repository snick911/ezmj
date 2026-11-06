package ezmj.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 一組設定值怎麼存成檔案。存什麼由各個類別決定，怎麼存由這裡決定，兩邊分開才能換存法而不動內容。
 * PLAIN 是看得懂的文字檔，環境設定、連線資料與服務器的榮譽榜用；玩家的存檔與紀錄用 SealedStore，玩家改不了。
 */
public interface PropertyStore {

    /** 一般的 Properties 文字檔。 */
    PropertyStore PLAIN = new PropertyStore() {
        @Override
        public Properties read(Path file) throws IOException {
            return PropertyFiles.read(file);
        }

        @Override
        public void write(Properties values, Path file) throws IOException {
            PropertyFiles.store(values, file);
        }
    };

    /** 讀不到、讀不懂或驗不過都丟 IOException；沒有檔案是 NoSuchFileException。 */
    Properties read(Path file) throws IOException;

    /** 先寫到暫存檔再換過去，存到一半當機也不會弄壞原本的檔案。 */
    void write(Properties values, Path file) throws IOException;
}
