package ezmj.engine;

import ezmj.model.Tile;
import ezmj.rules.ScoreResult;

/**
 * 一局的結果。流局時 winner 為 -1；自摸時 from 為 -1。
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
}
