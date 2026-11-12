package ezmj.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * 榮譽榜：每個類別各自留下數值最高的十筆。贏錢金額另外照底與每台分榜，每組底台各留十筆，
 * 不同底台的輸贏不放在同一個榜上比。存成文字檔，讀不懂的那筆會略過，不影響其他紀錄。
 */
public final class HonorRoll {

    public static final int SIZE = 10;
    /** 贏錢金額最多留幾組底台。超過時拿掉最久沒有新紀錄的那組，檔案與服務器的訊息才不會一直變大。 */
    public static final int MAX_STAKES = 20;
    /** 檔案的格式版本，見 PropertyFiles.FORMAT_KEY。第 2 版起有分底台的贏錢榜。 */
    private static final int FORMAT = 2;
    /** 分底台的贏錢榜存成 WINNINGS.底x台.名次.欄位；WINNINGS.名次.欄位 是不分底台的那份，留給舊版讀。 */
    private static final String STAKES_PREFIX = "WINNINGS.";

    public enum Category {
        WINS("胡牌數", GameRecord::wins),
        SELF_DRAWS("自摸數", GameRecord::selfDraws),
        WINNINGS("贏錢金額", GameRecord::winnings),
        DEAL_INS("放槍數", GameRecord::dealIns);

        private final String label;
        private final ToIntFunction<GameRecord> value;

        Category(String label, ToIntFunction<GameRecord> value) {
            this.label = label;
            this.value = value;
        }

        public String label() {
            return label;
        }

        public int value(GameRecord record) {
            return value.applyAsInt(record);
        }

        /** 說名次時用的名稱：贏錢金額要說是哪一組底台的榜。 */
        public String rankLabel(GameRecord record) {
            return this == WINNINGS ? label + "（" + Stakes.of(record).label() + "）" : label;
        }
    }

    /** 一組底與每台，先比底再比每台。 */
    public record Stakes(int basePay, int taiPay) implements Comparable<Stakes> {

        public static Stakes of(GameRecord record) {
            return new Stakes(record.basePay(), record.taiPay());
        }

        public String label() {
            return "底 " + basePay + "／每台 " + taiPay;
        }

        @Override
        public int compareTo(Stakes other) {
            int byBase = Integer.compare(basePay, other.basePay);
            return byBase != 0 ? byBase : Integer.compare(taiPay, other.taiPay);
        }

        /** 存檔的鍵裡用的，例如 100x20。 */
        private String key() {
            return basePay + "x" + taiPay;
        }
    }

    /** 贏錢金額這份是所有底台合起來的，只留給舊版的程式與用戶端。 */
    private final Map<Category, List<GameRecord>> records = new EnumMap<>(Category.class);
    private final Map<Stakes, List<GameRecord>> winnings = new TreeMap<>();

    public HonorRoll() {
        for (Category category : Category.values()) {
            records.put(category, new ArrayList<>());
        }
    }

    /**
     * 由高到低；數值相同時，先達成的排前面。贏錢金額是所有底台合起來的，畫面上要照底台分開看的用 top(Stakes)。
     */
    public List<GameRecord> top(Category category) {
        return Collections.unmodifiableList(records.get(category));
    }

    /** 有贏錢紀錄的底台，由小到大。 */
    public List<Stakes> stakes() {
        return List.copyOf(winnings.keySet());
    }

