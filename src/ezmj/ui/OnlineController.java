package ezmj.ui;

import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import ezmj.engine.Claim;
import ezmj.engine.GameView;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundView;
import ezmj.engine.WallRing;
import ezmj.model.AppSettings;
import ezmj.model.Tile;
import ezmj.online.OnlineClient;
import ezmj.online.OnlineGame;
import ezmj.online.OnlineProfile;
import ezmj.online.RoomInfo;
import ezmj.online.RoomListing;
import ezmj.online.RoomSettings;
import ezmj.online.ServerAddress;

/**
 * 連線牌局：把服務器的訊息演到畫面上，再把自己的決定送回去，全部在 EDT 上執行。
 * 規則都在服務器，這裡只看 OnlineGame 抄下來的局面。不碰本機的存檔、統計、成就與榮譽榜。
 *
 * 服務器每走一步是先送事件、再送局面，動畫卻要看走完之後的局面，所以事件先留著，收到局面才一起演。
 * 這局打完的局面也先留著，要等後面的 roundEnd 才知道結果，才畫得出來。
 */
final class OnlineController implements OnlineClient.Listener, TablePresenter.Table {

    private static final int RESULT_SECONDS = 60;

    /** 視窗提供的事。 */
    interface Host {
        Frame frame();

        void showRoom(RoomPanel room);

        void showTable(TablePanel table);

        void showHistory(GameView game);

        void refreshHistory();

        void closeHistory();

        /** 這次連線結束，回到輸入名字的畫面。message 不是 null 時告訴玩家發生什麼事。 */
        void backToForm(String message);
    }

    private final Host host;
    private final AppSettings settings;
    private final Path profileFile;
    private final List<RoundEvent> pendingEvents = new ArrayList<>();

    private OnlineProfile profile;
    private OnlineClient client;
    private Runnable afterLogin = () -> { };
    private RoomInfo room;
    private RoomPanel roomPanel;
    private OnlineGame game;
    private TablePanel view;
    private TablePresenter presenter;
    private boolean roundBound;
    private boolean dealPending;
    /** 這局打完的局面，等 roundEnd。 */
    private Map<String, Object> finishedState;
    /** 上一份 roundEnd。重連時服務器會再送一次，一樣的就不用再演。 */
    private Map<String, Object> lastRoundEnd;
    /** 每開一局加一，結果視窗關掉時分得出下一局是不是已經開始了。 */
    private int roundSerial;
    private boolean waiting;
    private int waitId;
    private List<Claim> claimOptions = List.of();
    /** 整場打完，結果視窗還沒看完，房間的變化先不要切畫面。 */
    private boolean finishing;
    private JDialog openDialog;
    private boolean shutDown;

    OnlineController(Host host, AppSettings settings, Path profileFile) {
        this.host = host;
        this.settings = settings;
        this.profileFile = profileFile;
        this.profile = OnlineProfile.load(profileFile);
    }

    void createRoom(String name, RoomSettings roomSettings) {
        login(name, () -> client.createRoom(roomSettings));
    }

    void joinRoom(String name, String code) {
        login(name, () -> client.joinRoom(code));
    }

    private void login(String name, Runnable next) {
        profile = profile.withName(name);
        afterLogin = next;
        client = new OnlineClient(ServerAddress.current(), this, SwingUtilities::invokeLater);
        client.loginWithName(name);
    }

    /** 接回程式關掉之前的牌局。 */
    void resume(OnlineProfile saved) {
        profile = saved;
        client = new OnlineClient(ServerAddress.current(), this, SwingUtilities::invokeLater);
        client.loginWithToken(saved.token());
    }

    /** 結束這次連線。 */
    void shutdown() {
        shutDown = true;
        if (client != null) {
            client.close();
        }
        closeOpenDialog();
        closeTable();
    }

    private void closeOpenDialog() {
        if (openDialog != null) {
            openDialog.dispose();
            openDialog = null;
        }
    }

    private void closeTable() {
        if (presenter != null) {
            presenter.stop();
        }
        host.closeHistory();
        game = null;
        view = null;
        presenter = null;
    }

    String describe() {
        return "連線牌局，" + (game == null ? "不在牌桌上" : game.toString());
    }

