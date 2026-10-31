package ezmj.rules;

import java.util.List;

/** 一手胡牌的牌型台數（不含莊家與連莊，那部分要看付款對象）。 */
public record ScoreResult(List<ScoreItem> items) {

    public ScoreResult {
        items = List.copyOf(items);
    }

    public int totalTai() {
        return items.stream().mapToInt(ScoreItem::tai).sum();
    }

    public boolean has(String name) {
        return items.stream().anyMatch(item -> item.name().equals(name));
    }
}
