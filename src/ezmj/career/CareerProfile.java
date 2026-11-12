package ezmj.career;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import ezmj.model.PropertyFiles;
import ezmj.model.PropertyStore;

/**
 * 一個修業檔案：名字、起始籌碼、帳上的籌碼、破產次數、過了哪些關，以及打到一半的那場是什麼。
 * 一般對戰帶帳上的籌碼上桌，每局結算完就照桌上的籌碼改帳，輸贏不會重來；挑戰用關卡自己的籌碼，只有過關的獎金入帳。
 * 存成文字檔，讀不懂的項目當作沒有。
 */
public final class CareerProfile {

    /** 帶上桌的籌碼最多這麼多，其餘留在帳上。四家加起來還放得進 int。 */
    public static final int MAX_TABLE_CHIPS = 100_000_000;
    /** 建立檔案時可以選的起始籌碼。 */
    public static final List<Integer> STARTING_CHIPS = List.of(1_000, 20_000, 100_000);
    public static final int DEFAULT_STARTING_CHIPS = 20_000;
    /** 一般對戰可以選的底與每台；實際能選哪些看帳上的籌碼，見 baseOptions 與 taiOptions。 */
    public static final List<Integer> BASE_OPTIONS = List.of(50, 100, 300, 500, 1_000, 2_000, 5_000, 10_000, 20_000,
            50_000, 100_000, 200_000);
    public static final List<Integer> TAI_OPTIONS = List.of(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000,
            20_000, 50_000, 100_000);
    /** 底最多是帳上的幾分之一。 */
    public static final int BASE_SHARE = 10;
    /** 每台最多是帳上的幾分之一。 */
    public static final int TAI_SHARE = 20;
    /** 帳上的籌碼連最小的底都選不了，而且沒有打到一半的牌局，就可以領救濟金。 */
    public static final int RELIEF_BELOW = BASE_OPTIONS.get(0) * BASE_SHARE;
    /** 救濟金補到這麼多，和最小的起始籌碼一樣，不會比新開一個檔案還多。 */
    public static final int RELIEF_TO = STARTING_CHIPS.get(0);
    /** 專家模式的檔案只能從這麼多籌碼開始。 */
    public static final int EXPERT_STARTING_CHIPS = 100;
    /** 專家模式的牌局每次等待固定這麼多秒，不能調整。 */
    public static final int EXPERT_WAIT_SECONDS = 10;
    /** 專家模式連最小的底都付不起才能領救濟金。 */
    public static final int EXPERT_RELIEF_BELOW = BASE_OPTIONS.get(0);
    /** 專家模式的救濟金補回起始籌碼，不會比新開一個專家檔案還多。 */
    public static final int EXPERT_RELIEF_TO = EXPERT_STARTING_CHIPS;
    /** 專家模式的挑戰獎金是原本的幾分之一。起始只有 100，過一關才不會一下子多出好幾十倍。 */
    public static final int EXPERT_REWARD_SHARE = 20;
    /** 第一次拿到第二、第三顆星，各再多給過關獎金的幾分之一。 */
    public static final int STAR_BONUS_SHARE = 5;
    public static final int MAX_NAME_LENGTH = 8;
    /** 檔案的格式版本，見 PropertyFiles.FORMAT_KEY。 */
    private static final int FORMAT = 1;

    /** 打到一半的牌局是哪一種。 */
    public enum Game {
        NONE, GENERAL, CHALLENGE
    }

    private final String name;
    private final int startingChips;
    private final String created;
    /** 專家模式：建立時決定，之後不能改。 */
    private final boolean expert;
    private long bankroll;
    private long peak;
    private int bankruptcies;
    private final Map<Challenge, String> cleared = new EnumMap<>(Challenge.class);
    /** 每關拿過哪幾顆星，見 Challenge.Check；幾次挑戰拿到的合起來算，只記過關的。 */
    private final Map<Challenge, Integer> stars = new EnumMap<>(Challenge.class);
    private Game game = Game.NONE;
    private Challenge challenge;
    /** 一般對戰開打時留在帳上、沒帶上桌的籌碼。 */
    private long reserve;

    /** 一般模式的檔案。 */
    public CareerProfile(String name, int startingChips, String created) {
        this(name, startingChips, created, false);
    }

