package ezmj.model;

/** 電腦玩家的等級。差別在看多少資訊、想多少，而不是拿到比較好的牌。 */
public enum BotLevel {
    BEGINNER("初學者"), NORMAL("一般"), AMATEUR("業餘"), PRO("職業");

    private final String label;

    BotLevel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
