package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import ezmj.bot.Strategy;
import ezmj.bot.TableView;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundResult;
import ezmj.engine.Settlement;
import ezmj.engine.TurnOptions;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Gender;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Suit;
import ezmj.model.Tile;
import ezmj.model.Wall;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;
import ezmj.rules.Scorer;
import ezmj.rules.WinContext;

/**
 * 獨立裁判：只照說明文字（基本規則、台數計算）另外算一遍，再和引擎逐步比對。
 * 胡牌判定不用 HandAnalyzer，而是暴力列舉 55 種面子的組合；計台不用 Scorer；
 * 行牌用自己的牌桌模型，每個動作之後手牌、面子、花牌、牌河、牌牆都要和引擎一樣。
 * 規則有改的話，這裡要跟著說明一起改。
 */
final class RefereeTest {

    private static final int RESERVED = 16;
    private static final int BASE = 100;
    private static final int PER_TAI = 20;
    private static final Map<String, Integer> FLOWER_WINS = new HashMap<>();

    /** 34 種刻子加 21 種順子，共 55 種面子；索引小於 34 的是刻子。 */
    private static final int[][] SETS = allSets();

    private RefereeTest() {
    }

    static void run() {
        T.section("獨立裁判");

        test("隨機牌型：計台與獨立算法完全相同", () -> {
            Random random = new Random(20261101);
            for (int i = 0; i < 6000; i++) {
                compareRandomHand(random);
            }
        });

        Seat[][] lineups = {
            {Seat.RANDOM, Seat.RANDOM, Seat.RANDOM, Seat.RANDOM},
            {Seat.GREEDY, Seat.GREEDY, Seat.GREEDY, Seat.GREEDY},
            {Seat.BEGINNER, Seat.NORMAL, Seat.AMATEUR, Seat.PRO},
            {Seat.RANDOM, Seat.GREEDY, Seat.NORMAL, Seat.AMATEUR},
        };
        for (Seat[] lineup : lineups) {
            test("整局比對 " + Arrays.toString(lineup) + "：每一步都和獨立模型相同", () -> {
                Random random = new Random(Arrays.hashCode(lineup));
                for (int i = 0; i < 150; i++) {
                    refereeRound(random, lineup, i % 7);
                }
            });
        }

        test("整局比對真的涵蓋八仙過海與七搶一", () -> {
            check(FLOWER_WINS.getOrDefault("八仙過海", 0) > 0 && FLOWER_WINS.getOrDefault("七搶一", 0) > 0, "樣本 " + FLOWER_WINS);
        });

        test("整場比對：結算、連莊、接莊、圈風與牌局結束都和獨立模型相同", () -> {
            Random random = new Random(11);
            for (int startingChips : new int[] {1_000_000, 2_000}) {
                for (int game = 0; game < 15; game++) {
                    refereeSession(random, startingChips);
                }
            }
        });
    }

    // ---------- 獨立的胡牌拆解 ----------

    private record Split(int pair, List<Integer> triplets, List<Integer> sequences) {
    }

    private static int[][] allSets() {
        List<int[]> sets = new ArrayList<>();
        for (int kind = 0; kind < Tile.KINDS; kind++) {
            sets.add(new int[] {kind, kind, kind});
        }
        for (int suit = 0; suit < 3; suit++) {
            for (int rank = 0; rank < 7; rank++) {
                int first = suit * 9 + rank;
                sets.add(new int[] {first, first + 1, first + 2});
            }
        }
        return sets.toArray(new int[0][]);
    }

    private static List<Split> splits(int[] counts) {
        List<Split> found = new ArrayList<>();
        int total = Arrays.stream(counts).sum();
        if (total % 3 != 2) {
            return found;
        }
        for (int pair = 0; pair < Tile.KINDS; pair++) {
            if (counts[pair] >= 2) {
                counts[pair] -= 2;
                chooseSets(counts, 0, (total - 2) / 3, pair, new ArrayDeque<>(), found);
                counts[pair] += 2;
            }
        }
        return found;
    }

    private static void chooseSets(int[] counts, int from, int needed, int pair, Deque<Integer> chosen, List<Split> found) {
        if (needed == 0) {
            if (Arrays.stream(counts).allMatch(count -> count == 0)) {
                List<Integer> triplets = new ArrayList<>();
                List<Integer> sequences = new ArrayList<>();
                for (int index : chosen) {
                    (index < Tile.KINDS ? triplets : sequences).add(SETS[index][0]);
                }
                found.add(new Split(pair, triplets, sequences));
            }
            return;
        }
        for (int index = from; index < SETS.length; index++) {
            int[] set = SETS[index];
            for (int kind : set) {
                counts[kind]--;
            }
            if (Arrays.stream(set).allMatch(kind -> counts[kind] >= 0)) {
                chosen.addLast(index);
                chooseSets(counts, index, needed - 1, pair, chosen, found);
                chosen.removeLast();
            }
            for (int kind : set) {
                counts[kind]++;
            }
        }
    }

    private static boolean canWin(int[] counts) {
        return !splits(counts.clone()).isEmpty();
    }

