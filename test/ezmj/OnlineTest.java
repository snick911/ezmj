package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.swing.plaf.basic.BasicHTML;

import ezmj.engine.Claim;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundView;
import ezmj.engine.WallRing;
import ezmj.model.BotLevel;
import ezmj.model.Dice;
import ezmj.model.GameSpeed;
import ezmj.model.Gender;
import ezmj.model.Meld;
import ezmj.online.Json;
import ezmj.online.OnlineClient;
import ezmj.online.OnlineGame;
import ezmj.online.OnlineProfile;
import ezmj.online.RoomInfo;
import ezmj.online.RoomListing;
import ezmj.online.RoomSettings;
import ezmj.online.ServerAddress;

/** 連線層：走真的 TCP 與 WebSocket，對面是測試自己開的假服務器；局面的轉換直接餵服務器格式的訊息。 */
final class OnlineTest {

    private static final OnlineClient.Timing FAST =
            new OnlineClient.Timing(100, 2_000, new int[] {20, 50}, 3_000);

    private OnlineTest() {
    }

    static void run() {
        T.section("連線");
        address();
        json();
        profile();
        client(false);
        client(true);
        webSocket();
        room();
        game();
    }

    private static void address() {
        test("位址：沒寫 port 用 7788", () -> eq(new ServerAddress("example.com", 7788), ServerAddress.parse(" example.com "), "位址"));
        test("位址：host:port", () -> eq(new ServerAddress("10.0.0.1", 9000), ServerAddress.parse("10.0.0.1:9000"), "位址"));
        test("位址：IPv6", () -> {
            eq(new ServerAddress("::1", 7788), ServerAddress.parse("[::1]"), "沒有 port");
            eq(new ServerAddress("::1", 9000), ServerAddress.parse("[::1]:9000"), "有 port");
        });
        test("位址：寫錯要拒絕", () -> {
            for (String bad : List.of("", ":7788", "host:", "host:0", "host:99999", "host:abc", "::1", "[::1", "[::1]x")) {
                fails(IllegalArgumentException.class, () -> ServerAddress.parse(bad), bad);
            }
        });
        test("位址：host:port 走 TCP", () -> check(ServerAddress.parse("example.com:9000").webSocket() == null, "沒有網址"));
        test("位址：wss 網址留著路徑，預設 port 443", () -> {
            ServerAddress address = ServerAddress.parse(" wss://ezmj-server.onrender.com/ws ");
            eq("ezmj-server.onrender.com", address.host(), "主機");
            eq(443, address.port(), "port");
            eq("wss://ezmj-server.onrender.com/ws", address.webSocket().toString(), "網址");
            eq("wss://ezmj-server.onrender.com/ws", address.toString(), "顯示網址");
        });
        test("位址：ws 網址預設 port 80，可以自己寫 port", () -> {
            eq(80, ServerAddress.parse("ws://example.com/ws").port(), "預設");
            eq(9000, ServerAddress.parse("ws://example.com:9000/ws").port(), "自己寫");
            eq("/a/b", ServerAddress.parse("ws://example.com/a/b").webSocket().getPath(), "路徑");
            check(ServerAddress.parse("WSS://example.com/ws").webSocket() != null, "大小寫都可以");
        });
        test("位址：網址裡的 IPv6", () -> {
            ServerAddress address = ServerAddress.parse("ws://[::1]:9000/ws");
            eq("::1", address.host(), "主機沒有括號");
            eq(9000, address.port(), "port");
            eq("ws://[::1]:9000/ws", address.toString(), "顯示網址");
        });
        test("位址：網址寫錯要拒絕", () -> {
            for (String bad : List.of("http://example.com", "https://example.com/ws", "ws://", "ws://:80/ws",
                    "ws://example.com:/ws", "ws://example.com:0/ws", "ws://example.com:99999/ws", "ws://example.com:abc/ws",
                    "ws://me@example.com/ws", "ws://example.com/ws#top", "ws://exa mple.com/ws")) {
                fails(IllegalArgumentException.class, () -> ServerAddress.parse(bad), bad);
            }
        });
    }

    private static void json() {
        test("JSON：來回一樣，中文不跳脫", () -> {
            String text = "{\"a\":1,\"b\":[true,null,\"小明\\n\\\"\"],\"c\":{\"d\":-2}}";
            eq(text, Json.write(Json.parse(text)), "來回");
        });
        test("JSON：整數是 Long", () -> eq(7L, Json.parse("7"), "整數"));
        test("JSON：壞掉的內容要拒絕", () -> {
            for (String bad : List.of("", "{", "[1,]", "{\"a\"}", "tru", "01", "\"abc", "1 2")) {
                fails(IllegalArgumentException.class, () -> Json.parse(bad), bad);
            }
        });
    }

    private static void profile() {
        test("連線資料：沒有檔案就用預設值", () -> {
            Path dir = Files.createTempDirectory("ezmj-online");
            OnlineProfile fresh = OnlineProfile.load(dir.resolve("none.properties"));
            eq("", fresh.name(), "還沒有名字");
            check(!fresh.canResume(), "沒有可以接回的牌局");
        });
        test("連線資料：存了讀得回來，牌局結束就清掉 token", () -> {
            Path file = Files.createTempDirectory("ezmj-online").resolve("sub").resolve("online.properties");
            OnlineProfile saved = OnlineProfile.load(file).withName("小明").withGame("secret", "ABC234");
            saved.save(file);
            OnlineProfile loaded = OnlineProfile.load(file);
            eq(saved, loaded, "讀回來");
            check(loaded.canResume(), "可以接回");
            loaded.withoutGame().save(file);
            OnlineProfile cleared = OnlineProfile.load(file);
            eq("小明", cleared.name(), "名字留著");
            check(!cleared.canResume() && cleared.token() == null, "token 清掉了");
        });
        test("連線資料：舊版檔案裡的服務器不會被拿來用，也不再存檔", () -> {
            Path file = Files.createTempDirectory("ezmj-online").resolve("online.properties");
            Files.writeString(file, "server=127.0.0.1\\:7788\nname=ming\n", StandardCharsets.ISO_8859_1);
            OnlineProfile old = OnlineProfile.load(file);
            eq("ming", old.name(), "名字還在");
            old.save(file);
            check(!Files.readString(file, StandardCharsets.ISO_8859_1).contains("server"), "檔案裡沒有服務器");
        });
        test("服務器：固定連 Render，只有測試能換", () -> {
            String before = System.getProperty(ServerAddress.TEST_OVERRIDE);
            try {
                System.clearProperty(ServerAddress.TEST_OVERRIDE);
                ServerAddress fixed = ServerAddress.current();
                eq("wss://ezmj-server.onrender.com/ws", fixed.toString(), "預設是 Render");
                eq("wss", fixed.webSocket().getScheme(), "走加密的 WebSocket");
                eq(443, fixed.port(), "port");
                System.setProperty(ServerAddress.TEST_OVERRIDE, "127.0.0.1:7788");
                eq("127.0.0.1:7788", ServerAddress.current().toString(), "測試可以換");
            } finally {
                if (before == null) {
                    System.clearProperty(ServerAddress.TEST_OVERRIDE);
                } else {
                    System.setProperty(ServerAddress.TEST_OVERRIDE, before);
                }
            }
        });
    }

