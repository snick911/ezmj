package ezmj.career;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import ezmj.model.Suit;
import ezmj.model.Tile;
import ezmj.rules.HandAnalyzer;

/**
 * 看聽的題目：一手已經聽牌的 16 張與它聽哪幾種牌。先組好五組面子加一對眼睛（17 張，已經胡了），
 * 再拿掉一張得到 16 張；聽哪幾種由 HandAnalyzer.waits 算，手上已經有 4 張的不算。
 * 聽的種數不在範圍內就重組。亂數由外面給，同一個種子出一樣的題目。不依賴 Swing。
 */
public final class WaitsQuiz {

    /** 一題：排好的 16 張與聽的牌（照牌的順序）。 */
    public record Hand(List<Tile> tiles, List<Tile> waits) {
        /** 選的牌剛好就是聽的那幾種才算對：少選、多選都不算。 */
        public boolean correct(Collection<Tile> answer) {
            return answer != null && new HashSet<>(answer).equals(new HashSet<>(waits)) && answer.size() == waits.size();
        }
    }

    /** 一色牌的修練裡，大約四手有三手是清一色。 */
    private static final double ONE_SUIT_SHARE = .75;
    /** 一組面子是順子的機率。 */
    private static final double SEQUENCE_SHARE = .65;
    /** 雜牌的手裡，一組面子是字牌刻子的機率。 */
    private static final double HONOR_SHARE = .12;
    private static final int MELDS = 5;
    private static final int MAX_TRIES = 20_000;
    private static final Suit[] NUMBER_SUITS = {Suit.WAN, Suit.TONG, Suit.TIAO};

    private WaitsQuiz() {
    }

    /** 這關的每一手，題數照 drill 的 questions。 */
    public static List<Hand> hands(Drill drill, Random random) {
        if (drill.isMemory()) {
            throw new IllegalArgumentException("記牌沒有看聽的題目");
        }
        List<Hand> hands = new ArrayList<>();
        for (int i = 0; i < drill.questions(); i++) {
            boolean oneSuit = drill.oneSuit() && random.nextDouble() < ONE_SUIT_SHARE;
            hands.add(hand(drill.minWaits(), drill.maxWaits(), oneSuit, random));
        }
        return hands;
    }

    /** 一手聽 minWaits～maxWaits 種的牌；oneSuit 是清一色。 */
    static Hand hand(int minWaits, int maxWaits, boolean oneSuit, Random random) {
        for (int attempt = 0; attempt < MAX_TRIES; attempt++) {
            List<Tile> winning = winningHand(oneSuit, random);
            if (winning == null) {
                continue;
            }
            List<Tile> tiles = new ArrayList<>(winning);
            tiles.remove(random.nextInt(tiles.size()));
            List<Tile> waits = HandAnalyzer.waits(tiles);
            if (waits.size() >= minWaits && waits.size() <= maxWaits) {
                tiles.sort(null);
                return new Hand(List.copyOf(tiles), List.copyOf(waits));
            }
        }
        throw new IllegalStateException("組不出聽 " + minWaits + "～" + maxWaits + " 種的牌");
    }

    /** 五組面子加一對眼睛的 17 張，同一種牌最多 4 張；組不出來回傳 null。 */
    private static List<Tile> winningHand(boolean oneSuit, Random random) {
        int[] counts = new int[Tile.KINDS];
        List<Tile> tiles = new ArrayList<>();
        Suit only = NUMBER_SUITS[random.nextInt(NUMBER_SUITS.length)];
        for (int meld = 0; meld < MELDS; meld++) {
            if (!addMeld(counts, tiles, oneSuit ? only : null, random)) {
                return null;
            }
        }
        for (int tries = 0; tries < 50; tries++) {
            Tile pair = randomTile(oneSuit ? only : null, random);
            if (counts[pair.index()] <= 2) {
                counts[pair.index()] += 2;
                tiles.add(pair);
                tiles.add(pair);
                return tiles;
            }
        }
        return null;
    }

    private static boolean addMeld(int[] counts, List<Tile> tiles, Suit only, Random random) {
        for (int tries = 0; tries < 50; tries++) {
            List<Tile> meld = new ArrayList<>();
            boolean honor = only == null && random.nextDouble() < HONOR_SHARE;
            if (!honor && random.nextDouble() < SEQUENCE_SHARE) {
                Suit suit = only != null ? only : NUMBER_SUITS[random.nextInt(NUMBER_SUITS.length)];
                int start = 1 + random.nextInt(7);
                for (int rank = start; rank < start + 3; rank++) {
                    meld.add(Tile.of(suit, rank));
                }
            } else {
                Tile tile = honor ? honorTile(random) : randomTile(only, random);
                meld.add(tile);
                meld.add(tile);
                meld.add(tile);
            }
            if (fits(counts, meld)) {
                for (Tile tile : meld) {
                    counts[tile.index()]++;
                    tiles.add(tile);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean fits(int[] counts, List<Tile> meld) {
        int[] after = counts.clone();
        for (Tile tile : meld) {
            if (++after[tile.index()] > 4) {
                return false;
            }
        }
        return true;
    }

    /** only 是 null 時三種數牌都可以。 */
    private static Tile randomTile(Suit only, Random random) {
        Suit suit = only != null ? only : NUMBER_SUITS[random.nextInt(NUMBER_SUITS.length)];
        return Tile.of(suit, 1 + random.nextInt(9));
    }

    private static Tile honorTile(Random random) {
        // 東南西北中發白：牌的編號 27 到 33
        return Tile.fromIndex(27 + random.nextInt(7));
    }
}