    /** 聽幾種牌；四張都在自己手上或面子裡的牌不算。 */
    private static int waitKinds(int[] concealed, List<Meld> melds) {
        int[] own = concealed.clone();
        for (Meld meld : melds) {
            for (Tile tile : meld.tiles()) {
                own[tile.index()]++;
            }
        }
        int kinds = 0;
        int[] trial = concealed.clone();
        for (int kind = 0; kind < Tile.KINDS; kind++) {
            if (own[kind] < 4) {
                trial[kind]++;
                if (canWin(trial)) {
                    kinds++;
                }
                trial[kind]--;
            }
        }
        return kinds;
    }

    // ---------- 獨立的計台：逐條照「台數計算」 ----------

    private record Win(int[] concealed, List<Meld> melds, List<Tile> flowers, Tile tile, boolean selfDraw, int roundWind,
                       int seatWind, boolean robbed, boolean afterKong, boolean last, boolean heavenly, boolean earthly) {
    }

    private static boolean isWind(int kind) {
        return kind >= 27 && kind < 31;
    }

    private static boolean isDragon(int kind) {
        return kind >= 31;
    }

    private static TreeMap<String, Integer> expectedScore(Win win) {
        TreeMap<String, Integer> best = null;
        for (Split split : splits(win.concealed().clone())) {
            TreeMap<String, Integer> items = scoreSplit(win, split);
            if (best == null || total(items) > total(best)) {
                best = items;
            }
        }
        return best;
    }

