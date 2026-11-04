package ezmj.online;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ezmj.model.Tile;

/**
 * 到 EZMJ Server 的連線，協定見服務器的 README。走 TCP 時一行一則 UTF-8 JSON，走 WebSocket 時一則文字訊息一則 JSON。
 * 這裡只管連線：登入、閒置時送 ping、保存 token、斷線後自己重連。訊息內容交給上層看。
 * 讀寫各一條執行緒，通知都透過建構時給的 Executor，畫面給 EDT 就不用自己換執行緒。
 */
public final class OnlineClient {

    /** 要和服務器的 Protocol.VERSION 一樣，對不起來服務器會拒絕登入。 */
    public static final int PROTOCOL = 1;

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** 免費主機閒置後會睡著，第一個連線要等它醒來，最久大約一分鐘。 */
    private static final int WEB_SOCKET_OPEN_TIMEOUT_MS = 90_000;
    /** 服務器 60 秒沒收到訊息就斷線，所以閒置時要比這個頻繁許多。 */
    private static final int PING_MS = 20_000;
    /** ping 都有回應，這段時間什麼都沒收到就是斷了，例如對方電腦關機時不會有任何通知。 */
    private static final int READ_TIMEOUT_MS = 50_000;
    private static final int[] RETRY_DELAYS_MS = {500, 1_000, 2_000, 4_000, 5_000};
    /** 全桌都斷線 2 分鐘服務器就關房間，再等也回不去，所以比那久一點就放棄。 */
    private static final int RETRY_FOR_MS = 180_000;
    private static final int MAX_LINE_BYTES = 1024 * 1024;
    private static final int MAX_QUEUED = 1024;

    public static final Timing NORMAL = new Timing(PING_MS, READ_TIMEOUT_MS, RETRY_DELAYS_MS, RETRY_FOR_MS);

    /** 時間設定，單位都是毫秒。測試會用比較短的。 */
    public record Timing(int pingMs, int readTimeoutMs, int[] retryDelaysMs, int retryForMs) {
    }

    /** 通知都在建構時給的 Executor 上，依序執行。自己呼叫 close 之後不再通知。 */
    public interface Listener {
        /** 登入成功。resumed 表示是用 token 接回來的，接著會收到服務器補送的局面。 */
        void loggedIn(Map<String, Object> welcome, boolean resumed);

        /** 服務器送來的訊息，pong 與 rooms 不算。只看大廳時服務器的錯誤也從這裡來，例如舊服務器不認得 listRooms。 */
        void message(Map<String, Object> message);

        /** 大廳的房間列表，回應 listRooms。 */
        void rooms(List<RoomListing> rooms);

        /** 連線斷了，正在重新連線。接回來之前送出的訊息都會丟掉，服務器會補送還在等的詢問。 */
        void connectionLost();

        /** 服務器不接受登入，不會再重試。token 失效的 code 是 unknownToken。 */
        void loginRejected(String code, String message);

        /** 連不上或重連放棄了，不會再重試。 */
        void closed(String reason);

        /** 收到看不懂的一行，已經略過。 */
        void badMessage(String line, RuntimeException error);
    }

    private final ServerAddress address;
    private final Listener listener;
    private final Executor callbacks;
    private final Timing timing;
    private final Object lock = new Object();

    private Link link;
    private volatile String token;
    private boolean finished;
    private boolean reconnecting;
    private boolean lostReported;
    private long retryDeadlineNanos;
    /** 用 browse 連的，不會登入，訊息直接往上傳。 */
    private volatile boolean browsing;
    /** 自己呼叫過 close，不要再通知。 */
    private volatile boolean silenced;

    public OnlineClient(ServerAddress address, Listener listener, Executor callbacks) {
        this(address, listener, callbacks, NORMAL);
    }

    public OnlineClient(ServerAddress address, Listener listener, Executor callbacks, Timing timing) {
        this.address = address;
        this.listener = listener;
        this.callbacks = callbacks;
        this.timing = timing;
    }

    public ServerAddress address() {
        return address;
    }

    /** 服務器發的身分，還沒登入時是 null。不能給別人。 */
    public String token() {
        return token;
    }

    /** 用名字登入，第一次進服務器時用。 */
    public void loginWithName(String name) {
        connect(Fields.object("type", "hello", "protocol", PROTOCOL, "name", name));
    }

