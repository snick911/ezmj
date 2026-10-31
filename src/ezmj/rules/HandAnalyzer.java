package ezmj.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import ezmj.model.Tile;

/** 拆解暗牌：判斷胡牌、列出所有拆法與聽牌。 */
public final class HandAnalyzer {

    /** 一組暗牌裡的順子或刻子。 */
    public record TileSet(boolean sequence, Tile first) {
        public boolean contains(Tile tile) {
            if (!sequence) {
                return first.equals(tile);
            }
            return first.suit() == tile.suit()
                    && tile.rank() >= first.rank() && tile.rank() <= first.rank() + 2;
        }
    }

    /** 一種拆法：一對眼睛加上若干組面子。 */
    public record Decomposition(Tile pair, List<TileSet> sets) {
    }

    private HandAnalyzer() {
    }

    public static boolean canWin(List<Tile> concealed) {
        return !decompose(concealed).isEmpty();
    }

    /** 列出所有拆法。張數不是 3n+2 時回傳空清單。 */
    public static List<Decomposition> decompose(List<Tile> concealed) {
        List<Decomposition> results = new ArrayList<>();
        if (concealed.size() % 3 != 2) {
            return results;
        }
        int[] counts = toCounts(concealed);
        for (int i = 0; i < Tile.KINDS; i++) {
            if (counts[i] >= 2) {
                counts[i] -= 2;
                collectSets(counts, 0, Tile.fromIndex(i), new ArrayDeque<>(), results);
                counts[i] += 2;
            }
        }
        return results;
    }

    /** 再摸到哪些牌可以胡。 */
    public static List<Tile> waits(List<Tile> concealed) {
        List<Tile> waits = new ArrayList<>();
        List<Tile> trial = new ArrayList<>(concealed);
        int[] counts = toCounts(concealed);
        for (int i = 0; i < Tile.KINDS; i++) {
            if (counts[i] >= 4) {
                continue;
            }
            Tile candidate = Tile.fromIndex(i);
            trial.add(candidate);
            if (canWin(trial)) {
                waits.add(candidate);
            }
            trial.remove(trial.size() - 1);
        }
        return waits;
    }

    private static void collectSets(int[] counts, int from, Tile pair,
                                    Deque<TileSet> current, List<Decomposition> results) {
        int i = from;
        while (i < Tile.KINDS && counts[i] == 0) {
            i++;
        }
        if (i == Tile.KINDS) {
            results.add(new Decomposition(pair, new ArrayList<>(current)));
            return;
        }
        Tile tile = Tile.fromIndex(i);
        if (counts[i] >= 3) {
            counts[i] -= 3;
            current.addLast(new TileSet(false, tile));
            collectSets(counts, i, pair, current, results);
            current.removeLast();
            counts[i] += 3;
        }
        if (tile.isNumber() && tile.rank() <= 7 && counts[i + 1] > 0 && counts[i + 2] > 0) {
            counts[i]--;
            counts[i + 1]--;
            counts[i + 2]--;
            current.addLast(new TileSet(true, tile));
            collectSets(counts, i, pair, current, results);
            current.removeLast();
            counts[i]++;
            counts[i + 1]++;
            counts[i + 2]++;
        }
    }

    private static int[] toCounts(List<Tile> tiles) {
        int[] counts = new int[Tile.KINDS];
        for (Tile tile : tiles) {
            counts[tile.index()]++;
        }
        return counts;
    }
}
