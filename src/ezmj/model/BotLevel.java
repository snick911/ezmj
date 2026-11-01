package ezmj.model;

import java.util.List;

/** 電腦玩家的等級。差別在看多少資訊、想多少，而不是拿到比較好的牌。 */
public enum BotLevel {
    BEGINNER("初學者"), NORMAL("一般"), AMATEUR("業餘"), PRO("職業");

    private final String label;

    BotLevel(String label) {
        this.label = label;
    }

    /** 一場牌局有三家電腦，每家一個等級。 */
    static void requireLineup(List<BotLevel> levels) {
        if (levels.size() != 3) {
            throw new IllegalArgumentException("要有三家電腦的等級：" + levels);
        }
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