    /** 兩種連線的行為要一樣，所以同一批測試各跑一次。 */
    private static void client(boolean webSocket) {
        String name = webSocket ? "WebSocket 客戶端：" : "客戶端：";
        test(name + "用名字登入，保存 token", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                probe.client.loginWithName("小明");
                Map<String, Object> hello = server.accept().next("hello");
                eq(1L, hello.get("protocol"), "協定版本");
                eq("小明", hello.get("name"), "UTF-8 的名字");
                check(!hello.containsKey("token"), "第一次不帶 token");
                server.current().send("{\"type\":\"welcome\",\"token\":\"t1\",\"room\":null}");
                eq("loggedIn:false", probe.next(), "登入成功");
                eq("t1", probe.client.token(), "保存 token");
            }
        });
        test(name + "收到的文字不會被 Swing 當成 HTML", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                connection.send("{\"type\":\"room\",\"name\":\"<html><h1>x\",\"note\":\"第一行\\n<HTML><img src=x>\","
                        + "\"players\":[{\"name\":\"<HtMl>y\"}],\"plain\":\"小明 <b>不是開頭\",\"seat\":2}");
                eq("message:room", probe.next(), "收到訊息");
                Map<String, Object> message = probe.lastMessage;
                plainOnScreen((String) message.get("name"), "<html><h1>x");
                plainOnScreen((String) message.get("note"), "第一行\n<HTML><img src=x>");
                Map<?, ?> player = (Map<?, ?>) ((List<?>) message.get("players")).get(0);
                plainOnScreen((String) player.get("name"), "<HtMl>y");
                eq("小明 <b>不是開頭", message.get("plain"), "沒有 <html> 的文字不動");
                eq(2L, message.get("seat"), "數字不動");
            }
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                probe.client.loginWithName("小明");
                server.accept().next("hello");
                server.current().send("{\"type\":\"error\",\"code\":\"x\",\"message\":\"不行\\n<html><img src=x>\"}");
                eq("rejected:x", probe.next(), "拒絕登入");
                plainOnScreen(probe.lastRejection, "不行\n<html><img src=x>");
            }
        });
        test(name + "決定都帶 waitId，訊息依序送出", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                probe.client.discard(5, tile("9s"));
                probe.client.listen(6, tile("1z"));
                probe.client.selfWin(7);
                probe.client.kong(8, tile("7z"));
                probe.client.claim(9, 2);
                probe.client.pass(10);
                probe.client.ready();
                eq("{\"type\":\"discard\",\"waitId\":5,\"tile\":\"9s\"}", connection.nextLine(), "打牌");
                eq("{\"type\":\"listen\",\"waitId\":6,\"tile\":\"1z\"}", connection.nextLine(), "聽牌");
                eq("{\"type\":\"selfWin\",\"waitId\":7}", connection.nextLine(), "自摸");
                eq("{\"type\":\"kong\",\"waitId\":8,\"tile\":\"7z\"}", connection.nextLine(), "槓");
                eq("{\"type\":\"claim\",\"waitId\":9,\"option\":2}", connection.nextLine(), "宣告");
                eq("{\"type\":\"pass\",\"waitId\":10}", connection.nextLine(), "過");
                eq("{\"type\":\"ready\"}", connection.nextLine(), "看完結果");
            }
        });
        test(name + "開房間帶上設定，加入帶房號", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                probe.client.createRoom(new RoomSettings(500, 10, 2,
                        List.of(BotLevel.PRO, BotLevel.BEGINNER, BotLevel.AMATEUR), 15, GameSpeed.FAST, "", false));
                Map<String, Object> create = connection.next("createRoom");
                eq(500L, create.get("chips"), "籌碼");
                eq(10L, create.get("basePay"), "底");
                eq(2L, create.get("taiPay"), "每台");
                eq(List.of("PRO", "BEGINNER", "AMATEUR"), create.get("botLevels"), "電腦等級");
                eq(15L, create.get("waitSeconds"), "等待秒數");
                eq("FAST", create.get("speed"), "速度");
                eq("", create.get("name"), "沒填名稱也送，由服務器取");
                eq(false, create.get("private"), "預設公開");
                eq(false, create.get("expert"), "預設不是專家模式");
                probe.client.createRoom(new RoomSettings(500, 10, 2,
                        List.of(BotLevel.PRO, BotLevel.BEGINNER, BotLevel.AMATEUR), 15, GameSpeed.FAST, "週五牌搭子", true,
                        true));
                Map<String, Object> named = connection.next("createRoom");
                eq("週五牌搭子", named.get("name"), "房間名稱");
                eq(true, named.get("private"), "私人房間");
                eq(true, named.get("expert"), "專家模式");
                probe.client.joinRoom("ABC234");
                Map<String, Object> join = connection.next("joinRoom");
                eq("ABC234", join.get("code"), "房號");
                // 服務器 0.7.0 起沒帶這個就不讓加入專家模式的房間
                eq(List.of("expert"), join.get("supports"), "看得懂專家模式");
            }
        });
        test(name + "只看大廳：不登入，第一則就是 listRooms，之後再要一次", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                probe.client.browse();
                FakeConnection connection = server.accept();
                eq("{\"type\":\"listRooms\"}", connection.nextLine(), "沒有 hello");
                connection.send(rooms(LISTING, LISTING.replace("ABC234", "XYZ789")
                        .replace("\"name\":\"週五牌搭子\",\"host\":\"小明\"", "\"name\":\"<html><b>x\",\"host\":\"<HTML>y\"")));
                eq("rooms:2", probe.next(), "收到列表");
                eq("XYZ789", probe.lastRooms.get(1).code(), "照服務器的順序");
                plainOnScreen(probe.lastRooms.get(1).name(), "<html><b>x");
                plainOnScreen(probe.lastRooms.get(1).host(), "<HTML>y");
                check(probe.client.token() == null, "沒有登入");
                probe.client.listRooms();
                connection.listRooms();
                eq("rooms:1", probe.next(), "再要一次");
            }
        });
        test(name + "只看大廳：舊服務器不認得 listRooms，錯誤往上傳，不當成登入失敗", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                probe.client.browse();
                FakeConnection connection = server.accept();
                connection.next("listRooms");
                connection.send("{\"type\":\"error\",\"code\":\"notLoggedIn\",\"message\":\"要先送 hello\"}");
                eq("message:error", probe.next(), "收到錯誤");
                eq("notLoggedIn", probe.lastMessage.get("code"), "錯誤的代碼");
                probe.client.listRooms();
                connection.listRooms();
                eq("rooms:1", probe.next(), "連線還在");
            }
        });
        test(name + "只看大廳：斷線就通知，不重連", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                probe.client.browse();
                server.accept().close();
                check(probe.next().startsWith("closed:"), "通知斷線");
                check(!server.acceptsAnother(300), "沒有 token 不重連");
            }
        });
        test(name + "登入之後也能要列表，看不懂的列表就略過", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                probe.client.listRooms();
                connection.next("listRooms");
                connection.send(rooms(LISTING.replace("\"humans\":2,", "")));
                eq("bad", probe.next(), "少了人數");
                connection.send(rooms());
                eq("rooms:0", probe.next(), "沒有房間");
                check(probe.client.isConnected(), "連線還在");
            }
        });
        test(name + "報聽與聊天的訊息", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                probe.client.declareListen(7);
                probe.client.chat("你好");
                eq("{\"type\":\"declareListen\",\"listenId\":7}", connection.nextLine(), "報聽");
                eq("{\"type\":\"chat\",\"text\":\"你好\"}", connection.nextLine(), "聊天");
                connection.send("{\"type\":\"chat\",\"seat\":1,\"name\":\"<html><b>阿土\",\"text\":\"<HTML>嗨\"}");
                eq("message:chat", probe.next(), "收到聊天走一般的訊息");
                plainOnScreen((String) probe.lastMessage.get("name"), "<html><b>阿土");
                plainOnScreen((String) probe.lastMessage.get("text"), "<HTML>嗨");
            }
        });
        test(name + "閒置時定期送 ping，pong 不往上傳", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                connection.next("ping");
                connection.next("ping");
                connection.send("{\"type\":\"pong\"}");
                connection.send("{\"type\":\"countdown\",\"seconds\":3}");
                eq("message:countdown", probe.next(), "pong 被吃掉，後面的照收");
            }
        });
        test(name + "看不懂的一行就略過，連線不斷", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                connection.send("{壞掉");
                connection.send("[1,2]");
                connection.send("{\"type\":\"countdown\",\"seconds\":3}");
                eq("bad", probe.next(), "壞的 JSON");
                eq("bad", probe.next(), "不是物件");
                eq("message:countdown", probe.next(), "後面的照收");
                check(probe.client.isConnected(), "連線還在");
            }
        });
        test(name + "斷線後用 token 重連，只通知一次", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection first = probe.loggedIn();
                first.close();
                eq("lost", probe.next(), "通知斷線");
                FakeConnection second = server.accept();
                Map<String, Object> hello = second.next("hello");
                eq("t1", hello.get("token"), "帶 token");
                check(!hello.containsKey("name"), "不再帶名字");
                // 接上了還沒登入又斷一次，還是同一次斷線
                second.close();
                FakeConnection third = server.accept();
                third.next("hello");
                third.send("{\"type\":\"welcome\",\"token\":\"t1\",\"room\":\"ABC234\"}");
                eq("loggedIn:true", probe.next(), "接回來了");
                third.send("{\"type\":\"state\"}");
                eq("message:state", probe.next(), "補送的局面");
                check(probe.client.isConnected(), "連線還在");
            }
        });
        test(name + "斷線時送的決定直接丟掉", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection first = probe.loggedIn();
                first.close();
                eq("lost", probe.next(), "通知斷線");
                probe.client.discard(1, tile("1w"));
                FakeConnection second = server.accept();
                eq("hello", ((Map<?, ?>) Json.parse(second.nextLine())).get("type"), "新連線第一則是 hello");
            }
        });
        test(name + "token 失效不再重試", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection first = probe.loggedIn();
                first.close();
                eq("lost", probe.next(), "通知斷線");
                FakeConnection second = server.accept();
                second.next("hello");
                second.send("{\"type\":\"error\",\"code\":\"unknownToken\",\"message\":\"失效\"}");
                eq("rejected:unknownToken", probe.next(), "通知失效");
                check(!server.acceptsAnother(300), "不再重連");
            }
        });
        test(name + "用存下來的 token 登入", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                probe.client.loginWithToken("saved");
                eq("saved", server.accept().next("hello").get("token"), "帶 token");
                server.current().send("{\"type\":\"welcome\",\"token\":\"saved\",\"room\":\"ABC234\"}");
                eq("loggedIn:true", probe.next(), "是接回來的");
            }
        });
        test(name + "一直連不上，重試到期限才通知", () -> {
            OnlineClient.Timing shortRetry = new OnlineClient.Timing(100, 2_000, new int[] {20}, 300);
            try (Probe probe = new Probe(address(webSocket, unusedPort()), shortRetry)) {
                long start = System.nanoTime();
                probe.client.loginWithName("小明");
                check(probe.next().startsWith("closed:"), "連不上");
                check(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(300), "到期限才放棄");
            }
        });
        test(name + "服務器還沒起來就一直重試，起來之後接上", () -> {
            int port = unusedPort();
            try (Probe probe = new Probe(address(webSocket, port))) {
                probe.client.loginWithName("小明");
                Thread.sleep(200);
                try (FakeServer server = new FakeServer(webSocket, port)) {
                    eq("小明", server.accept().next("hello").get("name"), "再送一樣的 hello");
                    server.current().send("{\"type\":\"welcome\",\"token\":\"t1\",\"room\":null}");
                    eq("loggedIn:false", probe.next(), "登入成功");
                }
            }
        });
        test(name + "只看大廳：連不上就通知，不重試", () -> {
            // 期限設得比 Probe 等通知的 5 秒長，有重試的話就等不到
            OnlineClient.Timing longRetry = new OnlineClient.Timing(100, 2_000, new int[] {20}, 10_000);
            try (Probe probe = new Probe(address(webSocket, unusedPort()), longRetry)) {
                probe.client.browse();
                check(probe.next().startsWith("closed:"), "連不上");
            }
        });
        test(name + "登入前就斷線不重試", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                probe.client.loginWithName("小明");
                server.accept().close();
                check(probe.next().startsWith("closed:"), "連線中斷");
                check(!server.acceptsAnother(300), "沒有 token 不重連");
            }
        });
        test(name + "重連放棄後通知", () -> {
            FakeServer server = new FakeServer(webSocket);
            OnlineClient.Timing shortRetry = new OnlineClient.Timing(100, 2_000, new int[] {20}, 200);
            try (Probe probe = new Probe(server, shortRetry)) {
                FakeConnection connection = probe.loggedIn();
                server.close();
                connection.close();
                eq("lost", probe.next(), "通知斷線");
                check(probe.next().startsWith("closed:"), "放棄了");
            }
        });
        test(name + "太久沒收到東西就當作斷線", () -> {
            OnlineClient.Timing shortRead = new OnlineClient.Timing(50, 200, new int[] {20}, 3_000);
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server, shortRead)) {
                probe.loggedIn();
                eq("lost", probe.next(), "對方沒回應");
                eq("t1", server.accept().next("hello").get("token"), "重連");
            }
        });
        test(name + "太長的一行就斷線", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                connection.send("x".repeat(1024 * 1024 + 10));
                eq("lost", probe.next(), "斷線");
            }
        });
        test(name + "自己關掉之後不再通知也不重連", () -> {
            try (FakeServer server = new FakeServer(webSocket); Probe probe = new Probe(server)) {
                FakeConnection connection = probe.loggedIn();
                probe.client.close();
                connection.close();
                check(probe.events.poll(300, TimeUnit.MILLISECONDS) == null, "沒有通知");
                check(!server.acceptsAnother(200), "沒有重連");
            }
        });
    }

    private static void webSocket() {
        test("WebSocket：連到網址的路徑", () -> {
            try (FakeServer server = new FakeServer(true); Probe probe = new Probe(server)) {
                probe.client.loginWithName("小明");
                eq("/ws", ((FakeWebSocket) server.accept()).path, "路徑");
            }
        });
        test("WebSocket：一則訊息分成幾段送來要接起來", () -> {
            try (FakeServer server = new FakeServer(true); Probe probe = new Probe(server)) {
                FakeWebSocket connection = (FakeWebSocket) probe.loggedIn();
                connection.sendFrame(0x01, "{\"type\":\"count".getBytes(StandardCharsets.UTF_8));
                connection.sendFrame(0x00, "down\",".getBytes(StandardCharsets.UTF_8));
                connection.sendFrame(0x80, "\"seconds\":3}".getBytes(StandardCharsets.UTF_8));
                connection.send("{\"type\":\"state\"}");
                eq("message:countdown", probe.next(), "接起來");
                eq("message:state", probe.next(), "下一則");
            }
        });
        test("WebSocket：對方好好關閉也要重連", () -> {
            try (FakeServer server = new FakeServer(true); Probe probe = new Probe(server)) {
                FakeWebSocket connection = (FakeWebSocket) probe.loggedIn();
                connection.sendFrame(0x88, new byte[] {0x03, (byte) 0xe8});
                eq("lost", probe.next(), "通知斷線");
                eq("t1", server.accept().next("hello").get("token"), "重連");
            }
        });
        test("WebSocket：二進位訊息就斷線", () -> {
            try (FakeServer server = new FakeServer(true); Probe probe = new Probe(server)) {
                FakeWebSocket connection = (FakeWebSocket) probe.loggedIn();
                connection.sendFrame(0x82, new byte[] {1, 2, 3});
                eq("lost", probe.next(), "斷線");
            }
        });
        test("WebSocket：服務器不接受升級時說出原因，不重試", () -> {
            try (FakeServer server = new FakeServer(true); Probe probe = new Probe(server)) {
                probe.client.loginWithName("小明");
                server.refuse("404 Not Found");
                String event = probe.next();
                check(event.startsWith("closed:") && event.contains("404"), event);
                check(!server.acceptsAnother(300), "不重試");
            }
        });
        test("WebSocket：升級時回 5xx 是暫時的，會重試", () -> {
            try (FakeServer server = new FakeServer(true); Probe probe = new Probe(server)) {
                probe.client.loginWithName("小明");
                server.refuse("503 Service Unavailable");
                eq("小明", server.accept().next("hello").get("name"), "重試之後送一樣的 hello");
                server.current().send("{\"type\":\"welcome\",\"token\":\"t1\",\"room\":null}");
                eq("loggedIn:false", probe.next(), "登入成功");
            }
        });
    }

    private static void room() {
        test("房間：讀出設定與玩家", () -> {
            RoomInfo info = RoomInfo.parse(parse(ROOM_PLAYING));
            eq("ABC234", info.code(), "房號");
            check(info.playing(), "打牌中");
            check(!info.isHost(), "座位 2 不是房主");
            eq(2, info.yourSeat(), "座位");
            eq(4, info.seats().size(), "四家");
            eq(Gender.MALE, info.seats().get(1).gender(), "電腦的性別");
            check(info.seats().get(0).gender() == null, "真人沒有性別");
            check(!info.seats().get(3).connected(), "座位 3 斷線");
            eq(List.of(BotLevel.NORMAL, BotLevel.PRO, BotLevel.BEGINNER), info.settings().botLevels(), "等級");
            eq(GameSpeed.FAST, info.settings().speed(), "速度");
        });
        test("房間：舊服務器沒有名稱、私人與專家模式，當成空的、公開與一般模式", () -> {
            RoomSettings settings = RoomInfo.parse(parse(ROOM_PLAYING)).settings();
            eq("", settings.name(), "沒有名稱");
            check(!settings.privateRoom(), "公開");
            check(!settings.expert(), "一般模式");
        });
        test("房間：讀出專家模式", () -> {
            check(RoomInfo.parse(parse(ROOM_PLAYING.replace("\"speed\":\"FAST\"", "\"speed\":\"FAST\",\"expert\":true")))
                    .settings().expert(), "專家模式");
            check(!RoomInfo.parse(parse(ROOM_PLAYING.replace("\"speed\":\"FAST\"", "\"speed\":\"FAST\",\"expert\":false")))
                    .settings().expert(), "明說不是");
        });
        test("房間：讀出名稱與私人", () -> {
            RoomSettings settings = RoomInfo.parse(parse(ROOM_PLAYING.replace("\"speed\":\"FAST\"",
                    "\"speed\":\"FAST\",\"name\":\"小明的房間\",\"private\":true"))).settings();
            eq("小明的房間", settings.name(), "名稱");
            check(settings.privateRoom(), "私人");
        });
        test("大廳：讀出房間列表", () -> {
            List<RoomListing> listed = RoomListing.parse(parse(rooms(LISTING, LISTING.replace("ABC234", "XYZ789")
                    .replace("\"humans\":2", "\"humans\":3").replace("\"chips\":5000", "\"chips\":20000"))));
            eq(2, listed.size(), "兩間");
            RoomListing first = listed.get(0);
            eq("ABC234", first.code(), "房號");
            eq("週五牌搭子", first.name(), "名稱");
            eq("小明", first.host(), "房主");
            eq(2, first.humans(), "真人數");
            eq(new RoomSettings(5000, 100, 20, List.of(BotLevel.NORMAL, BotLevel.PRO, BotLevel.BEGINNER), 30,
                    GameSpeed.FAST, "週五牌搭子", false), first.settings(), "設定");
            eq("XYZ789", listed.get(1).code(), "照服務器的順序");
            eq(3, listed.get(1).humans(), "第二間的人數");
            eq(20000, listed.get(1).settings().chips(), "第二間的籌碼");
            eq(0, RoomListing.parse(parse(rooms())).size(), "沒有房間");
            check(!first.settings().expert(), "舊服務器沒有專家模式");
            RoomListing expert = RoomListing.parse(parse(rooms(LISTING.replace("\"speed\":\"FAST\"",
                    "\"speed\":\"FAST\",\"expert\":true")))).get(0);
            check(expert.settings().expert(), "專家模式的房間");
        });
        test("大廳：缺欄位要拒絕", () -> {
            for (String key : List.of("code", "name", "host", "humans", "options")) {
                Map<String, Object> listing = new LinkedHashMap<>(parse(LISTING));
                listing.remove(key);
                fails(IllegalArgumentException.class,
                        () -> RoomListing.parse(Map.of("type", "rooms", "rooms", List.of(listing))), key);
            }
            fails(IllegalArgumentException.class, () -> RoomListing.parse(parse("{\"type\":\"rooms\"}")), "沒有 rooms");
        });
    }

    private static final String LISTING = "{\"code\":\"ABC234\",\"name\":\"週五牌搭子\",\"host\":\"小明\",\"humans\":2,"
            + "\"options\":{\"chips\":5000,\"basePay\":100,\"taiPay\":20,\"botLevels\":[\"NORMAL\",\"PRO\",\"BEGINNER\"],"
            + "\"waitSeconds\":30,\"speed\":\"FAST\",\"name\":\"週五牌搭子\",\"private\":false}}";

    /** 服務器的 rooms 訊息，listings 是一間一間的 JSON。 */
    private static String rooms(String... listings) {
        return "{\"type\":\"rooms\",\"rooms\":[" + String.join(",", listings) + "]}";
    }

    private static void game() {
        test("局面：座位轉成自己在 0", () -> {
            OnlineGame game = playing();
            eq(0, game.player(0).seat(), "自己");
            eq("小花", game.player(0).name(), "座位 2 是自己");
            eq("小美（斷線）", game.player(1).name(), "座位 3 是下家，斷線要標出來");
            eq("小明", game.player(2).name(), "座位 0 是對家");
            eq("阿土", game.player(3).name(), "座位 1 是上家");
            eq(Gender.MALE, game.player(3).gender(), "電腦的性別");
            eq(2, game.dealer(), "莊家座位 0");
            eq(1, game.seatWind(2), "莊家是東");
            eq(2, game.seatWind(3), "莊家的下家是南");
            eq(3, game.seatWind(0), "自己是西");
            eq(4, game.seatWind(1), "下家是北");
            eq(3, game.current(), "輪到座位 1");
            eq(Round.Phase.TURN, game.phase(), "階段");
            eq(70, game.liveTiles(), "牌牆");
        });
        test("局面：自己的牌看得到，別家只有張數", () -> {
            OnlineGame game = playing();
            eq(T.hand("1w1w2w"), game.player(0).concealed(), "手牌");
            eq(17, game.player(3).concealed().size(), "別家的張數");
            eq(Collections.nCopies(17, OnlineGame.HIDDEN), game.player(3).concealed(), "都是暗牌");
            eq(5000, game.player(3).chips(), "籌碼");
            eq(T.hand("1f"), game.player(1).flowers(), "花牌");
            eq(T.hand("9s"), game.player(1).discards(), "牌河");
            check(game.traits(0) == null, "自己沒有說明");
            eq("電腦", game.traits(3), "電腦");
            eq("斷線中，由電腦代打", game.traits(1), "斷線的真人");
            eq("玩家", game.traits(2), "連著的真人");
        });
        test("局面：別家暗槓在前面，亮出來的排在後面", () -> {
            OnlineGame game = playing();
            List<Meld> melds = game.player(1).melds();
            eq(2, melds.size(), "兩組");
            eq(Meld.Type.CONCEALED_KONG, melds.get(0).type(), "暗槓在前");
            eq(4, melds.get(0).tiles().size(), "暗槓四張");
            eq(Meld.Type.PONG, melds.get(1).type(), "最後是碰");
        });
        test("局面：剛摸的牌", () -> {
            OnlineGame game = playing();
            check(game.drawn() == null, "輪到別家但沒看到他摸牌");
            game.applyState(parse(state("TURN", 2, "\"drawn\":\"2w\"")), List.of());
            eq(tile("2w"), game.drawn(), "自己摸的牌");
            game.applyState(parse(state("TURN", 1, "\"drawn\":null")), List.of(game.parseEvent(parse(
                    "{\"type\":\"event\",\"kind\":\"DRAW\",\"seat\":1,\"tile\":null,\"from\":-1}"))));
            eq(OnlineGame.HIDDEN, game.drawn(), "別家摸牌只知道有一張");
            game.applyState(parse(state("CLAIMS", 1, "\"drawn\":null")), List.of(game.parseEvent(parse(
                    "{\"type\":\"event\",\"kind\":\"DISCARD\",\"seat\":1,\"tile\":\"3p\",\"from\":1}"))));
            check(game.drawn() == null, "打掉就沒有了");
        });
        test("局面：事件翻成畫面座位，別家摸牌用暗牌", () -> {
            OnlineGame game = playing();
            RoundEvent pong = game.parseEvent(parse("{\"type\":\"event\",\"kind\":\"PONG\",\"seat\":3,\"tile\":\"5z\",\"from\":0}"));
            eq(new RoundEvent(RoundEvent.Type.PONG, 1, tile("5z"), 2), pong, "碰");
            RoundEvent draw = game.parseEvent(parse("{\"type\":\"event\",\"kind\":\"DRAW\",\"seat\":1,\"tile\":null,\"from\":-1}"));
            eq(new RoundEvent(RoundEvent.Type.DRAW, 3, OnlineGame.HIDDEN, -1), draw, "別家摸牌");
            RoundEvent drawn = game.parseEvent(parse("{\"type\":\"event\",\"kind\":\"DRAWN_GAME\",\"seat\":-1,\"tile\":null,\"from\":-1}"));
            eq(new RoundEvent(RoundEvent.Type.DRAWN_GAME, -1, null, -1), drawn, "流局沒有座位");
            eq("小美 碰 中", game.describe(pong), "紀錄的文字用原本的名字");
            eq("流局", game.describe(drawn), "流局");
            check(game.describe(draw) == null, "摸牌不記");
        });
        test("局面：詢問直接用服務器說的，不自己算", () -> {
            OnlineGame.TurnAsk turn = OnlineGame.parseTurn(parse("{\"type\":\"askTurn\",\"waitId\":3,\"canSelfWin\":true,"
                    + "\"concealedKongs\":[\"1w\"],\"addKongs\":[\"2p\"],\"readyDiscards\":{\"9s\":[\"1w\",\"4w\"]}}"));
            eq(3, turn.waitId(), "waitId");
            check(turn.options().canSelfWin(), "可以自摸");
            eq(T.hand("1w"), turn.options().concealedKongs(), "暗槓");
            eq(T.hand("2p"), turn.options().addKongs(), "加槓");
            eq(T.hand("1w4w"), turn.readyDiscards().get(tile("9s")), "聽牌");
            OnlineGame.ClaimAsk claim = OnlineGame.parseClaim(parse("{\"type\":\"askClaim\",\"waitId\":4,\"tile\":\"3w\",\"from\":1,"
                    + "\"robbing\":false,\"options\":[{\"kind\":\"WIN\",\"tile\":\"3w\",\"fromHand\":[]},"
                    + "{\"kind\":\"CHOW\",\"tile\":\"3w\",\"fromHand\":[\"1w\",\"2w\"]}]}"));
            eq(4, claim.waitId(), "waitId");
            eq(2, claim.options().size(), "選項");
            eq(new Claim(Claim.Type.CHOW, tile("3w"), T.hand("1w2w")), claim.options().get(1), "吃");
            eq(1, claim.options().indexOf(new Claim(Claim.Type.CHOW, tile("3w"), T.hand("1w2w"))), "回覆用索引");
        });
        test("局面：一局結束攤開四家，輸贏轉成畫面座位", () -> {
            OnlineGame game = playing();
            OnlineGame.RoundEnd end = game.applyRoundEnd(parse(ROUND_END));
            eq(Round.Phase.FINISHED, game.phase(), "打完了");
            eq(3, game.result().winner(), "座位 1 胡");
            eq(0, game.result().from(), "座位 2 是自己放槍");
            check(!game.result().isSelfDraw(), "不是自摸");
            eq(tile("3w"), game.result().winTile(), "胡的牌");
            eq(3, game.result().score().totalTai(), "台數");
            eq(-200, end.settlement().deltas()[0], "自己輸");
            eq(200, end.settlement().deltas()[3], "座位 1 贏");
            eq(5, end.settlement().tais()[0], "付款台數");
            eq(4800, game.player(0).chips(), "籌碼也換了");
            eq(T.hand("7s7s"), game.player(1).concealed(), "別家的牌攤開了");
            eq(T.hand("3w"), game.player(3).concealed(), "胡牌的人");
            eq(T.hand("9s"), game.player(1).discards(), "牌河留著");
            check(!end.gameOver(), "還沒結束");
            eq(60000, end.nextRoundMs(), "下一局");
            eq(1, game.history().size(), "歷程");
            eq(7, game.history().get(0).number(), "局數");
            eq(-200, game.history().get(0).deltas()[0], "歷程的輸贏");
            eq(1, game.roundsPlayed(), "局數");
        });
        test("局面：流局", () -> {
            OnlineGame game = playing();
            OnlineGame.RoundEnd end = game.applyRoundEnd(parse(ROUND_END
                    .replace("\"drawnGame\":false,\"winner\":1,\"from\":2,\"selfDraw\":false,\"winTile\":\"3w\"",
                            "\"drawnGame\":true,\"winner\":-1,\"from\":-1,\"selfDraw\":false,\"winTile\":null")
                    .replace("\"nextRoundMs\":60000", "\"nextRoundMs\":10000")));
            check(game.result().isDrawnGame(), "流局");
            eq(10000, end.nextRoundMs(), "下一局");
            eq(1, game.drawnGames(), "流局次數");
        });
        test("局面：整場結束的成績", () -> {
            OnlineGame game = playing();
            check(!game.isOver(), "還沒結束");
            game.applyGameOver(parse("{\"type\":\"gameOver\",\"reason\":\"bankrupt\",\"rounds\":9,\"drawnGames\":2,\"standings\":["
                    + "{\"rank\":1,\"seat\":1,\"name\":\"阿土\",\"chips\":9000,\"winnings\":4000,\"wins\":3,\"selfDraws\":1,\"dealIns\":0},"
                    + "{\"rank\":2,\"seat\":2,\"name\":\"小花\",\"chips\":5000,\"winnings\":0,\"wins\":1,\"selfDraws\":0,\"dealIns\":1},"
                    + "{\"rank\":3,\"seat\":0,\"name\":\"小明\",\"chips\":6000,\"winnings\":1000,\"wins\":1,\"selfDraws\":0,\"dealIns\":2},"
                    + "{\"rank\":4,\"seat\":3,\"name\":\"小美\",\"chips\":0,\"winnings\":-5000,\"wins\":0,\"selfDraws\":0,\"dealIns\":4}]}"));
            check(game.isOver(), "結束了");
            eq(9, game.roundsPlayed(), "局數用服務器的");
            eq(2, game.drawnGames(), "流局用服務器的");
            eq(3, game.record(3, "2026-11-01").wins(), "座位 1 是上家");
            eq(1, game.record(3, "2026-11-01").selfDraws(), "自摸");
            eq(4, game.record(1, "2026-11-01").dealIns(), "座位 3 是下家");
            eq(0, game.record(0, "2026-11-01").winnings(), "自己");
        });
        test("局面：開新的一局清掉上一局", () -> {
            OnlineGame game = playing();
            game.applyRoundEnd(parse(ROUND_END));
            game.applyRoundStart(parse("{\"type\":\"roundStart\",\"number\":8,\"dealer\":1,\"roundWind\":2,\"streak\":0,\"dealMs\":3000}"));
            eq(Round.Phase.TURN, game.phase(), "新的一局");
            check(game.result() == null, "沒有結果了");
            eq(3, game.dealer(), "新的莊家");
            eq(2, game.roundWind(), "圈風");
            eq(0, game.player(0).concealed().size(), "手牌清空");
            eq(4800, game.player(0).chips(), "籌碼留著");
        });
        test("局面：局風從 roundStart 與 state 來，舊服務器沒有送就是 0", () -> {
            OnlineGame game = playing();
            eq(0, game.handWind(), "舊服務器");
            eq("東風圈", roundLabel(game), "退回原本的寫法");
            game.applyRoundStart(parse("{\"type\":\"roundStart\",\"number\":8,\"dealer\":1,\"roundWind\":2,\"handWind\":3,"
                    + "\"streak\":0,\"dealMs\":3000}"));
            eq(3, game.handWind(), "roundStart");
            eq("南風西局", roundLabel(game), "圈風加局風");
            game.applyState(parse(state("TURN", 1, "\"drawn\":null").replace("\"roundWind\":1,", "\"roundWind\":1,\"handWind\":2,")),
                    List.of());
            eq(2, game.handWind(), "state");
            eq("東風南局", roundLabel(game), "state 的圈風");
            game.applyState(parse(state("TURN", 1, "\"drawn\":null").replace("\"roundWind\":1,", "\"roundWind\":1,\"handWind\":9,")),
                    List.of());
            eq(0, game.handWind(), "不認得的數字也當成沒有");
            fails(IllegalArgumentException.class, () -> game.applyState(parse(state("TURN", 1, "\"drawn\":null")
                    .replace("\"roundWind\":1,", "\"roundWind\":1,\"handWind\":\"東\",")), List.of()), "不是數字");
        });
        test("局面：報聽是公開的，別家看 state 的 listening，自己看 listeningWaits", () -> {
            OnlineGame game = playing();
            check(!game.isListening(1) && !game.isListening(0), "還沒有人報聽");
            RoundEvent listen = game.parseEvent(parse("{\"type\":\"event\",\"kind\":\"LISTEN\",\"seat\":3,\"tile\":null}"));
            eq(new RoundEvent(RoundEvent.Type.LISTEN, 1, null, -1), listen, "沒有 from 也讀得懂，座位 3 是下家");
            eq("小美 報聽", game.describe(listen), "紀錄");
            game.applyState(parse(state("TURN", 1, "\"drawn\":null")
                    .replace("\"seat\":3,\"handCount\":9,", "\"seat\":3,\"handCount\":9,\"listening\":true,")
                    .replace("\"listeningWaits\":null", "\"listeningWaits\":[\"3w\"]")), List.of(listen));
            check(game.isListening(1), "下家報聽了");
            check(!game.isListening(2), "對家沒有");
            check(game.isListening(0), "自己報聽了");
            game.applyRoundStart(parse("{\"type\":\"roundStart\",\"number\":8,\"dealer\":1,\"roundWind\":1,\"streak\":0,\"dealMs\":3000}"));
            check(!game.isListening(1), "新的一局清掉");
            eq(9, OnlineGame.parseListenOffer(parse("{\"type\":\"canListen\",\"listenId\":9}")), "報聽的編號");
            OnlineGame.Chat chat = game.parseChat(parse("{\"type\":\"chat\",\"seat\":2,\"name\":\"小花\",\"text\":\"你好\"}"));
            eq(new OnlineGame.Chat(0, "小花", "你好"), chat, "座位 2 是自己");
            eq("＊＊打太慢", game.parseChat(parse("{\"type\":\"chat\",\"seat\":1,\"name\":\"小美\",\"text\":\"靠北打太慢\"}"))
                    .text(), "別人說的不雅字眼遮起來");
            fails(IllegalArgumentException.class, () -> game.parseEvent(parse("{\"type\":\"event\",\"kind\":\"DANCE\",\"seat\":1}")),
                    "不認得的事件");
        });
        test("局面：連線狀態變了名字跟著換，牌不動", () -> {
            OnlineGame game = playing();
            game.applyRoom(RoomInfo.parse(parse(ROOM_PLAYING.replace("\"connected\":false", "\"connected\":true"))));
            eq("小美", game.player(1).name(), "回來了");
            eq(9, game.player(1).concealed().size(), "牌還在");
        });
        test("局面：開門的骰子全桌一樣，斷線接回只有 state 也一樣", () -> {
            String start = "{\"type\":\"roundStart\",\"number\":7,\"dealer\":0,\"roundWind\":1,\"streak\":1,\"dealMs\":3000}";
            OnlineGame game = playing();
            OnlineGame other = new OnlineGame();
            other.applyRoom(RoomInfo.parse(parse(ROOM_PLAYING.replace("\"yourSeat\":2", "\"yourSeat\":0"))));
            other.applyRoundStart(parse(start));
            eq(game.dice(), other.dice(), "坐在別的位置看到的一樣");
            eq(Dice.of("ABC234/0/1/0/1"), game.dice(), "房號、莊家的服務器座位、圈風、局風、連莊");
            eq("小明 擲出 1、3、1，共 5 點，從小明的牌牆開門", WallRing.announce(game, game.dice()),
                    "5 點數回莊家自己；網頁版的 AllTests 也比對這一行");
            OnlineGame resumed = new OnlineGame();
            resumed.applyRoom(RoomInfo.parse(parse(ROOM_PLAYING)));
            resumed.applyState(parse(state("TURN", 1, "\"drawn\":null")), List.of());
            eq(game.dice(), resumed.dice(), "斷線接回");
            other.applyRoundStart(parse(start.replace("\"dealer\":0", "\"dealer\":1").replace("\"streak\":1", "\"streak\":0")));
            eq(Dice.of("ABC234/1/1/0/0"), other.dice(), "換莊");
        });
        test("局面：看不懂的牌要拒絕", () -> {
            OnlineGame game = playing();
            for (String bad : List.of("\"9z\"", "\"0w\"", "\"1x\"", "\"1\"", "7")) {
                fails(IllegalArgumentException.class, () -> game.applyState(parse(
                        state("TURN", 1, "\"drawn\":" + bad)), List.of()), bad);
            }
        });
    }

    private static final String ROOM_PLAYING = "{\"type\":\"room\",\"code\":\"ABC234\",\"state\":\"playing\",\"yourSeat\":2,"
            + "\"options\":{\"chips\":5000,\"basePay\":100,\"taiPay\":20,\"botLevels\":[\"NORMAL\",\"PRO\",\"BEGINNER\"],"
            + "\"waitSeconds\":30,\"speed\":\"FAST\"},\"players\":["
            + "{\"seat\":0,\"name\":\"小明\",\"human\":true,\"connected\":true,\"gender\":null},"
            + "{\"seat\":1,\"name\":\"阿土\",\"human\":false,\"connected\":false,\"gender\":\"MALE\"},"
            + "{\"seat\":2,\"name\":\"小花\",\"human\":true,\"connected\":true,\"gender\":null},"
            + "{\"seat\":3,\"name\":\"小美\",\"human\":true,\"connected\":false,\"gender\":null}]}";

    private static final String ROUND_END = "{\"type\":\"roundEnd\","
            + "\"drawnGame\":false,\"winner\":1,\"from\":2,\"selfDraw\":false,\"winTile\":\"3w\","
            + "\"items\":[{\"name\":\"平胡\",\"tai\":2},{\"name\":\"門清\",\"tai\":1}],"
            + "\"deltas\":[0,200,-200,0],\"tais\":[0,0,5,0],\"chips\":[5000,5200,4800,5000],"
            + "\"hands\":[{\"seat\":0,\"hand\":[\"5s\"],\"melds\":[],\"flowers\":[]},"
            + "{\"seat\":1,\"hand\":[\"3w\"],\"melds\":[],\"flowers\":[]},"
            + "{\"seat\":2,\"hand\":[\"6s\"],\"melds\":[],\"flowers\":[]},"
            + "{\"seat\":3,\"hand\":[\"7s\",\"7s\"],\"melds\":[{\"kind\":\"CONCEALED_KONG\",\"tiles\":[\"9p\",\"9p\",\"9p\",\"9p\"]}],\"flowers\":[\"1f\"]}],"
            + "\"gameOver\":false,\"nextRoundMs\":60000}";

    /** 座位 2 看到的局面。drawn 是要放進去的 "drawn" 欄位。 */
    private static String state(String phase, int current, String drawn) {
        return "{\"type\":\"state\",\"seat\":2,\"phase\":\"" + phase + "\",\"current\":" + current + ",\"dealer\":0,\"streak\":1,"
                + "\"roundWind\":1,\"seatWind\":3,\"liveTiles\":70,\"hand\":[\"1w\",\"1w\",\"2w\"]," + drawn + ","
                + "\"melds\":[],\"flowers\":[],\"discards\":[],\"chips\":5000,\"claimTile\":null,\"discarder\":-1,\"robbing\":false,"
                + "\"listeningWaits\":null,\"autoPlay\":false,\"opponents\":["
                + "{\"seat\":3,\"handCount\":9,\"melds\":[{\"kind\":\"PONG\",\"tiles\":[\"2p\",\"2p\",\"2p\"]}],\"concealedKongs\":1,"
                + "\"flowers\":[\"1f\"],\"discards\":[\"9s\"],\"chips\":5000},"
                + "{\"seat\":0,\"handCount\":16,\"melds\":[],\"concealedKongs\":0,\"flowers\":[],\"discards\":[],\"chips\":5000},"
                + "{\"seat\":1,\"handCount\":17,\"melds\":[],\"concealedKongs\":0,\"flowers\":[],\"discards\":[],\"chips\":5000}]}";
    }

    private static OnlineGame playing() {
        OnlineGame game = new OnlineGame();
        game.applyRoom(RoomInfo.parse(parse(ROOM_PLAYING)));
        game.applyRoundStart(parse("{\"type\":\"roundStart\",\"number\":7,\"dealer\":0,\"roundWind\":1,\"streak\":1,\"dealMs\":3000}"));
        game.applyState(parse(state("TURN", 1, "\"drawn\":null")), List.of());
        return game;
    }

    /** 牌桌在 ezmj.ui，只能用反射叫。 */
    private static String roundLabel(RoundView round) throws ReflectiveOperationException {
        Method method = Class.forName("ezmj.ui.TableCanvas").getDeclaredMethod("roundLabel", RoundView.class);
        method.setAccessible(true);
        return (String) method.invoke(null, round);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        return (Map<String, Object>) Json.parse(text);
    }

    /** 訊息視窗是每一行分開顯示，所以每一行都不能被當成 HTML；除了前面墊的空白，就是原本的文字。 */
    private static void plainOnScreen(String shown, String original) {
        for (String line : shown.split("\n")) {
            check(!BasicHTML.isHTMLString(line), "不該當成 HTML：" + line);
        }
        eq(original, shown.replaceAll("(?i) (?=<html>)", ""), "看起來的文字");
    }

    /** 把通知都變成文字排進佇列，測試一則一則對。 */
    private static final class Probe implements OnlineClient.Listener, AutoCloseable {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final OnlineClient client;
        volatile Map<String, Object> lastMessage;
        volatile List<RoomListing> lastRooms;
        volatile String lastRejection;
        private final FakeServer server;

        Probe(FakeServer server) {
            this(server, FAST);
        }

        Probe(FakeServer server, OnlineClient.Timing timing) {
            this.server = server;
            this.client = new OnlineClient(server.address(), this, Runnable::run, timing);
        }

        Probe(ServerAddress address) {
            this(address, FAST);
        }

        Probe(ServerAddress address, OnlineClient.Timing timing) {
            this.server = null;
            this.client = new OnlineClient(address, this, Runnable::run, timing);
        }

        /** 登入好，回傳這條連線。 */
        FakeConnection loggedIn() throws Exception {
            client.loginWithName("小明");
            FakeConnection connection = server.accept();
            connection.next("hello");
            connection.send("{\"type\":\"welcome\",\"token\":\"t1\",\"room\":null}");
            eq("loggedIn:false", next(), "登入");
            return connection;
        }

        String next() throws InterruptedException {
            String event = events.poll(5, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("等太久沒有通知");
            }
            return event;
        }

        @Override
        public void loggedIn(Map<String, Object> welcome, boolean resumed) {
            events.add("loggedIn:" + resumed);
        }

        @Override
        public void message(Map<String, Object> message) {
            lastMessage = message;
            events.add("message:" + message.get("type"));
        }

        @Override
        public void rooms(List<RoomListing> rooms) {
            lastRooms = rooms;
            events.add("rooms:" + rooms.size());
        }

        @Override
        public void connectionLost() {
            events.add("lost");
        }

        @Override
        public void loginRejected(String code, String message) {
            lastRejection = message;
            events.add("rejected:" + code);
        }

        @Override
        public void closed(String reason) {
            events.add("closed:" + reason);
        }

        @Override
        public void badMessage(String line, RuntimeException error) {
            events.add("bad");
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /** 現在沒有人在聽的 port，連過去會被拒絕。 */
    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ServerAddress address(boolean webSocket, int port) {
        return webSocket ? ServerAddress.parse("ws://127.0.0.1:" + port + "/ws") : new ServerAddress("127.0.0.1", port);
    }

    /** 測試自己開的服務器，要說什麼由測試決定。 */
    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket socket;
        private final List<FakeConnection> connections = new ArrayList<>();
        private final boolean webSocket;

        FakeServer(boolean webSocket) throws IOException {
            this(webSocket, 0);
        }

        /** port 給 0 時由系統挑。 */
        FakeServer(boolean webSocket, int port) throws IOException {
            this.webSocket = webSocket;
            this.socket = new ServerSocket(port);
        }

        ServerAddress address() {
            return OnlineTest.address(webSocket, socket.getLocalPort());
        }

        FakeConnection accept() throws IOException {
            socket.setSoTimeout(5_000);
            Socket accepted = socket.accept();
            FakeConnection connection = webSocket ? new FakeWebSocket(accepted) : new FakeConnection(accepted);
            connections.add(connection);
            return connection;
        }

        /** 下一條連線不給升級，回這個狀態，例如路徑寫錯的 "404 Not Found"。 */
        void refuse(String status) throws IOException {
            socket.setSoTimeout(5_000);
            try (Socket accepted = socket.accept()) {
                FakeWebSocket.readHead(new DataInputStream(new BufferedInputStream(accepted.getInputStream())));
                accepted.getOutputStream().write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
        }

        FakeConnection current() {
            return connections.get(connections.size() - 1);
        }

        boolean acceptsAnother(int waitMs) throws IOException {
            socket.setSoTimeout(waitMs);
            try (Socket extra = socket.accept()) {
                return extra != null;
            } catch (java.net.SocketTimeoutException e) {
                return false;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
            for (FakeConnection connection : connections) {
                connection.close();
            }
        }
    }

    private static class FakeConnection {
        private final Socket socket;
        private final BufferedReader in;
        final OutputStream out;

        FakeConnection(Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        String nextLine() throws IOException {
            return in.readLine();
        }

        /** 跳過其他訊息（通常是 ping），直到這種訊息。 */
        Map<String, Object> next(String type) throws IOException {
            while (true) {
                String line = nextLine();
                if (line == null) {
                    throw new AssertionError("等 " + type + " 時連線關閉了");
                }
                Map<String, Object> message = parse(line);
                if (type.equals(message.get("type"))) {
                    return message;
                }
            }
        }

        void send(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** 等到客戶端要列表，回一間 LISTING。服務器不主動推送，一定是一問一答。 */
        void listRooms() throws IOException {
            next("listRooms");
            send(rooms(LISTING));
        }

        void close() throws IOException {
            socket.close();
        }
    }

    /** 同樣的測試改走 WebSocket：建立時就完成升級，nextLine 與 send 收發的是一個訊框，沒有換行。 */
    private static final class FakeWebSocket extends FakeConnection {
        private static final int MASKED = 0x80;
        private static final int TEXT = 1;
        private static final int CLOSE = 8;
        private final DataInputStream data;
        final String path;

        FakeWebSocket(Socket socket) throws IOException {
            super(socket);
            data = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            List<String> head = readHead(data);
            path = head.get(0).split(" ")[1];
            String key = head.stream().filter(line -> line.toLowerCase().startsWith("sec-websocket-key:"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip()).findFirst().orElseThrow();
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        static List<String> readHead(DataInputStream in) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            while (true) {
                int b = in.readUnsignedByte();
                if (b != '\n') {
                    line.append((char) b);
                } else if (line.toString().strip().isEmpty()) {
                    return lines;
                } else {
                    lines.add(line.toString().strip());
                    line.setLength(0);
                }
            }
        }

        private static String accept(String key) throws IOException {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
                return Base64.getEncoder().encodeToString(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        }

        @Override
        String nextLine() throws IOException {
            while (true) {
                int first;
                try {
                    first = data.readUnsignedByte();
                } catch (EOFException e) {
                    return null;
                }
                int second = data.readUnsignedByte();
                check((second & MASKED) != 0, "客戶端的訊框要有遮罩");
                int length = second & ~MASKED;
                if (length == 126) {
                    length = data.readUnsignedShort();
                } else if (length == 127) {
                    length = (int) data.readLong();
                }
                byte[] mask = data.readNBytes(4);
                byte[] payload = data.readNBytes(length);
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
                int opcode = first & 0x0f;
                if (opcode == CLOSE) {
                    return null;
                }
                if (opcode == TEXT) {
                    return new String(payload, StandardCharsets.UTF_8);
                }
            }
        }

        @Override
        void send(String line) throws IOException {
            sendFrame(0x80 | TEXT, line.getBytes(StandardCharsets.UTF_8));
        }

        /** first 是訊框的第一個位元組：最高位表示最後一段，低位是種類。服務器送的不加遮罩。 */
        void sendFrame(int first, byte[] payload) throws IOException {
            DataOutputStream frame = new DataOutputStream(out);
            frame.writeByte(first);
            if (payload.length < 126) {
                frame.writeByte(payload.length);
            } else if (payload.length <= 0xffff) {
                frame.writeByte(126);
                frame.writeShort(payload.length);
            } else {
                frame.writeByte(127);
                frame.writeLong(payload.length);
            }
            frame.write(payload);
            frame.flush();
        }
    }
}
