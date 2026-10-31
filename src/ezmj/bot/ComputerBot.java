package ezmj.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ezmj.engine.Claim;
import ezmj.engine.Round;
import ezmj.engine.TurnOptions;
import ezmj.model.BotLevel;
import ezmj.model.Tile;

/** 把策略接到引擎上。這是 bot 套件裡除了 TableView 之外唯一碰 Round 的地方，而且只用來執行決定。 */
public final class ComputerBot implements Bot {

    private final Strategy strategy;

    public ComputerBot(BotLevel level, int basePay, int taiPay, Random random) {
        this.strategy = Strategy.of(level, basePay, taiPay, random);
    }

    @Override
    public void playTurn(Round round, int seat, TurnOptions options) {
        if (options.canSelfWin()) {
            round.declareSelfWin(seat);
            return;
        }
        TableView view = TableView.of(round, seat);
        boolean canKong = !options.concealedKongs().isEmpty() || !options.addKongs().isEmpty();
        Tile kong = canKong ? strategy.chooseKong(view, options) : null;
        if (kong == null) {
            round.discard(seat, strategy.chooseDiscard(view));
        } else if (options.concealedKongs().contains(kong)) {
            round.declareConcealedKong(seat, kong);
        } else {
            round.declareAddKong(seat, kong);
        }
    }

    @Override
    public Claim chooseClaim(Round round, int seat, List<Claim> options) {
        List<Claim> melds = new ArrayList<>();
        for (Claim option : options) {
            if (option.type() == Claim.Type.WIN) {
                return option;
            }
            melds.add(option);
        }
        return melds.isEmpty() ? null : strategy.chooseClaim(TableView.of(round, seat), melds);
    }
}
