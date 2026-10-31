package ezmj.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.ToIntFunction;

/** 榮譽榜：每個類別各自留下數值最高的十筆。存成文字檔，讀不懂的那筆會略過，不影響其他紀錄。 */
public final class HonorRoll {

    public static final int SIZE = 10;

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
    }

    private final Map<Category, List<GameRecord>> records = new EnumMap<>(Category.class);

    public HonorRoll() {
        for (Category category : Category.values()) {
            records.put(category, new ArrayList<>());
        }
    }

    /** 由高到低；數值相同時，先達成的排前面。 */
    public List<GameRecord> top(Category category) {
        return Collections.unmodifiableList(records.get(category));
    }

    /** 加入一場成績，回傳它在各類別拿到的名次（從 1 起算）。數值是 0 以下的類別不列入。 */
    public Map<Category, Integer> add(GameRecord record) {
        Map<Category, Integer> ranks = new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            int rank = insert(category, record);
            if (rank > 0) {
                ranks.put(category, rank);
            }
        }
        return ranks;
    }

    private int insert(Category category, GameRecord record) {
        int value = category.value(record);
        if (value <= 0) {
            return 0;
        }
        List<GameRecord> list = records.get(category);
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

    public static HonorRoll load(Path file) {
        HonorRoll roll = new HonorRoll();
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return roll;
        }
        for (Category category : Category.values()) {
            for (int i = 0; i < SIZE; i++) {
                GameRecord record = parse(values, category + "." + i + ".");
                if (record != null) {
                    roll.insert(category, record);
                }
            }
        }
        return roll;
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
                    BotLevel.valueOf(values.getProperty(prefix + "botLevel", "").trim()),
                    Integer.parseInt(values.getProperty(prefix + "basePay", "").trim()),
                    Integer.parseInt(values.getProperty(prefix + "taiPay", "").trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 先寫到暫存檔再換過去，存到一半當機也不會弄壞原本的紀錄。 */
    public void save(Path file) throws IOException {
        Properties values = new Properties();
        for (Category category : Category.values()) {
            List<GameRecord> list = records.get(category);
            for (int i = 0; i < list.size(); i++) {
                GameRecord record = list.get(i);
                String prefix = category + "." + i + ".";
                values.setProperty(prefix + "name", record.name());
                values.setProperty(prefix + "date", record.date());
                values.setProperty(prefix + "wins", String.valueOf(record.wins()));
                values.setProperty(prefix + "selfDraws", String.valueOf(record.selfDraws()));
                values.setProperty(prefix + "winnings", String.valueOf(record.winnings()));
                values.setProperty(prefix + "dealIns", String.valueOf(record.dealIns()));
                values.setProperty(prefix + "botLevel", record.botLevel().name());
                values.setProperty(prefix + "basePay", String.valueOf(record.basePay()));
                values.setProperty(prefix + "taiPay", String.valueOf(record.taiPay()));
            }
        }
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            values.store(out, "EZMJ");
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