    /** 這組底台的贏錢榜，由高到低；沒有紀錄是空的。 */
    public List<GameRecord> top(Stakes stakes) {
        List<GameRecord> list = winnings.get(stakes);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /** 最近有新紀錄的那組底台，同一天的取底台大的；還沒有贏錢紀錄是 null。畫面上預設先看這組。 */
    public Stakes latestStakes() {
        Stakes latest = null;
        String latestDate = "";
        for (Map.Entry<Stakes, List<GameRecord>> entry : winnings.entrySet()) {
            String date = newestDate(entry.getValue());
            if (latest == null || date.compareTo(latestDate) >= 0) {
                latest = entry.getKey();
                latestDate = date;
            }
        }
        return latest;
    }

    /** 榜上所有的紀錄，同一場只列一次。 */
    public List<GameRecord> allRecords() {
        Set<GameRecord> all = new LinkedHashSet<>();
        for (Category category : Category.values()) {
            all.addAll(records.get(category));
        }
        winnings.values().forEach(all::addAll);
        return List.copyOf(all);
    }

    /**
     * 加入一場成績，回傳它在各類別拿到的名次（從 1 起算）。數值是 0 以下的類別不列入。
     * 贏錢金額的名次是在同底台的榜上。
     */
    public Map<Category, Integer> add(GameRecord record) {
        Map<Category, Integer> ranks = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            int rank = insert(records.get(category), category, record);
            if (category == Category.WINNINGS) {
                rank = insertWinnings(record);
            }
            if (rank > 0) {
                ranks.put(category, rank);
            }
        }
        return ranks;
    }

    private static int insert(List<GameRecord> list, Category category, GameRecord record) {
        int value = category.value(record);
        if (value <= 0) {
            return 0;
        }
        int index = 0;
        while (index < list.size() && category.value(list.get(index)) >= value) {
            index++;
        }
        if (index >= SIZE) {
            return 0;
        }
        list.add(index, record);
        if (list.size() > SIZE) {
            list.remove(SIZE);
        }
        return index + 1;
    }

    /** 放進同底台的贏錢榜。底台的組數超過上限時，拿掉這組以外最久沒有新紀錄的那組。 */
    private int insertWinnings(GameRecord record) {
        if (record.winnings() <= 0) {
            return 0;
        }
        Stakes stakes = Stakes.of(record);
        int rank = insert(winnings.computeIfAbsent(stakes, key -> new ArrayList<>()), Category.WINNINGS, record);
        if (winnings.size() > MAX_STAKES) {
            winnings.remove(stalestExcept(stakes));
        }
        return rank;
    }

    /** 最新一筆的日期最早的那組，同一天的取底台小的。 */
    private Stakes stalestExcept(Stakes kept) {
        Stakes stalest = null;
        String stalestDate = "";
        for (Map.Entry<Stakes, List<GameRecord>> entry : winnings.entrySet()) {
            String date = newestDate(entry.getValue());
            if (!entry.getKey().equals(kept) && (stalest == null || date.compareTo(stalestDate) < 0)) {
                stalest = entry.getKey();
                stalestDate = date;
            }
        }
        return stalest;
    }

    /** 日期是 yyyy-MM-dd，照字串比就是照時間比。 */
    private static String newestDate(List<GameRecord> list) {
        String newest = "";
        for (GameRecord record : list) {
            if (record.date().compareTo(newest) > 0) {
                newest = record.date();
            }
        }
        return newest;
    }

    /** 明碼的文字檔。ezmjService 把服務器的榮譽榜經過這裡轉成文字，所以一直是明碼；本機的用 Records 封存。 */
    public static HonorRoll load(Path file) {
        return load(file, PropertyStore.PLAIN);
    }

    /**
     * 讀不到或讀不懂就是空的，壞掉的那筆略過。第 1 版的檔案還沒有分底台，
     * 每筆紀錄本來就記著底與每台，所以把各類別留下的紀錄照底台排進各自的贏錢榜。
     */
    public static HonorRoll load(Path file, PropertyStore store) {
        HonorRoll roll = new HonorRoll();
        Properties values;
        try {
            values = store.read(file);
        } catch (IOException e) {
            return roll;
        }
        for (Category category : Category.values()) {
            for (GameRecord record : parseList(values, category + ".")) {
                insert(roll.records.get(category), category, record);
            }
        }
        if (PropertyFiles.format(values) < 2) {
            // 原本的贏錢榜先排，同一場在幾個類別都有的只排一次
            Set<GameRecord> old = new LinkedHashSet<>(roll.records.get(Category.WINNINGS));
            old.addAll(roll.allRecords());
            old.forEach(roll::insertWinnings);
        } else {
            for (String key : stakesKeys(values)) {
                parseList(values, STAKES_PREFIX + key + ".").forEach(roll::insertWinnings);
            }
        }
        return roll;
    }

