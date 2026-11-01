package ezmj.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ezmj.model.Gender;

/** 服務器的 room 訊息。座位都是服務器的座位，等人的時候沒有人坐的座位不會列出來。 */
public record RoomInfo(String code, boolean playing, int yourSeat, RoomSettings settings, List<Seat> seats) {

    /** gender 只有電腦才有，真人是 null。 */
    public record Seat(int seat, String name, boolean human, boolean connected, Gender gender) {
    }

    public RoomInfo {
        seats = List.copyOf(seats);
    }

    public boolean isHost() {
        return yourSeat == 0;
    }

    public static RoomInfo parse(Map<String, Object> message) {
        List<Seat> seats = new ArrayList<>();
        for (Map<String, Object> player : Fields.objects(message, "players")) {
            Object gender = player.get("gender");
            seats.add(new Seat(Fields.integer(player, "seat"), Fields.string(player, "name"), Fields.bool(player, "human"),
                    Fields.bool(player, "connected"), gender == null ? null : Gender.valueOf(String.valueOf(gender))));
        }
        return new RoomInfo(Fields.string(message, "code"), Fields.string(message, "state").equals("playing"),
                Fields.integer(message, "yourSeat"), RoomSettings.from(Fields.object(message, "options")), seats);
    }
}
