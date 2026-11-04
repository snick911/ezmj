package ezmj.bot;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import ezmj.bot.TableView.Opponent;
import ezmj.model.Meld;
import ezmj.model.Suit;
import ezmj.model.Tile;

/**
 * 職業等級對一家對手的判讀，全部由桌面公開資訊推得：
 * 報聽了就一定聽牌，還多 1 台；不然看亮了幾組、打了幾巡、最近打什麼 → 多可能聽牌；牌河與剩餘張數 → 哪些牌型還湊得出來；
 * 面子和牌河的花色 → 是不是在做一色；攤出來的牌 → 胡了大概幾台。
 */
final class OpponentRead {

    /** 依面子數與巡數分段的聽牌率，來自大量自我對戰的統計，對局時只查表，不會看任何人的手牌。 */
    private static final double[][] TENPAI_BY_MELDS_AND_TURN = {
        {0.00, 0.04, 0.19, 0.28, 0.43},
        {0.01, 0.09, 0.30, 0.45, 0.51},
        {0.04, 0.19, 0.46, 0.70, 0.85},
        {0.25, 0.40, 0.66, 0.83, 0.85},
        {0.60, 0.87, 0.95, 0.97, 0.97},
        {1.00, 1.00, 1.00, 1.00, 1.00},
    };
    private static final double WAIT_SCALE = 0.02;
    /** 字牌大多早早就打掉，實際上很少有人聽字牌。 */
    private static final double HONOR_WEIGHT = 0.15;

    private final TableView view;
    private final Opponent opponent;
    private final int[] unseen;
    private final double tenpai;
    private final Suit flushSuit;
    private final double flushChance;
    private final double tai;

    OpponentRead(TableView view, Opponent opponent, int[] unseen) {
        this.view = view;
        this.opponent = opponent;
        this.unseen = unseen;
        this.flushSuit = leaningSuit();
        this.flushChance = flushSuit == null ? 0 : flushChance(flushSuit);
        this.tenpai = readTenpai();
        this.tai = readTai();
    }

    double tenpai() {
        return tenpai;
    }

    /** 打這張給這家胡的機率。 */
    double dealIn(Tile tile) {
        return tenpai * waitChance(tile);
    }

    /** 放槍給這家要付多少錢。 */
    double payment(int basePay, int taiPay) {
        boolean dealerInvolved = view.dealer() == view.seat() || view.dealer() == opponent.seat();
        double total = tai + (dealerInvolved ? 1 + 2 * view.streak() : 0);
        return basePay + total * taiPay;
    }

    /** 報聽的人一定聽牌了。 */
    private double readTenpai() {
        if (opponent.listening()) {
            return 1;
        }
        int turns = view.discardCount(opponent);
        double base = TENPAI_BY_MELDS_AND_TURN[Math.min(opponent.meldCount(), 5)][Math.min(turns / 4, 4)];
        // 一開始都打字牌和邊張，開始捨中張通常代表手牌已經成形
        List<Tile> river = opponent.discards();
        int recentMiddle = 0;
        for (int i = Math.max(0, river.size() - 3); i < river.size(); i++) {
            Tile tile = river.get(i);
            if (tile.isNumber() && tile.rank() >= 3 && tile.rank() <= 7) {
                recentMiddle++;
            }
        }
        return Math.min(1, base * (1 + 0.1 * recentMiddle));
    }

    /** 假設對方聽牌時，他聽這張的機率：把還湊得出來的牌型依可能性加總。 */
    double waitChance(Tile tile) {
        if (view.passedBy(opponent, tile)) {
            return 0.005;
        }
        double weight = shapeWeight(tile) * suitFactor(tile);
        double chance = 1 - Math.exp(-WAIT_SCALE * weight);
        return view.discardedBy(opponent, tile) ? chance * 0.1 : chance;
    }

    private double shapeWeight(Tile tile) {
        double weight = 0;
        int copies = unseen[tile.index()];
        // 對碰與單騎：要他手上也有這張
        weight += copies * (copies - 1) * 0.25;
        weight += copies * 0.35;
        if (!tile.isNumber()) {
            return weight * HONOR_WEIGHT;
        }
        int rank = tile.rank();
        // 兩面與邊張；打過另一端的筋就不會是兩面
        if (rank >= 3) {
            weight += pairOf(tile.suit(), rank - 2, rank - 1) * sujiFactor(tile.suit(), rank - 3);
        }
        if (rank <= 7) {
            weight += pairOf(tile.suit(), rank + 1, rank + 2) * sujiFactor(tile.suit(), rank + 3);
        }
        // 嵌張
        if (rank >= 2 && rank <= 8) {
            weight += pairOf(tile.suit(), rank - 1, rank + 1) * 0.6;
        }
        return weight;
    }

    private double pairOf(Suit suit, int rankA, int rankB) {
        double a = unseen[Tile.of(suit, rankA).index()] * neighborFactor(suit, rankA);
        double b = unseen[Tile.of(suit, rankB).index()] * neighborFactor(suit, rankB);
        return a * b / 4;
    }

    /** 他打過的牌就不太會留在手上組搭子。 */
    private double neighborFactor(Suit suit, int rank) {
        return view.discardedBy(opponent, Tile.of(suit, rank)) ? 0.6 : 1;
    }

    private double sujiFactor(Suit suit, int rank) {
        if (rank < 1 || rank > 9) {
            return 1;
        }
        return view.discardedBy(opponent, Tile.of(suit, rank)) ? 0.25 : 1;
    }

    private double suitFactor(Tile tile) {
        if (flushSuit == null) {
            return 1;
        }
        if (tile.suit() == flushSuit || tile.isHonor()) {
            return 1 + flushChance;
        }
        return 1 - 0.8 * flushChance;
    }

    /** 亮出來的面子只有一種數牌，就可能在做一色。 */
    private Suit leaningSuit() {
        Suit suit = null;
        for (Meld meld : opponent.openMelds()) {
            Suit meldSuit = meld.first().suit();
            if (meld.first().isHonor()) {
                continue;
            }
            if (suit != null && suit != meldSuit) {
                return null;
            }
            suit = meldSuit;
        }
        return suit;
    }

    private double flushChance(Suit suit) {
        Map<Suit, Integer> river = new EnumMap<>(Suit.class);
        int total = 0;
        for (Tile tile : opponent.discards()) {
            river.merge(tile.suit(), 1, Integer::sum);
            total++;
        }
        if (total < 4) {
            return 0;
        }
        double share = river.getOrDefault(suit, 0) / (double) total;
        double melded = opponent.openMelds().size();
        return Math.max(0, Math.min(1, (0.25 - share) * 3 + 0.15 * melded));
    }

    private double readTai() {
        double total = 1.5;
        for (Meld meld : opponent.openMelds()) {
            Tile tile = meld.first();
            if (tile.suit() == Suit.DRAGON || (tile.suit() == Suit.WIND
                    && (tile.rank() == view.roundWind() || tile.rank() == windOf(opponent.seat())))) {
                total += 1;
            }
        }
        for (Tile flower : opponent.flowers()) {
            int wind = windOf(opponent.seat());
            if (flower.rank() == wind || flower.rank() == wind + 4) {
                total += 1;
            }
        }
        if (opponent.meldCount() == 0) {
            total += 1;
        }
        if (opponent.listening()) {
            total += 1;
        }
        if (opponent.openMelds().size() >= 3 && opponent.openMelds().stream().noneMatch(Meld::isSequence)) {
            total += 2;
        }
        return total + flushChance * 4;
    }

    private int windOf(int seat) {
        return (seat - view.dealer() + 4) % 4 + 1;
    }
}
