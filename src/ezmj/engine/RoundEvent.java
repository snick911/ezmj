package ezmj.engine;

import ezmj.model.Tile;

/** 一局裡值得讓畫面與音效反應的事件。seat 是動作的人；from 是牌的來源座位，沒有來源時為 -1。 */
public record RoundEvent(Type type, int seat, Tile tile, int from) {

    public enum Type { DISCARD, CHOW, PONG, KONG, CONCEALED_KONG, ADD_KONG, WIN, SELF_WIN, DRAWN_GAME, DRAW, FLOWER }
}