    /** 專家模式的檔案起始籌碼一定是 EXPERT_STARTING_CHIPS。 */
    public CareerProfile(String name, int startingChips, String created, boolean expert) {
        if (name.isBlank() || name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("名字要 1 到 " + MAX_NAME_LENGTH + " 個字：" + name);
        }
        if (startingChips <= 0) {
            throw new IllegalArgumentException("起始籌碼要大於 0：" + startingChips);
        }
        if (expert && startingChips != EXPERT_STARTING_CHIPS) {
            throw new IllegalArgumentException("專家模式的起始籌碼是 " + EXPERT_STARTING_CHIPS + "：" + startingChips);
        }
        this.name = name;
        this.startingChips = startingChips;
        this.created = created;
        this.expert = expert;
        this.bankroll = startingChips;
        this.peak = startingChips;
    }

    public String name() {
        return name;
    }

    /** 專家模式：牌桌照專家模式打、等待固定 EXPERT_WAIT_SECONDS 秒，底台只要帳上付得起就能選。 */
    public boolean expert() {
        return expert;
    }

    /** 帳上不到這麼多、沒有打到一半的牌局，就能領救濟金。 */
    public int reliefBelow() {
        return expert ? EXPERT_RELIEF_BELOW : RELIEF_BELOW;
    }

    /** 救濟金補到這麼多。 */
    public int reliefTo() {
        return expert ? EXPERT_RELIEF_TO : RELIEF_TO;
    }

    public int startingChips() {
        return startingChips;
    }

    public String created() {
        return created;
    }

    /** 帳上的籌碼，一般對戰打到一半時包含桌上的。輸光時可能是負的。 */
    public long bankroll() {
        return bankroll;
    }

    /** 帳上有過最多的籌碼。 */
    public long peak() {
        return peak;
    }

    /** 帳上曾經到過的最高稱號；之後輸掉也不會拿走。還沒有是 null。 */
    public CareerTitle title() {
        return CareerTitle.of(peak);
    }

    /** 下一個稱號；全部都拿到了是 null。 */
    public CareerTitle nextTitle() {
        return CareerTitle.next(peak);
    }

    public int bankruptcies() {
        return bankruptcies;
    }

    public Game game() {
        return game;
    }

    /** 打到一半的挑戰；不是挑戰時是 null。 */
    public Challenge challenge() {
        return challenge;
    }

    private void setBankroll(long value) {
        bankroll = value;
        peak = Math.max(peak, value);
    }

    /** 一般對戰帶上桌的籌碼。 */
    public int tableChips() {
        return (int) Math.max(0, Math.min(bankroll, MAX_TABLE_CHIPS));
    }

    /** 帳上的籌碼選得了的底，由小到大；連最小的都選不了時是空的。專家模式只要不大於帳上的籌碼。 */
    public List<Integer> baseOptions() {
        int share = expert ? 1 : BASE_SHARE;
        return BASE_OPTIONS.stream().filter(base -> (long) base * share <= bankroll).collect(Collectors.toList());
    }

    /** 帳上的籌碼選得了的每台，由小到大。專家模式只要不大於帳上的籌碼。 */
    public List<Integer> taiOptions() {
        int share = expert ? 1 : TAI_SHARE;
        return TAI_OPTIONS.stream().filter(tai -> (long) tai * share <= bankroll).collect(Collectors.toList());
    }

    /** 籌碼越多能選的底台越大：底最多帳上的 1/10、每台最多 1/20（專家模式不大於帳上就好），而且要是選項裡有的。 */
    public boolean stakesAllowed(int basePay, int taiPay) {
        return baseOptions().contains(basePay) && taiOptions().contains(taiPay);
    }

    /** 開一場一般對戰，回傳帶上桌的籌碼。底台要是帳上的籌碼選得了的。 */
    public int startGeneral(int basePay, int taiPay) {
        requireNoGame();
        if (!stakesAllowed(basePay, taiPay)) {
            throw new IllegalArgumentException("籌碼 " + bankroll + " 選不了底 " + basePay + "、每台 " + taiPay);
        }
        int table = tableChips();
        reserve = bankroll - table;
        game = Game.GENERAL;
        return table;
    }

    /** 一般對戰每局結算完呼叫，帳跟著桌上的籌碼走。同一局重複呼叫結果一樣。 */
    public void settleGeneral(int humanChips) {
        if (game != Game.GENERAL) {
            throw new IllegalStateException("沒有在打一般對戰");
        }
        setBankroll(reserve + humanChips);
    }

