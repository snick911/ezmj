package ezmj;

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
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;

/**
 * 讓不同等級對打，比較每局平均輸贏。座位會輪替以抵銷位置影響。
 * 單獨執行可看完整對戰表：java -cp ... ezmj.Tournament [每組局數]
 */
final class Tournament {

    private static final int BASE = 100;
    private static final int PER_TAI = 20;

    /** 一個等級的累計成績。 */
    static final class Score {
        long money;
        int seatRounds;
        int wins;
        int selfDraws;
        int dealIns;
        long winMoney;

        double moneyPerRound() {
            return money / (double) seatRounds;
        }

        @Override
        public String toString() {
            return String.format("每局 %+7.1f  胡 %5.1f%%（自摸 %4.1f%%，每胡 %4.0f）  放槍 %5.1f%%", moneyPerRound(),
                    100.0 * wins / seatRounds, 100.0 * selfDraws / seatRounds,
                    winMoney / (double) Math.max(wins, 1), 100.0 * dealIns / seatRounds);
        }
    }

    private Tournament() {
    }

    /** 依序讓 lineup 的四個等級輪流坐四個位置，共打 rounds 局。 */
    static Map<BotLevel, Score> play(BotLevel[] lineup, int rounds, long seed) {
        Random random = new Random(seed);
        Map<BotLevel, Score> scores = new EnumMap<>(BotLevel.class);
        for (BotLevel level : lineup) {
            scores.computeIfAbsent(level, key -> new Score());
        }
        ComputerBot[] bots = new ComputerBot[4];
        for (int i = 0; i < 4; i++) {
            bots[i] = new ComputerBot(lineup[i], BASE, PER_TAI, new Random(seed + i));
        }
        int played = 0;
        GameSession session = null;
        while (played < rounds) {
            if (session == null || session.isOver()) {
                session = new GameSession(new GameConfig("測試", 1_000_000_000, BASE, PER_TAI, BotLevel.NORMAL), random);
            }
            int shift = played % 4;
            Round round = session.newRound(message -> { });
            round.start();
            while (round.phase() != Round.Phase.FINISHED) {
                if (round.phase() == Round.Phase.TURN) {
                    int seat = round.current();
                    bots[(seat + shift) % 4].playTurn(round, seat, round.turnOptions(seat));
                } else {
                    round.resolveClaims(claims(round, bots, shift));
                }
            }
            record(session.settle(round.result()), round.result(), scores, lineup, shift);
            played++;
        }
        return scores;
    }

    private static Map<Integer, Claim> claims(Round round, ComputerBot[] bots, int shift) {
        Map<Integer, Claim> decisions = new HashMap<>();
        for (int seat = 0; seat < 4; seat++) {
            List<Claim> options = round.claimOptions(seat);
            if (!options.isEmpty()) {
                Claim claim = bots[(seat + shift) % 4].chooseClaim(round, seat, options);
                if (claim != null) {
                    decisions.put(seat, claim);
                }
            }
        }
        return decisions;
    }

    private static void record(Settlement settlement, RoundResult result, Map<BotLevel, Score> scores,
                               BotLevel[] lineup, int shift) {
        for (int seat = 0; seat < 4; seat++) {
            Score score = scores.get(lineup[(seat + shift) % 4]);
            score.seatRounds++;
            score.money += settlement.deltas()[seat];
            if (!result.isDrawnGame() && seat == result.winner()) {
                score.wins++;
                score.winMoney += settlement.deltas()[seat];
                if (result.isSelfDraw()) {
                    score.selfDraws++;
                }
            }
            if (!result.isDrawnGame() && !result.isSelfDraw() && seat == result.from()) {
                score.dealIns++;
            }
        }
    }

    public static void main(String[] args) {
        int rounds = args.length > 0 ? Integer.parseInt(args[0]) : 4000;
        BotLevel[] levels = BotLevel.values();
        System.out.println("== 同桌：四個等級各一家 ==");
        printTable(new BotLevel[] {levels[0], levels[1], levels[2], levels[3]}, rounds * 4, 1);
        for (int i = 1; i < levels.length; i++) {
            BotLevel high = levels[i];
            BotLevel low = levels[i - 1];
            System.out.println("== " + high + " vs 三家" + low + " ==");
            printTable(new BotLevel[] {high, low, low, low}, rounds, 2L + i);
            System.out.println("== " + low + " vs 三家" + high + " ==");
            printTable(new BotLevel[] {low, high, high, high}, rounds, 20L + i);
        }
        System.out.println("== 平均表現：同等級互打 ==");
        for (BotLevel level : levels) {
            printTable(new BotLevel[] {level, level, level, level}, rounds, 40L + level.ordinal());
        }
    }

    private static void printTable(BotLevel[] lineup, int rounds, long seed) {
        long start = System.nanoTime();
        Map<BotLevel, Score> scores = play(lineup, rounds, seed);
        scores.forEach((level, score) -> System.out.println(String.format("  %-4s %s", level.label(), score)));
        System.out.println(String.format("  （%d 局，%.1f 秒）", rounds, (System.nanoTime() - start) / 1e9));
    }
}
