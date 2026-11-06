package ezmj.career;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import ezmj.engine.AchievementBook;
import ezmj.engine.GameSave;
import ezmj.engine.GameSession;
import ezmj.engine.PlayerStats;
import ezmj.engine.Round;
import ezmj.engine.ThinkTimer;
import ezmj.model.HonorRoll;
import ezmj.model.PropertyFiles;
import ezmj.model.PropertyStore;
import ezmj.model.SealedStore;

/**
 * 一套紀錄：成就、統計與榮譽榜，加上打到一半的牌局存檔，都放在同一個資料夾。
 * 練習牌局用使用者目錄的 .ezmj，每個修業檔案在自己的資料夾各有一套，彼此不相干。
 * 全部經過 STORE 封存，玩家用記事本改不了；改過或壞掉的檔案當作讀不懂。
 */
public final class Records {

    /** 玩家的存檔與紀錄一律這樣存。 */
    public static final PropertyStore STORE = SealedStore.INSTANCE;
    static final String SAVE = "save.properties";
    static final List<String> FILES = List.of(SAVE, "achievements.properties", "stats.properties", "honor.properties");
    /** 有這個檔案表示以前的明碼檔案已經封存過，之後明碼的一律當作讀不懂。 */
    private static final String SEALED_MARK = "sealed.properties";

    private final Path dir;
    private final HonorRoll honorRoll;
    private final AchievementBook achievements;
    private final PlayerStats stats;

    private Records(Path dir) {
        this.dir = dir;
        this.honorRoll = HonorRoll.load(honorFile(), STORE);
        this.achievements = AchievementBook.load(achievementFile(), STORE);
        this.stats = PlayerStats.load(statsFile(), STORE);
    }

    /** 讀不到、讀不懂或被改過的檔案當作空的。 */
    public static Records load(Path dir) {
        return new Records(dir);
    }

    /** 練習牌局的紀錄。程式開啟時讀，先把以前的明碼檔案封存起來。 */
    public static Records openHome(Path home) {
        sealLegacy(home);
        return load(home);
    }

    /**
     * 0.9.1 以前的存檔與紀錄是明碼，第一次開新版時原地改成封存的，內容不變，練習牌局的紀錄與存檔都還在。
     * 做完留下記號，之後就不再收明碼的檔案。改不了的檔案（例如本來就壞掉的）留著不動，讀的時候當作讀不懂。
     */
    public static void sealLegacy(Path home) {
        Path mark = home.resolve(SEALED_MARK);
        if (Files.exists(mark)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        dirs.add(home);
        for (CareerSlot slot : CareerSlot.all(home)) {
            dirs.add(slot.dir());
        }
        for (Path dir : dirs) {
            List<String> names = new ArrayList<>(FILES);
            names.add(CareerSlot.PROFILE);
            for (String name : names) {
                Path file = dir.resolve(name);
                try {
                    if (Files.isRegularFile(file) && !SealedStore.isSealed(file)) {
                        STORE.write(PropertyStore.PLAIN.read(file), file);
                    }
                } catch (IOException e) {
                    // 讀不懂的明碼檔案本來就接不回來，留著不動
                }
            }
        }
        Properties values = new Properties();
        values.setProperty(PropertyFiles.FORMAT_KEY, "1");
        try {
            STORE.write(values, mark);
        } catch (IOException e) {
            // 下次開啟再做一次，已經封存的檔案會跳過
        }
    }

    public HonorRoll honorRoll() {
        return honorRoll;
    }

    public AchievementBook achievements() {
        return achievements;
    }

    public PlayerStats stats() {
        return stats;
    }

    public Path saveFile() {
        return dir.resolve(SAVE);
    }

    /** 打到一半的牌局；沒有、讀不懂或被改過都丟 IOException。 */
    public GameSave.Loaded loadGame() throws IOException {
        return GameSave.load(saveFile(), STORE);
    }

    public void saveGame(long seed, GameSession session, Round round, boolean usedAutoPlay, ThinkTimer thinking)
            throws IOException {
        GameSave.save(saveFile(), STORE, seed, session, round, usedAutoPlay, thinking);
    }

    public void saveHonorRoll() throws IOException {
        honorRoll.save(honorFile(), STORE);
    }

    public void saveAchievements() throws IOException {
        achievements.save(achievementFile(), STORE);
    }

    public void saveStats() throws IOException {
        stats.save(statsFile(), STORE);
    }

    private Path honorFile() {
        return dir.resolve("honor.properties");
    }

    private Path achievementFile() {
        return dir.resolve("achievements.properties");
    }

    private Path statsFile() {
        return dir.resolve("stats.properties");
    }
}
