package ezmj.rules;

import java.util.List;

import ezmj.model.Meld;
import ezmj.model.Tile;

/**
 * 計台需要的胡牌情境。concealed 已包含胡的那張牌。
 * roundWind 與 seatWind 為 1-4（東南西北）。declaredListening 是胡的人之前報聽了。
 */
public record WinContext(
        List<Tile> concealed,
        List<Meld> melds,
        List<Tile> flowers,
        Tile winTile,
        boolean selfDraw,
        int roundWind,
        int seatWind,
        boolean robbedKong,
        boolean afterKong,
        boolean lastTile,
        boolean heavenly,
        boolean earthly,
        boolean declaredListening) {
}
