package ezmj.engine;

import java.util.List;

import ezmj.model.GameRecord;
import ezmj.model.Player;

/** 畫面要顯示整場牌局時讀的東西，只讀不改。單機是 GameSession 本身，連線則是服務器送來的結果。 */
public interface GameView {

    Player player(int seat);

    int basePay();

    int taiPay();

    /** 滑鼠停在名字上時補充的說明，沒有就是 null。 */
    String traits(int seat);

    /** 到目前為止這個座位的成績。 */
    GameRecord record(int seat, String date);

    /** 已經結算的局數，包含流局。 */
    int roundsPlayed();

    int drawnGames();

    /** 這場打完的每一局，先打的在前面。 */
    List<RoundRecord> history();
}
