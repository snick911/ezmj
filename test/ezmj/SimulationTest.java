package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ezmj.bot.ComputerBot;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundResult;
import ezmj.engine.Settlement;
import ezmj.engine.TurnOptions;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.rules.HandAnalyzer;

/** 用會積極宣告、朝胡牌打的測試玩家跑大量牌局，每一步都檢查規則不變量。 */
final class SimulationTest {

    private static final int ROUNDS = 20000;

    private SimulationTest() {
    }

    static void run() {
        T.section("大量模擬");

        test(ROUNDS + " 局積極玩家：每一步都符合不變量，各種結果都有涵蓋", () -> {
            Random random = new Random(20261031);
            int rounds = 0;
            int selfDraws = 0;
            int rons = 0;
            int drawn = 0;
            Map<Meld.Type, Integer> melds = new EnumMap<>(Meld.Type.class);
            while (rounds < ROUNDS) {
                GameSession session = new GameSession(new GameConfig("測試", 100_000_000, 100, 20, BotLevel.NORMAL), random);
                while (!session.isOver() && rounds < ROUNDS) {
                    rounds++;
                    Round round = session.newRound(message -> { });
                    round.start();
                    int guard = 0;
                    while (round.phase() != Round.Phase.FINISHED) {
                        check(++guard < 500, "牌局必須會結束");
                        assertInvariants(round);
                        if (round.phase() == Round.Phase.TURN) {
                            playTurn(round, random);
                        } else {
                            resolveClaims(round, random);
                        }
                    }
                    assertFinished(round);
                    RoundResult result = round.result();
                    if (result.isDrawnGame()) {
                        drawn++;
                    } else if (result.isSelfDraw()) {
                        selfDraws++;
                    } else {
                        rons++;
                    }
                    for (int seat = 0; seat < 4; seat++) {
                        for (Meld meld : round.player(seat).melds()) {
                            melds.merge(meld.type(), 1, Integer::sum);
                        }
                    }
                    assertSettlement(session, session.settle(result), result);
                }
            }
            System.out.println("  模擬結果：自摸 " + selfDraws + "、放槍 " + rons + "、流局 " + drawn + "、面子 " + melds);
            check(selfDraws > 100, "自摸樣本太少: " + selfDraws);
            check(rons > 100, "放槍樣本太少: " + rons);
            check(drawn > 100, "流局樣本太少: " + drawn);
            for (Meld.Type type : Meld.Type.values()) {
                check(melds.getOrDefault(type, 0) > 50, type + " 樣本太少: " + melds);
            }
        });

        for (BotLevel level : BotLevel.values()) {
            test(level + "電腦玩家跑完牌局都符合不變量", () -> {
                Random random = new Random(51);
                ComputerBot bot = new ComputerBot(level, 100, 20, random);
                for (int game = 0; game < 100; game++) {
                    GameSession session = new GameSession(new GameConfig("測試", 100_000, 100, 20, level), random);
                    Round round = session.newRound(message -> { });
                    round.start();
                    while (round.phase() != Round.Phase.FINISHED) {
                        assertInvariants(round);
                        if (round.phase() == Round.Phase.TURN) {
                            int seat = round.current();
                            bot.playTurn(round, seat, round.turnOptions(seat));
                        } else {
                            Map<Integer, Claim> decisions = new HashMap<>();
                            for (int seat = 0; seat < 4; seat++) {
                                List<Claim> options = round.claimOptions(seat);
                                if (!options.isEmpty()) {
                                    Claim claim = bot.chooseClaim(round, seat, options);
                                    if (claim != null) {
                                        decisions.put(seat, claim);
                                    }
                                }
                            }
                            round.resolveClaims(decisions);
                        }
                    }
                    assertFinished(round);
                }
            });
        }
    }

    private static void playTurn(Round round, Random random) {
        int seat = round.current();
        TurnOptions options = round.turnOptions(seat);
        if (options.canSelfWin()) {
            round.declareSelfWin(seat);
        } else if (!options.concealedKongs().isEmpty()) {
            round.declareConcealedKong(seat, options.concealedKongs().get(0));
        } else if (!options.addKongs().isEmpty()) {
            round.declareAddKong(seat, options.addKongs().get(0));
        } else {
            round.discard(seat, worstTile(round.player(seat), random));
        }
    }

    /** 打出最孤立的牌，讓手牌確實往胡牌靠近，模擬才會出現足夠的胡牌樣本。 */
    private static Tile worstTile(Player player, Random random) {
        List<Tile> hand = player.concealed();
        Tile worst = null;
        int worstScore = Integer.MAX_VALUE;
        for (Tile tile : hand) {
            int score = player.countInHand(tile) * 4;
            if (tile.isNumber()) {
                for (int offset : new int[] {-2, -1, 1, 2}) {
                    int rank = tile.rank() + offset;
                    if (rank >= 1 && rank <= 9 && player.countInHand(Tile.of(tile.suit(), rank)) > 0) {
                        score += Math.abs(offset) == 1 ? 2 : 1;
                    }
                }
            }
            score = score * 8 + random.nextInt(8);
            if (score < worstScore) {
                worstScore = score;
                worst = tile;
            }
        }
        return worst;
    }

