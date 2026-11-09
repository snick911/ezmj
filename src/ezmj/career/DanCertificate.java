package ezmj.career;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

import ezmj.engine.PlayerStats;
import ezmj.model.PropertyFiles;
import ezmj.model.PropertyStore;

/**
 * 一套紀錄的段位證照：拿到的最高段位、哪一天拿到、當時那一段看的成績，以及證書上的名字與個人圖像。
 * 段位只升不降，成績之後掉下來也不收回；證書上的數字是拿到那天的。
 * 練習牌局與每個修業檔案各有一張，存在那套紀錄的 certificate.properties，和其他紀錄一樣封存。
 */
public final class DanCertificate {

    /** 證書上的名字最多幾個字，和修業檔案的名字一樣。 */
    public static final int MAX_NAME_LENGTH = CareerProfile.MAX_NAME_LENGTH;
    /** 個人圖像用玩家上傳的圖，圖存在 Records.avatarFile()。其他的值是畫面上預設圖像的名稱，空字串是預設的第一張。 */
    public static final String CUSTOM_AVATAR = "custom";
    /** 檔案的格式版本，見 PropertyFiles.FORMAT_KEY。 */
    private static final int FORMAT = 1;

    /** 拿到段位的那一天，與當時那一段看的局數、胡牌數、自摸數。 */
    public record Grant(Dan dan, String date, int rounds, int wins, int selfDraws) {
        public double winRate() {
            return rounds == 0 ? 0 : wins / (double) rounds;
        }

        public double selfDrawRate() {
            return rounds == 0 ? 0 : selfDraws / (double) rounds;
        }
    }

    private Grant grant;
    private String name = "";
    private String avatar = "";

    /** 還沒入段是 null。 */
    public Grant grant() {
        return grant;
    }

    /** 目前的段位，還沒入段是 null。 */
    public Dan dan() {
        return grant == null ? null : grant.dan();
    }

    /** 證書上的名字，還不知道時是空字串。 */
    public String name() {
        return name;
    }

    /** 還沒有名字時才填，例如第一次記進統計的那一局。回傳有沒有改。 */
    public boolean fillName(String playerName) {
        String trimmed = playerName == null ? "" : playerName.trim();
        if (!name.isEmpty() || trimmed.isEmpty()) {
            return false;
        }
        name = trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
        return true;
    }

    /** 玩家自己改名字；空白或超過 MAX_NAME_LENGTH 字丟 IllegalArgumentException。 */
    public void rename(String newName) {
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("名字要 1 到 " + MAX_NAME_LENGTH + " 個字");
        }
        name = trimmed;
    }

    public String avatar() {
        return avatar;
    }

    public void setAvatar(String key) {
        avatar = key == null ? "" : key.trim();
    }

    /**
     * 成績到了比現在更高的段位就換一張新的證照，記下今天與那一段看的成績，回傳升到的段位；沒有升是 null。
     * 一次到好幾段時直接給最高的。
     */
    public Dan promote(PlayerStats stats, String date) {
        Dan best = Dan.best(stats);
        if (best == null || (grant != null && best.compareTo(grant.dan()) <= 0)) {
            return null;
        }
        PlayerStats.Totals pool = best.pool(stats);
        grant = new Grant(best, date, pool.rounds(), pool.wins(), pool.selfDraws());
        return best;
    }

    /** 明碼的文字檔，測試用；遊戲裡用 Records 封存。 */
    public static DanCertificate load(Path file) {
        return load(file, PropertyStore.PLAIN);
    }

    /** 讀不到或讀不懂就是還沒入段；認不得的段位名稱當作還沒入段，讀不懂的數字當作 0。 */
    public static DanCertificate load(Path file, PropertyStore store) {
        DanCertificate certificate = new DanCertificate();
        Properties values;
        try {
            values = store.read(file);
        } catch (IOException e) {
            return certificate;
        }
        certificate.name = values.getProperty("name", "").trim();
        certificate.avatar = values.getProperty("avatar", "").trim();
        Dan dan = Dan.parse(values.getProperty("dan", "").trim());
        if (dan != null) {
            certificate.grant = new Grant(dan, values.getProperty("date", "").trim(), number(values, "rounds"),
                    number(values, "wins"), number(values, "selfDraws"));
        }
        return certificate;
    }

    public void save(Path file) throws IOException {
        save(file, PropertyStore.PLAIN);
    }

    public void save(Path file, PropertyStore store) throws IOException {
        Properties values = new Properties();
        values.setProperty(PropertyFiles.FORMAT_KEY, String.valueOf(FORMAT));
        values.setProperty("name", name);
        values.setProperty("avatar", avatar);
        if (grant != null) {
            values.setProperty("dan", grant.dan().name());
            values.setProperty("date", grant.date());
            values.setProperty("rounds", String.valueOf(grant.rounds()));
            values.setProperty("wins", String.valueOf(grant.wins()));
            values.setProperty("selfDraws", String.valueOf(grant.selfDraws()));
        }
        store.write(values, file);
    }

    private static int number(Properties values, String key) {
        try {
            return Math.max(0, Integer.parseInt(values.getProperty(key, "").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
