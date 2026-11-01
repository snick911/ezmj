package ezmj.model;

import java.util.List;

/** 開始畫面選定的設定。botLevels 依序是座位 1、2、3 的電腦等級。 */
public record GameConfig(String playerName, int startingChips, int basePay, int taiPay, List<BotLevel> botLevels) {

    public GameConfig {
        BotLevel.requireLineup(botLevels);
        botLevels = List.copyOf(botLevels);
    }

    /** 三家同一個等級。 */
    public GameConfig(String playerName, int startingChips, int basePay, int taiPay, BotLevel botLevel) {
        this(playerName, startingChips, basePay, taiPay, List.of(botLevel, botLevel, botLevel));
    }
}