    private static int total(Map<String, Integer> items) {
        return items.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static TreeMap<String, Integer> scoreSplit(Win win, Split split) {
        TreeMap<String, Integer> items = new TreeMap<>();
        int winKind = win.tile().index();
        List<Integer> triplets = new ArrayList<>(split.triplets());
        int sequences = split.sequences().size();
        int hiddenTriplets = split.triplets().size();
        int openMelds = 0;
        for (Meld meld : win.melds()) {
            if (meld.type() == Meld.Type.CHOW) {
                sequences++;
                openMelds++;
            } else {
                triplets.add(meld.tiles().get(0).index());
                if (meld.type() == Meld.Type.CONCEALED_KONG) {
                    hiddenTriplets++;
                } else {
                    openMelds++;
                }
            }
        }
        boolean inSequence = split.sequences().stream().anyMatch(first -> winKind >= first && winKind <= first + 2);
        if (!win.selfDraw() && split.triplets().contains(winKind) && !inSequence) {
            hiddenTriplets--;
        }

        if (win.heavenly()) {
            items.put("天胡", 24);
        }
        if (win.earthly()) {
            items.put("地胡", 16);
        }
        scoreHonors(items, win, triplets, split.pair());
        scoreSuits(items, win);
        if (sequences == 0) {
            items.put("碰碰胡", 4);
        }
        if (hiddenTriplets >= 5) {
            items.put("五暗刻", 8);
        } else if (hiddenTriplets == 4) {
            items.put("四暗刻", 5);
        } else if (hiddenTriplets == 3) {
            items.put("三暗刻", 2);
        }

        int[] waiting = win.concealed().clone();
        waiting[winKind]--;
        boolean single = waitKinds(waiting, win.melds()) == 1;
        if (sequences == 5 && split.pair() < 27 && win.flowers().isEmpty() && !win.selfDraw() && !single) {
            items.put("平胡", 2);
        }
        if (openMelds == 0) {
            items.put(win.selfDraw() ? "門清一摸三" : "門清", win.selfDraw() ? 3 : 1);
        } else if (win.selfDraw()) {
            items.put("自摸", 1);
        }
        if (openMelds == 5 && !win.selfDraw()) {
            items.put("全求人", 2);
        } else if (single) {
            items.put("獨聽", 1);
        }
        if (win.last()) {
            items.put(win.selfDraw() ? "海底撈月" : "河底撈魚", 1);
        }
        if (win.afterKong() && win.selfDraw()) {
            items.put("槓上開花", 1);
        }
        if (win.robbed()) {
            items.put("搶槓", 1);
        }
        scoreFlowers(items, win);
        return items;
    }

    private static void scoreHonors(Map<String, Integer> items, Win win, List<Integer> triplets, int pair) {
        long winds = triplets.stream().filter(RefereeTest::isWind).count();
        if (winds == 4) {
            items.put("大四喜", 16);
        } else if (winds == 3 && isWind(pair)) {
            items.put("小四喜", 8);
        } else {
            if (triplets.contains(26 + win.roundWind())) {
                items.put("圈風", 1);
            }
            if (triplets.contains(26 + win.seatWind())) {
                items.put("門風", 1);
            }
        }
        long dragons = triplets.stream().filter(RefereeTest::isDragon).count();
        if (dragons == 3) {
            items.put("大三元", 8);
        } else if (dragons == 2 && isDragon(pair)) {
            items.put("小三元", 4);
        } else if (dragons > 0) {
            items.put("三元牌", (int) dragons);
        }
    }

    private static void scoreSuits(Map<String, Integer> items, Win win) {
        boolean[] numberSuits = new boolean[3];
        boolean honor = false;
        List<Integer> kinds = new ArrayList<>();
        for (int kind = 0; kind < Tile.KINDS; kind++) {
            if (win.concealed()[kind] > 0) {
                kinds.add(kind);
            }
        }
        for (Meld meld : win.melds()) {
            kinds.add(meld.tiles().get(0).index());
        }
        for (int kind : kinds) {
            if (kind >= 27) {
                honor = true;
            } else {
                numberSuits[kind / 9] = true;
            }
        }
        int suits = (numberSuits[0] ? 1 : 0) + (numberSuits[1] ? 1 : 0) + (numberSuits[2] ? 1 : 0);
        if (suits == 0) {
            items.put("字一色", 16);
        } else if (suits == 1) {
            items.put(honor ? "混一色" : "清一色", honor ? 4 : 8);
        }
    }

    private static void scoreFlowers(Map<String, Integer> items, Win win) {
        int proper = 0;
        int seasons = 0;
        int plants = 0;
        for (Tile flower : win.flowers()) {
            if ((flower.rank() - 1) % 4 + 1 == win.seatWind()) {
                proper++;
            }
            if (flower.rank() <= 4) {
                seasons++;
            } else {
                plants++;
            }
        }
        if (proper > 0) {
            items.put("正花", proper);
        }
        int flowerKongs = (seasons == 4 ? 1 : 0) + (plants == 4 ? 1 : 0);
        if (flowerKongs > 0) {
            items.put("花槓", flowerKongs * 2);
        }
    }

    /** 引擎的項目合併成同樣的鍵：「圈風 東」算圈風，同名相加。 */
    private static TreeMap<String, Integer> engineItems(ScoreResult score) {
        TreeMap<String, Integer> items = new TreeMap<>();
        for (ScoreItem item : score.items()) {
            items.merge(item.name().split(" ")[0], item.tai(), Integer::sum);
        }
        return items;
    }

    private static int[] countsOf(List<Tile> tiles) {
        int[] counts = new int[Tile.KINDS];
        for (Tile tile : tiles) {
            counts[tile.index()]++;
        }
        return counts;
    }

    // ---------- 隨機牌型 ----------

    private static void compareRandomHand(Random random) {
        int[] held = new int[Tile.KINDS];
        int[] concealed = new int[Tile.KINDS];
        List<Meld> melds = new ArrayList<>();
        int style = random.nextInt(6);
        int suit = random.nextInt(3);
        int meldShare = random.nextInt(4);
        int sets = 0;
        for (int attempt = 0; sets < 5; attempt++) {
            if (attempt > 200) {
                return;
            }
            boolean triplet = style == 2 || style == 5 || (style != 1 && random.nextBoolean());
            int kind;
            if (style == 3) {
                kind = suit * 9 + random.nextInt(9);
            } else if (style == 4 || style == 5) {
                kind = random.nextBoolean() ? 27 + random.nextInt(7) : suit * 9 + random.nextInt(9);
            } else {
                kind = random.nextInt(Tile.KINDS);
            }
            if (!triplet && kind >= 27) {
                triplet = true;
            }
            if (!triplet && kind % 9 > 6) {
                kind -= kind % 9 - 6;
            }
            Meld.Type type = null;
            if (random.nextInt(4) < meldShare) {
                Meld.Type[] kinds = {Meld.Type.PONG, Meld.Type.OPEN_KONG, Meld.Type.CONCEALED_KONG};
                type = triplet ? kinds[random.nextInt(3)] : Meld.Type.CHOW;
            }
            int[] tiles = triplet ? new int[] {kind, kind, kind, kind} : new int[] {kind, kind + 1, kind + 2};
            int size = type == Meld.Type.OPEN_KONG || type == Meld.Type.CONCEALED_KONG ? 4 : 3;
            int[] after = held.clone();
            for (int i = 0; i < size; i++) {
                after[tiles[i]]++;
            }
            if (Arrays.stream(after).anyMatch(count -> count > 4)) {
                continue;
            }
            held = after;
            sets++;
            if (type == null) {
                for (int i = 0; i < size; i++) {
                    concealed[tiles[i]]++;
                }
            } else {
                List<Tile> meldTiles = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    meldTiles.add(Tile.fromIndex(tiles[i]));
                }
                melds.add(new Meld(type, meldTiles));
            }
        }
        int pair = style == 4 || style == 5 ? 27 + random.nextInt(7) : random.nextInt(Tile.KINDS);
        if (held[pair] > 2) {
            return;
        }
        concealed[pair] += 2;

        List<Tile> hand = new ArrayList<>();
        for (int kind = 0; kind < Tile.KINDS; kind++) {
            hand.addAll(Collections.nCopies(concealed[kind], Tile.fromIndex(kind)));
        }
        Tile winTile = hand.get(random.nextInt(hand.size()));
        boolean selfDraw = random.nextBoolean();
        boolean robbed = !selfDraw && random.nextInt(10) == 0;
        boolean last = !robbed && random.nextInt(10) == 0;
        boolean afterKong = selfDraw && random.nextInt(4) == 0
                && melds.stream().anyMatch(meld -> meld.tiles().size() == 4);
        boolean clean = selfDraw && melds.isEmpty();
        boolean heavenly = clean && random.nextInt(10) == 0;
        boolean earthly = clean && !heavenly && random.nextInt(10) == 0;
        int roundWind = 1 + random.nextInt(4);
        int seatWind = 1 + random.nextInt(4);
        List<Tile> flowers = new ArrayList<>();
        int flowerStyle = random.nextInt(5);
        for (int rank = 1; rank <= 8; rank++) {
            boolean take = flowerStyle == 1 ? random.nextBoolean() : flowerStyle == 2 || (flowerStyle == 3 && rank <= 4);
            if (take) {
                flowers.add(Tile.of(Suit.FLOWER, rank));
            }
        }
        Collections.shuffle(hand, random);
        ScoreResult actual = Scorer.score(new WinContext(hand, melds, flowers, winTile, selfDraw, roundWind, seatWind,
                robbed, afterKong, last, heavenly, earthly));
        TreeMap<String, Integer> expected = expectedScore(new Win(concealed, melds, flowers, winTile, selfDraw, roundWind,
                seatWind, robbed, afterKong, last, heavenly, earthly));
        eq(total(expected), actual.totalTai(), "計台 " + hand + " 面子 " + melds + " 胡 " + winTile
                + " 自摸 " + selfDraw + " 預期 " + expected + " 實際 " + actual.items());
    }

