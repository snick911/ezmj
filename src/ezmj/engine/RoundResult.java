package ezmj.engine;

import ezmj.model.Tile;
import ezmj.rules.ScoreResult;

/**
 * 一局的結果。流局時 winner 為 -1；自摸與八仙過海時 from 為 -1。
 * 八仙過海與七搶一時 winTile 是最後亮出或被搶的那張花牌，手牌不一定成牌。
 */
public record RoundResult(int winner, int from, Tile winTile, ScoreResult score) {

    public static RoundResult drawnGame() {
        return new RoundResult(-1, -1, null, null);
    }

    public boolean isDrawnGame() {
        return winner < 0;
    }

    public boolean isSelfDraw() {
        return winner >= 0 && from < 0;
    }

    /** 靠花牌胡的，也就是八仙過海或七搶一。 */
    public boolean isFlowerWin() {
        return winTile != null && winTile.isFlower();
    }
}
