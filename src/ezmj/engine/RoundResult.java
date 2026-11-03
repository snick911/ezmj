package ezmj.engine;

import ezmj.model.Tile;
import ezmj.rules.ScoreResult;

/**
 * 一局的結果。流局時 winner 為 -1；自摸與八仙過海時 from 為 -1。
 * 八仙過海與七搶一時 winTile 是最後亮出或被搶的那張花牌，手牌不一定成牌。
 */
public record RoundResult(int winner, int from, Tile winTile, ScoreResult score) {

    /** 牌型台數到這裡以上就是大胡。 */
    public static final int BIG_WIN_TAI = 10;

    public static RoundResult drawnGame() {
        return new RoundResult(-1, -1, null, null);
    }

    public boolean isDrawnGame() {
        return winner < 0;
    }

    public boolean isSelfDraw() {
        return winner >= 0 && from < 0;
    }

    /** 牌型台數 10 台以上，胡或自摸都算，胡牌的大字換成大胡的樣子。不算莊家台，和結果視窗的「牌型合計」一樣。 */
    public boolean isBigWin() {
        return winner >= 0 && score.totalTai() >= BIG_WIN_TAI;
    }

    /** 靠花牌胡的，也就是八仙過海或七搶一。 */
    public boolean isFlowerWin() {
        return winTile != null && winTile.isFlower();
    }
}