    public void startChallenge(Challenge next) {
        requireNoGame();
        if (!isUnlocked(next)) {
            throw new IllegalStateException("還沒開放：" + next);
        }
        game = Game.CHALLENGE;
        challenge = next;
    }

    /** 這個檔案第一次過這關拿得到的獎金：專家模式是原本的 1/EXPERT_REWARD_SHARE。 */
    public int rewardFor(Challenge stage) {
        return expert ? stage.reward() / EXPERT_REWARD_SHARE : stage.reward();
    }

    /** 第一次拿到這關的第二或第三顆星，各多給這麼多：過關獎金的 1/STAR_BONUS_SHARE。 */
    public int starBonus(Challenge stage) {
        return rewardFor(stage) / STAR_BONUS_SHARE;
    }

    /** 這關拿過哪幾顆星，第 i 個位元是第 i+1 顆；還沒過關是 0。 */
    public int starMask(Challenge stage) {
        return stars.getOrDefault(stage, 0);
    }

    /** 這關拿過幾顆星，還沒過關是 0。 */
    public int stars(Challenge stage) {
        return Integer.bitCount(starMask(stage));
    }

    /** 全部關卡加起來拿了幾顆星。 */
    public int totalStars() {
        return stars.values().stream().mapToInt(Integer::bitCount).sum();
    }

    /** 只拿到過關那一顆星，見另一個 clearChallenge。 */
    public int clearChallenge(Challenge done, String date) {
        return clearChallenge(done, date, 1);
    }

    /**
     * 挑戰過關，earned 是這次拿到哪幾顆星（Challenge.Check 的 stars，一定有過關那顆），回傳這次拿到的獎金。
     * 打到一半的挑戰也一起結束。第一次過關給過關獎金（見 rewardFor）並記下日期；
     * 以前沒拿過的加星條件，每顆再給 starBonus。已經過關、也沒有新的星的話回傳 0。
     */
    public int clearChallenge(Challenge done, String date, int earned) {
        if ((earned & 1) == 0 || (earned & ~Challenge.ALL_STARS) != 0) {
            throw new IllegalArgumentException("拿到的星不對：" + Integer.toBinaryString(earned));
        }
        if (game == Game.CHALLENGE && challenge == done) {
            endGame();
        }
        int reward = 0;
        if (!cleared.containsKey(done)) {
            cleared.put(done, date);
            reward += rewardFor(done);
        }
        // 過關那顆不另外給，新拿到的加星條件每顆給一次
        int fresh = earned & ~starMask(done) & ~1;
        reward += Integer.bitCount(fresh) * starBonus(done);
        stars.put(done, starMask(done) | earned);
        setBankroll(bankroll + reward);
        return reward;
    }

    /** 打完、放棄或讀不回來的牌局都從這裡結束。一般對戰已經結算的局照算，打到一半的那局不算。 */
    public void endGame() {
        game = Game.NONE;
        challenge = null;
        reserve = 0;
    }

    public boolean isCleared(Challenge stage) {
        return cleared.containsKey(stage);
    }

    /** 過關的日期；還沒過是 null。 */
    public String clearedDate(Challenge stage) {
        return cleared.get(stage);
    }

    public int clearedCount() {
        return cleared.size();
    }

    /** 其中一條線過了幾關：drills 是修練，不然是完整牌局的關卡。 */
    public int clearedCount(boolean drills) {
        return (int) Challenge.line(drills).stream().filter(cleared::containsKey).count();
    }

    /** 過了前一關才開放。已經過的關一直開放，以後在中間插入新關卡時，舊檔過過的關也不會被鎖起來。 */
    public boolean isUnlocked(Challenge stage) {
        return stage.previous() == null || cleared.containsKey(stage.previous()) || cleared.containsKey(stage);
    }

    public boolean canTakeRelief() {
        return game == Game.NONE && bankroll < reliefBelow();
    }

    /** 補到救濟金的數目，記一次破產。 */
    public void takeRelief() {
        if (!canTakeRelief()) {
            throw new IllegalStateException("現在不能領救濟金");
        }
        bankruptcies++;
        setBankroll(reliefTo());
    }

    private void requireNoGame() {
        if (game != Game.NONE) {
            throw new IllegalStateException("還有一場沒打完：" + game);
        }
    }

