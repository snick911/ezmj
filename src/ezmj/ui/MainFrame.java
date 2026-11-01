package ezmj.ui;

import java.awt.CardLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import ezmj.AppVersion;
import ezmj.bot.Bot;
import ezmj.bot.ComputerBot;
import ezmj.bot.SeatedBots;
import ezmj.engine.Achievement;
import ezmj.engine.AchievementBook;
import ezmj.engine.GameSave;
import ezmj.engine.GameSession;
import ezmj.engine.GameView;
import ezmj.engine.PlayerStats;
import ezmj.engine.Round;
import ezmj.engine.Settlement;
import ezmj.model.AppSettings;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.GameRecord;
import ezmj.model.GameSpeed;
import ezmj.model.Gender;
import ezmj.model.HonorRoll;
import ezmj.model.MusicTrack;
import ezmj.model.Resolution;
import ezmj.model.Tile;
import ezmj.online.OnlineProfile;
import ezmj.online.RoomSettings;

/** 主視窗：以 CardLayout 切換開始畫面與牌桌。 */
public final class MainFrame extends JFrame {

    private static final String CARD_START = "start";
    private static final String CARD_TABLE = "table";
    private static final String CARD_ONLINE = "online";
    private static final String CARD_ROOM = "room";
    private static final int DRAWN_RESULT_SECONDS = 10;
    private static final int RESULT_SECONDS = 60;
    private static final Path SETTINGS_FILE = Path.of(System.getProperty("user.home"), ".ezmj", "settings.properties");
    private static final Path HONOR_FILE = Path.of(System.getProperty("user.home"), ".ezmj", "honor.properties");
    private static final Path ACHIEVEMENT_FILE = Path.of(System.getProperty("user.home"), ".ezmj", "achievements.properties");
    private static final Path STATS_FILE = Path.of(System.getProperty("user.home"), ".ezmj", "stats.properties");
    private static final Path SAVE_FILE = Path.of(System.getProperty("user.home"), ".ezmj", "save.properties");
    private static final Path VOICE_DIR =Path.of(System.getProperty("user.home"), ".ezmj", "voice");
    private static final Path ONLINE_FILE = Path.of(System.getProperty("user.home"), ".ezmj", "online.properties");

    // 要在建立任何畫面之前讀取，字型才會一開始就是設定的大小
    private final AppSettings settings = loadSettings();
    private final HonorRoll honorRoll = HonorRoll.load(HONOR_FILE);
    private final AchievementBook achievements = AchievementBook.load(ACHIEVEMENT_FILE);
    private final PlayerStats stats = PlayerStats.load(STATS_FILE);
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final StartPanel startPanel = new StartPanel(this::startGame, this::resumeGame, () -> showOnlineForm(null));
    private final OnlinePanel onlinePanel = new OnlinePanel(new OnlinePanel.Actions() {
        @Override
        public void create(String name, RoomSettings roomSettings) {
            connecting().createRoom(name, roomSettings);
        }

        @Override
        public void join(String name, String code) {
            connecting().joinRoom(name, code);
        }

        @Override
        public void resume(OnlineProfile profile) {
            connecting().resume(profile);
        }

        @Override
        public void back() {
            leaveOnline();
            cards.show(root, CARD_START);
            startPanel.focusName();
        }
    });
    private final HelpDialog helpDialog = new HelpDialog(this);
    private final JCheckBoxMenuItem voiceItem = checkItem("語音", settings.voiceEnabled(), this::changeVoice);
    private TablePanel tablePanel;
    private GameController controller;
    private OnlineController online;
    private RoomPanel roomPanel;
    private HistoryDialog historyDialog;
    private boolean achievementSaveWarned;
    private boolean statsSaveWarned;
    private boolean gameSaveWarned;
    private boolean hasSavedGame;
    /** 這場牌局的亂數種子，出錯時用來重現。 */
    private long gameSeed;

