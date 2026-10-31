package ezmj.bot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import ezmj.model.Tile;

/** 牌效率的共用計算：打哪張之後向聽最少、進張最多。 */
final class Efficiency {

    /** 打出 tile 之後的向聽與進張張數。 */
    record Choice(Tile tile, int shanten, int ukeire) {
    }

    /** 速度優先；一樣快時先打字牌與邊張，留下容易連成面子的中張。 */
    static final Comparator<Choice> FASTEST = Comparator.comparingInt(Choice::shanten)
            .thenComparing(Comparator.comparingInt(Choice::ukeire).reversed())
            .thenComparingInt(choice -> usefulness(choice.tile()));

    private Efficiency() {
    }

    static int[] counts(Collection<Tile> tiles) {
        int[] counts = new int[Tile.KINDS];
        for (Tile tile : tiles) {
            counts[tile.index()]++;
        }
        return counts;
    }

    /** 3n+1 張的手牌有幾張牌可以讓向聽變少；available 決定每種牌算幾張。 */
    static int ukeire(int[] counts, int sets, int shanten, int[] available) {
        long improving = Shanten.improving(counts, sets, shanten);
        int total = 0;
        for (int i = 0; i < Tile.KINDS; i++) {
            if ((improving >> i & 1) != 0) {
                total += Math.max(available[i], 0);
            }
        }
        return total;
    }

    /** 3n+2 張的手牌，每種可打的牌各評估一次。 */
    static List<Choice> discards(int[] counts, int sets, int[] available) {
        List<Choice> choices = new ArrayList<>();
        for (int i = 0; i < Tile.KINDS; i++) {
            if (counts[i] > 0) {
                counts[i]--;
                int shanten = Shanten.of(counts, sets);
                choices.add(new Choice(Tile.fromIndex(i), shanten, ukeire(counts, sets, shanten, available)));
                counts[i]++;
            }
        }
        return choices;
    }

    static Choice fastest(List<Choice> choices) {
        return choices.stream().min(FASTEST).orElseThrow();
    }

    /** 字牌 0、么九 1、二八 2、其餘中張 3；越小越沒有連接的可能。 */
    static int usefulness(Tile tile) {
        if (tile.isHonor()) {
            return 0;
        }
        return Math.min(Math.min(tile.rank(), 10 - tile.rank()), 3);
    }
}
