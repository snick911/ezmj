package ezmj.online;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ezmj.model.Meld;
import ezmj.model.Tile;

/** 讀服務器訊息的欄位。內容來自網路，缺欄位或型別不對一律丟出 IllegalArgumentException。 */
final class Fields {

    private static final List<Tile> ALL_TILES = Tile.fullSet();

    private Fields() {
    }

    /** 依序是鍵、值、鍵、值。 */
    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static String string(Map<String, Object> message, String key) {
        Object value = message.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("欄位 " + key + " 要是字串：" + value);
        }
        return (String) value;
    }

    static int integer(Map<String, Object> message, String key) {
        Object value = message.get(key);
        if (!(value instanceof Long) || (Long) value != ((Long) value).intValue()) {
            throw new IllegalArgumentException("欄位 " + key + " 要是整數：" + value);
        }
        return ((Long) value).intValue();
    }

    static boolean bool(Map<String, Object> message, String key) {
        Object value = message.get(key);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("欄位 " + key + " 要是 true 或 false：" + value);
        }
        return (Boolean) value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> message, String key) {
        Object value = message.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("欄位 " + key + " 要是物件：" + value);
        }
        return (Map<String, Object>) value;
    }

    static List<Object> list(Map<String, Object> message, String key) {
        Object value = message.get(key);
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("欄位 " + key + " 要是陣列：" + value);
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        return list;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> message, String key) {
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Object item : list(message, key)) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("欄位 " + key + " 的內容要是物件：" + item);
            }
            objects.add((Map<String, Object>) item);
        }
        return objects;
    }

    static List<Integer> integers(Map<String, Object> message, String key) {
        List<Integer> numbers = new ArrayList<>();
        for (Object item : list(message, key)) {
            if (!(item instanceof Long) || (Long) item != ((Long) item).intValue()) {
                throw new IllegalArgumentException("欄位 " + key + " 的內容要是整數：" + item);
            }
            numbers.add(((Long) item).intValue());
        }
        return numbers;
    }

    /** 沒有這張牌時是 null。 */
    static Tile tileOrNull(Map<String, Object> message, String key) {
        Object value = message.get(key);
        return value == null ? null : tile(String.valueOf(value));
    }

    static Tile tile(Map<String, Object> message, String key) {
        return tile(string(message, key));
    }

    static List<Tile> tiles(Map<String, Object> message, String key) {
        return tiles(list(message, key));
    }

    /** 這個欄位是 null 時回傳 null。 */
    static List<Tile> tilesOrNull(Map<String, Object> message, String key) {
        return message.get(key) == null ? null : tiles(message, key);
    }

    static List<Tile> tiles(List<Object> codes) {
        List<Tile> tiles = new ArrayList<>();
        for (Object code : codes) {
            tiles.add(tile(String.valueOf(code)));
        }
        return tiles;
    }

    static Tile tile(String code) {
        List<Tile> parsed;
        try {
            parsed = code.length() == 2 ? Tile.parseAll(code) : List.of();
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            parsed = List.of();
        }
        if (parsed.isEmpty() || !ALL_TILES.contains(parsed.get(0))) {
            throw new IllegalArgumentException("不是一張牌：" + code);
        }
        return parsed.get(0);
    }

    static List<Meld> melds(Map<String, Object> message, String key) {
        List<Meld> melds = new ArrayList<>();
        for (Map<String, Object> meld : objects(message, key)) {
            melds.add(new Meld(Meld.Type.valueOf(string(meld, "kind")), tiles(meld, "tiles")));
        }
        return melds;
    }
}
