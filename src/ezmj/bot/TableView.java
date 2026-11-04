package ezmj.bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ezmj.engine.DiscardEvent;
import ezmj.engine.Round;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;

/**
 * 一個座位在牌桌上合法看得到的全部資訊。策略只拿得到這份資料，碰不到 Round，
 * 所以別家手牌、別家暗槓的內容與牌牆順序在結構上就讀不到，不靠自律。
 */
public record TableView(int seat, int dealer, int streak, int roundWind, int seatWind, int liveTiles,
                        List<Tile> hand, List<Meld> melds, List<Tile> flowers, List<Tile> discards,
                        List<Opponent> opponents, List<DiscardEvent> history,
                        Tile claimTile, int discarder, boolean robbing, int chips) {

    /** 別家攤在桌上的部分。暗槓只知道有幾組，不知道是什麼牌。籌碼與有沒有報聽大家都看得到。 */
    public record Opponent(int seat, List<Meld> openMelds, int concealedKongs,
                           List<Tile> flowers, List<Tile> discards, int chips, boolean listening) {

        public int meldCount() {
            return openMelds.size() + concealedKongs;
        }
    }

    public static TableView of(Round round, int seat) {
        Player me = round.player(seat);
        List<Opponent> opponents = new ArrayList<>();
        for (int offset = 1; offset < 4; offset++) {
            Player other = round.player((seat + offset) % 4);
            List<Meld> open = new ArrayList<>();
            int concealedKongs = 0;
            for (Meld meld : other.melds()) {
                if (meld.isConcealed()) {
                    concealedKongs++;
                } else {
                    open.add(meld);
                }
            }
            opponents.add(new Opponent(other.seat(), List.copyOf(open), concealedKongs,
                    List.copyOf(other.flowers()), List.copyOf(other.discards()), other.chips(),
                    round.isListening(other.seat())));
        }
        boolean claiming = round.phase() == Round.Phase.CLAIMS;
        return new TableView(seat, round.dealer(), round.streak(), round.roundWind(), round.seatWind(seat),
                round.wall().liveCount(), List.copyOf(me.concealed()), List.copyOf(me.melds()),
                List.copyOf(me.flowers()), List.copyOf(me.discards()), List.copyOf(opponents),
                List.copyOf(round.discardHistory()), claiming ? round.claimTile() : null,
                claiming ? round.discarder() : -1, claiming && round.isRobbing(), me.chips());
    }

    /** 還要湊幾組面子才能胡。 */
    public int setsNeeded() {
        return 5 - melds.size();
    }

    /** 只看自己的牌時，每種牌最多還可能有幾張。 */
    public int[] notInOwnTiles() {
        int[] left = fours();
        subtract(left, hand);
        for (Meld meld : melds) {
            subtract(left, meld.tiles());
        }
        return left;
    }

    /** 扣掉全桌看得到的牌之後，每種牌還剩幾張（在牌牆或別家手上）。 */
    public int[] unseen() {
        int[] left = notInOwnTiles();
        subtract(left, discards);
        for (Opponent opponent : opponents) {
            subtract(left, opponent.discards());
            for (Meld meld : opponent.openMelds()) {
                subtract(left, meld.tiles());
            }
        }
        if (robbing) {
            left[claimTile.index()]--;
        }
        return left;
    }

    /** 這家打過這張，包含已經被吃碰走的。 */
    public boolean discardedBy(Opponent opponent, Tile tile) {
        return history.contains(new DiscardEvent(opponent.seat(), tile));
    }

    /** 這家上次打牌之後有人打過這張而他沒胡，代表他現在不是聽這張。 */
    public boolean passedBy(Opponent opponent, Tile tile) {
        int settled = history.size() - (claimTile != null && !robbing ? 1 : 0);
        for (int i = settled - 1; i >= 0; i--) {
            DiscardEvent event = history.get(i);
            if (event.seat() == opponent.seat()) {
                return false;
            }
            if (event.tile().equals(tile)) {
                return true;
            }
        }
        return false;
    }

    /** 這家總共打過幾張。 */
    public int discardCount(Opponent opponent) {
        int count = 0;
        for (DiscardEvent event : history) {
            if (event.seat() == opponent.seat()) {
                count++;
            }
        }
        return count;
    }

    private static int[] fours() {
        int[] counts = new int[Tile.KINDS];
        Arrays.fill(counts, 4);
        return counts;
    }

    private static void subtract(int[] counts, List<Tile> tiles) {
        for (Tile tile : tiles) {
            counts[tile.index()]--;
        }
    }
}
