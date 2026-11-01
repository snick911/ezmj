package ezmj.engine;

import ezmj.model.Player;
import ezmj.model.Tile;

/** 畫面要演一局牌時讀的東西，只讀不改。單機是 Round 本身，連線則是服務器送來的局面。 */
public interface RoundView {

    Player player(int seat);

    Round.Phase phase();

    int current();

    Tile drawn();

    Tile claimTile();

    int discarder();

    boolean isRobbing();

    int dealer();

    int roundWind();

    int streak();

    int seatWind(int seat);

    int liveTiles();

    RoundResult result();
}
