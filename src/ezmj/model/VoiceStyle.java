package ezmj.model;

/**
 * 喊牌語音的風格。Windows 內建語音念起來很平，用 SSML 調整：整句提高 base 個百分比，再照每個字的高低
 * （0 到 9）每級多提高 step 個百分比，語速與音量也調大；hype 的句尾加驚嘆號。每種風格的語音檔分開存。
 */
public enum VoiceStyle {
    BRISK("輕快", "提高、加快、加大聲", 15, 0, 20, 30, false),
    LIVELY("抑揚", "逐字有高有低", 10, 6, 15, 30, false),
    HYPE("熱血", "高低更大、更快、句尾驚嘆", 25, 10, 25, 40, true);

    public static final VoiceStyle DEFAULT = HYPE;

    private final String label;
    private final String description;
    private final int basePitch;
    private final int stepPitch;
    private final int rate;
    private final int volume;
    private final boolean exclaim;

    VoiceStyle(String label, String description, int basePitch, int stepPitch, int rate, int volume, boolean exclaim) {
        this.label = label;
        this.description = description;
        this.basePitch = basePitch;
        this.stepPitch = stepPitch;
        this.rate = rate;
        this.volume = volume;
        this.exclaim = exclaim;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 高低是 level（0 到 9）的字，音高要提高幾個百分比。 */
    public int pitch(int level) {
        return basePitch + stepPitch * level;
    }

    /** 語速加快幾個百分比。 */
    public int rate() {
        return rate;
    }

    /** 音量加大幾個百分比。 */
    public int volume() {
        return volume;
    }

    /** 句尾加驚嘆號。 */
    public boolean exclaim() {
        return exclaim;
    }

    /** 讀設定檔用；認不得就用預設的。 */
    public static VoiceStyle fromKey(String key) {
        for (VoiceStyle style : values()) {
            if (style.name().equals(key)) {
                return style;
            }
        }
        return DEFAULT;
    }
}
