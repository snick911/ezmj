package ezmj.bot;

import java.util.List;

import ezmj.engine.Claim;
import ezmj.engine.Round;
import ezmj.engine.TurnOptions;
import ezmj.model.Player;
import ezmj.model.Tile;

/** 佔位用的電腦玩家，沒有策略：能胡就胡，其餘一律不宣告，並打出剛摸到的牌。 */
public final class PlaceholderBot implements Bot {

    @Override
    public void playTurn(Round round, int seat, TurnOptions options) {
        if (options.canSelfWin()) {
            round.declareSelfWin(seat);
            return;
        }
        Player player = round.player(seat);
        Tile drawn = round.drawn();
        round.discard(seat, drawn != null ? drawn : player.concealed().get(player.concealed().size() - 1));
    }

    @Override
    public Claim chooseClaim(Round round, int seat, List<Claim> options) {
        return options.stream().filter(c -> c.type() == Claim.Type.WIN).findFirst().orElse(null);
    }
}
