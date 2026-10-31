package ezmj.engine;

import ezmj.model.Tile;

/** 桌上公開發生過的一次打牌。牌河會因吃碰而少牌，這份紀錄不會。 */
public record DiscardEvent(int seat, Tile tile) {
}
