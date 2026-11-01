package ezmj.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 玩家在牌桌上的狀態。座位 0 是真人，依 0→1→2→3 逆時針行牌。 */
public final class Player {

    private final String name;
    private final int seat;
    private final boolean human;
    private final Gender gender;
    private int chips;

    private final List<Tile> concealed = new ArrayList<>();
    private final List<Meld> melds = new ArrayList<>();
    private final List<Tile> flowers = new ArrayList<>();
    private final List<Tile> discards = new ArrayList<>();

    public Player(String name, int seat, boolean human, Gender gender, int chips) {
        this.name = name;
        this.seat = seat;
        this.human = human;
        this.gender = gender;
        this.chips = chips;
    }

    public void resetForRound() {
        concealed.clear();
        melds.clear();
        flowers.clear();
        discards.clear();
    }

    public void addToHand(Tile tile) {
        concealed.add(tile);
        Collections.sort(concealed);
    }

    public void removeFromHand(Tile tile) {
        if (!concealed.remove(tile)) {
            throw new IllegalArgumentException(name + " 手上沒有 " + tile);
        }
    }

    public int countInHand(Tile tile) {
        return Collections.frequency(concealed, tile);
    }

    public void addMeld(Meld meld) {
        melds.add(meld);
    }

    public void upgradePongToKong(Tile tile) {
        for (int i = 0; i < melds.size(); i++) {
            Meld meld = melds.get(i);
            if (meld.type() == Meld.Type.PONG && meld.first().equals(tile)) {
                melds.set(i, meld.upgradedToKong());
                return;
            }
        }
        throw new IllegalArgumentException(name + " 沒有碰過 " + tile);
    }

    public void addFlower(Tile tile) {
        flowers.add(tile);
    }

    public void removeFlower(Tile tile) {
        flowers.remove(tile);
    }

    public void addDiscard(Tile tile) {
        discards.add(tile);
    }

    public void removeLastDiscard() {
        discards.remove(discards.size() - 1);
    }

    public void addChips(int delta) {
        chips += delta;
    }

    public String name() {
        return name;
    }

    public int seat() {
        return seat;
    }

    public boolean isHuman() {
        return human;
    }

    public Gender gender() {
        return gender;
    }

    public int chips() {
        return chips;
    }

    public List<Tile> concealed() {
        return Collections.unmodifiableList(concealed);
    }

    public List<Meld> melds() {
        return Collections.unmodifiableList(melds);
    }

    public List<Tile> flowers() {
        return Collections.unmodifiableList(flowers);
    }

    public List<Tile> discards() {
        return Collections.unmodifiableList(discards);
    }
}
