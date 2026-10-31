package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundResult;
import ezmj.engine.Settlement;
import ezmj.model.GameConfig;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;

/** 金額結算、莊家輪替、連莊、圈風與牌局結束。 */
final class SessionTest {

    private static final int BASE = 100;
    private static final int PER_TAI = 20;
    private static final int CHIPS = 20_000;

    private SessionTest() {
    }

    private static GameSession session() {
        return new GameSession(new GameConfig("測試", CHIPS, BASE, PER_TAI), new Random(5));
    }

    private static RoundResult ron(int winner, int from, int tai) {
        return new RoundResult(winner, from, tile("1w"), new ScoreResult(List.of(new ScoreItem("測試", tai))));
    }

    private static RoundResult tsumo(int winner, int tai) {
        return new RoundResult(winner, -1, tile("1w"), new ScoreResult(List.of(new ScoreItem("測試", tai))));
    }

    /** 由莊家位置算出相對座位，讓案例不依賴隨機的起莊。 */
    private static int rel(GameSession session, int offset) {
        return (session.dealer() + offset) % 4;
    }

    private static void deltas(GameSession session, Settlement settlement, int dealerSeat, int... byOffset) {
        for (int offset = 0; offset < 4; offset++) {
            eq(byOffset[offset], settlement.deltas()[(dealerSeat + offset) % 4], "莊家起算第 " + offset + " 家輸贏");
            eq(CHIPS + byOffset[offset], session.player((dealerSeat + offset) % 4).chips(), "籌碼");
        }
    }

    static void run() {
        T.section("結算與牌局");

        test("開局設定：真人在座位 0、四家本金相同", () -> {
            GameSession session = session();
            eq("測試", session.player(0).name(), "姓名");
            check(session.player(0).isHuman(), "座位 0 是真人");
            Set<String> names = new HashSet<>();
            for (int seat = 0; seat < 4; seat++) {
                eq(CHIPS, session.player(seat).chips(), "本金");
                names.add(session.player(seat).name());
                if (seat > 0) {
                    check(!session.player(seat).isHuman(), "其他家是電腦");
                }
            }
            eq(4, names.size(), "姓名不重複");
            eq(1, session.roundWind(), "東風圈開始");
            eq(0, session.streak(), "沒有連莊");
        });

        test("閒家胡閒家：只有放槍者付，不計莊家台", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            Settlement settlement = session.settle(ron(rel(session, 1), rel(session, 2), 3));
            int pay = BASE + 3 * PER_TAI;
            deltas(session, settlement, dealer, 0, pay, -pay, 0);
        });

