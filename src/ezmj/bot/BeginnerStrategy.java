package ezmj.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ezmj.engine.Claim;
import ezmj.engine.TurnOptions;
import ezmj.model.Tile;

/**
 * 初學者：眼裡只有自己的手牌。
 * 憑感覺打「看起來連不起來」的牌，不算向聽也不算張數，能吃就吃、能碰就碰，別人打什麼都不管。
 */
final class BeginnerStrategy implements Strategy {

    private final Random random;

    BeginnerStrategy(Random random) {
        this.random = random;
    }

    @Override
    public Tile chooseDiscard(TableView view) {
        int[] counts = Efficiency.counts(view.hand());
        List<Tile> loneliest = new ArrayList<>();
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < Tile.KINDS; i++) {
            if (counts[i] == 0) {
                continue;
            }
            Tile tile = Tile.fromIndex(i);
            int connected = connections(tile, counts);
            if (connected < lowest) {
                lowest = connected;
                loneliest.clear();
            }
            if (connected == lowest) {
                loneliest.add(tile);
            }
        }
        return loneliest.get(random.nextInt(loneliest.size()));
    }

    /** 一樣的牌算 4 分、隔壁的牌算 2 分、隔一張的牌算 1 分。 */
    private static int connections(Tile tile, int[] counts) {
        int connected = counts[tile.index()] * 4;
        if (tile.isNumber()) {
            for (int offset : new int[] {-2, -1, 1, 2}) {
                int rank = tile.rank() + offset;
                if (rank >= 1 && rank <= 9 && counts[Tile.of(tile.suit(), rank).index()] > 0) {
                    connected += Math.abs(offset) == 1 ? 2 : 1;
                }
            }
        }
        return connected;
    }

    @Override
    public Tile chooseKong(TableView view, TurnOptions options) {
        if (!options.concealedKongs().isEmpty()) {
            return options.concealedKongs().get(0);
        }
        return options.addKongs().isEmpty() ? null : options.addKongs().get(0);
    }

    @Override
    public Claim chooseClaim(TableView view, List<Claim> options) {
        return options.get(0);
    }

    /** 初學者看不出自己已經聽牌，不報聽。 */
    @Override
    public boolean declareListening(TableView view, List<Tile> waits) {
        return false;
    }
}
