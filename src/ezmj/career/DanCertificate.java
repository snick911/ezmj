package ezmj.career;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;

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
    private static final Random NUMBERS = new Random();

    /**
     * 拿到段位的那一天、當時那一段看的局數、胡牌數、自摸數、放槍數，與證號的前 13 碼（serial）。
     * 放槍數是後來才記的，以前發的證照不知道，是 UNKNOWN。
     * 證號的規則見 CertificateNumber：serial 升段時決定，存起來之後不變；最後三碼驗證碼照證書上的名字算，見 number。
     */
    public record Grant(Dan dan, String date, int rounds, int wins, int selfDraws, int dealIns, String serial) {

        /** 不知道的數字。 */
        public static final int UNKNOWN = -1;

        /** 沒有 serial 的（0.15.0 的存檔、測試）由段位、日期與成績算出一個，每次都一樣。 */
        public Grant(Dan dan, String date, int rounds, int wins, int selfDraws, int dealIns) {
            this(dan, date, rounds, wins, selfDraws, dealIns,
                    CertificateNumber.derivedBody(dan, date, rounds, wins, selfDraws));
        }

        public double winRate() {
            return rounds == 0 ? 0 : wins / (double) rounds;
        }

        public double selfDrawRate() {
            return rounds == 0 ? 0 : selfDraws / (double) rounds;
        }

        /** 放槍數不知道時是 false。 */
        public boolean knowsDealIns() {
            return dealIns >= 0;
        }

        public double dealInRate() {
            return rounds == 0 || dealIns < 0 ? 0 : dealIns / (double) rounds;
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

    /** 16 碼證號，還沒入段是 null。驗證碼照證書上的名字算，名字固定之後證號就不變。 */
    public String number() {
        return grant == null ? null : CertificateNumber.full(grant.serial(), name);
    }

    /** 證書上的名字，還不知道時是空字串。玩家不能改：練習牌局是第一局的名字，修業檔案是檔案的名字。 */
    public String name() {
        return name;
    }

    /** 練習牌局：還沒有名字時才填，用第一次記進統計那一局的名字，之後換名字打也不變。回傳有沒有改。 */
    public boolean fillName(String playerName) {
        String trimmed = trimmed(playerName);
        if (!name.isEmpty() || trimmed.isEmpty()) {
            return false;
        }
        name = trimmed;
        return true;
    }

    /** 修業檔案：一律是建立檔案時取的名字。回傳有沒有改。 */
    public boolean matchProfile(String profileName) {
        String trimmed = trimmed(profileName);
        if (trimmed.isEmpty() || trimmed.equals(name)) {
            return false;
        }
        name = trimmed;
        return true;
    }

    private static String trimmed(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
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
        grant = new Grant(best, date, pool.rounds(), pool.wins(), pool.selfDraws(), pool.dealIns(),
                CertificateNumber.body(best, date, NUMBERS));
        return best;
    }

    private static boolean isSerial(String text) {
        return text.length() == CertificateNumber.BODY_LENGTH && text.startsWith(CertificateNumber.PREFIX);
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
            int dealIns = values.getProperty("dealIns") == null ? Grant.UNKNOWN : number(values, "dealIns");
            Grant grant = new Grant(dan, values.getProperty("date", "").trim(), number(values, "rounds"),
                    number(values, "wins"), number(values, "selfDraws"), dealIns);
            String saved = values.getProperty("serial", "").trim();
            certificate.grant = isSerial(saved) ? new Grant(grant.dan(), grant.date(), grant.rounds(), grant.wins(),
                    grant.selfDraws(), grant.dealIns(), saved) : grant;
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
            if (grant.knowsDealIns()) {
                values.setProperty("dealIns", String.valueOf(grant.dealIns()));
            }
            values.setProperty("serial", grant.serial());
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
