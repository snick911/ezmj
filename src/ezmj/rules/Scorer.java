package ezmj.rules;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import ezmj.model.Meld;
import ezmj.model.Suit;
import ezmj.model.Tile;
import ezmj.rules.HandAnalyzer.Decomposition;
import ezmj.rules.HandAnalyzer.TileSet;

/** 計算牌型台數。同一手牌有多種拆法時取台數最高者。 */
public final class Scorer {

    private Scorer() {
    }

    /** 集滿八張花牌立即胡。不看手牌，只算這一項；莊家台在結算時另外加。 */
    public static ScoreResult eightFlowers() {
        return new ScoreResult(List.of(new ScoreItem("八仙過海", 8)));
    }

    /** 自己七張花、別家一張花時立即胡。不看手牌，只算這一項；莊家台在結算時另外加。 */
    public static ScoreResult sevenRobOne() {
        return new ScoreResult(List.of(new ScoreItem("七搶一", 8)));
    }

    public static ScoreResult score(WinContext ctx) {
        ScoreResult best = null;
        for (Decomposition decomposition : HandAnalyzer.decompose(ctx.concealed())) {
            ScoreResult candidate = scoreDecomposition(ctx, decomposition);
            if (best == null || candidate.totalTai() > best.totalTai()) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("不是胡牌牌型: " + ctx.concealed());
        }
        return best;
    }

    private static ScoreResult scoreDecomposition(WinContext ctx, Decomposition dec) {
        List<ScoreItem> items = new ArrayList<>();
        Tile pair = dec.pair();

        List<Tile> triplets = new ArrayList<>();
        int sequences = 0;
        int concealedTriplets = 0;
        for (Meld meld : ctx.melds()) {
            if (meld.isSequence()) {
                sequences++;
            } else {
                triplets.add(meld.first());
                if (meld.isConcealed()) {
                    concealedTriplets++;
                }
            }
        }
        for (TileSet set : dec.sets()) {
            if (set.sequence()) {
                sequences++;
            } else {
                triplets.add(set.first());
                concealedTriplets++;
            }
        }
        if (!ctx.selfDraw() && completesTripletOnly(dec, ctx.winTile())) {
            concealedTriplets--;
        }

        boolean exposed = ctx.melds().stream().anyMatch(meld -> !meld.isConcealed());
        boolean singleWait = HandAnalyzer.waits(withoutWinTile(ctx), ctx.melds()).size() == 1;
        boolean allExposed = ctx.melds().size() == 5
                && ctx.melds().stream().noneMatch(Meld::isConcealed);

        if (ctx.heavenly()) {
            items.add(new ScoreItem("天胡", 24));
        }
        if (ctx.earthly()) {
            items.add(new ScoreItem("地胡", 16));
        }

        addWindPatterns(items, ctx, triplets, pair);
        addDragonPatterns(items, triplets, pair);
        addSuitPatterns(items, ctx);

        if (sequences == 0) {
            items.add(new ScoreItem("碰碰胡", 4));
        }
        if (concealedTriplets >= 5) {
            items.add(new ScoreItem("五暗刻", 8));
        } else if (concealedTriplets == 4) {
            items.add(new ScoreItem("四暗刻", 5));
        } else if (concealedTriplets == 3) {
            items.add(new ScoreItem("三暗刻", 2));
        }

        if (sequences == 5 && !pair.isHonor() && ctx.flowers().isEmpty()
                && !ctx.selfDraw() && !singleWait) {
            items.add(new ScoreItem("平胡", 2));
        }

        if (!exposed && ctx.selfDraw()) {
            items.add(new ScoreItem("門清一摸三", 3));
        } else if (!exposed) {
            items.add(new ScoreItem("門清", 1));
        } else if (ctx.selfDraw()) {
            items.add(new ScoreItem("自摸", 1));
        }
        if (allExposed && !ctx.selfDraw()) {
            items.add(new ScoreItem("全求人", 2));
        } else if (singleWait) {
            items.add(new ScoreItem("獨聽", 1));
        }

        if (ctx.lastTile()) {
            items.add(new ScoreItem(ctx.selfDraw() ? "海底撈月" : "河底撈魚", 1));
        }
        if (ctx.afterKong() && ctx.selfDraw()) {
            items.add(new ScoreItem("槓上開花", 1));
        }
        if (ctx.robbedKong()) {
            items.add(new ScoreItem("搶槓", 1));
        }
        addFlowerPatterns(items, ctx);
        return new ScoreResult(items);
    }

