package ezmj.model;

/**
 * 條子怎麼喊：「X條」或「X索」。自己的照環境設定，其他人照名字決定，同一個人每次都一樣，大約一半一半。
 * 畫面上一律寫「條」。
 */
public enum BambooCall {
    TIAO("條"), SUO("索");

    public static final BambooCall DEFAULT = TIAO;

    private final String word;

    BambooCall(String word) {
        this.word = word;
    }

    public String word() {
        return word;
    }

    /** 別人喊哪一種：照名字算，同一個名字每次都一樣。 */
    public static BambooCall forName(String name) {
        return Math.floorMod(name.hashCode(), 2) == 0 ? TIAO : SUO;
    }

    /** 讀設定檔用；認不得就用預設的。 */
    public static BambooCall fromKey(String key) {
        for (BambooCall call : values()) {
            if (call.name().equals(key)) {
                return call;
            }
        }
        return DEFAULT;
    }
}
