package ezmj.bot;

import java.util.List;

import ezmj.engine.Claim;
import ezmj.engine.TurnOptions;
import ezmj.model.Tile;

/**
 * 一般：會想自己還差幾步聽牌，但不去數張數。
 * 看得出哪張牌打了不會退步，吃碰也會想有沒有變快；同樣快時憑牌型留中張，
 * 不會算還有幾張能進，也不看牌河、不防守。
 */
final class NormalStrategy implements Strategy {

    @Override
    public Tile chooseDiscard(TableView view) {
        int[] counts = Efficiency.counts(view.hand());
        int sets = view.setsNeeded();
        Tile best = null;
        int bestShanten = Integer.MAX_VALUE;
        for (int i = 0; i < Tile.KINDS; i++) {
            if (counts[i] == 0) {
                continue;
            }
            Tile tile = Tile.fromIndex(i);
            counts[i]--;
            int shanten = Shanten.of(counts, sets);
            counts[i]++;
            if (shanten < bestShanten || (shanten == bestShanten
                    && Efficiency.usefulness(tile) < Efficiency.usefulness(best))) {
                bestShanten = shanten;
                best = tile;
            }
        }
        return best;
    }

    /** 槓完向聽沒有變差就槓，等於多摸一張。 */
    @Override
    public Tile chooseKong(TableView view, TurnOptions options) {
        int[] counts = Efficiency.counts(view.hand());
        int sets = view.setsNeeded();
        int now = Efficiency.fastest(Efficiency.discards(counts, sets, view.notInOwnTiles())).shanten();
        for (Tile tile : options.concealedKongs()) {
            counts[tile.index()] -= 4;
            boolean keepsPace = Shanten.of(counts, sets - 1) <= now;
            counts[tile.index()] += 4;
            if (keepsPace) {
                return tile;
            }
        }
        for (Tile tile : options.addKongs()) {
            counts[tile.index()]--;
            boolean keepsPace = Shanten.of(counts, sets) <= now;
            counts[tile.index()]++;
            if (keepsPace) {
                return tile;
            }
        }
        return null;
    }

    /** 宣告完向聽有變少才要；有好幾種時挑最快的。 */
    @Override
    public Claim chooseClaim(TableView view, List<Claim> options) {
        return fastestClaim(view, options, view.notInOwnTiles());
    }

    static Claim fastestClaim(TableView view, List<Claim> options, int[] available) {
        int[] counts = Efficiency.counts(view.hand());
        int now = Shanten.of(counts, view.setsNeeded());
        int sets = view.setsNeeded() - 1;
        Claim best = null;
        Efficiency.Choice bestChoice = null;
        for (Claim claim : options) {
            for (Tile tile : claim.fromHand()) {
                counts[tile.index()]--;
            }
            Efficiency.Choice choice;
            if (claim.type() == Claim.Type.KONG) {
                int after = Shanten.of(counts, sets);
                choice = after <= now
                        ? new Efficiency.Choice(claim.tile(), after, Efficiency.ukeire(counts, sets, after, available))
                        : null;
            } else {
                Efficiency.Choice fastest = Efficiency.fastest(Efficiency.discards(counts, sets, available));
                choice = fastest.shanten() < now ? fastest : null;
            }
            for (Tile tile : claim.fromHand()) {
                counts[tile.index()]++;
            }
            if (choice != null && (bestChoice == null || Efficiency.FASTEST.compare(choice, bestChoice) < 0)) {
                best = claim;
                bestChoice = choice;
            }
        }
        return best;
    }
}
