package ezmj.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import ezmj.model.GameSpeed;
import ezmj.online.ServerAddress;

/**
 * 實機試打：真的開一台服務器，開真的 Swing 視窗，用按按鈕和點牌的方式打連線牌局，再驗證斷線重連。
 * 不是自動測試的一部分，因為要有桌面環境，也要有 ezmjService 編好的服務器。
 *
 *   java -cp build\classes;build\test-classes ezmj.ui.OnlineTrial [服務器的 classpath] [輸出資料夾]
 *
 * 每個客戶端是獨立的程式，使用者目錄指到輸出資料夾，不會碰到真正的設定與存檔。
 * 輸出資料夾留有各自的紀錄與畫面截圖。
 */
public final class OnlineTrial {

    private static final int WATCHDOG_MS = 180_000;
    private static final int TICK_MS = 120;
    private static final int DROP_AFTER_DECISIONS = 3;
    private static final int KILL_AFTER_DECISIONS = 14;
    private static final List<String> LOCAL_ONLY_FILES =
            List.of("save.properties", "stats.properties", "achievements.properties", "honor.properties");

    private OnlineTrial() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--client")) {
            new Client(args).run();
        } else {
            orchestrate(Path.of(args[0]).toAbsolutePath().toString(), Path.of(args[1]).toAbsolutePath());
        }
    }

    // ---------------------------------------------------------------- 總指揮

    private static void orchestrate(String serverClasspath, Path out) throws Exception {
        Files.createDirectories(out);
        int failures = 0;
        failures += singlePlayer(out.resolve("local"));
        int port = freePort();
        Process server = new ProcessBuilder(java(), "-Dfile.encoding=UTF-8", "-cp", serverClasspath,
                "ezmj.server.EzmjServer", String.valueOf(port))
                .redirectErrorStream(true).redirectOutput(out.resolve("server.log").toFile()).start();
        try {
            Thread.sleep(1500);
            failures += reconnect(out.resolve("reconnect"), port);
            failures += gameOver(out.resolve("gameover"), port);
        } finally {
            server.destroy();
        }
        System.out.println(failures == 0 ? "全部通過" : "失敗 " + failures + " 項");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 單機照常：打完一局，要有本機紀錄。 */
    private static int singlePlayer(Path dir) throws Exception {
        Process client = client(dir, "單機", "local", 0);
        return expect("單機", client, 0);
    }

    /** 兩人開房打牌：來賓先網路中斷再接回，之後程式被殺掉，重開再從 token 接回。 */
    private static int reconnect(Path dir, int port) throws Exception {
        Files.createDirectories(dir);
        Process host = client(dir, "房主", "host", port, "--leaveAfter", "3", "--chips", "20000", "--expectDisconnectMark");
        Process guest = client(dir, "來賓", "guest", port, "--proxy", "--drop", "--killAfter", String.valueOf(KILL_AFTER_DECISIONS));
        Path killFile = dir.resolve("kill-guest");
        long deadline = System.currentTimeMillis() + 300_000;
        while (!Files.exists(killFile) && guest.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        int failures = 0;
        if (!Files.exists(killFile)) {
            System.out.println("失敗：來賓沒有走到要殺掉的地方");
            failures++;
        } else {
            guest.destroyForcibly().waitFor();
            System.out.println("來賓的程式被殺掉了，重開接回");
        }
        Process resumed = client(dir, "來賓", "resume", port, "--leaveAfter", "1");
        failures += expect("房主", host, 0);
        failures += expect("來賓接回", resumed, 0);
        return failures;
    }

    /** 打完整場：看最後成績、回到房間、再開一場。籌碼給很少，有人胡就有人輸光。 */
    private static int gameOver(Path dir, int port) throws Exception {
        Files.createDirectories(dir);
        Process host = client(dir, "甲", "host", port, "--chips", "100", "--secondGame");
        Process guest = client(dir, "乙", "guest", port, "--secondGame");
        return expect("甲", host, 0) + expect("乙", guest, 0);
    }

    private static int expect(String name, Process process, int code) throws InterruptedException {
        if (!process.waitFor(420, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            System.out.println("失敗：" + name + " 等太久");
            return 1;
        }
        System.out.println(name + " 結束，代碼 " + process.exitValue());
        return process.exitValue() == code ? 0 : 1;
    }

    private static Process client(Path dir, String name, String role, int port, String... options) throws IOException {
        Path home = dir.resolve("home-" + name);
        Files.createDirectories(home.resolve(".ezmj"));
        Path settings = home.resolve(".ezmj").resolve("settings.properties");
        if (!Files.exists(settings)) {
            Files.writeString(settings, "sound=false\nvoice=false\nmusic=OFF\nmusicVolume=0\n");
        }
        // 一定要換掉服務器，試打不能碰到線上的 Render；假裝斷線的來賓會再換成自己的 Proxy
        List<String> command = new ArrayList<>(List.of(java(), "-Dfile.encoding=UTF-8", "-Duser.home=" + home,
                "-D" + ServerAddress.TEST_OVERRIDE + "=127.0.0.1:" + port, "-cp", System.getProperty("java.class.path"), OnlineTrial.class.getName(),
                "--client", role, name, String.valueOf(port), dir.toString()));
        Collections.addAll(command, options);
        File log = dir.resolve(name + "-" + role + ".log").toFile();
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---------------------------------------------------------------- 一個客戶端

    /** 一個客戶端程式。所有畫面動作都在 EDT 上，點按鈕用 invokeLater，才不會被 modal 視窗卡住。 */
    private static final class Client {
        private final String role;
        private final String name;
        private final int port;
        private final Path dir;
        private final Path home = Path.of(System.getProperty("user.home"), ".ezmj");
        private final Map<String, String> options = new java.util.HashMap<>();
        private final Set<Object> handled = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private final Set<String> shots = new HashSet<>();
        private final Random random = new Random();
        private final PrintWriter logFile;

        private MainFrame frame;
        private Proxy proxy;
        private int results;
        private int decisions;
        private int games;
        private int lastHintPress;
        private long lastProgress = System.currentTimeMillis();
        private boolean filledForm;
        private boolean startClicked;
        private boolean joinedWritten;
        private boolean leaving;
        private boolean dropped;
        private boolean sawLostHint;
        private boolean sawDisconnectMark;
        private boolean sawFinal;
        private TablePanel currentTable;
        private String lastHint = "";
        private int decisionsAtDrop;

        Client(String[] args) throws IOException {
            role = args[1];
            name = args[2];
            port = Integer.parseInt(args[3]);
            dir = Path.of(args[4]);
            for (int i = 5; i < args.length; i++) {
                boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
                options.put(args[i], flag ? "true" : args[++i]);
            }
            logFile = new PrintWriter(Files.newBufferedWriter(dir.resolve(name + "-" + role + "-trial.txt"),
                    StandardCharsets.UTF_8), true);
        }

        void run() throws Exception {
            if (options.containsKey("--proxy")) {
                proxy = new Proxy(port);
            }
            ErrorReporter.install();
            SwingUtilities.invokeAndWait(() -> {
                frame = new MainFrame();
                frame.setVisible(true);
            });
            log("程式開好了");
            while (true) {
                SwingUtilities.invokeAndWait(this::tick);
                if (System.currentTimeMillis() - lastProgress > WATCHDOG_MS) {
                    shot("watchdog");
                    fail("太久沒有進度，提示是：" + lastHint);
                }
                Thread.sleep(TICK_MS);
            }
        }

        private void log(String message) {
            lastProgress = System.currentTimeMillis();
            String line = name + "/" + role + "：" + message;
            System.out.println(line);
            logFile.println(line);
        }

        private void fail(String message) {
            log("失敗：" + message);
            System.exit(1);
        }

        private void finish() {
            Set<String> found = new HashSet<>();
            for (String file : LOCAL_ONLY_FILES) {
                if (Files.exists(home.resolve(file))) {
                    found.add(file);
                }
            }
            if (role.equals("local")) {
                if (!found.contains("stats.properties")) {
                    fail("單機打完一局應該要有統計");
                }
            } else if (!found.isEmpty()) {
                fail("連線牌局不應該寫本機紀錄：" + found);
            }
            if (options.containsKey("--expectDisconnectMark") && !sawDisconnectMark) {
                fail("對手斷線時應該要在名字旁看到斷線");
            }
            if (options.containsKey("--secondGame") && !sawFinal) {
                fail("應該要看到整場結算");
            }
            if (options.containsKey("--drop") && !sawLostHint) {
                fail("斷線時應該要看到重新連線的提示");
            }
            log("通過，結果視窗 " + results + " 次，自己決定 " + decisions + " 次，本機紀錄檔 " + found);
            System.exit(0);
        }

        private void tick() {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window instanceof JDialog && handleDialog((JDialog) window)) {
                    return;
                }
            }
            for (Component card : ((Container) frame.getContentPane()).getComponents()) {
                if (!card.isVisible()) {
                    continue;
                }
                if (card instanceof StartPanel) {
                    onStart((StartPanel) card);
                } else if (card instanceof OnlinePanel) {
                    onForm((OnlinePanel) card);
                } else if (card instanceof RoomPanel) {
                    onRoom((RoomPanel) card);
                } else if (card instanceof TablePanel) {
                    onTable((TablePanel) card);
                }
                return;
            }
        }

        private boolean handleDialog(JDialog dialog) {
            if (dialog instanceof ResultDialog) {
                onResult(dialog);
                return true;
            }
            if (dialog instanceof FinalDialog) {
                onFinal(dialog);
                return true;
            }
            JOptionPane pane = find(dialog, JOptionPane.class);
            if (pane != null) {
                onOptionPane(dialog, pane);
                return true;
            }
            return false;
        }

        private void onOptionPane(JDialog dialog, JOptionPane pane) {
            if (!handled.add(dialog)) {
                return;
            }
            String text = String.valueOf(pane.getMessage());
            log("對話框「" + dialog.getTitle() + "」：" + text);
            List<JButton> buttons = all(pane, JButton.class);
            if (dialog.getTitle().equals("離開牌局")) {
                JButton discard = buttons.stream().filter(b -> b.getText().equals("不存檔離開")).findFirst().orElse(buttons.get(0));
                click(discard);
            } else if (dialog.getTitle().equals("開始遊戲")) {
                click(buttons.get(0));
            } else {
                shot("message");
                fail("沒有預期會跳出這個對話框：" + text);
            }
        }

        private void onStart(StartPanel panel) {
            if (leaving) {
                finish();
            }
            if (!role.equals("local")) {
                clickText(panel, "連線對戰");
                return;
            }
            if (results > 0) {
                finish();
            }
            List<JTextField> fields = all(panel, JTextField.class);
            if (!filledForm) {
                filledForm = true;
                fields.get(0).setText("單機");
                log("開始單機牌局");
                clickText(panel, "開始遊戲");
            }
        }

        private void onForm(OnlinePanel panel) {
            if (leaving) {
                finish();
                return;
            }
            shot("form");
            if (filledForm) {
                return;
            }
            List<JTextField> fields = all(panel, JTextField.class);
            if (proxy != null) {
                System.setProperty(ServerAddress.TEST_OVERRIDE, "127.0.0.1:" + proxy.port());
            }
            switch (role) {
                case "host":
                    fields.get(0).setText(name);
                    setRoomOptions(panel);
                    filledForm = true;
                    log("開房間");
                    clickText(panel, "開房間");
                    break;
                case "guest":
                    Path codeFile = dir.resolve("code.txt");
                    if (Files.exists(codeFile)) {
                        fields.get(0).setText(name);
                        fields.get(1).setText(read(codeFile).toLowerCase());
                        filledForm = true;
                        log("加入房間");
                        clickText(panel, "加入房間");
                    }
                    break;
                default:
                    JButton resume = findText(panel, "接回牌局");
                    if (resume == null || !resume.isVisible()) {
                        fail("重開程式後應該要能接回牌局");
                        return;
                    }
                    filledForm = true;
                    log("重開程式，按「" + resume.getText() + "」");
                    shot("resume-form");
                    click(resume);
                    break;
            }
        }

        private void setRoomOptions(OnlinePanel panel) {
            List<JComboBox<?>> boxes = new ArrayList<>();
            for (JComboBox<?> box : all(panel, JComboBox.class)) {
                boxes.add(box);
            }
            @SuppressWarnings("unchecked")
            JComboBox<Integer> chips = (JComboBox<Integer>) boxes.get(0);
            int amount = Integer.parseInt(options.getOrDefault("--chips", "20000"));
            chips.addItem(amount);
            chips.setSelectedItem(amount);
            boxes.get(boxes.size() - 1).setSelectedItem(GameSpeed.FAST);
            all(panel, JSpinner.class).get(0).setValue(15);
            shot("form-filled");
        }

        private void onRoom(RoomPanel panel) {
            shot(sawFinal ? "room-after-final" : "room");
            if (leaving) {
                return;
            }
            JLabel title = (JLabel) field(panel, "title");
            if (role.equals("host")) {
                String code = title.getText().replace("房號 ", "");
                if (code.length() == 6 && !Files.exists(dir.resolve("code.txt"))) {
                    write(dir.resolve("code.txt"), code);
                    log("房號 " + code);
                }
                JButton start = findText(panel, "開始牌局");
                boolean wantsStart = !startClicked || (sawFinal && games < 2);
                if (start != null && start.isEnabled() && wantsStart && Files.exists(dir.resolve("joined.txt"))
                        && roomHasTwoPlayers(panel)) {
                    startClicked = true;
                    log("開局");
                    click(start);
                }
            } else if (!joinedWritten) {
                joinedWritten = true;
                JButton start = findText(panel, "等房主開局");
                if (start == null || start.isEnabled()) {
                    fail("來賓不應該能開局");
                }
                log("進房間 " + title.getText());
                write(dir.resolve("joined.txt"), name);
            }
        }

        private boolean roomHasTwoPlayers(RoomPanel panel) {
            return all(panel, JLabel.class).stream().filter(label -> label.getText().contains("連線中")).count() >= 2;
        }

        private void onTable(TablePanel table) {
            if (table != currentTable) {
                currentTable = table;
                games++;
                log("上牌桌，第 " + games + " 場");
            }
            String hint = ((JLabel) field(table, "hint")).getText();
            if (!hint.equals(lastHint)) {
                lastHint = hint;
                if (hint.contains("連線中斷")) {
                    sawLostHint = true;
                    shot("reconnecting");
                    log("看到提示：" + hint);
                }
            }
            checkDisconnectMark(table);

            if (options.containsKey("--secondGame") && games >= 2 && !leaving) {
                leaving = true;
                shot("second-game");
                log("第二場也開起來了，離開");
                clickText(table, "離開牌局");
                return;
            }
            int leaveAfter = Integer.parseInt(options.getOrDefault("--leaveAfter", "0"));
            if ((role.equals("local") && results >= 1) || (leaveAfter > 0 && results >= leaveAfter)) {
                if (!leaving) {
                    leaving = true;
                    log("打完了，離開牌局");
                    clickText(table, "離開牌局");
                }
                return;
            }
            if (!role.equals("local") && ((JButton) field(table, "autoPlayButton")).isShowing()) {
                fail("多人遊戲不該有電腦代打的按鈕");
            }
            maybeDrop();
            if (decide(table, hint)) {
                maybeKill();
            }
        }

        /** 第一次輪到自己時截圖，之後照提示決定。回傳這次有沒有做決定。 */
        private boolean decide(TablePanel table, String hint) {
            List<JButton> actions = actionButtons(table);
            if (hint.startsWith("輪到你了") && actions.isEmpty()) {
                shot("my-turn");
                pressHand(table);
                return countDecision();
            }
            if (actions.isEmpty()) {
                if (hint.contains("選要") || hint.contains("再選一張")) {
                    pressHand(table);
                }
                return false;
            }
            JButton choice = choose(actions, hint);
            if (handled.add(choice)) {
                shot(hint.contains("要宣告嗎") ? "claim" : "turn-actions");
                log(hint + " → " + choice.getText());
                click(choice);
                return countDecision();
            }
            if (hint.startsWith("輪到你了") || hint.contains("選")) {
                pressHand(table);
            }
            return false;
        }

        private boolean countDecision() {
            decisions++;
            return true;
        }

        /** 能胡就胡；吃碰槓擲硬幣，選牌也要走到；輪到自己時多半是打牌，沒有想按的就回傳還沒決定的按鈕。 */
        private JButton choose(List<JButton> actions, String hint) {
            for (JButton action : actions) {
                if (action.getText().equals("胡") || action.getText().equals("自摸")) {
                    return action;
                }
            }
            if (hint.contains("要宣告嗎")) {
                List<JButton> claims = new ArrayList<>(actions);
                claims.removeIf(action -> action.getText().equals("過"));
                if (!claims.isEmpty() && random.nextBoolean()) {
                    return claims.get(random.nextInt(claims.size()));
                }
                return actions.get(actions.size() - 1);
            }
            for (JButton action : actions) {
                if (action.getText().equals("取消")) {
                    return action;
                }
            }
            // 主人才試聽牌模式，來賓還要繼續決定才看得到重連後的詢問
            for (JButton action : actions) {
                if (action.getText().startsWith("打") && role.equals("host") && random.nextInt(3) == 0) {
                    return action;
                }
            }
            return actions.get(0);
        }

        /** 從右邊往左點手牌，就像玩家點牌。點到不能點的地方不會有反應。 */
        private void pressHand(TablePanel table) {
            JComponent canvas = (JComponent) field(table, "canvas");
            int y = canvas.getHeight() - 14;
            lastHintPress = lastHintPress <= 20 ? canvas.getWidth() - 12 : lastHintPress - 24;
            int x = lastHintPress;
            SwingUtilities.invokeLater(() -> canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1)));
        }

        private void checkDisconnectMark(TablePanel table) {
            JLabel[][] cells = (JLabel[][]) field(table, "scoreCells");
            for (JLabel[] row : cells) {
                if (row[1].getText().contains("（斷線）") && !sawDisconnectMark) {
                    sawDisconnectMark = true;
                    shot("opponent-disconnected");
                    log("看到對手斷線：" + row[1].getText());
                }
            }
        }

        private void maybeDrop() {
            if (options.containsKey("--drop") && !dropped && decisions >= DROP_AFTER_DECISIONS) {
                dropped = true;
                decisionsAtDrop = decisions;
                log("切斷網路 4 秒");
                proxy.drop(4_000);
            } else if (dropped && decisions == decisionsAtDrop + 2) {
                decisionsAtDrop = -100;
                shot("after-reconnect");
                log("重連之後又輪到自己決定了");
            }
        }

        private void maybeKill() {
            if (options.containsKey("--killAfter") && decisions >= Integer.parseInt(options.get("--killAfter"))) {
                shot("before-kill");
                log("等著被殺掉");
                write(dir.resolve("kill-guest"), "now");
                lastProgress = Long.MAX_VALUE;
                options.remove("--killAfter");
            }
        }

        private void onResult(JDialog dialog) {
            if (!handled.add(dialog)) {
                return;
            }
            results++;
            String headline = all(dialog, JLabel.class).get(0).getText();
            log("第 " + results + " 次結果：" + headline);
            shot("result-" + results);
            shotOf(dialog, "result-dialog-" + results);
            clickText(dialog, all(dialog, JButton.class).get(0).getText().substring(0, 2));
        }

        private void onFinal(JDialog dialog) {
            if (!handled.add(dialog)) {
                return;
            }
            sawFinal = true;
            log("整場結束：" + all(dialog, JLabel.class).get(0).getText());
            shotOf(dialog, "final-dialog");
            clickText(dialog, role.equals("local") ? "回到開始畫面" : "回到房間");
        }

        private List<JButton> actionButtons(TablePanel table) {
            List<JButton> buttons = new ArrayList<>();
            for (JButton button : all((Container) field(table, "actionButtons"), JButton.class)) {
                buttons.add(button);
            }
            return buttons;
        }

        private void shot(String label) {
            if (!shots.add(label)) {
                return;
            }
            shotOf(frame, label);
        }

        private void shotOf(Window window, String label) {
            Component content = window instanceof JFrame ? ((JFrame) window).getRootPane() : ((JDialog) window).getRootPane();
            BufferedImage image = new BufferedImage(Math.max(1, content.getWidth()), Math.max(1, content.getHeight()),
                    BufferedImage.TYPE_INT_RGB);
            content.paint(image.getGraphics());
            try {
                Files.createDirectories(dir.resolve("shots"));
                ImageIO.write(image, "png", dir.resolve("shots").resolve(name + "-" + label + ".png").toFile());
            } catch (IOException e) {
                log("截圖失敗：" + e);
            }
        }

        private void clickText(Container root, String prefix) {
            JButton button = findText(root, prefix);
            if (button == null) {
                fail("找不到按鈕：" + prefix);
                return;
            }
            click(button);
        }

        private static JButton findText(Container root, String prefix) {
            for (JButton button : all(root, JButton.class)) {
                if (button.getText().startsWith(prefix) && button.isShowing()) {
                    return button;
                }
            }
            return null;
        }

        private static void click(JButton button) {
            SwingUtilities.invokeLater(() -> button.doClick(0));
        }

        private static <T> T find(Container root, Class<T> type) {
            List<T> found = all(root, type);
            return found.isEmpty() ? null : found.get(0);
        }

        private static <T> List<T> all(Container root, Class<? super T> type) {
            List<T> found = new ArrayList<>();
            collect(root, type, found);
            return found;
        }

        @SuppressWarnings("unchecked")
        private static <T> void collect(Container root, Class<? super T> type, List<T> found) {
            for (Component child : root.getComponents()) {
                if (type.isInstance(child)) {
                    found.add((T) child);
                }
                if (child instanceof Container) {
                    collect((Container) child, type, found);
                }
            }
        }

        private static Object field(Object owner, String name) {
            try {
                Field field = owner.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private static String read(Path file) {
            try {
                return Files.readString(file).strip();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void write(Path file, String text) {
            try {
                Files.writeString(file, text);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---------------------------------------------------------------- 假裝網路斷線

    /** 客戶端到服務器中間的一段，可以切斷，切斷的這段時間連不上。 */
    private static final class Proxy {
        private final int target;
        private final ServerSocket listener = new ServerSocket(0);
        private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());
        private volatile long blockedUntil;

        Proxy(int target) throws IOException {
            this.target = target;
            daemon(this::acceptLoop);
        }

        int port() {
            return listener.getLocalPort();
        }

        void drop(int blockMs) {
            blockedUntil = System.currentTimeMillis() + blockMs;
            synchronized (open) {
                for (Socket socket : open) {
                    closeQuietly(socket);
                }
                open.clear();
            }
        }

        private void acceptLoop() {
            while (true) {
                try {
                    Socket inbound = listener.accept();
                    if (System.currentTimeMillis() < blockedUntil) {
                        closeQuietly(inbound);
                        continue;
                    }
                    Socket outbound = new Socket("127.0.0.1", target);
                    open.add(inbound);
                    open.add(outbound);
                    daemon(() -> pump(inbound, outbound));
                    daemon(() -> pump(outbound, inbound));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private static void pump(Socket from, Socket to) {
            byte[] buffer = new byte[8192];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
                // 切斷了
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException e) {
                // 本來就要關了
            }
        }

        private static void daemon(Runnable task) {
            Thread thread = new Thread(task, "trial-proxy");
            thread.setDaemon(true);
            thread.start();
        }
    }
}