    /** 用之前的 token 接回來。 */
    public void loginWithToken(String savedToken) {
        token = savedToken;
        connect(Fields.object("type", "hello", "protocol", PROTOCOL, "token", savedToken));
    }

    /**
     * 不登入，只看大廳：連上就送 listRooms，之後要更新列表再呼叫 listRooms。
     * 沒有 token，所以斷線不重連，直接通知 closed。要開房或加入時另外開一個登入的連線。
     */
    public void browse() {
        browsing = true;
        connect(Fields.object("type", "listRooms"));
    }

    private void connect(Map<String, Object> first) {
        background("ezmj-connect", () -> {
            try {
                install(open()).send(first);
            } catch (IOException e) {
                finish(() -> listener.closed("連不上服務器 " + address + "：" + e.getMessage()));
            }
        });
    }

    /** 要大廳的房間列表，不用登入，回應從 Listener.rooms 通知。 */
    public void listRooms() {
        send(Fields.object("type", "listRooms"));
    }

    public void createRoom(RoomSettings settings) {
        Map<String, Object> message = settings.toMessage();
        message.put("type", "createRoom");
        send(message);
    }

    public void joinRoom(String code) {
        send(Fields.object("type", "joinRoom", "code", code));
    }

    public void leave() {
        send(Fields.object("type", "leave"));
    }

    public void start() {
        send(Fields.object("type", "start"));
    }

    public void ready() {
        send(Fields.object("type", "ready"));
    }

    public void discard(int waitId, Tile tile) {
        send(Fields.object("type", "discard", "waitId", waitId, "tile", tile.code()));
    }

    /** 舊的報聽：打出這張並報聽。新的畫面打完牌才報，用 declareListen；這個留給還在用的程式。 */
    public void listen(int waitId, Tile tile) {
        send(Fields.object("type", "listen", "waitId", waitId, "tile", tile.code()));
    }

    /** 報聽，listenId 是服務器 canListen 給的編號，過期了服務器會忽略。 */
    public void declareListen(int listenId) {
        send(Fields.object("type", "declareListen", "listenId", listenId));
    }

    /** 房間裡的聊天，要先在房間裡。服務器會轉給房間裡每一位連著的真人，也包括自己。 */
    public void chat(String text) {
        send(Fields.object("type", "chat", "text", text));
    }

    public void selfWin(int waitId) {
        send(Fields.object("type", "selfWin", "waitId", waitId));
    }

    public void kong(int waitId, Tile tile) {
        send(Fields.object("type", "kong", "waitId", waitId, "tile", tile.code()));
    }

    public void claim(int waitId, int option) {
        send(Fields.object("type", "claim", "waitId", waitId, "option", option));
    }

    public void pass(int waitId) {
        send(Fields.object("type", "pass", "waitId", waitId));
    }

    /** 目前有可以用的連線。 */
    public boolean isConnected() {
        synchronized (lock) {
            return link != null && link.loggedIn;
        }
    }

    /** 斷線中就丟掉：服務器牌局不等人，接回來時會補送還在等的詢問。 */
    private void send(Map<String, Object> message) {
        Link current;
        synchronized (lock) {
            current = link;
        }
        if (current != null) {
            current.send(message);
        }
    }

    /** 結束這個連線，不再重連也不再通知。 */
    public void close() {
        silenced = true;
        Link current;
        synchronized (lock) {
            finished = true;
            current = link;
            link = null;
        }
        if (current != null) {
            current.close();
        }
    }

    private Link open() throws IOException {
        if (address.webSocket() != null) {
            return new Link(WebSocketTransport.open(address.webSocket(), timing.readTimeoutMs()));
        }
        return new Link(TcpTransport.open(address, timing.readTimeoutMs()));
    }

    /** 這條連線開始用。已經 close 的話就直接關掉，呼叫端不用分辨。 */
    private Link install(Link next) {
        boolean keep;
        synchronized (lock) {
            keep = !finished;
            if (keep) {
                link = next;
            }
        }
        if (keep) {
            next.start();
        } else {
            next.close();
        }
        return next;
    }