    public MainFrame() {
        super("台灣十六張麻將 v" + AppVersion.get());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmExit();
            }
        });
        ErrorReporter.setContext(this::describeGame);
        ErrorReporter.setUiHandler(this::recoverFromError);
        setIconImages(List.of(TilePainter.appIcon(32), TilePainter.appIcon(64)));
        root.add(startPanel, CARD_START);
        root.add(onlinePanel, CARD_ONLINE);
        setContentPane(root);
        setJMenuBar(buildMenuBar());
        refreshResume();

        setMinimumSize(new Dimension(1100, 720));
        applyResolution(settings.resolution());
        SoundEffects.setVolume(settings.soundVolume());
        if (settings.soundEnabled()) {
            SoundEffects.preload();
        }
        BackgroundMusic.setVolume(settings.musicVolume());
        BackgroundMusic.select(settings.music());
        VoiceLines.setVolume(settings.voiceVolume());
        if (settings.voiceEnabled()) {
            prepareVoice();
        }
    }

    private void prepareVoice() {
        VoiceLines.prepare(VOICE_DIR, () -> SwingUtilities.invokeLater(() -> {
            voiceItem.setText("語音（這台電腦沒有中文語音）");
            voiceItem.setEnabled(false);
        }));
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
        Theme.setTilePercent(loaded.tilePercent());
        return loaded;
    }

    private JMenuBar buildMenuBar() {
        JMenu preferences = topMenu("環境設定");
        preferences.add(buildFontMenu());
        preferences.add(buildTileMenu());
        preferences.add(menuItem("等待時間…", this::editWaitSeconds));
        preferences.add(buildSpeedMenu());
        preferences.add(buildResolutionMenu());
        preferences.add(buildMusicMenu());
        preferences.add(menuItem("音量…", this::editVolumes));
        preferences.add(checkItem("音效", settings.soundEnabled(), this::changeSound));
        preferences.add(voiceItem);
        preferences.add(buildPlayerVoiceMenu());
        preferences.add(checkItem("動畫", settings.animationEnabled(), this::changeAnimation));

        JMenu honor = topMenu("榮譽榜");
        for (HonorRoll.Category category : HonorRoll.Category.values()) {
            honor.add(menuItem(category.label(), () -> helpDialog.showHonorRoll(honorRoll, category)));
        }

        JMenu achievementMenu = topMenu("成就");
        achievementMenu.add(menuItem("全部成就", () -> helpDialog.showAchievements(achievements, null)));
        for (Achievement.Group group : Achievement.Group.values()) {
            achievementMenu.add(menuItem(group.label(), () -> helpDialog.showAchievements(achievements, group)));
        }

        JMenu statsMenu = topMenu("統計");
        statsMenu.add(menuItem("我的統計", () -> helpDialog.showStats(stats)));

        JMenu help = topMenu("說明");
        help.add(menuItem("基本規則", helpDialog::showRules));
        help.add(menuItem("台數計算", helpDialog::showScoring));

        JMenuBar bar = new JMenuBar();
        bar.setBackground(Theme.PANEL);
        bar.setBorderPainted(false);
        bar.add(preferences);
        bar.add(honor);
        bar.add(achievementMenu);
        bar.add(statsMenu);
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

    private void changeVoice(boolean enabled) {
        settings.setVoiceEnabled(enabled);
        saveSettings();
        if (enabled) {
            prepareVoice();
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

    private JMenu buildSpeedMenu() {
        JMenu menu = new JMenu("遊戲速度");
        menu.setFont(Theme.font(Font.PLAIN, 15f));
        ButtonGroup group = new ButtonGroup();
        for (GameSpeed speed : GameSpeed.values()) {
            String suffix = speed == GameSpeed.NORMAL ? "（預設）" : "";
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(speed.label() + suffix, speed == settings.gameSpeed());
            item.setFont(Theme.font(Font.PLAIN, 15f));
            item.addActionListener(e -> changeGameSpeed(speed));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    /** 下一個動作就會用新的速度。 */
    private void changeGameSpeed(GameSpeed speed) {
        settings.setGameSpeed(speed);
        saveSettings();
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

    private JMenu buildMusicMenu() {
        JMenu menu = new JMenu("背景音樂");
        menu.setFont(Theme.font(Font.PLAIN, 15f));
        ButtonGroup group = new ButtonGroup();
        for (MusicTrack track : MusicTrack.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(track.label(), track == settings.music());
            item.setFont(Theme.font(Font.PLAIN, 15f));
            item.addActionListener(e -> changeMusic(track));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    private JMenu buildPlayerVoiceMenu() {
        JMenu menu = new JMenu("我的語音");
        menu.setFont(Theme.font(Font.PLAIN, 15f));
        ButtonGroup group = new ButtonGroup();
        for (Gender gender : Gender.values()) {
            String suffix = gender == Gender.FEMALE ? "（預設）" : "";
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(gender.voiceLabel() + suffix,
                    gender == settings.playerGender());
            item.setFont(Theme.font(Font.PLAIN, 15f));
            item.addActionListener(e -> changePlayerVoice(gender));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    /** 選了就喊一聲，聽聽看。牌局中改也會立刻生效。 */
    private void changePlayerVoice(Gender gender) {
        settings.setPlayerGender(gender);
        saveSettings();
        if (settings.voiceEnabled()) {
            VoiceLines.play(VoiceLines.Line.PONG, gender);
        }
    }

    private void changeMusic(MusicTrack track) {
        settings.setMusic(track);
        saveSettings();
        BackgroundMusic.select(track);
    }

    /** 拉動時立刻生效；音效與語音拉完會響一聲，聽聽看大小。 */
    private void editVolumes() {
        JSlider music = volumeSlider(settings.musicVolume());
        music.addChangeListener(e -> {
            settings.setMusicVolume(music.getValue());
            BackgroundMusic.setVolume(music.getValue());
        });
        JSlider sound = volumeSlider(settings.soundVolume());
        sound.addChangeListener(e -> {
            settings.setSoundVolume(sound.getValue());
            SoundEffects.setVolume(sound.getValue());
            if (!sound.getValueIsAdjusting() && settings.soundEnabled()) {
                SoundEffects.play(SoundEffects.Cue.DISCARD);
            }
        });
        JSlider voice = volumeSlider(settings.voiceVolume());
        voice.addChangeListener(e -> {
            settings.setVoiceVolume(voice.getValue());
            VoiceLines.setVolume(voice.getValue());
            if (!voice.getValueIsAdjusting() && settings.voiceEnabled()) {
                VoiceLines.play(VoiceLines.Line.PONG, settings.playerGender());
            }
        });
        Object[] message = {"背景音樂", music, "音效", sound, "語音", voice};
        JOptionPane.showMessageDialog(this, message, "音量", JOptionPane.PLAIN_MESSAGE);
        saveSettings();
    }

    private static JSlider volumeSlider(int value) {
        JSlider slider = new JSlider(0, 100, value);
        slider.setMajorTickSpacing(25);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setFont(Theme.font(Font.PLAIN, 13f));
        slider.setPreferredSize(new Dimension(Theme.scaled(360), slider.getPreferredSize().height));
        return slider;
    }

    private void changeResolution(Resolution resolution) {
        settings.setResolution(resolution);
        saveSettings();
        applyResolution(resolution);
    }

    private JMenu buildTileMenu() {
        JMenu menu = new JMenu("麻將大小");
        menu.setFont(Theme.font(Font.PLAIN, 15f));
        ButtonGroup group = new ButtonGroup();
        for (int percent : AppSettings.TILE_PERCENTS) {
            String suffix = percent == AppSettings.DEFAULT_TILE_PERCENT ? "（預設）" : "";
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(percent + "%" + suffix,
                    percent == settings.tilePercent());
            item.setFont(Theme.font(Font.PLAIN, 15f));
            item.addActionListener(e -> changeTilePercent(percent));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    private void changeTilePercent(int percent) {
        settings.setTilePercent(percent);
        saveSettings();
        Theme.setTilePercent(percent);
        repaint();
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

    /** 開始一場新牌局。存檔只有一份，開新局會蓋掉，先問一聲。 */
    public void startGame(GameConfig config) {
        if (hasSavedGame && JOptionPane.showConfirmDialog(this, "開新的牌局會蓋掉上次的存檔，確定嗎？", "開始遊戲",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        long seed = new Random().nextLong();
        openTable(new GameSession(config, new Random(seed)), seed).startRound();
    }

    private void resumeGame() {
        try {
            GameSave.Loaded saved = GameSave.load(SAVE_FILE);
            openTable(saved.session(), saved.seed()).resume(saved);
        } catch (IOException e) {
            ErrorReporter.note("讀取牌局存檔", e);
            JOptionPane.showMessageDialog(this, "上次的牌局讀不出來，只能開新的牌局。", "繼續牌局", JOptionPane.WARNING_MESSAGE);
            refreshResume();
        }
    }

    /** 擺好牌桌，還沒開始打。電腦的亂數也由種子決定，這樣整場才重現得出來。 */
    private GameController openTable(GameSession session, long seed) {
        GameConfig config = session.config();
        gameSeed = seed;
        Bot bot = new SeatedBots(config.botLevels(), List.of(session.personality(1), session.personality(2), session.personality(3)),
                config.basePay(), config.taiPay(), new Random(seed + 1));
        Bot proxy = new ComputerBot(BotLevel.PRO, config.basePay(), config.taiPay(), new Random(seed + 2));
        controller = new GameController(session, bot, proxy, this::handleRoundEnd, settings, this::saveGame);
        if (tablePanel != null) {
            root.remove(tablePanel);
        }
        GameController current = controller;
        closeHistory();
        tablePanel = new TablePanel(tile -> current.humanDiscard(tile), this::confirmLeave, current::toggleAutoPlay,
                () -> showHistory(session));
        controller.attach(tablePanel);
        root.add(tablePanel, CARD_TABLE);
        cards.show(root, CARD_TABLE);
        return current;
    }

    /** 連線對戰的入口。連線牌局不碰本機的存檔、統計、成就與榮譽榜。 */
    private void showOnlineForm(String message) {
        online = null;
        onlinePanel.setProfile(OnlineProfile.load(ONLINE_FILE));
        cards.show(root, CARD_ONLINE);
        if (message != null) {
            JOptionPane.showMessageDialog(this, message, "連線對戰", JOptionPane.WARNING_MESSAGE);
        }
    }

    private OnlineController connecting() {
        leaveOnline();
        onlinePanel.setBusy("正在連線，服務器閒置後第一次可能要等一分鐘…");
        online = new OnlineController(onlineHost(), settings, ONLINE_FILE);
        return online;
    }

    private void leaveOnline() {
        if (online != null) {
            online.shutdown();
            online = null;
        }
    }

    private OnlineController.Host onlineHost() {
        return new OnlineController.Host() {
            @Override
            public Frame frame() {
                return MainFrame.this;
            }

            @Override
            public void showRoom(RoomPanel room) {
                if (roomPanel != null) {
                    root.remove(roomPanel);
                }
                roomPanel = room;
                root.add(room, CARD_ROOM);
                cards.show(root, CARD_ROOM);
            }

            @Override
            public void showTable(TablePanel table) {
                if (tablePanel != null) {
                    root.remove(tablePanel);
                }
                tablePanel = table;
                root.add(table, CARD_TABLE);
                cards.show(root, CARD_TABLE);
            }

            @Override
            public void showHistory(GameView game) {
                MainFrame.this.showHistory(game);
            }

            @Override
            public void refreshHistory() {
                if (historyDialog != null && historyDialog.isDisplayable()) {
                    historyDialog.refresh();
                }
            }

            @Override
            public void closeHistory() {
                MainFrame.this.closeHistory();
            }

            @Override
            public void backToForm(String message) {
                showOnlineForm(message);
            }
        };
    }

    /** 開始畫面的繼續按鈕跟著存檔走；讀不懂的存檔就當作沒有，開新局時會蓋掉。 */
    private void refreshResume() {
        hasSavedGame = false;
        String summary = null;
        if (Files.exists(SAVE_FILE)) {
            try {
                GameSession saved = GameSave.load(SAVE_FILE).session();
                summary = saved.config().playerName() + "：" + Tile.windName(saved.roundWind()) + "風圈，第 "
                        + (saved.roundsPlayed() + 1) + " 局，籌碼 " + saved.player(0).chips();
                hasSavedGame = true;
            } catch (IOException e) {
                ErrorReporter.note("讀取牌局存檔", e);
            }
        }
        startPanel.setResume(summary);
    }

    /** 每次輪到真人決定時都會存，存不了只提醒一次。 */
    private void saveGame() {
        if (controller == null) {
            return;
        }
        try {
            controller.save(SAVE_FILE, gameSeed);
        } catch (IOException e) {
            ErrorReporter.note("儲存牌局", e);
            if (!gameSaveWarned) {
                gameSaveWarned = true;
                JOptionPane.showMessageDialog(this, "牌局無法存檔，關掉遊戲之後就接不回來。\n" + e.getMessage(),
                        "牌局存檔", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void deleteSavedGame() {
        try {
            Files.deleteIfExists(SAVE_FILE);
        } catch (IOException e) {
            ErrorReporter.note("刪除牌局存檔", e);
        }
    }

    private String describeGame() {
        if (online != null) {
            return online.describe();
        }
        return controller == null ? "不在牌局中" : "種子 " + gameSeed + "，" + controller.describe();
    }

    /** 畫面執行緒出錯時牌局的流程已經斷了。回到開始畫面，存檔還是上一次輪到真人時的狀態，可以接著打。 */
    private void recoverFromError() {
        boolean inGame = controller != null;
        if (inGame) {
            returnToStart();
        } else if (online != null) {
            // 連線牌局的 token 還留著，可以從連線對戰接回
            leaveOnline();
            returnToStart();
        }
        JOptionPane.showMessageDialog(this, "遊戲發生錯誤，內容記在：\n" + ErrorReporter.logFile()
                        + (inGame && hasSavedGame ? "\n\n牌局存到上一次輪到你的時候，可以按「繼續上次的牌局」接著打。" : ""),
                "錯誤", JOptionPane.ERROR_MESSAGE);
    }

    public GameController controller() {
        return controller;
    }

    /** 已經開著就拿到最前面，不開第二個。 */
    private void showHistory(GameView game) {
        if (historyDialog == null || !historyDialog.isDisplayable()) {
            historyDialog = new HistoryDialog(this, Dialog.ModalityType.MODELESS, game);
        }
        historyDialog.setVisible(true);
        historyDialog.toFront();
    }

    private void closeHistory() {
        if (historyDialog != null) {
            historyDialog.dispose();
            historyDialog = null;
        }
    }

    /** 結算完先把該存的都存好，這時程式被關掉也不會少記。回傳的動作才跳出結果。 */
    private Runnable handleRoundEnd(GameSession session, Round round, Settlement settlement, Runnable next) {
        if (historyDialog != null && historyDialog.isDisplayable()) {
            historyDialog.refresh();
        }
        boolean over = session.isOver();
        List<Achievement> earned = List.of();
        if (!controller.usedAutoPlay()) {
            earned = achievements.recordRound(session, LocalDate.now().toString());
            saveAchievements();
            stats.recordRound(session);
            if (over) {
                stats.recordGame(session);
            }
            saveStats();
        }
        String note = over ? recordGame(session) : "";
        List<Achievement> gameEarned = over ? recordGameAchievements(session) : List.of();
        if (over) {
            deleteSavedGame();
        } else {
            saveGame();
        }
        List<Achievement> roundEarned = earned;
        return () -> showRoundEnd(session, round, settlement, roundEarned, over, note, gameEarned, next);
    }

    private void showRoundEnd(GameSession session, Round round, Settlement settlement, List<Achievement> earned, boolean over,
                              String note, List<Achievement> gameEarned, Runnable next) {
        new ResultDialog(this, session, round, settlement, earned, over ? "查看最終排名" : "下一局",
                round.result().isDrawnGame() ? DRAWN_RESULT_SECONDS : RESULT_SECONDS).setVisible(true);
        if (controller == null) {
            return;
        }
        if (over) {
            new FinalDialog(this, session, note, gameEarned, "回到開始畫面").setVisible(true);
            returnToStart();
        } else {
            next.run();
        }
    }

    private void saveStats() {
        try {
            stats.save(STATS_FILE);
        } catch (IOException e) {
            if (!statsSaveWarned) {
                statsSaveWarned = true;
                JOptionPane.showMessageDialog(this, "統計無法儲存，下次開啟時會不見。\n" + e.getMessage(),
                        "統計", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private List<Achievement> recordGameAchievements(GameSession session) {
        if (controller.usedAutoPlay()) {
            return List.of();
        }
        List<Achievement> earned = achievements.recordGame(session, controller.averageThinkSeconds(),
                controller.thinkCount(), LocalDate.now().toString());
        saveAchievements();
        return earned;
    }

    /** 每局都會存，存不了只提醒一次，不要每局跳一個視窗。 */
    private void saveAchievements() {
        try {
            achievements.save(ACHIEVEMENT_FILE);
        } catch (IOException e) {
            if (!achievementSaveWarned) {
                achievementSaveWarned = true;
                JOptionPane.showMessageDialog(this, "成就無法儲存，下次開啟時會不見。\n" + e.getMessage(),
                        "成就", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    /** 牌局打到結束才記錄，中途離開或用過電腦代打都不算。回傳榮譽榜的結果，沒有要說的就是空字串。 */
    private String recordGame(GameSession session) {
        GameRecord record = session.record(0, LocalDate.now().toString());
        if (controller.usedAutoPlay()) {
            return "這場用過電腦代打，不列入榮譽榜；開代打之後的局也不列入成就。";
        }
        Map<HonorRoll.Category, Integer> ranks = honorRoll.add(record);
        if (!ranks.isEmpty()) {
            try {
                honorRoll.save(HONOR_FILE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "榮譽榜無法儲存，這場成績下次開啟時會不見。\n" + e.getMessage(),
                        "榮譽榜", JOptionPane.WARNING_MESSAGE);
            }
        }
        if (ranks.isEmpty()) {
            return "";
        }
        return "登上榮譽榜：" + ranks.entrySet().stream()
                .map(entry -> entry.getKey().label() + "第 " + entry.getValue() + " 名")
                .collect(Collectors.joining("、"));
    }

    private void confirmLeave() {
        if (controller.isOver() || askToSave("離開牌局", "要存檔之後再離開嗎？之後可以從開始畫面繼續。", "存檔並離開", "不存檔離開")) {
            returnToStart();
        }
    }

    private void confirmExit() {
        if (online != null && !online.confirmExit()) {
            return;
        }
        if (controller == null || controller.isOver()
                || askToSave("關閉遊戲", "要存檔之後再關閉嗎？下次可以從開始畫面繼續。", "存檔並關閉", "不存檔關閉")) {
            dispose();
            System.exit(0);
        }
    }

    /** 問要不要存，照選的存好或刪掉；回傳是否真的要離開，按取消就是 false。 */
    private boolean askToSave(String title, String message, String saveLabel, String discardLabel) {
        String[] options = {saveLabel, discardLabel, "取消"};
        int answer = JOptionPane.showOptionDialog(this, message, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer == 0) {
            saveGame();
        } else if (answer == 1) {
            deleteSavedGame();
        }
        return answer == 0 || answer == 1;
    }

    private void returnToStart() {
        closeHistory();
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        refreshResume();
        cards.show(root, CARD_START);
        startPanel.focusName();
    }
}
