package ezmj.model;

/** 角色的性別，決定喊牌用哪種聲音。 */
public enum Gender {
    FEMALE("女聲"), MALE("男聲");

    private final String voiceLabel;

    Gender(String voiceLabel) {
        this.voiceLabel = voiceLabel;
    }

    public String voiceLabel() {
        return voiceLabel;
    }

    /** 設定檔裡的值；不認得就用女聲。 */
    public static Gender fromKey(String key) {
        return MALE.name().equals(key) ? MALE : FEMALE;
    }
}
