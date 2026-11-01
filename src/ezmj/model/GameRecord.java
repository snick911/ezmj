package ezmj.model;

import java.util.List;
import java.util.stream.Collectors;

/** 真人玩家一場牌局的成績。wins 包含自摸；winnings 是結束時籌碼減去本金，輸錢時為負。botLevels 依序是座位 1、2、3。 */
public record GameRecord(String name, String date, int wins, int selfDraws, int winnings, int dealIns,
                         List<BotLevel> botLevels, int basePay, int taiPay) {

    public GameRecord {
        BotLevel.requireLineup(botLevels);
        botLevels = List.copyOf(botLevels);
    }

    /** 三家同一個等級。 */
    public GameRecord(String name, String date, int wins, int selfDraws, int winnings, int dealIns,
                      BotLevel botLevel, int basePay, int taiPay) {
        this(name, date, wins, selfDraws, winnings, dealIns, List.of(botLevel, botLevel, botLevel), basePay, taiPay);
    }

    /** 三家同等級就顯示一個，不同就依座位列出。 */
    public String botLevelsLabel() {
        if (botLevels.stream().distinct().count() == 1) {
            return botLevels.get(0).label();
        }
        return botLevels.stream().map(BotLevel::label).collect(Collectors.joining("／"));
    }
}