    // ---------- 獨立的牌桌模型 ----------

    private static final class SeatModel {
        final int[] hand = new int[Tile.KINDS];
        final List<Meld> melds = new ArrayList<>();
        final List<Tile> flowers = new ArrayList<>();
        final List<Tile> discards = new ArrayList<>();
    }

    private static final class Model {
        final SeatModel[] seats = {new SeatModel(), new SeatModel(), new SeatModel(), new SeatModel()};
        final int[] discardCount = new int[4];
        final Deque<Tile> wall;
        final int dealer;
        Round.Phase phase = Round.Phase.TURN;
        int current;
        Tile drawn;
        boolean discardOnly;
        boolean replaced;
        boolean anyMeld;
        Tile claimTile;
        int discarder = -1;
        boolean robbing;
        int winner = -1;
        int from = -1;
        Tile winTile;
        boolean robbedWin;
        boolean afterKongWin;
        boolean lastWin;
        boolean firstDrawWin;
        String flowerItem;

        Model(List<Tile> order, int dealer) {
            this.wall = new ArrayDeque<>(order);
            this.dealer = dealer;
            List<List<Tile>> dealtFlowers = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            for (int i = 0; i < 16; i++) {
                for (int offset = 0; offset < 4; offset++) {
                    int seat = (dealer + offset) % 4;
                    Tile tile = wall.pollFirst();
                    if (tile.isFlower()) {
                        dealtFlowers.get(seat).add(tile);
                    } else {
                        seats[seat].hand[tile.index()]++;
                    }
                }
            }
            for (int offset = 0; offset < 4; offset++) {
                int seat = (dealer + offset) % 4;
                PriorityQueue<Tile> pending = new PriorityQueue<>(dealtFlowers.get(seat));
                while (!pending.isEmpty()) {
                    if (reveal(seat, pending.poll())) {
                        return;
                    }
                    Tile tile = wall.pollLast();
                    if (tile.isFlower()) {
                        pending.add(tile);
                    } else {
                        seats[seat].hand[tile.index()]++;
                    }
                }
            }
            draw(dealer, false);
        }

        /** 八張都亮出來時才可能立即胡：一家八張，或一家七張、別家一張。 */
        private boolean reveal(int seat, Tile flower) {
            seats[seat].flowers.add(flower);
            int shown = 0;
            int most = 0;
            for (int s = 0; s < 4; s++) {
                shown += seats[s].flowers.size();
                if (seats[s].flowers.size() > seats[most].flowers.size()) {
                    most = s;
                }
            }
            if (shown < 8 || seats[most].flowers.size() < 7) {
                return false;
            }
            winner = most;
            if (seats[most].flowers.size() == 8) {
                winTile = flower;
                flowerItem = "八仙過海";
            } else {
                for (int s = 0; s < 4; s++) {
                    if (s != most && seats[s].flowers.size() == 1) {
                        from = s;
                        winTile = seats[s].flowers.remove(0);
                        seats[most].flowers.add(winTile);
                        break;
                    }
                }
                flowerItem = "七搶一";
            }
            phase = Round.Phase.FINISHED;
            drawn = null;
            claimTile = null;
            robbing = false;
            return true;
        }

        int live() {
            return Math.max(0, wall.size() - RESERVED);
        }

        void draw(int seat, boolean replacement) {
            current = seat;
            discardOnly = false;
            if (live() == 0) {
                drawnGame();
                return;
            }
            Tile tile = replacement ? wall.pollLast() : wall.pollFirst();
            while (tile.isFlower()) {
                if (reveal(seat, tile)) {
                    return;
                }
                if (live() == 0) {
                    drawnGame();
                    return;
                }
                tile = wall.pollLast();
            }
            seats[seat].hand[tile.index()]++;
            drawn = tile;
            replaced = replacement;
            phase = Round.Phase.TURN;
        }

        void drawnGame() {
            phase = Round.Phase.FINISHED;
            drawn = null;
            claimTile = null;
            robbing = false;
        }

        void selfWin(int seat) {
            winner = seat;
            winTile = drawn;
            afterKongWin = replaced;
            lastWin = live() == 0;
            firstDrawWin = discardCount[seat] == 0 && !anyMeld;
            phase = Round.Phase.FINISHED;
            drawn = null;
        }

        void discard(int seat, Tile tile) {
            seats[seat].hand[tile.index()]--;
            seats[seat].discards.add(tile);
            discardCount[seat]++;
            claimTile = tile;
            discarder = seat;
            drawn = null;
            discardOnly = false;
            phase = Round.Phase.CLAIMS;
        }

