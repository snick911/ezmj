package ezmj.model;

import java.util.List;

/**
 * 開始畫面選定的設定。botLevels 依序是座位 1、2、3 的電腦等級。底和每台不能大於籌碼本金，等於可以。
 * expert 是專家模式：打牌前沒有聽牌提示，紀錄只留一筆，棄牌每家只看得到最後一張，也不顯示剩幾張。
 */
public record GameConfig(String playerName, int startingChips, int basePay, int taiPay, List<BotLevel> botLevels,
                         boolean expert) {

    /** 底或每台比籌碼本金大時的說明，畫面直接顯示這句。 */
    public static final String PAYS_TOO_HIGH = "底和每台不能大於籌碼本金";

    public GameConfig {
        BotLevel.requireLineup(botLevels);
        if (!paysFit(startingChips, basePay, taiPay)) {
            throw new IllegalArgumentException(PAYS_TOO_HIGH);
        }
        botLevels = List.copyOf(botLevels);
    }

    /** 不是專家模式。 */
    public GameConfig(String playerName, int startingChips, int basePay, int taiPay, List<BotLevel> botLevels) {
        this(playerName, startingChips, basePay, taiPay, botLevels, false);
    }

    /** 三家同一個等級，不是專家模式。 */
    public GameConfig(String playerName, int startingChips, int basePay, int taiPay, BotLevel botLevel) {
        this(playerName, startingChips, basePay, taiPay, List.of(botLevel, botLevel, botLevel));
    }

    /** 同樣的設定，換成專家模式或一般模式。 */
    public GameConfig withExpert(boolean on) {
        return new GameConfig(playerName, startingChips, basePay, taiPay, botLevels, on);
    }

    /** 底和每台都不大於籌碼本金。開始畫面與開房間的畫面先用這個檢查，不合就不能按。 */
    public static boolean paysFit(int startingChips, int basePay, int taiPay) {
        return basePay <= startingChips && taiPay <= startingChips;
    }
}
