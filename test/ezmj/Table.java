package ezmj;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import ezmj.engine.Round;
import ezmj.model.Gender;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Suit;
import ezmj.model.Tile;
import ezmj.model.Wall;

/**
 * 依劇本排好牌牆開局。沒寫滿 16 張的手牌會自動補上非萬子的牌，
 * 所以只要宣告相關的牌都用萬子，就不會被補的牌干擾。
 * 花牌排在留牌區，除非劇本指定，否則不會被摸到。
 */
final class Table {

    private final int dealer;
    private final String[] hands = {"", "", "", ""};
    private String draws = "";
    private String backs = "";
    private String lastLive = "";

    private Table(int dealer) {
        this.dealer = dealer;
    }

    static Table dealer(int dealer) {
        return new Table(dealer);
    }

    Table hand(int seat, String tiles) {
        hands[seat] = tiles;
        return this;
    }

    /** 依序從牌頭摸到的牌，第一張是莊家的第 17 張。 */
    Table draws(String tiles) {
        draws = tiles;
        return this;
    }

    /** 依序從牌尾補到的牌。 */
    Table backs(String tiles) {
        backs = tiles;
        return this;
    }

    /** 牌牆最後一張可摸的牌。 */
    Table lastLive(String tiles) {
        lastLive = tiles;
        return this;
    }

    Round start() {
        List<Tile> pool = new ArrayList<>(Tile.fullSet());
        List<List<Tile>> seats = new ArrayList<>();
        for (String spec : hands) {
            seats.add(take(pool, T.hand(spec)));
        }
        List<Tile> front = take(pool, T.hand(draws));
        List<Tile> back = take(pool, T.hand(backs));
        List<Tile> last = take(pool, T.hand(lastLive));

        Collections.shuffle(pool, new Random(99));
        List<Tile> fill = pool.stream().filter(t -> !t.isFlower() && t.suit() != Suit.WAN).collect(Collectors.toList());
        for (List<Tile> seat : seats) {
            while (seat.size() < 16) {
                Tile tile = fill.remove(fill.size() - 1);
                pool.remove(tile);
                seat.add(tile);
            }
        }

        List<Tile> order = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            for (int offset = 0; offset < 4; offset++) {
                order.add(seats.get((dealer + offset) % 4).get(i));
            }
        }
        order.addAll(front);
        order.addAll(pool.stream().filter(t -> !t.isFlower()).collect(Collectors.toList()));
        order.addAll(pool.stream().filter(Tile::isFlower).collect(Collectors.toList()));
        Collections.reverse(back);
        order.addAll(back);
        if (!last.isEmpty()) {
            order.add(144 - Wall.RESERVED - 1, last.get(0));
        }

        Player[] players = new Player[4];
        for (int seat = 0; seat < 4; seat++) {
            players[seat] = new Player("P" + seat, seat, seat == 0, Gender.FEMALE, 100_000);
        }
        Round round = new Round(players, new Wall(order), dealer, 1, 1, 0, message -> { });
        round.start();
        return round;
    }

    private static List<Tile> take(List<Tile> pool, List<Tile> wanted) {
        for (Tile tile : wanted) {
            if (!pool.remove(tile)) {
                throw new IllegalArgumentException("劇本用了超過張數上限的牌: " + tile);
            }
        }
        return new ArrayList<>(wanted);
    }

    /** 全場張數，加槓等待搶槓時那張牌算在桌上。 */
    static int totalTiles(Round round) {
        int total = round.wall().size();
        for (int seat = 0; seat < 4; seat++) {
            Player player = round.player(seat);
            total += player.concealed().size() + player.flowers().size() + player.discards().size();
            for (Meld meld : player.melds()) {
                total += meld.tiles().size();
            }
        }
        return total + (round.isRobbing() ? 1 : 0);
    }

    /** 打出手上剛摸的牌並讓所有人放棄宣告。 */
    static void discardDrawnAndPass(Round round) {
        round.discard(round.current(), round.drawn());
        round.resolveClaims(Map.of());
    }
}
