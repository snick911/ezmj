package ezmj.ui;

import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import ezmj.AppVersion;
import ezmj.bot.Bot;
import ezmj.bot.ComputerBot;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.Settlement;
import ezmj.model.AppSettings;
import ezmj.model.GameConfig;
import ezmj.model.Player;
import ezmj.model.Resolution;

/** 主視窗：以 CardLayout 切換開始畫面與牌桌。 */
public final class MainFrame extends JFrame {

    private static final String CARD_START = "start";
    private static final String CARD_TABLE = "table";
    private static final Path SETTINGS_FILE = Path.of(System.getProperty("user.home"), ".ezmj", "settings.properties");

    // 要在建立任何畫面之前讀取，字型才會一開始就是設定的大小
    private final AppSettings settings = loadSettings();
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final StartPanel startPanel = new StartPanel(this::startGame);
    private final HelpDialog helpDialog = new HelpDialog(this);
    private TablePanel tablePanel;
    private GameController controller;

    public MainFrame() {
        super("台灣十六張麻將 v" + AppVersion.get());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(List.of(TilePainter.appIcon(32), TilePainter.appIcon(64)));
        root.add(startPanel, CARD_START);
        setContentPane(root);
        setJMenuBar(buildMenuBar());

        setMinimumSize(new Dimension(1100, 720));
        applyResolution(settings.resolution());
        if (settings.soundEnabled()) {
            SoundEffects.preload();
        }
    }

    /** 比螢幕大時縮到螢幕內。最大化之前先設好一般大小，還原視窗時才有合理的大小。 */
    private void applyResolution(Resolution resolution) {
        Resolution normal = resolution.isMaximized() ? Resolution.DEFAULT : resolution;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setExtendedState(NORMAL);
        setSize(Math.min(normal.width(), screen.width), Math.min(normal.height(), screen.height - 40));
        setLocationRelativeTo(null);
        if (resolution.isMaximized()) {
            setExtendedState(MAXIMIZED_BOTH);
        }
    }

    private static AppSettings loadSettings() {
        AppSettings loaded = AppSettings.load(SETTINGS_FILE);
        Theme.setFontPercent(loaded.fontPercent());
        return loaded;
    }

    private JMenuBar buildMenuBar() {
        JMenu preferences = topMenu("環境設定");
        preferences.add(buildFontMenu());
        preferences.add(menuItem("等待時間…", this::editWaitSeconds));
        preferences.add(buildResolutionMenu());
        preferences.add(checkItem("音效", settings.soundEnabled(), this::changeSound));
        preferences.add(checkItem("動畫", settings.animationEnabled(), this::changeAnimation));

        JMenu help = topMenu("說明");
        help.add(menuItem("基本規則", helpDialog::showRules));
        help.add(menuItem("台數計算", helpDialog::showScoring));

        JMenuBar bar = new JMenuBar();
        bar.setBackground(Theme.PANEL);
        bar.setBorderPainted(false);
        bar.add(preferences);
        bar.add(help);
        return bar;
    }

    private static JMenu topMenu(String text) {
        JMenu menu = new JMenu(text);
        menu.setFont(Theme.font(Font.PLAIN, 15f));
        menu.setForeground(Theme.TEXT);
        return menu;
    }

