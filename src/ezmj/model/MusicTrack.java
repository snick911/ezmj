package ezmj.model;

/** 背景音樂的選項。OFF 表示不播放。 */
public enum MusicTrack {
    OFF("關閉", null),
    TEAHOUSE("茶館閒情", null),
    NIGHT_MARKET("夜市熱鬧", null),
    BAMBOO("竹林清風", null),
    MOONLIGHT("月夜琴音", null),
    CLEAR_THE_BOARD("清空牌桌（Clear the Board）", "clear_the_board.mp3"),
    SUNLIGHT("木板暖陽（Sunlight on the Floorboards）", "sunlight_on_the_floorboards.mp3");

    private final String label;
    private final String recording;

    MusicTrack(String label, String recording) {
        this.label = label;
        this.recording = recording;
    }

    public String label() {
        return label;
    }

    /** 錄好的曲子的 MP3 檔名，放在 ezmj/ui/music；照樂譜即時合成的曲子是 null。 */
    public String recording() {
        return recording;
    }

    public boolean isRecorded() {
        return recording != null;
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