    private static void resolveClaims(Round round, Random random) {
        Map<Integer, Claim> decisions = new HashMap<>();
        for (int seat = 0; seat < 4; seat++) {
            List<Claim> options = new ArrayList<>(round.claimOptions(seat));
            if (options.isEmpty()) {
                continue;
            }
            Claim first = options.get(0);
            boolean takeIt = first.type() == Claim.Type.WIN
                    || random.nextInt(10) < (first.type() == Claim.Type.CHOW ? 3 : 7);
            if (takeIt) {
                decisions.put(seat, first);
            }
        }
        round.resolveClaims(decisions);
    }

    private static void assertInvariants(Round round) {
        eq(144, Table.totalTiles(round), "全場張數");
        int[] counts = new int[Tile.KINDS];
        for (int seat = 0; seat < 4; seat++) {
            Player player = round.player(seat);
            int effective = player.concealed().size() + player.melds().size() * 3;
            boolean holding = round.phase() == Round.Phase.TURN && round.current() == seat;
            eq(holding ? 17 : 16, effective, player.name() + " 有效張數");
            check(player.concealed().stream().noneMatch(Tile::isFlower), "手牌不可有花");
            check(player.discards().stream().noneMatch(Tile::isFlower), "牌河不可有花");
            check(player.melds().size() <= 5, "面子不超過 5 組");
            for (Tile tile : player.concealed()) {
                counts[tile.index()]++;
            }
            for (Tile tile : player.discards()) {
                counts[tile.index()]++;
            }
            for (Meld meld : player.melds()) {
                assertMeldShape(meld);
                for (Tile tile : meld.tiles()) {
                    counts[tile.index()]++;
                }
            }
        }
        for (int i = 0; i < Tile.KINDS; i++) {
            check(counts[i] <= 4, Tile.fromIndex(i) + " 超過 4 張");
        }
        check(round.wall().size() >= 16, "留牌不可被摸走");
    }

    private static void assertMeldShape(Meld meld) {
        List<Tile> tiles = meld.tiles();
        switch (meld.type()) {
            case CHOW:
                eq(3, tiles.size(), "順子張數");
                check(tiles.get(0).isNumber(), "順子必須是數牌");
                for (int i = 1; i < 3; i++) {
                    eq(tiles.get(0).suit(), tiles.get(i).suit(), "順子同花色");
                    eq(tiles.get(0).rank() + i, tiles.get(i).rank(), "順子連續");
                }
                break;
            case PONG:
                eq(3, tiles.size(), "刻子張數");
                check(tiles.stream().allMatch(tiles.get(0)::equals), "刻子相同");
                break;
            default:
                eq(4, tiles.size(), "槓張數");
                check(tiles.stream().allMatch(tiles.get(0)::equals), "槓相同");
                break;
        }
    }

    private static void assertFinished(Round round) {
        eq(144, Table.totalTiles(round), "結束時全場張數");
        RoundResult result = round.result();
        if (result.isDrawnGame()) {
            eq(16, round.wall().size(), "流局時剩留牌");
            return;
        }
        Player winner = round.player(result.winner());
        eq(17, winner.concealed().size() + winner.melds().size() * 3, "胡牌者有效張數");
        check(HandAnalyzer.canWin(winner.concealed()), "胡牌者手牌必須成牌");
        check(winner.concealed().contains(result.winTile()), "胡的牌必須在手上");
        check(result.score().totalTai() >= 0, "台數不可為負");
        if (!result.isSelfDraw()) {
            check(result.from() != result.winner(), "不能胡自己的牌");
        }
    }

    private static void assertSettlement(GameSession session, Settlement settlement, RoundResult result) {
        int sum = 0;
        for (int delta : settlement.deltas()) {
            sum += delta;
        }
        eq(0, sum, "結算總和");
        if (result.isDrawnGame()) {
            for (int delta : settlement.deltas()) {
                eq(0, delta, "流局不算錢");
            }
            return;
        }
        check(settlement.deltas()[result.winner()] > 0, "贏家要收錢");
        for (int seat = 0; seat < 4; seat++) {
            if (seat == result.winner()) {
                continue;
            }
            boolean pays = result.isSelfDraw() || seat == result.from();
            check(pays == settlement.deltas()[seat] < 0, "付款對象錯誤 " + session.player(seat).name());
        }
    }
}