    private static JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(Theme.font(Font.PLAIN, 15f));
        item.addActionListener(e -> action.run());
        return item;
    }

    private static JCheckBoxMenuItem checkItem(String text, boolean selected, Consumer<Boolean> action) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, selected);
        item.setFont(Theme.font(Font.PLAIN, 15f));
        item.addActionListener(e -> action.accept(item.isSelected()));
        return item;
    }

    private void changeSound(boolean enabled) {
        settings.setSoundEnabled(enabled);
        saveSettings();
        if (enabled) {
            SoundEffects.preload();
        }
    }

    private void changeAnimation(boolean enabled) {
        settings.setAnimationEnabled(enabled);
        saveSettings();
    }

    private JMenu buildFontMenu() {
        JMenu menu = new JMenu("字體大小");
        menu.setFont(Theme.font(Font.PLAIN, 15f));
        ButtonGroup group = new ButtonGroup();
        for (int percent : AppSettings.FONT_PERCENTS) {
            String suffix = percent == AppSettings.DEFAULT_FONT_PERCENT ? "（預設）" : "";
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(percent + "%" + suffix,
                    percent == settings.fontPercent());
            item.setFont(Theme.font(Font.PLAIN, 15f));
            item.addActionListener(e -> changeFontPercent(percent));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    /** 放不進螢幕的解析度不能選。 */
    private JMenu buildResolutionMenu() {
        JMenu menu = new JMenu("解析度");
        menu.setFont(Theme.font(Font.PLAIN, 15f));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        ButtonGroup group = new ButtonGroup();
        for (Resolution resolution : Resolution.OPTIONS) {
            String suffix = resolution.equals(Resolution.DEFAULT) ? "（預設）" : "";
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(resolution.label() + suffix,
                    resolution.equals(settings.resolution()));
            item.setFont(Theme.font(Font.PLAIN, 15f));
            item.setEnabled(resolution.width() <= screen.width && resolution.height() <= screen.height);
            item.addActionListener(e -> changeResolution(resolution));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    private void changeResolution(Resolution resolution) {
        settings.setResolution(resolution);
        saveSettings();
        applyResolution(resolution);
    }

    private void changeFontPercent(int percent) {
        settings.setFontPercent(percent);
        saveSettings();
        Theme.setFontPercent(percent, getRootPane(), helpDialog);
        startPanel.syncSizes();
        if (tablePanel != null) {
            tablePanel.syncSizes();
        }
        helpDialog.refreshFonts();
    }

    private void editWaitSeconds() {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(settings.waitSeconds(),
                AppSettings.NO_WAIT_LIMIT, AppSettings.MAX_WAIT_SECONDS, 1));
        spinner.setFont(Theme.font(Font.PLAIN, 15f));
        Object[] message = {
            "每次等待的秒數，-1 表示不限時。",
            "時間到還沒決定：吃、碰、槓、胡視為放棄，輪到出牌則打出最右邊的手牌。",
            spinner,
        };
        int answer = JOptionPane.showConfirmDialog(this, message, "等待時間", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            spinner.commitEdit();
        } catch (ParseException e) {
            return;
        }
        settings.setWaitSeconds((Integer) spinner.getValue());
        saveSettings();
        if (controller != null) {
            controller.restartCountdown();
        }
    }

    private void saveSettings() {
        try {
            settings.save(SETTINGS_FILE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "環境設定無法儲存，下次開啟會恢復原本的設定。\n" + e.getMessage(),
                    "環境設定", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 開始一場新牌局。 */
    public void startGame(GameConfig config) {
        GameSession session = new GameSession(config, new Random());
        Bot bot = new ComputerBot(config.botLevel(), config.basePay(), config.taiPay(), new Random());
        controller = new GameController(session, bot, this::handleRoundEnd, settings);
        if (tablePanel != null) {
            root.remove(tablePanel);
        }
        GameController current = controller;
        tablePanel = new TablePanel(tile -> current.humanDiscard(tile), this::confirmLeave);
        controller.attach(tablePanel);
        root.add(tablePanel, CARD_TABLE);
        cards.show(root, CARD_TABLE);
        controller.startRound();
    }

    public GameController controller() {
        return controller;
    }

    private void handleRoundEnd(GameSession session, Round round, Settlement settlement, Runnable next) {
        boolean over = session.isOver();
        new ResultDialog(this, session, round, settlement, over ? "查看最終排名" : "下一局").setVisible(true);
        if (controller == null) {
            return;
        }
        if (over) {
            showFinalRanking(session);
            returnToStart();
        } else {
            next.run();
        }
    }

    private void showFinalRanking(GameSession session) {
        Player[] ranking = {session.player(0), session.player(1), session.player(2), session.player(3)};
        Arrays.sort(ranking, Comparator.comparingInt(Player::chips).reversed());
        String text = Arrays.stream(ranking)
                .map(player -> player.name() + "：" + player.chips())
                .collect(Collectors.joining("\n"));
        JOptionPane.showMessageDialog(this, text, "牌局結束", JOptionPane.INFORMATION_MESSAGE);
    }

    private void confirmLeave() {
        int answer = JOptionPane.showConfirmDialog(this, "確定要離開這場牌局嗎？", "離開牌局",
                JOptionPane.YES_NO_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            returnToStart();
        }
    }

    private void returnToStart() {
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        cards.show(root, CARD_START);
        startPanel.focusName();
    }
}
