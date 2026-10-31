package ezmj.engine;

import java.util.List;

import ezmj.model.Tile;

/** 對別人打出的牌可以做的宣告。fromHand 是吃、碰、槓要從手牌拿出的牌。 */
public record Claim(Type type, Tile tile, List<Tile> fromHand) {

    /** 宣告順序同時也是優先序：胡 > 槓、碰 > 吃。 */
    public enum Type { WIN, KONG, PONG, CHOW }

    public Claim {
        fromHand = List.copyOf(fromHand);
    }

    public String verb() {
        switch (type) {
            case WIN:  return "胡";
            case KONG: return "槓";
            case PONG: return "碰";
            default:   return "吃";
        }
    }

    /** 按鈕文字；吃牌可能有多種組合，所以要標出用哪兩張。 */
    public String label() {
        return type == Type.CHOW
                ? verb() + " " + fromHand.get(0).label() + fromHand.get(1).label()
                : verb();
    }
}
