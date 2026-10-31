package ezmj.model;

import java.util.ArrayList;
import java.util.List;

/** 一組已宣告的面子（吃、碰、明槓、暗槓）。 */
public record Meld(Type type, List<Tile> tiles) {

    public enum Type { CHOW, PONG, OPEN_KONG, CONCEALED_KONG }

    public Meld {
        tiles = List.copyOf(tiles);
    }

    public Tile first() {
        return tiles.get(0);
    }

    public boolean isSequence() {
        return type == Type.CHOW;
    }

    public boolean isConcealed() {
        return type == Type.CONCEALED_KONG;
    }

    /** 把碰加成明槓。 */
    public Meld upgradedToKong() {
        List<Tile> kong = new ArrayList<>(tiles);
        kong.add(first());
        return new Meld(Type.OPEN_KONG, kong);
    }
}
