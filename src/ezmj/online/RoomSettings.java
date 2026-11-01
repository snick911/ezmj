package ezmj.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ezmj.model.BotLevel;
import ezmj.model.GameSpeed;

/** 開房間時選的設定。botLevels 依序是座位 1、2、3，只有沒人坐的座位會用到。範圍由服務器檢查。 */
public record RoomSettings(int chips, int basePay, int taiPay, List<BotLevel> botLevels, int waitSeconds, GameSpeed speed) {

    public static final int MIN_WAIT_SECONDS = 5;
    public static final int MAX_WAIT_SECONDS = 300;

    public RoomSettings {
        botLevels = List.copyOf(botLevels);
    }

    Map<String, Object> toMessage() {
        List<String> levels = new ArrayList<>();
        botLevels.forEach(level -> levels.add(level.name()));
        return Fields.object("chips", chips, "basePay", basePay, "taiPay", taiPay, "botLevels", levels,
                "waitSeconds", waitSeconds, "speed", speed.name());
    }

    static RoomSettings from(Map<String, Object> message) {
        List<BotLevel> levels = new ArrayList<>();
        for (Object level : Fields.list(message, "botLevels")) {
            levels.add(BotLevel.valueOf(String.valueOf(level)));
        }
        return new RoomSettings(Fields.integer(message, "chips"), Fields.integer(message, "basePay"),
                Fields.integer(message, "taiPay"), levels, Fields.integer(message, "waitSeconds"),
                GameSpeed.valueOf(Fields.string(message, "speed")));
    }
}