    private void linkLost(Link lost) {
        synchronized (lock) {
            if (lost != link || finished) {
                return;
            }
            link = null;
            if (token == null) {
                finished = true;
            } else if (!reconnecting) {
                reconnecting = true;
                if (!lostReported) {
                    lostReported = true;
                    retryDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timing.retryForMs());
                    notifyListener(listener::connectionLost);
                }
                background("ezmj-reconnect", this::reconnectLoop);
                return;
            } else {
                return;
            }
        }
        notifyListener(() -> listener.closed("連線中斷"));
    }

    private void reconnectLoop() {
        int attempt = 0;
        while (true) {
            int[] delays = timing.retryDelaysMs();
            try {
                Thread.sleep(delays[Math.min(attempt++, delays.length - 1)]);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (lock) {
                if (finished) {
                    return;
                }
                if (System.nanoTime() - retryDeadlineNanos > 0) {
                    reconnecting = false;
                    break;
                }
            }
            Link next;
            try {
                next = open();
            } catch (IOException e) {
                continue;
            }
            synchronized (lock) {
                reconnecting = false;
            }
            install(next).send(Fields.object("type", "hello", "protocol", PROTOCOL, "token", token));
            return;
        }
        finish(() -> listener.closed("重新連線失敗，連不上服務器 " + address));
    }

    /** 不再重試，然後通知上層。 */
    private void finish(Runnable notice) {
        Link current;
        synchronized (lock) {
            finished = true;
            current = link;
            link = null;
        }
        if (current != null) {
            current.close();
        }
        notifyListener(notice);
    }

    private void notifyListener(Runnable notice) {
        callbacks.execute(() -> {
            if (!silenced) {
                notice.run();
            }
        });
    }

    private static void background(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void received(Link from, String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            Object parsed = Fields.withoutHtml(Json.parse(line));
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("訊息要是 JSON 物件");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) parsed;
            dispatch(from, message);
        } catch (IllegalArgumentException e) {
            notifyListener(() -> listener.badMessage(line, e));
        }
    }

    private void dispatch(Link from, Map<String, Object> message) {
        String type = Fields.string(message, "type");
        if (type.equals("pong")) {
            return;
        }
        if (type.equals("rooms")) {
            List<RoomListing> rooms = RoomListing.parse(message);
            notifyListener(() -> listener.rooms(rooms));
        } else if (from.loggedIn || browsing) {
            notifyListener(() -> listener.message(message));
        } else if (type.equals("welcome")) {
            welcomed(from, message);
        } else if (type.equals("error")) {
            String code = Fields.string(message, "code");
            String reason = Fields.string(message, "message");
            finish(() -> listener.loginRejected(code, reason));
        }
    }

    private void welcomed(Link from, Map<String, Object> welcome) {
        boolean resumed = token != null;
        token = Fields.string(welcome, "token");
        from.loggedIn = true;
        synchronized (lock) {
            lostReported = false;
        }
        notifyListener(() -> listener.loggedIn(welcome, resumed));
    }

    /** 一條連線。斷了就換一個新的。 */
    private final class Link {
        private final Transport transport;
        private final BlockingQueue<String> outgoing = new LinkedBlockingQueue<>(MAX_QUEUED);
        private final Thread writer = new Thread(this::writeLoop, "ezmj-client-write");
        private final Thread reader = new Thread(this::readLoop, "ezmj-client-read");
        /** 收到 welcome 時由讀取執行緒改成 true。 */
        private volatile boolean loggedIn;

        Link(Transport transport) {
            this.transport = transport;
            writer.setDaemon(true);
            reader.setDaemon(true);
        }

        void start() {
            reader.start();
            writer.start();
        }

        void send(Map<String, Object> message) {
            if (!outgoing.offer(Json.write(message))) {
                close();
            }
        }

        void close() {
            transport.close();
            writer.interrupt();
        }

        private void readLoop() {
            try {
                String line;
                while ((line = transport.read()) != null) {
                    received(this, line);
                }
            } catch (IOException e) {
                // 對方斷線或太久沒有訊息，都當作斷線
            } finally {
                close();
                linkLost(this);
            }
        }

        /** 閒置超過 pingMs 就送 ping，服務器才知道還連著。 */
        private void writeLoop() {
            String ping = Json.write(Fields.object("type", "ping"));
            try {
                while (true) {
                    String line = outgoing.poll(timing.pingMs(), TimeUnit.MILLISECONDS);
                    transport.write(line == null ? ping : line);
                }
            } catch (IOException | InterruptedException e) {
                // 連線已經斷了，close 會讓讀取執行緒收尾
            } finally {
                close();
            }
        }
    }

    /** 收發一則一則的訊息，訊息都不含結尾的換行。 */
    private interface Transport {
        /** 等下一則訊息。對方關閉或訊息太長時回傳 null，太久沒有訊息時丟 IOException。 */
        String read() throws IOException;

        void write(String message) throws IOException, InterruptedException;

        /** 可以重複呼叫，也會讓等在 read 的執行緒結束。 */
        void close();
    }

    /** 一行一則 UTF-8 JSON，以 \n 結尾。 */
    private static final class TcpTransport implements Transport {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        private TcpTransport(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        static Transport open(ServerAddress address, int readTimeoutMs) throws IOException {
            Socket socket = new Socket();
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(readTimeoutMs);
                socket.connect(new InetSocketAddress(address.host(), address.port()), CONNECT_TIMEOUT_MS);
                return new TcpTransport(socket);
            } catch (IOException | RuntimeException e) {
                socket.close();
                if (e instanceof IOException) {
                    throw (IOException) e;
                }
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public String read() throws IOException {
            line.reset();
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    return line.toString(StandardCharsets.UTF_8);
                } else if (line.size() >= MAX_LINE_BYTES) {
                    return null;
                }
                line.write(b);
            }
            return null;
        }

        @Override
        public void write(String message) throws IOException {
            out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException e) {
                // 本來就要關了
            }
        }
    }

    /** 一則文字訊息是一則 JSON，不用換行。TLS 由 JDK 處理，要走 wss 打包時就要帶上 jdk.crypto.ec。 */
    private static final class WebSocketTransport implements Transport, WebSocket.Listener {
        /** 放進 incoming 表示不會再有訊息，只比較是不是同一個物件。 */
        private static final String END = new String("end");
        /** 第一次用到才建，只走 TCP 的話不會碰到 java.net.http。 */
        private static final HttpClient HTTP =
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS)).build();

        private final int readTimeoutMs;
        /** 讀走一則才跟 JDK 要下一則，所以這裡最多只有一則訊息和 END。 */
        private final BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        /** 只有 JDK 的通知會用到，它保證一次一個。 */
        private final StringBuilder partial = new StringBuilder();
        /** open 回傳之前就設好了，之後才有別的執行緒用這個物件。 */
        private WebSocket socket;

        private WebSocketTransport(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        static Transport open(URI url, int readTimeoutMs) throws IOException {
            WebSocketTransport transport = new WebSocketTransport(readTimeoutMs);
            try {
                transport.socket = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofMillis(WEB_SOCKET_OPEN_TIMEOUT_MS))
                        .buildAsync(url, transport).get();
                return transport;
            } catch (ExecutionException e) {
                throw new IOException(openFailure(e.getCause()), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("連線被中斷");
            }
        }

        /** 這些例外常常沒有訊息，直接顯示給玩家只會看到 null。 */
        private static String openFailure(Throwable cause) {
            if (cause instanceof WebSocketHandshakeException) {
                return "服務器回應 HTTP " + ((WebSocketHandshakeException) cause).getResponse().statusCode();
            }
            return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        }

        @Override
        public String read() throws IOException {
            String message;
            try {
                message = incoming.poll(readTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("連線被中斷");
            }
            if (message == null) {
                throw new SocketTimeoutException("太久沒有訊息");
            }
            if (message == END) {
                return null;
            }
            socket.request(1);
            return message;
        }

        @Override
        public void write(String message) throws IOException, InterruptedException {
            try {
                // 上一則送完才能送下一則，所以等它送完
                socket.sendText(message, true).get();
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            }
        }

        @Override
        public void close() {
            incoming.add(END);
            // 網路已經斷了的話 Close 訊框永遠送不完，不能一直等
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(1, TimeUnit.SECONDS)
                    .whenComplete((sent, error) -> socket.abort());
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > MAX_LINE_BYTES) {
                incoming.add(END);
            } else if (last) {
                incoming.add(partial.toString());
                partial.setLength(0);
            } else {
                webSocket.request(1);
            }
            return null;
        }

        /** 協定只用文字訊息，對方送這個就是有問題。服務器收到二進位訊息也是直接斷線。 */
        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            incoming.add(END);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            incoming.add(END);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            incoming.add(END);
        }
    }
}
