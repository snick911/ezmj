package ezmj.bot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ezmj.bot.Efficiency.Choice;
import ezmj.bot.TableView.Opponent;
import ezmj.engine.Claim;
import ezmj.engine.TurnOptions;
import ezmj.model.Meld;
import ezmj.model.Personality;
import ezmj.model.Tile;

/**
 * 職業：看自己的牌、大家打的牌、剩下的牌，再把攻守換算成同一把尺來比。
 * 每個選項的分數 = 這手胡得到的機率 × 胡了贏多少 − 放槍機率 × 放槍賠多少。
 * 這套規則底大台小、牌局又快，速度最值錢，所以平常只在進張差不多的打法之間挑；
 * 只有估計胡牌機會已經很低時，才把最安全的牌也列入考慮，也就是棄胡。
 * 個性與籌碼局勢會調整兩邊的比重，見 Posture。
 */
final class ProStrategy implements Strategy {

    private static final double TSUMO_SHARE = 0.25;

    /**
     * 累積的牌感：依向聽（列）與牌牆剩 8、24、40、56 張（行）的最終胡牌率上限，
     * 來自大量自我對戰的統計。對局時只查表，不會看任何人的手牌。
     */
    private static final double[][] WIN_BY_SHANTEN_AND_WALL = {
        {0.45, 0.68, 0.92, 1.14},
        {0.12, 0.24, 0.40, 0.62},
        {0.02, 0.12, 0.24, 0.40},
        {0.005, 0.05, 0.13, 0.26},
        {0.002, 0.02, 0.09, 0.16},
    };
    /** 進張對胡牌率的影響會飽和：1 − e^(−進張 / 這個值)。 */
    private static final double[] UKEIRE_SCALE = {6, 10, 15, 10, 10};
    private static final int WALL_STEP = 16;
    private static final int WALL_FIRST = 8;
    private static final int KONG_DRAW_WALL = 4;
    private static final double TYPICAL_WAITS = 5;
    private static final double DEEP_SHANTEN_DECAY = 0.6;
    /** 還沒聽牌時進張只影響這個比例以上的部分，免得為了進張退向聽。 */
    private static final double UKEIRE_FLOOR = 0.4;
    private static final double MAX_WIN_CHANCE = 0.95;

    private static final int FOLD_CANDIDATES = 3;
    /** 字牌與么九比較容易被別家打出來，聽這些牌時張數加權。 */
    private static final double EASY_WAIT_WEIGHT = 1.25;
    private static final double PRESSURE_WEIGHT = 0.4;
    /** 報聽要的剩餘張數，字牌與么九加權。 */
    private static final double LISTEN_WAIT_TILES = 4;
    /** 每台不到底的五分之一，就是底大台小。 */
    private static final int CHEAP_TAI_RATIO = 5;
    private static final double DANGEROUS_TENPAI = 0.7;

    private final Personality personality;
    private final int basePay;
    private final int taiPay;

    ProStrategy(Personality personality, int basePay, int taiPay) {
        this.personality = personality;
        this.basePay = basePay;
        this.taiPay = taiPay;
    }

    @Override
    public Tile chooseDiscard(TableView view) {
        return new Situation(view).bestDiscard(Efficiency.counts(view.hand()), view.melds()).tile;
    }

    @Override
    public Tile chooseKong(TableView view, TurnOptions options) {
        Situation situation = new Situation(view);
        int[] counts = Efficiency.counts(view.hand());
        double best = situation.bestDiscard(counts, view.melds()).score;
        Tile choice = null;
        for (Tile tile : options.concealedKongs()) {
            counts[tile.index()] -= 4;
            Meld kong = new Meld(Meld.Type.CONCEALED_KONG, List.of(tile, tile, tile, tile));
            double score = situation.waitingScore(counts, withMeld(view.melds(), kong), KONG_DRAW_WALL);
            counts[tile.index()] += 4;
            if (score >= best) {
                best = score;
                choice = tile;
            }
        }
        for (Tile tile : options.addKongs()) {
            counts[tile.index()]--;
            // 加槓和打出這張一樣可能被搶，但換到一張補牌
            double score = situation.waitingScore(counts, view.melds(), KONG_DRAW_WALL) - situation.risk(tile);
            counts[tile.index()]++;
            if (score >= best) {
                best = score;
                choice = tile;
            }
        }
        return choice;
    }

    @Override
    public Claim chooseClaim(TableView view, List<Claim> options) {
        Situation situation = new Situation(view);
        int[] counts = Efficiency.counts(view.hand());
        double best = situation.waitingScore(counts, view.melds(), 0);
        Claim choice = null;
        for (Claim claim : options) {
            for (Tile tile : claim.fromHand()) {
                counts[tile.index()]--;
            }
            double score;
            if (claim.type() == Claim.Type.KONG) {
                score = situation.waitingScore(counts, withClaim(view.melds(), Meld.Type.OPEN_KONG, claim), KONG_DRAW_WALL);
            } else {
                Meld.Type type = claim.type() == Claim.Type.PONG ? Meld.Type.PONG : Meld.Type.CHOW;
                score = situation.bestDiscard(counts, withClaim(view.melds(), type, claim)).score;
            }
            for (Tile tile : claim.fromHand()) {
                counts[tile.index()]++;
            }
            if (score > best) {
                best = score;
                choice = claim;
            }
        }
        return choice;
    }

