package ezmj.model;

import java.util.List;

/** 開始畫面選定的設定。botLevels 依序是座位 1、2、3 的電腦等級。底和每台不能大於籌碼本金，等於可以。 */
public record GameConfig(String playerName, int startingChips, int basePay, int taiPay, List<BotLevel> botLevels) {

    /** 底或每台比籌碼本金大時的說明，畫面直接顯示這句。 */
    public static final String PAYS_TOO_HIGH = "底和每台不能大於籌碼本金";

    public GameConfig {
        BotLevel.requireLineup(botLevels);
        if (!paysFit(startingChips, basePay, taiPay)) {
            throw new IllegalArgumentException(PAYS_TOO_HIGH);
        }
        botLevels = List.copyOf(botLevels);
    }

    /** 三家同一個等級。 */
    public GameConfig(String playerName, int startingChips, int basePay, int taiPay, BotLevel botLevel) {
        this(playerName, startingChips, basePay, taiPay, List.of(botLevel, botLevel, botLevel));
    }

    /** 底和每台都不大於籌碼本金。開始畫面與開房間的畫面先用這個檢查，不合就不能按。 */
    public static boolean paysFit(int startingChips, int basePay, int taiPay) {
        return basePay <= startingChips && taiPay <= startingChips;
    }
}
