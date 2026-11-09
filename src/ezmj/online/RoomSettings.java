package ezmj.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ezmj.model.BotLevel;
import ezmj.model.GameSpeed;

/**
 * 開房間時選的設定。botLevels 依序是座位 1、2、3，只有沒人坐的座位會用到。範圍由服務器檢查。
 * name 是房間名稱，開房時空白就由服務器用房主的名字取；服務器送來的一定有值，舊服務器沒有這欄時是空的。
 * privateRoom 的房間不列在大廳，只能用房號加入。
 * expert 是專家模式：服務器不給可以聽的牌、打完牌才問要不要報聽。舊服務器沒有這欄時當作 false。
 */
public record RoomSettings(int chips, int basePay, int taiPay, List<BotLevel> botLevels, int waitSeconds, GameSpeed speed,
                           String name, boolean privateRoom, boolean expert) {

    public static final int MIN_WAIT_SECONDS = 5;
    public static final int MAX_WAIT_SECONDS = 300;
    /** 房間名稱去掉前後空白後最多幾個字，用 code point 算。 */
    public static final int MAX_NAME_LENGTH = 30;

    public RoomSettings {
        botLevels = List.copyOf(botLevels);
    }

    /** 不是專家模式的房間，給還沒有專家模式選項的呼叫端。 */
    public RoomSettings(int chips, int basePay, int taiPay, List<BotLevel> botLevels, int waitSeconds, GameSpeed speed,
                        String name, boolean privateRoom) {
        this(chips, basePay, taiPay, botLevels, waitSeconds, speed, name, privateRoom, false);
    }

    Map<String, Object> toMessage() {
        List<String> levels = new ArrayList<>();
        botLevels.forEach(level -> levels.add(level.name()));
        return Fields.object("chips", chips, "basePay", basePay, "taiPay", taiPay, "botLevels", levels,
                "waitSeconds", waitSeconds, "speed", speed.name(), "name", name, "private", privateRoom,
                "expert", expert);
    }

    static RoomSettings from(Map<String, Object> message) {
        List<BotLevel> levels = new ArrayList<>();
        for (Object level : Fields.list(message, "botLevels")) {
            levels.add(BotLevel.valueOf(String.valueOf(level)));
        }
        return new RoomSettings(Fields.integer(message, "chips"), Fields.integer(message, "basePay"),
                Fields.integer(message, "taiPay"), levels, Fields.integer(message, "waitSeconds"),
                GameSpeed.valueOf(Fields.string(message, "speed")),
                message.get("name") == null ? "" : Fields.string(message, "name"),
                message.get("private") != null && Fields.bool(message, "private"),
                message.get("expert") != null && Fields.bool(message, "expert"));
    }
}
