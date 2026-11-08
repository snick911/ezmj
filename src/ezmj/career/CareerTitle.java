package ezmj.career;

/**
 * 修業檔案的稱號：帳上的籌碼曾經到過這麼多就拿到，之後輸掉也不會拿走，所以看的是最高籌碼。
 * 宣告順序由低到高；稱號不存檔，每次從最高籌碼算，新增或調整門檻舊檔照樣算得出來。
 */
public enum CareerTitle {
    RISING(200_000, "牌桌新貴"),
    MILLION(1_000_000, "百萬雀士"),
    TYCOON(5_000_000, "麻將大亨"),
    MAGNATE(20_000_000, "富甲一方"),
    LEGEND(100_000_000, "億萬雀神");

    private final long threshold;
    private final String label;

    CareerTitle(long threshold, String label) {
        this.threshold = threshold;
        this.label = label;
    }

    public long threshold() {
        return threshold;
    }

    public String label() {
        return label;
    }

    /** 最高籌碼到過的最高稱號；一個都沒有是 null。 */
    public static CareerTitle of(long peak) {
        CareerTitle reached = null;
        for (CareerTitle title : values()) {
            if (peak >= title.threshold) {
                reached = title;
            }
        }
        return reached;
    }

    /** 還沒拿到的下一個稱號；全部都拿到了是 null。 */
    public static CareerTitle next(long peak) {
        for (CareerTitle title : values()) {
            if (peak < title.threshold) {
                return title;
            }
        }
        return null;
    }
}