    /** 關閉程式前問一聲。牌局中關掉座位由電腦代打，token 還留著，下次開啟可以接回。 */
    boolean confirmExit() {
        return game == null || game.isOver() || JOptionPane.showConfirmDialog(host.frame(),
                "關閉之後由電腦代打，下次開啟可以從「連線對戰」接回。確定要關閉嗎？", "關閉遊戲",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void saveProfile() {
        try {
            profile.save(profileFile);
        } catch (IOException e) {
            ErrorReporter.note("儲存連線資料", e);
        }
    }

    private void forgetGame() {
        if (profile.canResume()) {
            profile = profile.withoutGame();
            saveProfile();
        }
    }

    private void end(String message) {
        shutdown();
        host.backToForm(message);
    }

    @Override
    public void loggedIn(Map<String, Object> welcome, boolean resumed) {
        if (!resumed) {
            saveProfile();
            afterLogin.run();
            return;
        }
        if (welcome.get("room") == null) {
            forgetGame();
            end("這場牌局已經結束了。");
            return;
        }
        if (roomPanel != null && view == null) {
            roomPanel.setNotice(" ");
        }
    }

    @Override
    public void connectionLost() {
        pendingEvents.clear();
        finishedState = null;
        dealPending = false;
        waiting = false;
        if (presenter != null) {
            presenter.showIdle("連線中斷，正在重新連線…");
        } else if (roomPanel != null) {
            roomPanel.setNotice("連線中斷，正在重新連線…");
        }
    }

    @Override
    public void loginRejected(String code, String message) {
        if (code.equals("unknownToken")) {
            forgetGame();
            end("這場牌局已經結束，或這個座位已經不在了。");
        } else {
            end(message);
        }
    }

    @Override
    public void closed(String reason) {
        end(profile.canResume() ? reason + "\n\n牌局還在的話可以按「接回牌局」再試一次。" : reason);
    }

    @Override
    public void badMessage(String line, RuntimeException error) {
        ErrorReporter.note("看不懂服務器的訊息", error);
    }

    /** 房間列表由大廳的 LobbyBrowser 另外要，這條連線不會送 listRooms。 */
    @Override
    public void rooms(List<RoomListing> rooms) {
    }

    /** 看不懂的訊息就略過：服務器每走一步都送完整的局面，下一份就補回來了。 */
    @Override
    public void message(Map<String, Object> message) {
        if (shutDown) {
            return;
        }
        try {
            dispatch(message);
        } catch (IllegalArgumentException e) {
            ErrorReporter.note("看不懂服務器的訊息", e);
        }
    }

    private void dispatch(Map<String, Object> message) {
        switch (String.valueOf(message.get("type"))) {
            case "room":
                onRoom(RoomInfo.parse(message));
                break;
            case "error":
                onError(String.valueOf(message.get("code")), String.valueOf(message.get("message")));
                break;
            case "canListen":
                if (presenter != null) {
                    presenter.canListen(OnlineGame.parseListenOffer(message));
                }
                break;
            case "chat":
                onChat(message);
                break;
            case "left":
                forgetGame();
                end(null);
                break;
            case "roomClosed":
                forgetGame();
                end(closeReason(String.valueOf(message.get("reason"))));
                break;
            case "roundStart":
                onRoundStart(message);
                break;
            case "state":
                onState(message);
                break;
            case "event":
                if (game != null) {
                    pendingEvents.add(game.parseEvent(message));
                }
                break;
            case "askTurn":
                onAskTurn(message);
                break;
            case "askClaim":
                onAskClaim(message);
                break;
            case "countdown":
                onCountdown(message);
                break;
            case "roundEnd":
                onRoundEnd(message);
                break;
            case "gameOver":
                onGameOver(message);
                break;
            default:
                break;
        }
    }

    private static String closeReason(String reason) {
        switch (reason) {
            case "empty":         return null;
            case "abandoned":     return "全桌都斷線太久，房間關閉了。";
            case "serverStopping": return "服務器關閉了。";
            default:              return "服務器發生錯誤，房間關閉了。";
        }
    }

    private void onRoom(RoomInfo info) {
        room = info;
        if (info.playing()) {
            if (game == null || game.isOver()) {
                closeOpenDialog();
                finishing = false;
                closeTable();
                openTable(info);
            }
            game.applyRoom(info);
            profile = profile.withGame(client.token(), info.code());
            saveProfile();
            if (roundBound) {
                view.refresh(game, game);
            }
        } else if (!finishing) {
            closeTable();
            showRoom();
        }
    }

    private void showRoom() {
        if (roomPanel == null) {
            roomPanel = new RoomPanel(client::start, this::leaveRoom);
        }
        roomPanel.setRoom(room);
        host.showRoom(roomPanel);
    }

    private void openTable(RoomInfo info) {
        game = new OnlineGame();
        presenter = new TablePresenter(this, settings);
        view = new TablePanel(presenter::humanDiscard, this::confirmLeave, null, () -> host.showHistory(game));
        presenter.attach(view);
        view.showRoomCode(info.code());
        // 連線的牌桌都有聊天，跟電腦打的一人房間也有，只是沒有人回
        view.setChat(client::chat);
        host.showTable(view);
        pendingEvents.clear();
        finishedState = null;
        lastRoundEnd = null;
        roundBound = false;
        dealPending = false;
        waiting = false;
    }

    private void onError(String code, String text) {
        if (room == null) {
            // 開房間或加入失敗，回去改了再試
            end(text);
        } else if (isChatError(code, text)) {
            if (view != null) {
                view.showChatNotice(code.equals("unknownType") ? "這台服務器還不能聊天。" : text);
            }
        } else if (presenter != null) {
            view.setHint(text);
        } else {
            JOptionPane.showMessageDialog(host.frame(), text, "連線對戰", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 聊天的錯誤顯示在聊天區。舊服務器不認得 chat，回的是 unknownType，訊息裡有 chat。 */
    private static boolean isChatError(String code, String text) {
        return code.equals("badChat") || code.equals("chatTooFast") || (code.equals("unknownType") && text.contains("chat"));
    }

    /** 牌桌上才看得到聊天，在房間畫面等人時收到的就略過。服務器不補送，斷線期間的訊息也沒有。 */
    private void onChat(Map<String, Object> message) {
        if (view == null || game == null) {
            return;
        }
        OnlineGame.Chat chat = game.parseChat(message);
        view.appendChat(chat.name(), chat.text(), chat.seat() == 0);
    }

    private void leaveRoom() {
        if (client.isConnected()) {
            client.leave();
        } else {
            forgetGame();
            end(null);
        }
    }

    private void confirmLeave() {
        if (game != null && !game.isOver() && JOptionPane.showConfirmDialog(host.frame(),
                "離開之後就回不來了，座位由電腦接手。確定要離開嗎？", "離開牌局",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        leaveRoom();
    }

    private void onRoundStart(Map<String, Object> message) {
        if (game == null) {
            return;
        }
        closeOpenDialog();
        roundSerial++;
        pendingEvents.clear();
        finishedState = null;
        lastRoundEnd = null;
        waiting = false;
        game.applyRoundStart(message);
        view.bindRound(game, game.dice());
        roundBound = true;
        dealPending = true;
    }

    private void onState(Map<String, Object> state) {
        if (game == null) {
            return;
        }
        if (Round.Phase.FINISHED.name().equals(state.get("phase"))) {
            finishedState = state;
            return;
        }
        // 開新的一局時才會走到這裡還開著結果視窗，例如結果視窗開著的時候斷線，錯過了 roundStart
        if (lastRoundEnd != null) {
            closeOpenDialog();
            roundSerial++;
            lastRoundEnd = null;
        }
        List<RoundEvent> events = takeEvents();
        game.applyState(state, events);
        bindRoundOnce();
        waiting = false;
        if (dealPending) {
            dealPending = false;
            view.appendLog(WallRing.announce(game, game.dice()));
            if (settings.animationEnabled()) {
                presenter.showDeal();
            } else {
                view.refresh(game, game);
                presenter.showIdle("發牌中…");
            }
            return;
        }
        view.refresh(game, game);
        playEvents(events);
        presenter.showIdle(idleHint());
    }

    private List<RoundEvent> takeEvents() {
        List<RoundEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    /** 重連回來時沒有 roundStart，第一份局面才接上牌桌。骰子用 state 的資料算，和別家看到的一樣。 */
    private void bindRoundOnce() {
        if (!roundBound) {
            view.bindRound(game, game.dice());
            roundBound = true;
        }
    }

    private void playEvents(List<RoundEvent> events) {
        for (RoundEvent event : events) {
            String line = game.describe(event);
            if (line != null) {
                view.appendLog(line);
            }
            presenter.event(event);
        }
    }

    private String idleHint() {
        return game.phase() == Round.Phase.TURN && game.current() != 0
                ? "等待 " + game.player(game.current()).name() + " 出牌…" : " ";
    }

    private void onAskTurn(Map<String, Object> message) {
        if (game == null) {
            return;
        }
        OnlineGame.TurnAsk ask = OnlineGame.parseTurn(message);
        waitId = ask.waitId();
        waiting = true;
        presenter.askTurn(ask.waitId(), ask.options());
    }

    private void onAskClaim(Map<String, Object> message) {
        if (game == null) {
            return;
        }
        OnlineGame.ClaimAsk ask = OnlineGame.parseClaim(message);
        waitId = ask.waitId();
        waiting = true;
        claimOptions = ask.options();
        presenter.askClaim(ask.waitId(), ask.options());
    }

    private void onCountdown(Map<String, Object> message) {
        if (presenter == null) {
            return;
        }
        // 全桌都會收到，沒有要自己決定時就是在等別人宣告
        if (!waiting && game.phase() == Round.Phase.CLAIMS) {
            presenter.showIdle("等待其他玩家決定…");
        }
        Object seconds = message.get("seconds");
        presenter.secondsLeft(seconds instanceof Long ? ((Long) seconds).intValue() : 0);
    }

    private void onRoundEnd(Map<String, Object> message) {
        if (game == null) {
            return;
        }
        Map<String, Object> state = finishedState;
        finishedState = null;
        if (message.equals(lastRoundEnd)) {
            return;
        }
        lastRoundEnd = message;
        List<RoundEvent> events = takeEvents();
        if (state != null) {
            game.applyState(state, events);
        }
        OnlineGame.RoundEnd end = game.applyRoundEnd(message);
        bindRoundOnce();
        dealPending = false;
        waiting = false;
        finishing = end.gameOver();
        view.refresh(game, game);
        playEvents(events);
        presenter.showIdle(" ");
        host.refreshHistory();
        int serial = roundSerial;
        // 有動畫時先讓胡牌的大字演完，再跳出結果；大胡演得比較久。台數在上面的 applyRoundEnd 就有了
        int bannerMs = settings.animationEnabled() ? TableCanvas.endBannerMs(game.result()) : 0;
        TablePresenter.after(bannerMs, () -> showResult(end, serial, bannerMs));
    }

    /**
     * 結果視窗是 modal，開著的時候訊息還是會進來，所以關掉之後要先看局面是不是已經往下走了。
     * 服務器從送出 roundEnd 開始算下一局的時間，大字演掉的部分要扣掉。
     */
    private void showResult(OnlineGame.RoundEnd end, int serial, int bannerMs) {
        if (shutDown || serial != roundSerial || game == null) {
            return;
        }
        OnlineGame shown = game;
        int seconds = end.gameOver() ? RESULT_SECONDS : Math.max(1, (end.nextRoundMs() - bannerMs + 999) / 1000);
        openDialog = new ResultDialog(host.frame(), game, game, end.settlement(), List.of(),
                end.gameOver() ? "查看最終排名" : "下一局", seconds);
        openDialog.setVisible(true);
        openDialog = null;
        if (shutDown || game != shown || serial != roundSerial) {
            return;
        }
        if (end.gameOver()) {
            showFinal(shown);
        } else {
            client.ready();
            presenter.showIdle("等其他玩家看完結果…");
        }
    }

    private void onGameOver(Map<String, Object> message) {
        if (game != null) {
            game.applyGameOver(message);
        }
        forgetGame();
    }

    /** 看完最後的成績就回到房間，可以再開一場。 */
    private void showFinal(OnlineGame shown) {
        if (shown.isOver()) {
            openDialog = new FinalDialog(host.frame(), shown, "", List.of(), "回到房間");
            openDialog.setVisible(true);
            openDialog = null;
        }
        if (shutDown || game != shown) {
            return;
        }
        finishing = false;
        closeTable();
        if (room != null && !room.playing()) {
            showRoom();
        }
    }

    @Override
    public RoundView round() {
        return game;
    }

    @Override
    public GameView game() {
        return game;
    }

    @Override
    public List<Tile> listeningWaits() {
        return game.listeningWaits();
    }

    @Override
    public boolean isAutoPlay() {
        return false;
    }

    @Override
    public boolean isWaiting(int id) {
        return waiting && id == waitId;
    }

    /** 送出之後就收起按鈕，不會再按一次。局面要等服務器的下一份。 */
    private boolean sending(int id) {
        if (!isWaiting(id)) {
            return false;
        }
        waiting = false;
        presenter.showIdle(" ");
        return true;
    }

    @Override
    public void discard(int id, Tile tile) {
        if (sending(id)) {
            client.discard(id, tile);
        }
    }

    /** 不屬於哪一次等待，過期的編號服務器會忽略。成立時全桌會收到 LISTEN 事件。 */
    @Override
    public void declareListening(int listenId) {
        client.declareListen(listenId);
    }

    @Override
    public void declareSelfWin(int id) {
        if (sending(id)) {
            client.selfWin(id);
        }
    }

    @Override
    public void declareKong(int id, Tile tile) {
        if (sending(id)) {
            client.kong(id, tile);
        }
    }

    @Override
    public void answerClaim(int id, Claim claim) {
        if (!sending(id)) {
            return;
        }
        if (claim == null) {
            client.pass(id);
        } else {
            client.claim(id, claimOptions.indexOf(claim));
        }
    }
}
