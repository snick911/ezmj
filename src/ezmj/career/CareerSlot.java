package ezmj.career;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 修業檔案的一格，資料夾是 .ezmj\career\1 到 3。裡面有 profile.properties 才算有檔案，
 * 成就、統計、榮譽榜與牌局存檔都在同一個資料夾，見 Records。
 */
public final class CareerSlot {

    public static final int COUNT = 3;
    private static final String[] LABELS = {"檔案一", "檔案二", "檔案三"};
    static final String PROFILE = "profile.properties";

    private final int number;
    private final Path dir;

    private CareerSlot(int number, Path dir) {
        this.number = number;
        this.dir = dir;
    }

    /** home 是 .ezmj 資料夾。 */
    public static List<CareerSlot> all(Path home) {
        List<CareerSlot> slots = new ArrayList<>();
        for (int number = 1; number <= COUNT; number++) {
            slots.add(new CareerSlot(number, home.resolve("career").resolve(String.valueOf(number))));
        }
        return slots;
    }

    /** 從 1 起算。 */
    public int number() {
        return number;
    }

    public String label() {
        return LABELS[number - 1];
    }

    public boolean exists() {
        return Files.exists(dir.resolve(PROFILE));
    }

    Path dir() {
        return dir;
    }

    /** 讀不懂或被改過都丟 IOException。 */
    public CareerProfile loadProfile() throws IOException {
        return CareerProfile.load(dir.resolve(PROFILE), Records.STORE);
    }

    public void saveProfile(CareerProfile profile) throws IOException {
        profile.save(dir.resolve(PROFILE), Records.STORE);
    }

    public Records loadRecords() {
        return Records.load(dir);
    }

    /** 建立新檔案。舊的紀錄先清掉，新檔案從零開始。 */
    public void create(CareerProfile profile) throws IOException {
        delete();
        saveProfile(profile);
    }

    /** 刪掉這格的檔案與紀錄，不能復原。只刪遊戲寫的檔案，資料夾裡有別的檔案就留著。 */
    public void delete() throws IOException {
        List<String> files = new ArrayList<>(Records.FILES);
        files.add(PROFILE);
        files.add(Records.AVATAR);
        for (String file : files) {
            Files.deleteIfExists(dir.resolve(file));
            Files.deleteIfExists(dir.resolve(file + ".tmp"));
        }
        try {
            Files.deleteIfExists(dir);
        } catch (DirectoryNotEmptyException e) {
            // 裡面有不是遊戲寫的檔案，資料夾留著
        }
    }
}