    /** 明碼的文字檔，測試用；遊戲裡的檔案由 CareerSlot 封存。 */
    public void save(Path file) throws IOException {
        save(file, PropertyStore.PLAIN);
    }

    public void save(Path file, PropertyStore store) throws IOException {
        Properties values = new Properties();
        values.setProperty(PropertyFiles.FORMAT_KEY, String.valueOf(FORMAT));
        values.setProperty("name", name);
        values.setProperty("startingChips", String.valueOf(startingChips));
        values.setProperty("created", created);
        values.setProperty("expert", String.valueOf(expert));
        values.setProperty("bankroll", String.valueOf(bankroll));
        values.setProperty("peak", String.valueOf(peak));
        values.setProperty("bankruptcies", String.valueOf(bankruptcies));
        cleared.forEach((stage, date) -> values.setProperty("cleared." + stage.name(), date));
        // 拿過哪幾顆星，例如「1,3」是過關與第三顆
        stars.forEach((stage, mask) -> values.setProperty("stars." + stage.name(), starNumbers(mask)));
        values.setProperty("game", game.name());
        if (challenge != null) {
            values.setProperty("game.challenge", challenge.name());
        }
        values.setProperty("game.reserve", String.valueOf(reserve));
        store.write(values, file);
    }

    /** 明碼的文字檔，測試用。 */
    public static CareerProfile load(Path file) throws IOException {
        return load(file, PropertyStore.PLAIN);
    }

    /**
     * 名字或起始籌碼讀不懂就丟 IOException，其他讀不懂或沒有的項目用預設值，所以新增的項目舊檔也讀得出來。
     * 過關用 Challenge 的名稱存，認不得的略過，所以關卡的名稱不要改。
     */
    public static CareerProfile load(Path file, PropertyStore store) throws IOException {
        Properties values = store.read(file);
        CareerProfile profile;
        try {
            // 以前的檔案沒有專家模式，當作一般模式
            profile = new CareerProfile(values.getProperty("name", ""),
                    Integer.parseInt(values.getProperty("startingChips", "").trim()),
                    values.getProperty("created", "").trim(),
                    "true".equals(values.getProperty("expert", "").trim()));
        } catch (IllegalArgumentException e) {
            throw new IOException("檔案內容不正確", e);
        }
        profile.bankroll = number(values, "bankroll", profile.startingChips);
        profile.peak = Math.max(profile.bankroll, number(values, "peak", profile.bankroll));
        profile.bankruptcies = (int) Math.max(0, number(values, "bankruptcies", 0));
        for (Challenge stage : Challenge.values()) {
            String date = values.getProperty("cleared." + stage.name());
            if (date != null) {
                profile.cleared.put(stage, date.trim());
                // 以前的檔案沒有記星，過關就是一顆；過關那顆一定有
                profile.stars.put(stage, starMask(values.getProperty("stars." + stage.name(), "")) | 1);
            }
        }
        try {
            profile.game = Game.valueOf(values.getProperty("game", Game.NONE.name()).trim());
        } catch (IllegalArgumentException e) {
            profile.game = Game.NONE;
        }
        if (profile.game == Game.CHALLENGE) {
            profile.challenge = Challenge.fromKey(values.getProperty("game.challenge", "").trim());
            if (profile.challenge == null) {
                profile.game = Game.NONE;
            }
        }
        profile.reserve = profile.game == Game.GENERAL ? number(values, "game.reserve", 0) : 0;
        return profile;
    }

    /** 例如位元 101 寫成「1,3」。 */
    private static String starNumbers(int mask) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Challenge.MAX_STARS; i++) {
            if ((mask & (1 << i)) != 0) {
                text.append(text.length() == 0 ? "" : ",").append(i + 1);
            }
        }
        return text.toString();
    }

    /** 「1,3」讀回位元 101；讀不懂或超出範圍的數字略過。 */
    private static int starMask(String text) {
        int mask = 0;
        for (String part : text.split(",")) {
            try {
                int number = Integer.parseInt(part.trim());
                if (number >= 1 && number <= Challenge.MAX_STARS) {
                    mask |= 1 << (number - 1);
                }
            } catch (NumberFormatException e) {
                // 略過
            }
        }
        return mask;
    }

    private static long number(Properties values, String key, long fallback) {
        try {
            return Long.parseLong(values.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
