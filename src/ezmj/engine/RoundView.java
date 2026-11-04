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

    /**
     * 局風：這一圈的第幾局，編碼和圈風一樣，1 到 4 是東、南、西、北局。依起莊算，莊家是起莊那位時是東局，
     * 下一家是南局，連莊時不變。連線時舊服務器沒有送就是 0，畫面只顯示圈風。
     */
    int handWind();

    int streak();

    int seatWind(int seat);

    /** 這個座位報聽了。報聽是公開的，全桌都看得到。 */
    boolean isListening(int seat);

    int liveTiles();

    RoundResult result();
}