        void concealedKong(int seat, Tile tile) {
            seats[seat].hand[tile.index()] -= 4;
            seats[seat].melds.add(new Meld(Meld.Type.CONCEALED_KONG, List.of(tile, tile, tile, tile)));
            anyMeld = true;
            draw(seat, true);
        }

        void addKong(int seat, Tile tile) {
            seats[seat].hand[tile.index()]--;
            anyMeld = true;
            claimTile = tile;
            discarder = seat;
            robbing = true;
            drawn = null;
            phase = Round.Phase.CLAIMS;
        }

        Set<String> turnOptions(int seat) {
            Set<String> options = new TreeSet<>();
            if (discardOnly) {
                return options;
            }
            if (canWin(seats[seat].hand)) {
                options.add("自摸");
            }
            if (live() > 0) {
                for (int kind = 0; kind < Tile.KINDS; kind++) {
                    if (seats[seat].hand[kind] == 4) {
                        options.add("暗槓" + kind);
                    }
                }
                for (Meld meld : seats[seat].melds) {
                    if (meld.type() == Meld.Type.PONG && seats[seat].hand[meld.tiles().get(0).index()] > 0) {
                        options.add("加槓" + meld.tiles().get(0).index());
                    }
                }
            }
            return options;
        }

        Set<String> claimOptions(int seat) {
            Set<String> options = new TreeSet<>();
            if (seat == discarder) {
                return options;
            }
            int[] hand = seats[seat].hand;
            int kind = claimTile.index();
            int[] withTile = hand.clone();
            withTile[kind]++;
            if (canWin(withTile)) {
                options.add("WIN");
            }
            if (robbing) {
                return options;
            }
            if (hand[kind] == 3 && live() > 0) {
                options.add("KONG");
            }
            if (hand[kind] >= 2) {
                options.add("PONG");
            }
            if (seat == (discarder + 1) % 4 && kind < 27) {
                int rank = kind % 9;
                int base = kind - rank;
                for (int first = Math.max(0, rank - 2); first <= Math.min(6, rank); first++) {
                    boolean all = true;
                    for (int r = first; r < first + 3; r++) {
                        all &= r == rank || hand[base + r] > 0;
                    }
                    if (all) {
                        options.add("CHOW" + first);
                    }
                }
            }
            return options;
        }

        /** 胡 > 槓、碰 > 吃；同優先由放槍者下家起最近的人。 */
        void resolve(Map<Integer, Claim> decisions) {
            int chosen = -1;
            int bestRank = Integer.MAX_VALUE;
            for (int distance = 1; distance <= 3; distance++) {
                int seat = (discarder + distance) % 4;
                Claim claim = decisions.get(seat);
                if (claim != null) {
                    int rank = claim.type() == Claim.Type.WIN ? 0 : claim.type() == Claim.Type.CHOW ? 2 : 1;
                    if (rank < bestRank) {
                        bestRank = rank;
                        chosen = seat;
                    }
                }
            }
            if (chosen < 0) {
                passed();
            } else if (bestRank == 0) {
                ron(chosen);
            } else {
                meld(chosen, decisions.get(chosen));
            }
        }

        private void passed() {
            Tile tile = claimTile;
            claimTile = null;
            if (robbing) {
                robbing = false;
                List<Meld> melds = seats[discarder].melds;
                for (int i = 0; i < melds.size(); i++) {
                    if (melds.get(i).type() == Meld.Type.PONG && melds.get(i).tiles().get(0).equals(tile)) {
                        melds.set(i, new Meld(Meld.Type.OPEN_KONG, List.of(tile, tile, tile, tile)));
                    }
                }
                draw(discarder, true);
            } else {
                draw((discarder + 1) % 4, false);
            }
        }

        private void ron(int seat) {
            if (!robbing) {
                seats[discarder].discards.remove(seats[discarder].discards.size() - 1);
            }
            seats[seat].hand[claimTile.index()]++;
            winner = seat;
            from = discarder;
            winTile = claimTile;
            robbedWin = robbing;
            lastWin = live() == 0;
            phase = Round.Phase.FINISHED;
            claimTile = null;
            robbing = false;
        }

        private void meld(int seat, Claim claim) {
            seats[discarder].discards.remove(seats[discarder].discards.size() - 1);
            for (Tile tile : claim.fromHand()) {
                seats[seat].hand[tile.index()]--;
            }
            List<Tile> tiles = new ArrayList<>(claim.fromHand());
            tiles.add(claim.tile());
            Collections.sort(tiles);
            anyMeld = true;
            claimTile = null;
            if (claim.type() == Claim.Type.KONG) {
                seats[seat].melds.add(new Meld(Meld.Type.OPEN_KONG, tiles));
                draw(seat, true);
            } else {
                seats[seat].melds.add(new Meld(claim.type() == Claim.Type.PONG ? Meld.Type.PONG : Meld.Type.CHOW, tiles));
                current = seat;
                drawn = null;
                discardOnly = true;
                phase = Round.Phase.TURN;
            }
        }

        void compare(Round round) {
            eq(phase, round.phase(), "階段");
            if (phase == Round.Phase.TURN) {
                eq(current, round.current(), "輪到誰");
            }
            eq(wall.size(), round.wall().size(), "牌牆張數");
            check(java.util.Objects.equals(drawn, round.drawn()), "剛摸的牌：" + drawn + " / " + round.drawn());
            for (int seat = 0; seat < 4; seat++) {
                Player player = round.player(seat);
                check(Arrays.equals(seats[seat].hand, countsOf(player.concealed())), "座位 " + seat + " 手牌 " + player.concealed());
                eq(seats[seat].melds, player.melds(), "座位 " + seat + " 面子");
                eq(seats[seat].flowers, player.flowers(), "座位 " + seat + " 花牌");
                eq(seats[seat].discards, player.discards(), "座位 " + seat + " 牌河");
            }
        }
    }