    /**
     * 報聽多 1 台，代價是手牌鎖死：不能再換聽、不能吃碰槓，也不能棄胡，摸到什麼就得打什麼；
     * 別家知道你聽了也會防著打，比較難胡別家的牌。所以要剩下的牌夠多才報：
     * 底大台小時多的那 1 台不值錢，要更多張；別家看起來已經聽牌時報了就沒辦法防守，也要更多張。
     */
    @Override
    public boolean declareListening(TableView view, List<Tile> waits) {
        int[] unseen = view.unseen();
        double live = 0;
        for (Tile wait : waits) {
            live += unseen[wait.index()] * (Efficiency.usefulness(wait) <= 1 ? EASY_WAIT_WEIGHT : 1);
        }
        double needed = LISTEN_WAIT_TILES + (taiPay * CHEAP_TAI_RATIO < basePay ? 2 : 0);
        if (personality == Personality.BOLD) {
            needed -= 1;
        } else if (personality == Personality.CAUTIOUS) {
            needed += 1;
        }
        double danger = 0;
        for (Opponent opponent : view.opponents()) {
            danger = Math.max(danger, new OpponentRead(view, opponent, unseen).tenpai());
        }
        if (danger >= DANGEROUS_TENPAI) {
            needed += 2;
        }
        return live >= needed;
    }

    private static List<Meld> withClaim(List<Meld> melds, Meld.Type type, Claim claim) {
        List<Tile> tiles = new ArrayList<>(claim.fromHand());
        tiles.add(claim.tile());
        tiles.sort(Comparator.naturalOrder());
        return withMeld(melds, new Meld(type, tiles));
    }

    private static List<Meld> withMeld(List<Meld> melds, Meld meld) {
        List<Meld> extended = new ArrayList<>(melds);
        extended.add(meld);
        return extended;
    }

    private record Scored(Tile tile, double score) {
    }

    /** 一次決策裡共用的判讀，避免同一份桌面重算。 */
    private final class Situation {
        private final TableView view;
        private final int[] unseen;
        private final List<OpponentRead> reads = new ArrayList<>();
        private final double pressure;
        private final Posture posture;
        private final double[] riskCache = new double[Tile.KINDS];

        Situation(TableView view) {
            this.view = view;
            this.unseen = view.unseen();
            this.posture = Posture.of(personality, view, basePay, taiPay);
            double tenpaiSum = 0;
            for (Opponent opponent : view.opponents()) {
                OpponentRead read = new OpponentRead(view, opponent, unseen);
                reads.add(read);
                tenpaiSum += read.tenpai();
            }
            // 比這個牌牆深度下平常的局面更多人快聽時，自己胡的機會就再打折
            double usual = Math.max(0, 2.2 - view.liveTiles() / 28.0);
            this.pressure = Math.exp(-PRESSURE_WEIGHT * (tenpaiSum - usual));
            Arrays.fill(riskCache, -1);
        }

        /** 打這張的期望賠款，依心態加權。 */
        double risk(Tile tile) {
            int index = tile.index();
            if (riskCache[index] < 0) {
                double total = 0;
                for (OpponentRead read : reads) {
                    total += read.dealIn(tile) * read.payment(basePay, taiPay);
                }
                riskCache[index] = total * posture.caution();
            }
            return riskCache[index];
        }

        private double dealInChance(Tile tile) {
            double chance = 0;
            for (OpponentRead read : reads) {
                chance += read.dealIn(tile);
            }
            return Math.min(chance, 1);
        }

        /** 3n+2 張時最好的打法。候選是差不多快的牌；胡的機會很低時再加上最安全的牌。 */
        Scored bestDiscard(int[] counts, List<Meld> melds) {
            int sets = 5 - melds.size();
            List<Choice> choices = Efficiency.discards(counts, sets, unseen);
            Choice fastest = Efficiency.fastest(choices);
            Set<Choice> candidates = new LinkedHashSet<>();
            candidates.add(fastest);
            for (Choice choice : choices) {
                if (choice.shanten() == fastest.shanten() && choice.ukeire() >= fastest.ukeire() * posture.nearlyAsFast()) {
                    candidates.add(choice);
                }
            }
            counts[fastest.tile().index()]--;
            double fastestChance = winChance(counts, sets, fastest.shanten(), 0);
            counts[fastest.tile().index()]++;
            if (fastestChance < posture.foldBelow()) {
                choices.stream().sorted(Comparator.comparingDouble(choice -> risk(choice.tile())))
                        .limit(FOLD_CANDIDATES).forEach(candidates::add);
            }

            Scored best = null;
            for (Choice choice : candidates) {
                Tile tile = choice.tile();
                counts[tile.index()]--;
                double offense = offense(counts, melds, choice.shanten(), 0);
                counts[tile.index()]++;
                double score = offense * (1 - dealInChance(tile)) - risk(tile);
                if (best == null || score > best.score
                        || (score == best.score && Efficiency.usefulness(tile) < Efficiency.usefulness(best.tile))) {
                    best = new Scored(tile, score);
                }
            }
            return best;
        }

