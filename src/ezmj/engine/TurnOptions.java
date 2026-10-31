package ezmj.engine;

import java.util.List;

import ezmj.model.Tile;

/** 輪到自己時，除了打牌之外還能做的事。 */
public record TurnOptions(boolean canSelfWin, List<Tile> concealedKongs, List<Tile> addKongs) {

    public static final TurnOptions DISCARD_ONLY = new TurnOptions(false, List.of(), List.of());
}
