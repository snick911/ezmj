package ezmj.model;

/** 開始畫面選定的設定。 */
public record GameConfig(String playerName, int startingChips, int basePay, int taiPay, BotLevel botLevel) {
}
