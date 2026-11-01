package ezmj.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import ezmj.model.PropertyFiles;

/** 玩家達成的成就與累計次數。存成文字檔，讀不懂的項目會略過，不影響其他紀錄。 */
public final class AchievementBook {

    private static final int HUMAN_SEAT = 0;
    private static final String UNLOCKED_PREFIX = "unlocked.";
    private static final String COUNT_PREFIX = "count.";

    private final Map<Achievement, String> unlocked = new EnumMap<>(Achievement.class);
    private final Map<Achievement.Counter, Integer> counts = new EnumMap<>(Achievement.Counter.class);

    /** 一局結算完呼叫：累計這局，回傳這次新達成的成就。 */
    public List<Achievement> recordRound(GameSession session, String date) {
        List<RoundRecord> history = session.history();
        RoundResult result = history.get(history.size() - 1).result();
        add(Achievement.Counter.ROUNDS);
        if (result.winner() == HUMAN_SEAT) {
            add(Achievement.Counter.WINS);
            if (result.isSelfDraw()) {
                add(Achievement.Counter.SELF_DRAWS);
            }
        } else if (result.from() == HUMAN_SEAT) {
            add(Achievement.Counter.DEAL_INS);
        }
        return unlockMet(new AchievementFacts(session, this, false, 0, 0), date);
    }

    /** 整場打完呼叫，回傳這次新達成的成就。 */
    public List<Achievement> recordGame(GameSession session, double averageThinkSeconds, int thinkCount, String date) {
        add(Achievement.Counter.GAMES);
        return unlockMet(new AchievementFacts(session, this, true, averageThinkSeconds, thinkCount), date);
    }

    private void add(Achievement.Counter counter) {
        counts.merge(counter, 1, Integer::sum);
    }

    private List<Achievement> unlockMet(AchievementFacts facts, String date) {
        List<Achievement> earned = new ArrayList<>();
        for (Achievement achievement : Achievement.values()) {
            if (!isUnlocked(achievement) && achievement.isMet(facts)) {
                unlocked.put(achievement, date);
                earned.add(achievement);
            }
        }
        return earned;
    }

    public boolean isUnlocked(Achievement achievement) {
        return unlocked.containsKey(achievement);
    }

    /** 達成的日期；還沒達成是 null。 */
    public String unlockedDate(Achievement achievement) {
        return unlocked.get(achievement);
    }

    public int unlockedCount() {
        return unlocked.size();
    }

    public int count(Achievement.Counter counter) {
        return counts.getOrDefault(counter, 0);
    }

    public static AchievementBook load(Path file) {
        AchievementBook book = new AchievementBook();
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return book;
        }
        for (Achievement achievement : Achievement.values()) {
            String date = values.getProperty(UNLOCKED_PREFIX + achievement.name());
            if (date != null) {
                book.unlocked.put(achievement, date.trim());
            }
        }
        for (Achievement.Counter counter : Achievement.Counter.values()) {
            try {
                int count = Integer.parseInt(values.getProperty(COUNT_PREFIX + counter.name(), "").trim());
                if (count > 0) {
                    book.counts.put(counter, count);
                }
            } catch (NumberFormatException e) {
                // 這個次數讀不懂就當作還沒累計
            }
        }
        return book;
    }

    public void save(Path file) throws IOException {
        Properties values = new Properties();
        unlocked.forEach((achievement, date) -> values.setProperty(UNLOCKED_PREFIX + achievement.name(), date));
        counts.forEach((counter, count) -> values.setProperty(COUNT_PREFIX + counter.name(), String.valueOf(count)));
        PropertyFiles.store(values, file);
    }
}
