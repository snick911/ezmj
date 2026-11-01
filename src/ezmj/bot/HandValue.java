package ezmj.bot;

import java.util.List;

import ezmj.model.Meld;
import ezmj.model.Suit;
import ezmj.model.Tile;

/** 估計目前這手牌做完大概值幾台（不含自摸與莊家，那些在算錢時才加）。 */
final class HandValue {

    private static final int HONORS = 3;

    private HandValue() {
    }

    /** ambition 放大門清與大牌的部分；1 就是照實估。 */
    static double estimate(TableView view, int[] counts, List<Meld> melds, double ambition) {
        double tai = 0.8;
        boolean closed = melds.stream().allMatch(Meld::isConcealed);
        if (closed) {
            tai += ambition;
        }
        for (Tile flower : view.flowers()) {
            if (flower.rank() == view.seatWind() || flower.rank() == view.seatWind() + 4) {
                tai += 1;
            }
        }
        tai += ambition * (honorValue(view, counts, melds) + flushValue(counts, melds) + tripletValue(counts, melds));
        return tai;
    }

    private static double honorValue(TableView view, int[] counts, List<Meld> melds) {
        double tai = 0;
        for (Meld meld : melds) {
            tai += scoring(view, meld.first());
        }
        for (int i = 27; i < Tile.KINDS; i++) {
            Tile tile = Tile.fromIndex(i);
            if (counts[i] >= 3) {
                tai += scoring(view, tile);
            } else if (counts[i] == 2) {
                tai += 0.35 * scoring(view, tile);
            }
        }
        return tai;
    }

    private static int scoring(TableView view, Tile tile) {
        if (tile.suit() == Suit.DRAGON) {
            return 1;
        }
        if (tile.suit() != Suit.WIND) {
            return 0;
        }
        return (tile.rank() == view.seatWind() ? 1 : 0) + (tile.rank() == view.roundWind() ? 1 : 0);
    }

    /** 雜牌越少越可能做成一色：差兩三張時才值得為它調整打法。 */
    private static double flushValue(int[] counts, List<Meld> melds) {
        int[] tally = suitTally(counts, melds);
        int main = Math.max(tally[0], Math.max(tally[1], tally[2]));
        int stray = tally[0] + tally[1] + tally[2] - main;
        double full = tally[HONORS] > 0 ? 4 : 8;
        if (stray == 0) {
            return full;
        }
        if (stray == 1) {
            return full * 0.5;
        }
        if (stray == 2) {
            return full * 0.3;
        }
        return stray == 3 ? full * 0.1 : 0;
    }

    /** 萬、筒、條、字各有幾張，手牌與面子都算。 */
    private static int[] suitTally(int[] counts, List<Meld> melds) {
        int[] tally = new int[HONORS + 1];
        for (int i = 0; i < Tile.KINDS; i++) {
            tally[Math.min(i / 9, HONORS)] += counts[i];
        }
        for (Meld meld : melds) {
            Tile tile = meld.first();
            tally[tile.isNumber() ? tile.index() / 9 : HONORS] += meld.tiles().size();
        }
        return tally;
    }

    /** 想做一色時看這張是不是雜牌：不是最多的那種數牌就是，字牌不算。 */
    static boolean isStray(int[] counts, List<Meld> melds, Tile tile) {
        if (!tile.isNumber()) {
            return false;
        }
        int[] tally = suitTally(counts, melds);
        int main = Math.max(tally[0], Math.max(tally[1], tally[2]));
        return tally[tile.index() / 9] < main;
    }

    /** 碰碰胡與暗刻：刻子與對子夠多才算有機會。 */
    private static double tripletValue(int[] counts, List<Meld> melds) {
        int triplets = 0;
        int pairs = 0;
        int concealedTriplets = 0;
        for (Meld meld : melds) {
            if (meld.isSequence()) {
                return 0;
            }
            triplets++;
            if (meld.isConcealed()) {
                concealedTriplets++;
            }
        }
        for (int count : counts) {
            if (count >= 3) {
                triplets++;
                concealedTriplets++;
            } else if (count == 2) {
                pairs++;
            }
        }
        double tai = 0;
        if (triplets + pairs >= 5) {
            tai += 4 * Math.min(1, triplets / 4.0) * 0.5;
        }
        if (concealedTriplets >= 3) {
            tai += 2;
        } else if (concealedTriplets == 2) {
            tai += 0.4;
        }
        return tai;
    }
}
