package ezmj.bot;

import java.util.List;

import ezmj.model.Meld;
import ezmj.model.Suit;
import ezmj.model.Tile;

/** 估計目前這手牌做完大概值幾台（不含自摸與莊家，那些在算錢時才加）。 */
final class HandValue {

    private HandValue() {
    }

    static double estimate(TableView view, int[] counts, List<Meld> melds) {
        double tai = 0.8;
        boolean closed = melds.stream().allMatch(Meld::isConcealed);
        if (closed) {
            tai += 1;
        }
        for (Tile flower : view.flowers()) {
            if (flower.rank() == view.seatWind() || flower.rank() == view.seatWind() + 4) {
                tai += 1;
            }
        }
        tai += honorValue(view, counts, melds);
        tai += flushValue(counts, melds);
        tai += tripletValue(counts, melds);
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
        int[] perSuit = new int[3];
        int honors = 0;
        for (int i = 0; i < Tile.KINDS; i++) {
            if (i < 27) {
                perSuit[i / 9] += counts[i];
            } else {
                honors += counts[i];
            }
        }
        for (Meld meld : melds) {
            Tile tile = meld.first();
            if (tile.isNumber()) {
                perSuit[tile.index() / 9] += meld.tiles().size();
            } else {
                honors += meld.tiles().size();
            }
        }
        int main = Math.max(perSuit[0], Math.max(perSuit[1], perSuit[2]));
        int stray = perSuit[0] + perSuit[1] + perSuit[2] - main;
        double full = honors > 0 ? 4 : 8;
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
