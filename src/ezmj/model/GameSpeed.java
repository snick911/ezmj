package ezmj.model;

/** 遊戲速度：電腦出牌前與等宣告時停多久。只影響節奏，不影響牌局結果。 */
public enum GameSpeed {
    SLOW("慢", 900, 300), NORMAL("中", 550, 200), FAST("快", 250, 100);

    private final String label;
    private final int turnDelayMs;
    private final int claimDelayMs;

    GameSpeed(String label, int turnDelayMs, int claimDelayMs) {
        this.label = label;
        this.turnDelayMs = turnDelayMs;
        this.claimDelayMs = claimDelayMs;
    }

    public String label() {
        return label;
    }

    public int turnDelayMs() {
        return turnDelayMs;
    }

    public int claimDelayMs() {
        return claimDelayMs;
    }

    /** 設定檔裡的值；不認得就用中速。 */
    public static GameSpeed fromKey(String key) {
        for (GameSpeed speed : values()) {
            if (speed.name().equals(key)) {
                return speed;
            }
        }
        return NORMAL;
    }
}