        /** 3n+1 張等下一張時的分數；下次摸牌還是要打一張，先扣掉手上最安全那張的風險。 */
        double waitingScore(int[] counts, List<Meld> melds, int extraWall) {
            double safest = Double.MAX_VALUE;
            for (int i = 0; i < Tile.KINDS; i++) {
                if (counts[i] > 0) {
                    safest = Math.min(safest, risk(Tile.fromIndex(i)));
                }
            }
            return offense(counts, melds, Shanten.of(counts, 5 - melds.size()), extraWall) - safest;
        }

        /** 3n+1 張的攻擊分：胡得到的機率 × 胡了贏多少，依心態加權。 */
        private double offense(int[] counts, List<Meld> melds, int shanten, int extraWall) {
            return posture.attack() * winChance(counts, 5 - melds.size(), shanten, extraWall)
                    * winMoney(HandValue.estimate(view, counts, melds, posture.ambition()), melds);
        }

        private double winChance(int[] counts, int sets, int shanten, int extraWall) {
            int row = Math.min(shanten, WIN_BY_SHANTEN_AND_WALL.length - 1);
            double wall = (view.liveTiles() + extraWall - WALL_FIRST) / (double) WALL_STEP;
            double[] curve = WIN_BY_SHANTEN_AND_WALL[row];
            int low = Math.max(0, Math.min((int) Math.floor(wall), curve.length - 2));
            double ceiling = Math.max(0, curve[low] + (curve[low + 1] - curve[low]) * (wall - low))
                    * Math.pow(DEEP_SHANTEN_DECAY, shanten - row);
            double tiles = shanten == 0 ? easedWaits(counts, sets) : Efficiency.ukeire(counts, sets, shanten, unseen);
            double saturation = 1 - Math.exp(-tiles / UKEIRE_SCALE[row]);
            double chance = ceiling * pressure
                    * (shanten == 0 ? saturation : UKEIRE_FLOOR + (1 - UKEIRE_FLOOR) * saturation);
            if (shanten == 1) {
                chance *= nextWaitFactor(counts, sets);
            }
            return Math.min(chance, MAX_WIN_CHANCE);
        }

        private double easedWaits(int[] counts, int sets) {
            long improving = Shanten.improving(counts, sets, 0);
            double total = 0;
            for (int i = 0; i < Tile.KINDS; i++) {
                if ((improving >> i & 1) != 0 && unseen[i] > 0) {
                    total += unseen[i] * (Efficiency.usefulness(Tile.fromIndex(i)) <= 1 ? EASY_WAIT_WEIGHT : 1);
                }
            }
            return total;
        }

        /** 往前多想一步：一向聽摸到有效牌後，平均會聽多少張，相對於一般水準是好是壞。 */
        private double nextWaitFactor(int[] counts, int sets) {
            long improving = Shanten.improving(counts, sets, 1);
            double weighted = 0;
            int useful = 0;
            for (int i = 0; i < Tile.KINDS; i++) {
                if ((improving >> i & 1) == 0 || unseen[i] <= 0) {
                    continue;
                }
                counts[i]++;
                int best = 0;
                for (int j = 0; j < Tile.KINDS; j++) {
                    if (counts[j] > 0) {
                        counts[j]--;
                        if (Shanten.of(counts, sets) == 0) {
                            best = Math.max(best, Efficiency.ukeire(counts, sets, 0, unseen));
                        }
                        counts[j]++;
                    }
                }
                weighted += best * unseen[i];
                useful += unseen[i];
                counts[i]--;
            }
            if (useful == 0) {
                return 1;
            }
            double scale = UKEIRE_SCALE[0];
            return (1 - Math.exp(-weighted / useful / scale)) / (1 - Math.exp(-TYPICAL_WAITS / scale));
        }

        /** 胡牌的平均收入，含莊家台與自摸三家付。 */
        private double winMoney(double tai, List<Meld> melds) {
            boolean closed = melds.stream().allMatch(Meld::isConcealed);
            boolean dealer = view.dealer() == view.seat();
            double bonus = 1 + 2 * view.streak();
            double ron = basePay + (tai + (dealer ? bonus : bonus / 3)) * taiPay;
            double tsumoTai = tai + (closed ? 2 : 1);
            double tsumo = dealer
                    ? 3 * (basePay + (tsumoTai + bonus) * taiPay)
                    : 3 * (basePay + tsumoTai * taiPay) + bonus * taiPay;
            return (1 - TSUMO_SHARE) * ron + TSUMO_SHARE * tsumo;
        }
    }
}
