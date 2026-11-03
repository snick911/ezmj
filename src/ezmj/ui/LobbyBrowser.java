package ezmj.ui;

import java.util.List;
import java.util.Map;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

import ezmj.online.OnlineClient;
import ezmj.online.RoomListing;
import ezmj.online.ServerAddress;

/**
 * 大廳的房間列表：開一條不登入的連線，每 5 秒要一次列表。服務器不會主動推送。全部在 EDT 上。
 * 開房、加入與接回走 OnlineController 自己登入的連線，和這條無關，所以列表拿不到時那些照樣能用。
 */
final class LobbyBrowser implements OnlineClient.Listener {

    private static final int REFRESH_MS = 5_000;

    /** 列表顯示的地方。 */
    interface View {
        /** 新的列表，照服務器的順序。 */
        void showRooms(List<RoomListing> rooms);

        /** 拿不到列表，清空之後顯示這段說明。 */
        void showListProblem(String text);
    }

    private final View view;
    private final Timer timer = new Timer(REFRESH_MS, e -> request());
    private OnlineClient client;

    LobbyBrowser(View view) {
        this.view = view;
    }

    /** 開始定期更新，已經開始了就只是再要一次。舊服務器停下來之後也是用這個重新開始。 */
    void refresh() {
        timer.restart();
        request();
    }

    /** 離開大廳畫面時關掉連線。 */
    void stop() {
        timer.stop();
        closeClient();
    }

    /** 連線斷了就重開，連上時會自己先要一次。 */
    private void request() {
        if (client == null) {
            client = new OnlineClient(ServerAddress.current(), this, SwingUtilities::invokeLater);
            client.browse();
        } else {
            client.listRooms();
        }
    }

    private void closeClient() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public void rooms(List<RoomListing> rooms) {
        view.showRooms(rooms);
    }

    /** 只會送 listRooms，所以收到錯誤就是服務器還不認得它，再要也沒有用。 */
    @Override
    public void message(Map<String, Object> message) {
        if ("error".equals(message.get("type"))) {
            stop();
            view.showListProblem("這台服務器還不能列出房間，請用房號加入或自己開一間。");
        }
    }

    /** 下一次定期更新時重連。 */
    @Override
    public void closed(String reason) {
        client = null;
        view.showListProblem("連不上服務器，稍後會自動再試。");
    }

    @Override
    public void badMessage(String line, RuntimeException error) {
        ErrorReporter.note("看不懂服務器的房間列表", error);
    }

    /** 不登入，不會收到。 */
    @Override
    public void loggedIn(Map<String, Object> welcome, boolean resumed) {
    }

    /** 沒有 token 不會重連，斷線直接走 closed。 */
    @Override
    public void connectionLost() {
    }

    /** 沒有送 hello，錯誤都走 message。 */
    @Override
    public void loginRejected(String code, String message) {
    }
}
