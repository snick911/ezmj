package ezmj.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 大廳列出的一間房間，來自服務器的 rooms 訊息。host 是座位 0 的名字。
 * 平常只有等人中、公開、還有空位的房間；listRooms 說會補位的話，還有隨時補位、正在打的牌局，playing 是 true，
 * openSeats 是還能坐幾個人（牌局中是還能替換幾家電腦）。ezmjService 0.9.1 和更早的版本沒有這兩欄，
 * 這時 playing 是 false、openSeats 用 4 減真人數。
 */
public record RoomListing(String code, String name, String host, int humans, RoomSettings settings, boolean playing,
                          int openSeats) {

    private static final int SEATS = 4;

    /** 等人中的房間，空位是 4 減真人數。 */
    public RoomListing(String code, String name, String host, int humans, RoomSettings settings) {
        this(code, name, host, humans, settings, false, SEATS - humans);
    }

    /** 依服務器給的順序：等人的在前面，接著是可以補位的牌局，各自新開的在前面。 */
    public static List<RoomListing> parse(Map<String, Object> message) {
        List<RoomListing> rooms = new ArrayList<>();
        for (Map<String, Object> room : Fields.objects(message, "rooms")) {
            int humans = Fields.integer(room, "humans");
            rooms.add(new RoomListing(Fields.string(room, "code"), Fields.string(room, "name"), Fields.string(room, "host"),
                    humans, RoomSettings.from(Fields.object(room, "options")),
                    room.get("playing") != null && Fields.bool(room, "playing"),
                    room.get("openSeats") != null ? Fields.integer(room, "openSeats") : SEATS - humans));
        }
        return List.copyOf(rooms);
    }
}