        test("閒家胡莊家：多計莊家 1 台", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            Settlement settlement = session.settle(ron(rel(session, 1), dealer, 3));
            int pay = BASE + 4 * PER_TAI;
            deltas(session, settlement, dealer, -pay, pay, 0, 0);
        });

        test("莊家胡閒家：多計莊家 1 台", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            Settlement settlement = session.settle(ron(dealer, rel(session, 2), 3));
            int pay = BASE + 4 * PER_TAI;
            deltas(session, settlement, dealer, pay, 0, -pay, 0);
        });

        test("閒家自摸：三家都付，莊家多付莊家台", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            Settlement settlement = session.settle(tsumo(rel(session, 3), 2));
            int idle = BASE + 2 * PER_TAI;
            int fromDealer = BASE + 3 * PER_TAI;
            deltas(session, settlement, dealer, -fromDealer, -idle, -idle, fromDealer + 2 * idle);
        });

        test("莊家自摸：三家都多付莊家台", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            Settlement settlement = session.settle(tsumo(dealer, 2));
            int pay = BASE + 3 * PER_TAI;
            deltas(session, settlement, dealer, 3 * pay, -pay, -pay, -pay);
        });

        test("零台胡牌（屁胡）仍付底", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            Settlement settlement = session.settle(ron(rel(session, 1), rel(session, 2), 0));
            deltas(session, settlement, dealer, 0, BASE, -BASE, 0);
        });

        test("連莊：連 N 拉 N，莊家台為 1+2N", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            session.settle(RoundResult.drawnGame());
            session.settle(RoundResult.drawnGame());
            eq(2, session.streak(), "連二");
            eq(dealer, session.dealer(), "莊家不變");
            Settlement settlement = session.settle(ron(dealer, rel(session, 1), 1));
            int pay = BASE + (1 + 5) * PER_TAI;
            eq(pay, settlement.deltas()[dealer], "莊家連二胡牌");
            eq(3, session.streak(), "胡牌再連");
            eq(dealer, session.dealer(), "莊家連莊");
        });

        test("連莊中閒家互胡不計莊家台", () -> {
            GameSession session = session();
            session.settle(RoundResult.drawnGame());
            int a = rel(session, 1);
            int b = rel(session, 2);
            Settlement settlement = session.settle(ron(a, b, 2));
            eq(BASE + 2 * PER_TAI, settlement.deltas()[a], "只算牌型台");
        });

        test("流局：沒有金錢往來，莊家連莊", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            Settlement settlement = session.settle(RoundResult.drawnGame());
            deltas(session, settlement, dealer, 0, 0, 0, 0);
            eq(dealer, session.dealer(), "莊家不變");
            eq(1, session.streak(), "連一");
        });

        test("閒家胡牌：下莊給下家，連莊歸零", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            session.settle(RoundResult.drawnGame());
            session.settle(ron(rel(session, 2), rel(session, 3), 1));
            eq((dealer + 1) % 4, session.dealer(), "下莊");
            eq(0, session.streak(), "連莊歸零");
        });

        test("莊家放槍也會下莊", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            session.settle(ron(rel(session, 2), dealer, 1));
            eq((dealer + 1) % 4, session.dealer(), "下莊");
        });

        test("四次下莊換圈風，四圈後結束", () -> {
            GameSession session = session();
            int firstDealer = session.dealer();
            for (int wind = 1; wind <= 4; wind++) {
                eq(wind, session.roundWind(), "圈風");
                for (int change = 0; change < 4; change++) {
                    check(!session.isOver(), "第 " + wind + " 圈還沒結束");
                    eq((firstDealer + change) % 4, session.dealer(), "莊家順序");
                    session.settle(ron(rel(session, 1), rel(session, 2), 0));
                }
            }
            check(session.isOver(), "四圈打完結束");
        });

        test("新一局會傳入目前的莊家與圈風，手牌清空", () -> {
            GameSession session = session();
            int dealer = session.dealer();
            Round first = session.newRound(message -> { });
            first.start();
            eq(dealer, first.dealer(), "莊家");
            eq(1, first.roundWind(), "圈風");
            session.settle(ron(rel(session, 1), rel(session, 2), 0));
            Round second = session.newRound(message -> { });
            second.start();
            eq((dealer + 1) % 4, second.dealer(), "下一局莊家");
            eq(144, Table.totalTiles(second), "換局後張數不會累積");
        });

        test("有人籌碼歸零或變負就結束", () -> {
            GameSession exact = new GameSession(new GameConfig("測試", BASE, BASE, PER_TAI), new Random(5));
            check(!exact.isOver(), "開局未結束");
            exact.settle(ron(rel(exact, 1), rel(exact, 2), 0));
            check(exact.isOver(), "輸到剛好 0 結束");

            GameSession negative = new GameSession(new GameConfig("測試", BASE, BASE, PER_TAI), new Random(5));
            negative.settle(ron(rel(negative, 1), rel(negative, 2), 5));
            check(negative.isOver(), "輸到負數結束");
        });

        test("結算總和恆為 0", () -> {
            Random random = new Random(41);
            GameSession session = new GameSession(new GameConfig("測試", 1_000_000, 300, 50), random);
            for (int i = 0; i < 2000 && !session.isOver(); i++) {
                int winner = random.nextInt(4);
                int from = (winner + 1 + random.nextInt(3)) % 4;
                int tai = random.nextInt(40);
                RoundResult result = random.nextBoolean() ? ron(winner, from, tai) : tsumo(winner, tai);
                int sum = 0;
                for (int delta : session.settle(result).deltas()) {
                    sum += delta;
                }
                eq(0, sum, "第 " + i + " 局");
            }
        });
    }
}
