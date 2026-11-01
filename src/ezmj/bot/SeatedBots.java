package ezmj.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import ezmj.engine.Claim;
import ezmj.engine.Round;
import ezmj.engine.TurnOptions;
import ezmj.model.BotLevel;
import ezmj.model.Personality;

/** 三家電腦各用自己的等級與個性。兩個清單都依序是座位 1、2、3，真人坐在座位 0，不會用到這裡。 */
public final class SeatedBots implements Bot {

    private final List<Bot> bots = new ArrayList<>();

    public SeatedBots(List<BotLevel> levels, int basePay, int taiPay, Random random) {
        this(levels, List.of(Personality.BALANCED, Personality.BALANCED, Personality.BALANCED), basePay, taiPay, random);
    }

    public SeatedBots(List<BotLevel> levels, List<Personality> personalities, int basePay, int taiPay, Random random) {
        for (int i = 0; i < levels.size(); i++) {
            bots.add(new ComputerBot(levels.get(i), personalities.get(i), basePay, taiPay, random));
        }
    }

    @Override
    public void playTurn(Round round, int seat, TurnOptions options) {
        bots.get(seat - 1).playTurn(round, seat, options);
    }

    @Override
    public Claim chooseClaim(Round round, int seat, List<Claim> options) {
        return bots.get(seat - 1).chooseClaim(round, seat, options);
    }
}
