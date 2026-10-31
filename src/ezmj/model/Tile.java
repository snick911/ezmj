package ezmj.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一張牌。同花色同點數的牌視為相等，不區分實體。
 * 點數：數牌 1-9；風牌 1-4（東南西北）；三元 1-3（中發白）；花牌 1-8（春夏秋冬梅蘭竹菊）。
 */
public record Tile(Suit suit, int rank) implements Comparable<Tile> {

    /** 不含花牌的牌種數，用於手牌計數陣列。 */
    public static final int KINDS = 34;

    private static final String[] NUMBERS = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};
    private static final String[] WINDS = {"東", "南", "西", "北"};
    private static final String[] DRAGONS = {"中", "發", "白"};
    private static final String[] FLOWERS = {"春", "夏", "秋", "冬", "梅", "蘭", "竹", "菊"};

    public static Tile of(Suit suit, int rank) {
        return new Tile(suit, rank);
    }

    /** 記法：1w=一萬、1p=一筒、1s=一條、1z-4z=東南西北、5z-7z=中發白、1f-8f=春夏秋冬梅蘭竹菊。空白會略過。 */
    public static List<Tile> parseAll(String text) {
        List<Tile> tiles = new ArrayList<>();
        String compact = text.replace(" ", "");
        for (int i = 0; i < compact.length(); i += 2) {
            int rank = compact.charAt(i) - '0';
            switch (compact.charAt(i + 1)) {
                case 'w': tiles.add(of(Suit.WAN, rank)); break;
                case 'p': tiles.add(of(Suit.TONG, rank)); break;
                case 's': tiles.add(of(Suit.TIAO, rank)); break;
                case 'f': tiles.add(of(Suit.FLOWER, rank)); break;
                case 'z':
                    tiles.add(rank <= 4 ? of(Suit.WIND, rank) : of(Suit.DRAGON, rank - 4));
                    break;
                default:
                    throw new IllegalArgumentException("無法解析: " + text);
            }
        }
        return tiles;
    }

    /** 完整的 144 張牌。 */
    public static List<Tile> fullSet() {
        List<Tile> tiles = new ArrayList<>(144);
        for (int index = 0; index < KINDS; index++) {
            tiles.addAll(Collections.nCopies(4, fromIndex(index)));
        }
        for (int rank = 1; rank <= 8; rank++) {
            tiles.add(of(Suit.FLOWER, rank));
        }
        return tiles;
    }

    /** 計數陣列索引 0-33；花牌沒有索引。 */
    public int index() {
        switch (suit) {
            case WAN:    return rank - 1;
            case TONG:   return 9 + rank - 1;
            case TIAO:   return 18 + rank - 1;
            case WIND:   return 27 + rank - 1;
            case DRAGON: return 31 + rank - 1;
            default: throw new IllegalStateException("花牌沒有索引: " + this);
        }
    }

    public static Tile fromIndex(int index) {
        if (index < 9) {
            return of(Suit.WAN, index + 1);
        } else if (index < 18) {
            return of(Suit.TONG, index - 8);
        } else if (index < 27) {
            return of(Suit.TIAO, index - 17);
        } else if (index < 31) {
            return of(Suit.WIND, index - 26);
        }
        return of(Suit.DRAGON, index - 30);
    }

    public boolean isFlower() {
        return suit == Suit.FLOWER;
    }

    public boolean isHonor() {
        return suit == Suit.WIND || suit == Suit.DRAGON;
    }

    public boolean isNumber() {
        return suit == Suit.WAN || suit == Suit.TONG || suit == Suit.TIAO;
    }

    public String label() {
        switch (suit) {
            case WAN:    return NUMBERS[rank - 1] + "萬";
            case TONG:   return NUMBERS[rank - 1] + "筒";
            case TIAO:   return NUMBERS[rank - 1] + "條";
            case WIND:   return WINDS[rank - 1];
            case DRAGON: return DRAGONS[rank - 1];
            default:     return FLOWERS[rank - 1];
        }
    }

    public static String windName(int wind) {
        return WINDS[wind - 1];
    }

    public static String numberName(int rank) {
        return NUMBERS[rank - 1];
    }

    @Override
    public int compareTo(Tile other) {
        int bySuit = suit.compareTo(other.suit);
        return bySuit != 0 ? bySuit : Integer.compare(rank, other.rank);
    }

    @Override
    public String toString() {
        return label();
    }
}
