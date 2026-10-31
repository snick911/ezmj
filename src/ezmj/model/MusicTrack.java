package ezmj.model;

/** 背景音樂的選項。OFF 表示不播放。 */
public enum MusicTrack {
    OFF("關閉"),
    TEAHOUSE("茶館閒情"),
    NIGHT_MARKET("夜市熱鬧"),
    BAMBOO("竹林清風"),
    MOONLIGHT("月夜琴音");

    private final String label;

    MusicTrack(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 找不到對應的選項時回傳 fallback。 */
    public static MusicTrack fromKey(String key, MusicTrack fallback) {
        for (MusicTrack track : values()) {
            if (track.name().equals(key)) {
                return track;
            }
        }
        return fallback;
    }
}