    /** 放槍的牌只能湊成刻子時，該刻子不算暗刻。 */
    private static boolean completesTripletOnly(Decomposition dec, Tile winTile) {
        boolean inTriplet = false;
        for (TileSet set : dec.sets()) {
            if (set.contains(winTile)) {
                if (set.sequence()) {
                    return false;
                }
                inTriplet = true;
            }
        }
        return inTriplet && !dec.pair().equals(winTile);
    }

    private static List<Tile> withoutWinTile(WinContext ctx) {
        List<Tile> hand = new ArrayList<>(ctx.concealed());
        hand.remove(ctx.winTile());
        return hand;
    }

    private static void addWindPatterns(List<ScoreItem> items, WinContext ctx,
                                        List<Tile> triplets, Tile pair) {
        long winds = triplets.stream().filter(t -> t.suit() == Suit.WIND).count();
        if (winds == 4) {
            items.add(new ScoreItem("大四喜", 16));
        } else if (winds == 3 && pair.suit() == Suit.WIND) {
            items.add(new ScoreItem("小四喜", 8));
        } else {
            if (triplets.contains(Tile.of(Suit.WIND, ctx.roundWind()))) {
                items.add(new ScoreItem("圈風 " + Tile.windName(ctx.roundWind()), 1));
            }
            if (triplets.contains(Tile.of(Suit.WIND, ctx.seatWind()))) {
                items.add(new ScoreItem("門風 " + Tile.windName(ctx.seatWind()), 1));
            }
        }
    }

    private static void addDragonPatterns(List<ScoreItem> items, List<Tile> triplets, Tile pair) {
        long dragons = triplets.stream().filter(t -> t.suit() == Suit.DRAGON).count();
        if (dragons == 3) {
            items.add(new ScoreItem("大三元", 8));
        } else if (dragons == 2 && pair.suit() == Suit.DRAGON) {
            items.add(new ScoreItem("小三元", 4));
        } else {
            for (Tile tile : triplets) {
                if (tile.suit() == Suit.DRAGON) {
                    items.add(new ScoreItem("三元牌 " + tile.label(), 1));
                }
            }
        }
    }

    private static void addSuitPatterns(List<ScoreItem> items, WinContext ctx) {
        Set<Suit> numberSuits = EnumSet.noneOf(Suit.class);
        boolean hasHonor = false;
        List<Tile> all = new ArrayList<>(ctx.concealed());
        for (Meld meld : ctx.melds()) {
            all.addAll(meld.tiles());
        }
        for (Tile tile : all) {
            if (tile.isHonor()) {
                hasHonor = true;
            } else {
                numberSuits.add(tile.suit());
            }
        }
        if (numberSuits.isEmpty()) {
            items.add(new ScoreItem("字一色", 16));
        } else if (numberSuits.size() == 1) {
            items.add(hasHonor ? new ScoreItem("混一色", 4) : new ScoreItem("清一色", 8));
        }
    }

    private static void addFlowerPatterns(List<ScoreItem> items, WinContext ctx) {
        int seasons = 0;
        int plants = 0;
        for (Tile flower : ctx.flowers()) {
            if (flower.rank() <= 4) {
                seasons++;
            } else {
                plants++;
            }
            if (flower.rank() == ctx.seatWind() || flower.rank() == ctx.seatWind() + 4) {
                items.add(new ScoreItem("正花 " + flower.label(), 1));
            }
        }
        if (seasons == 4) {
            items.add(new ScoreItem("花槓 春夏秋冬", 2));
        }
        if (plants == 4) {
            items.add(new ScoreItem("花槓 梅蘭竹菊", 2));
        }
    }
}