    private static List<GameRecord> parseList(Properties values, String prefix) {
        List<GameRecord> list = new ArrayList<>();
        for (int i = 0; i < SIZE; i++) {
            GameRecord record = parse(values, prefix + i + ".");
            if (record != null) {
                list.add(record);
            }
        }
        return list;
    }

    /** 檔案裡分底台的贏錢榜有哪幾組，例如 100x20。 */
    private static Set<String> stakesKeys(Properties values) {
        Set<String> keys = new TreeSet<>();
        for (String name : values.stringPropertyNames()) {
            int end = name.indexOf('.', STAKES_PREFIX.length());
            if (name.startsWith(STAKES_PREFIX) && end > 0) {
                String key = name.substring(STAKES_PREFIX.length(), end);
                if (key.matches("\\d+x\\d+")) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    private static GameRecord parse(Properties values, String prefix) {
        String name = values.getProperty(prefix + "name");
        String date = values.getProperty(prefix + "date");
        if (name == null || date == null) {
            return null;
        }
        try {
            return new GameRecord(name, date,
                    Integer.parseInt(values.getProperty(prefix + "wins", "").trim()),
                    Integer.parseInt(values.getProperty(prefix + "selfDraws", "").trim()),
                    Integer.parseInt(values.getProperty(prefix + "winnings", "").trim()),
                    Integer.parseInt(values.getProperty(prefix + "dealIns", "").trim()),
                    parseLevels(values.getProperty(prefix + "botLevel", "")),
                    Integer.parseInt(values.getProperty(prefix + "basePay", "").trim()),
                    Integer.parseInt(values.getProperty(prefix + "taiPay", "").trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 逗號隔開的三家等級。舊版只存一個，那時三家都是同一個等級。 */
    private static List<BotLevel> parseLevels(String text) {
        List<BotLevel> levels = new ArrayList<>();
        for (String name : text.split(",")) {
            levels.add(BotLevel.valueOf(name.trim()));
        }
        return levels.size() == 1 ? Collections.nCopies(3, levels.get(0)) : levels;
    }

    /** 明碼的文字檔，見 load(Path)。先寫到暫存檔再換過去，存到一半當機也不會弄壞原本的紀錄。 */
    public void save(Path file) throws IOException {
        save(file, PropertyStore.PLAIN);
    }

    public void save(Path file, PropertyStore store) throws IOException {
        Properties values = new Properties();
        values.setProperty(PropertyFiles.FORMAT_KEY, String.valueOf(FORMAT));
        for (Category category : Category.values()) {
            write(values, category + ".", records.get(category));
        }
        for (Map.Entry<Stakes, List<GameRecord>> entry : winnings.entrySet()) {
            write(values, STAKES_PREFIX + entry.getKey().key() + ".", entry.getValue());
        }
        store.write(values, file);
    }

    private static void write(Properties values, String listPrefix, List<GameRecord> list) {
        for (int i = 0; i < list.size(); i++) {
            GameRecord record = list.get(i);
            String prefix = listPrefix + i + ".";
            values.setProperty(prefix + "name", record.name());
            values.setProperty(prefix + "date", record.date());
            values.setProperty(prefix + "wins", String.valueOf(record.wins()));
            values.setProperty(prefix + "selfDraws", String.valueOf(record.selfDraws()));
            values.setProperty(prefix + "winnings", String.valueOf(record.winnings()));
            values.setProperty(prefix + "dealIns", String.valueOf(record.dealIns()));
            values.setProperty(prefix + "botLevel", record.botLevels().stream()
                    .map(BotLevel::name).collect(Collectors.joining(",")));
            values.setProperty(prefix + "basePay", String.valueOf(record.basePay()));
            values.setProperty(prefix + "taiPay", String.valueOf(record.taiPay()));
        }
    }
}
