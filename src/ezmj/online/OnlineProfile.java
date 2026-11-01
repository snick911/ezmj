package ezmj.online;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import ezmj.model.PropertyFiles;

/**
 * 連線畫面記住的東西：上次的服務器與名字，還有牌局中的 token 與房號，程式關掉之後還接得回來。
 * token 等於這個座位的身分，牌局結束就清掉。沒有 token 時 token 與 room 都是 null。
 */
public record OnlineProfile(String server, String name, String token, String room) {

    private static final String DEFAULT_SERVER = "127.0.0.1:" + ServerAddress.DEFAULT_PORT;

    public boolean canResume() {
        return token != null && room != null;
    }

    public OnlineProfile withLogin(String nextServer, String nextName) {
        return new OnlineProfile(nextServer, nextName, token, room);
    }

    public OnlineProfile withGame(String nextToken, String nextRoom) {
        return new OnlineProfile(server, name, nextToken, nextRoom);
    }

    public OnlineProfile withoutGame() {
        return withGame(null, null);
    }

    /** 沒有檔案或讀不懂就當作第一次用。 */
    public static OnlineProfile load(Path file) {
        Properties values = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                values.load(in);
            } catch (IOException | IllegalArgumentException e) {
                values.clear();
            }
        }
        return new OnlineProfile(values.getProperty("server", DEFAULT_SERVER), values.getProperty("name", ""),
                values.getProperty("token"), values.getProperty("room"));
    }

    public void save(Path file) throws IOException {
        Properties values = new Properties();
        values.setProperty("server", server);
        values.setProperty("name", name);
        if (canResume()) {
            values.setProperty("token", token);
            values.setProperty("room", room);
        }
        PropertyFiles.store(values, file);
    }
}
