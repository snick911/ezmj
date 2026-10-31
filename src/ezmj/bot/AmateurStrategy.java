package ezmj.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ezmj.bot.TableView.Opponent;
import ezmj.engine.Claim;
import ezmj.engine.TurnOptions;
import ezmj.model.Suit;
import ezmj.model.Tile;

/**
 * 業餘：開始抬頭看桌面。
 * 快聽牌時會算進張，而且用全桌扣完後真正剩下的張數來算；看到別家亮出很多面子就提高警覺，
 * 進張差不多時挑現物、筋、見過的字牌，牌牆剩不多又差很遠時就保命。
 * 判斷靠固定規則，不會估對手到底聽什麼，也不會算這手值幾台。
 */
final class AmateurStrategy implements Strategy {

    private static final double NEARLY_AS_FAST = 0.8;
    private static final int COUNTS_TILES_WITHIN = 1;
    private static final int MID_WALL = 48;
    private static final int LATE_WALL = 32;

    @Override
    public Tile chooseDiscard(TableView view) {
        int[] unseen = view.unseen();
        int[] counts = Efficiency.counts(view.hand());
        List<Efficiency.Choice> choices = judged(Efficiency.discards(counts, view.setsNeeded(), unseen));
        Efficiency.Choice fastest = Efficiency.fastest(choices);
        List<Opponent> threats = threats(view);

        if (threats.isEmpty()) {
            Comparator<Efficiency.Choice> keepValue = Comparator.comparingInt(choice -> pairValue(view, counts, choice.tile()));
            return choices.stream()
                    .filter(choice -> choice.shanten() == fastest.shanten() && choice.ukeire() == fastest.ukeire())
                    .min(keepValue.thenComparing(Efficiency.FASTEST)).orElseThrow().tile();
        }
        Comparator<Efficiency.Choice> safest = Comparator.<Efficiency.Choice>comparingInt(
                choice -> -safety(view, threats, choice.tile(), unseen)).thenComparing(Efficiency.FASTEST);
        if (tooFarToPush(view, fastest.shanten())) {
            return choices.stream().min(safest).orElseThrow().tile();
        }
        return closeEnough(choices, fastest).stream().min(safest).orElseThrow().tile();
    }

    /** 牌牆剩得不多而手牌還差很遠，胡的機會已經不值得冒險。 */
    private static boolean tooFarToPush(TableView view, int shanten) {
        return (shanten >= 3 && view.liveTiles() < MID_WALL) || (shanten == 2 && view.liveTiles() < LATE_WALL);
    }

    /** 離聽牌還遠時不會去數張數，憑牌型判斷；快聽了才認真算。 */
    private static List<Efficiency.Choice> judged(List<Efficiency.Choice> choices) {
        if (Efficiency.fastest(choices).shanten() <= COUNTS_TILES_WITHIN) {
            return choices;
        }
        List<Efficiency.Choice> judged = new ArrayList<>();
        for (Efficiency.Choice choice : choices) {
            judged.add(new Efficiency.Choice(choice.tile(), choice.shanten(), 0));
        }
        return judged;
    }

    private static List<Efficiency.Choice> closeEnough(List<Efficiency.Choice> choices, Efficiency.Choice fastest) {
        List<Efficiency.Choice> close = new ArrayList<>();
        for (Efficiency.Choice choice : choices) {
            if (choice.shanten() == fastest.shanten() && choice.ukeire() >= fastest.ukeire() * NEARLY_AS_FAST) {
                close.add(choice);
            }
        }
        return close;
    }

    /** 亮三組以上、亮兩組且打了八巡、或已經打了十二巡的人，就當作快聽了。 */
    private static List<Opponent> threats(TableView view) {
        List<Opponent> threats = new ArrayList<>();
        for (Opponent opponent : view.opponents()) {
            int turns = view.discardCount(opponent);
            if (opponent.meldCount() >= 3 || (opponent.meldCount() == 2 && turns >= 8) || turns >= 12) {
                threats.add(opponent);
            }
        }
        return threats;
    }

    /** 對所有威脅中最不安全的那家來看，這張牌有多安全；3 現物、2 見過的字牌、1 筋或么九、0 其他。 */
    private static int safety(TableView view, List<Opponent> threats, Tile tile, int[] unseen) {
        int lowest = 3;
        for (Opponent threat : threats) {
            lowest = Math.min(lowest, safetyAgainst(view, threat, tile, unseen));
        }
        return lowest;
    }

    private static int safetyAgainst(TableView view, Opponent threat, Tile tile, int[] unseen) {
        if (view.discardedBy(threat, tile) || view.passedBy(threat, tile)) {
            return 3;
        }
        if (tile.isHonor()) {
            return unseen[tile.index()] <= 2 ? 2 : 0;
        }
        if (tile.rank() == 1 || tile.rank() == 9 || isSuji(view, threat, tile)) {
            return 1;
        }
        return 0;
    }

    private static boolean isSuji(TableView view, Opponent threat, Tile tile) {
        return (tile.rank() > 3 && view.discardedBy(threat, Tile.of(tile.suit(), tile.rank() - 3)))
                || (tile.rank() < 7 && view.discardedBy(threat, Tile.of(tile.suit(), tile.rank() + 3)));
    }

    /** 自風、圈風、三元牌的對子會加台，一樣快時不拆。 */
    private static int pairValue(TableView view, int[] counts, Tile tile) {
        if (counts[tile.index()] < 2 || !tile.isHonor()) {
            return 0;
        }
        boolean scoring = tile.suit() == Suit.DRAGON
                || tile.rank() == view.seatWind() || tile.rank() == view.roundWind();
        return scoring ? 1 : 0;
    }

    @Override
    public Tile chooseKong(TableView view, TurnOptions options) {
        return new NormalStrategy().chooseKong(view, options);
    }

    /** 宣告完還是差太遠時不動，留著手牌好防守。 */
    @Override
    public Claim chooseClaim(TableView view, List<Claim> options) {
        int[] unseen = view.unseen();
        Claim best = NormalStrategy.fastestClaim(view, options, unseen);
        if (best == null || best.type() == Claim.Type.KONG || threats(view).isEmpty()) {
            return best;
        }
        int[] counts = Efficiency.counts(view.hand());
        for (Tile tile : best.fromHand()) {
            counts[tile.index()]--;
        }
        int after = Efficiency.fastest(Efficiency.discards(counts, view.setsNeeded() - 1, unseen)).shanten();
        return tooFarToPush(view, after) ? null : best;
    }
}
