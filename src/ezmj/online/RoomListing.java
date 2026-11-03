package ezmj.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 大廳列出的一間房間，來自服務器的 rooms 訊息。只有等人中、公開、還有空位的房間。host 是座位 0 的名字。 */
public record RoomListing(String code, String name, String host, int humans, RoomSettings settings) {

    /** 依服務器給的順序，新開的在前面。 */
    public static List<RoomListing> parse(Map<String, Object> message) {
        List<RoomListing> rooms = new ArrayList<>();
        for (Map<String, Object> room : Fields.objects(message, "rooms")) {
            rooms.add(new RoomListing(Fields.string(room, "code"), Fields.string(room, "name"), Fields.string(room, "host"),
                    Fields.integer(room, "humans"), RoomSettings.from(Fields.object(room, "options"))));
        }
        return List.copyOf(rooms);
    }
}
