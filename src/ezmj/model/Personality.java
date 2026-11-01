package ezmj.model;

/** 電腦的打牌個性。和等級無關：等級決定看多少、想多少，個性決定同樣的局面偏向哪邊。 */
public enum Personality {
    BALANCED("穩健"), BOLD("愛衝"), CAUTIOUS("保守"), GREEDY("愛做大牌");

    private final String label;

    Personality(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 存檔裡的值；不認得就當作穩健。 */
    public static Personality fromKey(String key) {
        for (Personality personality : values()) {
            if (personality.name().equals(key)) {
                return personality;
            }
        }
        return BALANCED;
    }
}