    // ---------- 排牌牆與整局比對 ----------

    private enum Seat { RANDOM, GREEDY, BEGINNER, NORMAL, AMATEUR, PRO }

    /** 排出容易出現少見情況的牌牆。style：0 一般、1 同花色、2 同種牌連發、3 開局差一張胡、4 花牌集中、5 七張加別家一張、6 七張加第八張在牌頭。 */
    private static List<Tile> riggedWall(Random random, int dealer, int style) {
        List<Tile> pool = new ArrayList<>(Tile.fullSet());
        Collections.shuffle(pool, random);
        List<List<Tile>> hands = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<Tile> front = new ArrayList<>();
        if (style == 1) {
            for (List<Tile> hand : hands) {
                Suit wanted = Suit.values()[random.nextInt(4)];
                Iterator<Tile> tiles = pool.iterator();
                while (tiles.hasNext() && hand.size() < 13) {
                    Tile tile = tiles.next();
                    if (tile.suit() == wanted || (tile.isHonor() && random.nextInt(3) == 0)) {
                        hand.add(tile);
                        tiles.remove();
                    }
                }
            }
        } else if (style == 2) {
            int salt = random.nextInt(34);
            pool.sort(Comparator.comparingInt(tile -> tile.isFlower() ? 99 : (tile.index() * 7 + salt) % 34));
            for (List<Tile> hand : hands) {
                for (int i = 0; i < 14; i++) {
                    hand.add(pool.remove(0));
                }
            }
            Collections.shuffle(pool, random);
        } else if (style == 3) {
            int seat = random.nextInt(4);
            List<Tile> winning = winningHand(random, pool);
            if (winning != null) {
                Tile last = winning.remove(random.nextInt(winning.size()));
                hands.get(seat).addAll(winning);
                for (int i = 0; i < Math.floorMod(seat - dealer, 4); i++) {
                    Tile filler = pool.stream().filter(tile -> !tile.isFlower()).findFirst().orElseThrow();
                    pool.remove(filler);
                    front.add(filler);
                }
                front.add(last);
            }
        } else if (style == 4) {
            List<Tile> flowers = pool.stream().filter(Tile::isFlower).limit(4 + random.nextInt(5)).toList();
            for (Tile flower : flowers) {
                pool.remove(flower);
                hands.get(0).add(flower);
            }
        } else if (style == 5 || style == 6) {
            List<Tile> flowers = pool.stream().filter(Tile::isFlower).toList();
            pool.removeAll(flowers);
            int seat = random.nextInt(4);
            hands.get(seat).addAll(flowers.subList(0, 7));
            if (style == 5) {
                hands.get((seat + 1 + random.nextInt(3)) % 4).add(flowers.get(7));
            } else {
                for (int i = random.nextInt(6); i > 0; i--) {
                    front.add(pool.remove(0));
                }
                front.add(flowers.get(7));
            }
        }
        for (List<Tile> hand : hands) {
            while (hand.size() < 16) {
                hand.add(pool.remove(0));
            }
            Collections.shuffle(hand, random);
        }
        List<Tile> order = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            for (int offset = 0; offset < 4; offset++) {
                order.add(hands.get((dealer + offset) % 4).get(i));
            }
        }
        order.addAll(front);
        order.addAll(pool);
        return order;
    }

    private static List<Tile> winningHand(Random random, List<Tile> pool) {
        for (int attempt = 0; attempt < 40; attempt++) {
            List<Tile> left = new ArrayList<>(pool);
            List<Tile> hand = new ArrayList<>();
            boolean triplets = random.nextBoolean();
            boolean ok = true;
            for (int set = 0; set < 5 && ok; set++) {
                boolean triplet = triplets || random.nextBoolean();
                int kind = random.nextInt(triplet ? Tile.KINDS : 27);
                if (!triplet && kind % 9 > 6) {
                    kind -= kind % 9 - 6;
                }
                for (int i = 0; i < 3 && ok; i++) {
                    Tile tile = Tile.fromIndex(triplet ? kind : kind + i);
                    ok = left.remove(tile);
                    hand.add(tile);
                }
            }
            Tile pair = Tile.fromIndex(random.nextInt(Tile.KINDS));
            ok = ok && left.remove(pair) && left.remove(pair);
            if (ok) {
                hand.add(pair);
                hand.add(pair);
                pool.clear();
                pool.addAll(left);
                return hand;
            }
        }
        return null;
    }

    private static Strategy[] strategies(Seat[] lineup, Random random) {
        Strategy[] strategies = new Strategy[4];
        for (int seat = 0; seat < 4; seat++) {
            if (lineup[seat].ordinal() >= Seat.BEGINNER.ordinal()) {
                BotLevel level = BotLevel.values()[lineup[seat].ordinal() - Seat.BEGINNER.ordinal()];
                strategies[seat] = Strategy.of(level, BASE, PER_TAI, new Random(random.nextLong()));
            }
        }
        return strategies;
    }

    private static void refereeRound(Random random, Seat[] lineup, int style) {
        int dealer = random.nextInt(4);
        int roundWind = 1 + random.nextInt(4);
        List<Tile> order = riggedWall(random, dealer, style);
        Player[] players = new Player[4];
        for (int seat = 0; seat < 4; seat++) {
            players[seat] = new Player("P" + seat, seat, seat == 0, Gender.FEMALE, 100_000);
        }
        Round round = new Round(players, new Wall(order), dealer, roundWind, 1, 0, message -> { });
        Model model = new Model(order, dealer);
        round.start();
        model.compare(round);
        playRound(round, model, lineup, strategies(lineup, random), random);
        checkResult(round, model, roundWind);
    }

    private static void checkResult(Round round, Model model, int roundWind) {
        RoundResult result = round.result();
        if (model.winner < 0) {
            check(result.isDrawnGame(), "應該流局：" + result);
            return;
        }
        eq(model.winner, result.winner(), "胡牌者");
        eq(model.from, result.from(), "放槍者");
        eq(model.winTile, result.winTile(), "胡的牌");
        if (model.flowerItem != null) {
            eq(new TreeMap<>(Map.of(model.flowerItem, 8)), engineItems(result.score()), "靠花牌胡只算這一項");
            eq(8, round.player(model.winner).flowers().size(), "胡牌者有八張花");
            FLOWER_WINS.merge(model.flowerItem, 1, Integer::sum);
            return;
        }
        int seatWind = Math.floorMod(model.winner - model.dealer, 4) + 1;
        SeatModel seat = model.seats[model.winner];
        TreeMap<String, Integer> expected = expectedScore(new Win(seat.hand.clone(), seat.melds, seat.flowers, model.winTile,
                model.from < 0, roundWind, seatWind, model.robbedWin, model.afterKongWin, model.lastWin,
                model.firstDrawWin && model.winner == model.dealer, model.firstDrawWin && model.winner != model.dealer));
        eq(expected, engineItems(result.score()), "實戰計台 " + round.player(model.winner).concealed() + " 面子 " + seat.melds);
    }

    private static void playRound(Round round, Model model, Seat[] lineup, Strategy[] strategies, Random random) {
        for (int guard = 0; round.phase() != Round.Phase.FINISHED; guard++) {
            check(guard < 1000, "牌局必須會結束");
            if (round.phase() == Round.Phase.TURN) {
                playTurn(round, model, lineup, strategies, random);
            } else {
                Map<Integer, Claim> decisions = new HashMap<>();
                for (int seat = 0; seat < 4; seat++) {
                    List<Claim> options = round.claimOptions(seat);
                    eq(model.claimOptions(seat), claimNames(options), "座位 " + seat + " 對 " + round.claimTile() + " 的宣告");
                    Claim claim = options.isEmpty() ? null : chooseClaim(round, seat, options, lineup[seat], strategies[seat], random);
                    if (claim != null) {
                        decisions.put(seat, claim);
                    }
                }
                round.resolveClaims(decisions);
                model.resolve(decisions);
            }
            model.compare(round);
        }
    }

    private static Set<String> claimNames(List<Claim> claims) {
        Set<String> names = new TreeSet<>();
        for (Claim claim : claims) {
            if (claim.type() == Claim.Type.CHOW) {
                int lowest = Math.min(claim.tile().index(), claim.fromHand().get(0).index());
                names.add("CHOW" + lowest % 9);
            } else {
                names.add(claim.type().name());
            }
        }
        return names;
    }

    private static void playTurn(Round round, Model model, Seat[] lineup, Strategy[] strategies, Random random) {
        int seat = round.current();
        TurnOptions options = round.turnOptions(seat);
        Set<String> names = new TreeSet<>();
        if (options.canSelfWin()) {
            names.add("自摸");
        }
        options.concealedKongs().forEach(tile -> names.add("暗槓" + tile.index()));
        options.addKongs().forEach(tile -> names.add("加槓" + tile.index()));
        eq(model.turnOptions(seat), names, "座位 " + seat + " 的回合選項");

        Seat kind = lineup[seat];
        if (options.canSelfWin() && (kind != Seat.RANDOM || random.nextInt(10) > 0)) {
            round.declareSelfWin(seat);
            model.selfWin(seat);
            return;
        }
        Tile kong = chooseKong(round, seat, options, kind, strategies[seat], random);
        if (kong != null && options.concealedKongs().contains(kong)) {
            round.declareConcealedKong(seat, kong);
            model.concealedKong(seat, kong);
        } else if (kong != null) {
            round.declareAddKong(seat, kong);
            model.addKong(seat, kong);
        } else {
            Tile tile;
            if (strategies[seat] != null) {
                tile = strategies[seat].chooseDiscard(TableView.of(round, seat));
            } else {
                List<Tile> hand = round.player(seat).concealed();
                tile = hand.get(random.nextInt(hand.size()));
            }
            round.discard(seat, tile);
            model.discard(seat, tile);
        }
    }

    private static Tile chooseKong(Round round, int seat, TurnOptions options, Seat kind, Strategy strategy, Random random) {
        List<Tile> kongs = new ArrayList<>(options.concealedKongs());
        kongs.addAll(options.addKongs());
        if (kongs.isEmpty()) {
            return null;
        }
        if (strategy != null) {
            return strategy.chooseKong(TableView.of(round, seat), options);
        }
        return kind == Seat.GREEDY || random.nextBoolean() ? kongs.get(random.nextInt(kongs.size())) : null;
    }

    private static Claim chooseClaim(Round round, int seat, List<Claim> options, Seat kind, Strategy strategy, Random random) {
        Claim win = options.stream().filter(claim -> claim.type() == Claim.Type.WIN).findFirst().orElse(null);
        if (win != null && (kind != Seat.RANDOM || random.nextInt(10) > 0)) {
            return win;
        }
        if (kind == Seat.RANDOM) {
            return random.nextInt(3) == 0 ? null : options.get(random.nextInt(options.size()));
        }
        List<Claim> melds = options.stream().filter(claim -> claim.type() != Claim.Type.WIN).toList();
        if (melds.isEmpty()) {
            return null;
        }
        return strategy != null ? strategy.chooseClaim(TableView.of(round, seat), melds) : melds.get(random.nextInt(melds.size()));
    }

    // ---------- 整場 ----------

    /** 照說明算結算、連莊、接莊、圈風與結束條件，和 GameSession 比對。 */
    private static void refereeSession(Random random, int startingChips) {
        GameSession session = new GameSession(new GameConfig("裁判", startingChips, BASE, PER_TAI, BotLevel.NORMAL), random);
        Seat[] lineup = {Seat.GREEDY, Seat.NORMAL, Seat.GREEDY, Seat.BEGINNER};
        Strategy[] strategies = strategies(lineup, random);
        int firstDealer = session.dealer();
        int dealer = firstDealer;
        int wind = 1;
        int streak = 0;
        int[] chips = {startingChips, startingChips, startingChips, startingChips};
        int rounds = 0;
        int drawn = 0;
        while (!session.isOver()) {
            eq(dealer, session.dealer(), "莊家");
            eq(streak, session.streak(), "連莊");
            eq(wind, session.roundWind(), "圈風");
            Round round = session.newRound(message -> { });
            round.start();
            playWithoutModel(round, lineup, strategies, random);
            RoundResult result = round.result();
            eq(streak, round.streak(), "這局的連莊");
            eq(dealer, round.dealer(), "這局的莊家");
            eq(wind, round.roundWind(), "這局的圈風");

            int[] expected = new int[4];
            if (!result.isDrawnGame()) {
                for (int seat = 0; seat < 4; seat++) {
                    if (seat != result.winner() && (result.isSelfDraw() || seat == result.from())) {
                        boolean dealerInvolved = seat == dealer || result.winner() == dealer;
                        int tai = result.score().totalTai() + (dealerInvolved ? 1 + 2 * streak : 0);
                        expected[seat] -= BASE + tai * PER_TAI;
                        expected[result.winner()] += BASE + tai * PER_TAI;
                    }
                }
            }
            Settlement settlement = session.settle(result);
            check(Arrays.equals(expected, settlement.deltas()), "結算：預期 " + Arrays.toString(expected)
                    + "，實際 " + Arrays.toString(settlement.deltas()));
            rounds++;
            for (int seat = 0; seat < 4; seat++) {
                chips[seat] += expected[seat];
                eq(chips[seat], session.player(seat).chips(), "座位 " + seat + " 籌碼");
            }
            if (result.isDrawnGame()) {
                drawn++;
            }
            if (result.isDrawnGame() || result.winner() == dealer) {
                streak++;
            } else {
                streak = 0;
                dealer = (dealer + 1) % 4;
                if (dealer == firstDealer) {
                    wind++;
                }
            }
            boolean over = wind > 4 || Arrays.stream(chips).anyMatch(amount -> amount <= 0);
            eq(over, session.isOver(), "牌局結束");
        }
        eq(rounds, session.roundsPlayed(), "局數");
        eq(drawn, session.drawnGames(), "流局數");
        eq(startingChips * 4, Arrays.stream(chips).sum(), "籌碼總和不變");
    }

    private static void playWithoutModel(Round round, Seat[] lineup, Strategy[] strategies, Random random) {
        while (round.phase() != Round.Phase.FINISHED) {
            if (round.phase() == Round.Phase.TURN) {
                int seat = round.current();
                TurnOptions options = round.turnOptions(seat);
                if (options.canSelfWin()) {
                    round.declareSelfWin(seat);
                    continue;
                }
                Tile kong = chooseKong(round, seat, options, lineup[seat], strategies[seat], random);
                if (kong != null && options.concealedKongs().contains(kong)) {
                    round.declareConcealedKong(seat, kong);
                } else if (kong != null) {
                    round.declareAddKong(seat, kong);
                } else if (strategies[seat] != null) {
                    round.discard(seat, strategies[seat].chooseDiscard(TableView.of(round, seat)));
                } else {
                    List<Tile> hand = round.player(seat).concealed();
                    round.discard(seat, hand.get(random.nextInt(hand.size())));
                }
            } else {
                Map<Integer, Claim> decisions = new HashMap<>();
                for (int seat = 0; seat < 4; seat++) {
                    List<Claim> options = round.claimOptions(seat);
                    Claim claim = options.isEmpty() ? null : chooseClaim(round, seat, options, lineup[seat], strategies[seat], random);
                    if (claim != null) {
                        decisions.put(seat, claim);
                    }
                }
                round.resolveClaims(decisions);
            }
        }
    }
}
