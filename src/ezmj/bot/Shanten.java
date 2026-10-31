package ezmj.bot;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ezmj.model.Tile;

/**
 * 向聽數：還差幾張才聽牌。0 是聽牌，-1 是已經胡了。
 * 把牌拆成面子、搭子與眼睛，向聽 = 2 × (需要的面子 − 面子) − 搭子 − 眼睛。
 * 每個花色先各算一張表再合併，表依牌型快取，一次決策要算上千手牌也夠快。
 */
public final class Shanten {

    private static final int MAX_SETS = 5;
    private static final int SLOTS = MAX_SETS + 1;
    private static final int NONE = -1;
    private static final int[] POWERS = {1, 5, 25, 125, 625, 3125, 15625, 78125, 390625};

    private static final Map<Integer, int[]> SUIT_TABLES = new ConcurrentHashMap<>();
    private static final Map<Integer, int[]> HONOR_TABLES = new ConcurrentHashMap<>();

    private Shanten() {
    }

    /** counts 是暗牌的 34 種計數，sets 是還需要的面子數。 */
    public static int of(int[] counts, int sets) {
        int[] merged = suitTable(code(counts, 0, 9));
        merged = merge(merged, suitTable(code(counts, 9, 9)));
        merged = merge(merged, suitTable(code(counts, 18, 9)));
        merged = merge(merged, honorTable(code(counts, 27, 7)));
        return evaluate(merged, sets);
    }

    /**
     * 向聽為 shanten 的手牌，再多一張哪些牌會讓向聽變少；回傳以牌種索引為位元的遮罩。
     * 每張牌只動到一個花色，其他花色先合併好，比逐張呼叫 of 快很多。
     */
    static long improving(int[] counts, int sets, int shanten) {
        int[] codes = {code(counts, 0, 9), code(counts, 9, 9), code(counts, 18, 9), code(counts, 27, 7)};
        int[][] tables = {suitTable(codes[0]), suitTable(codes[1]), suitTable(codes[2]), honorTable(codes[3])};
        int[] numbersAB = merge(tables[0], tables[1]);
        int[] numbersCH = merge(tables[2], tables[3]);
        int[][] others = {merge(tables[1], numbersCH), merge(tables[0], numbersCH),
            merge(numbersAB, tables[3]), merge(numbersAB, tables[2])};
        long mask = 0;
        for (int i = 0; i < Tile.KINDS; i++) {
            if (counts[i] >= 4) {
                continue;
            }
            int group = Math.min(i / 9, 3);
            int code = codes[group] + POWERS[i - group * 9];
            int[] table = group == 3 ? honorTable(code) : suitTable(code);
            if (evaluate(merge(others[group], table), sets) < shanten) {
                mask |= 1L << i;
            }
        }
        return mask;
    }

    private static int evaluate(int[] merged, int sets) {
        int best = Integer.MAX_VALUE;
        for (int pair = 0; pair < 2; pair++) {
            for (int meldCount = 0; meldCount <= sets; meldCount++) {
                int partials = merged[slot(pair, meldCount)];
                if (partials != NONE) {
                    int missing = sets - meldCount;
                    best = Math.min(best, 2 * missing - Math.min(partials, missing) - pair);
                }
            }
        }
        return best;
    }

    private static int code(int[] counts, int from, int length) {
        int code = 0;
        for (int i = 0; i < length; i++) {
            code += counts[from + i] * POWERS[i];
        }
        return code;
    }

    private static int slot(int pair, int meldCount) {
        return pair * SLOTS + meldCount;
    }

    /** 表的內容：有沒有眼睛、幾組面子時，最多能有幾個搭子。 */
    private static int[] merge(int[] a, int[] b) {
        int[] merged = emptyTable();
        for (int pairA = 0; pairA < 2; pairA++) {
            for (int meldsA = 0; meldsA < SLOTS; meldsA++) {
                int partialsA = a[slot(pairA, meldsA)];
                if (partialsA == NONE) {
                    continue;
                }
                for (int pairB = 0; pairB + pairA < 2; pairB++) {
                    for (int meldsB = 0; meldsA + meldsB < SLOTS; meldsB++) {
                        int partialsB = b[slot(pairB, meldsB)];
                        if (partialsB != NONE) {
                            int target = slot(pairA + pairB, meldsA + meldsB);
                            merged[target] = Math.max(merged[target], partialsA + partialsB);
                        }
                    }
                }
            }
        }
        return merged;
    }

    private static int[] emptyTable() {
        int[] table = new int[SLOTS * 2];
        Arrays.fill(table, NONE);
        return table;
    }

    private static int[] suitTable(int code) {
        int[] cached = SUIT_TABLES.get(code);
        if (cached != null) {
            return cached;
        }
        int[] table = emptyTable();
        if (code == 0) {
            table[slot(0, 0)] = 0;
        } else {
            int i = firstTile(code);
            int first = POWERS[i];
            int count = code / first % 5;
            absorb(table, suitTable(code - first), 0, 0, 0);
            if (count >= 3) {
                absorb(table, suitTable(code - 3 * first), 0, 1, 0);
            }
            if (count >= 2) {
                absorb(table, suitTable(code - 2 * first), 1, 0, 0);
                absorb(table, suitTable(code - 2 * first), 0, 0, 1);
            }
            if (i + 1 < 9 && digit(code, i + 1) > 0) {
                absorb(table, suitTable(code - first - POWERS[i + 1]), 0, 0, 1);
                if (i + 2 < 9 && digit(code, i + 2) > 0) {
                    absorb(table, suitTable(code - first - POWERS[i + 1] - POWERS[i + 2]), 0, 1, 0);
                }
            }
            if (i + 2 < 9 && digit(code, i + 2) > 0) {
                absorb(table, suitTable(code - first - POWERS[i + 2]), 0, 0, 1);
            }
        }
        SUIT_TABLES.put(code, table);
        return table;
    }

    private static int[] honorTable(int code) {
        int[] cached = HONOR_TABLES.get(code);
        if (cached != null) {
            return cached;
        }
        int[] table = emptyTable();
        if (code == 0) {
            table[slot(0, 0)] = 0;
        } else {
            int first = POWERS[firstTile(code)];
            int count = code / first % 5;
            absorb(table, honorTable(code - first), 0, 0, 0);
            if (count >= 3) {
                absorb(table, honorTable(code - 3 * first), 0, 1, 0);
            }
            if (count >= 2) {
                absorb(table, honorTable(code - 2 * first), 1, 0, 0);
                absorb(table, honorTable(code - 2 * first), 0, 0, 1);
            }
        }
        HONOR_TABLES.put(code, table);
        return table;
    }

    private static void absorb(int[] table, int[] rest, int pair, int meldCount, int partials) {
        for (int restPair = 0; restPair + pair < 2; restPair++) {
            for (int restMelds = 0; restMelds + meldCount < SLOTS; restMelds++) {
                int restPartials = rest[slot(restPair, restMelds)];
                if (restPartials != NONE) {
                    int target = slot(restPair + pair, restMelds + meldCount);
                    table[target] = Math.max(table[target], restPartials + partials);
                }
            }
        }
    }

    private static int firstTile(int code) {
        int i = 0;
        while (digit(code, i) == 0) {
            i++;
        }
        return i;
    }

    private static int digit(int code, int i) {
        return code / POWERS[i] % 5;
    }
}
