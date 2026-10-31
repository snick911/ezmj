package ezmj.model;

/** 真人玩家一場牌局的成績。wins 包含自摸；winnings 是結束時籌碼減去本金，輸錢時為負。 */
public record GameRecord(String name, String date, int wins, int selfDraws, int winnings, int dealIns,
                         BotLevel botLevel, int basePay, int taiPay) {
}
