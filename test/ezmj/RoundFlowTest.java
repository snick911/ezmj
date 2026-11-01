package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import ezmj.engine.Claim;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundResult;
import ezmj.engine.TurnOptions;
import ezmj.model.Gender;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.model.Wall;
import ezmj.rules.HandAnalyzer;

/** 一局的行牌流程：每個案例都用排好的牌牆走實際流程。 */
final class RoundFlowTest {

    /** 聽 1s、4s、7s 的 16 張。 */
    private static final String WAITING = "1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s 9p9p";
    /** 只聽 3w 的 16 張。 */
    private static final String WINS_ON_3W = "1w2w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p";
    /** 只聽 5w 的 16 張。 */
    private static final String WINS_ON_5W = "3w4w 1p2p3p 7p8p9p 2s3s4s 5s6s7s 9p9p";
    /** 九蓮寶燈加一組刻子，一到九萬都聽。 */
    private static final String NINE_WAITS = "1w1w1w 2w3w4w5w6w7w8w 9w9w9w 3p3p3p";

    private RoundFlowTest() {
    }

    private static List<Claim.Type> types(Round round, int seat) {
        return round.claimOptions(seat).stream().map(Claim::type).collect(Collectors.toList());
    }

    private static Claim option(Round round, int seat, Claim.Type type) {
        return round.claimOptions(seat).stream().filter(c -> c.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("座位 " + seat + " 沒有 " + type));
    }

    private static void conserved(Round round) {
        eq(144, Table.totalTiles(round), "全場張數");
    }

    /** 不宣告地行牌，直到牌牆可摸張數歸零且輪到某家手上有最後一張牌。 */
    private static void playToLastTile(Round round) {
        while (round.wall().liveCount() > 0) {
            Table.discardDrawnAndPass(round);
        }
    }

    static void run() {
        T.section("行牌流程");
        dealing();
        flowers();
        turnOrder();
        chow();
        pongAndKong();
        priority();
        manyWaits();
        afterClaim();
        kongs();
        winning();
        endOfWall();
        illegalActions();
        rightmostTile();
        events();
        readyDiscards();
    }

    private static void readyDiscards() {
        test("聽牌選項：列出打哪張會聽牌、聽哪些牌", () -> {
            Round round = Table.dealer(0).hand(0, WAITING).draws("9w").start();
            Map<Tile, List<Tile>> ready = round.readyDiscards(0);
            eq(T.hand("1s4s7s"), ready.get(tile("9w")), "打掉剛摸的牌");
            for (List<Tile> waits : ready.values()) {
                check(!waits.isEmpty(), "列出來的都要真的有聽");
            }
            fails(IllegalStateException.class, () -> round.readyDiscards(1), "還沒輪到");

            Round far = Table.dealer(0).hand(0, "1w3w5w7w9w 1p3p5p7p9p 1s3s5s7s9s 1z").draws("3z").start();
            check(far.readyDiscards(0).isEmpty(), "差很遠時沒有選項");
        });

        test("聽牌選項：吃碰之後也能聽牌", () -> {
            Round round = Table.dealer(1).hand(0, "1w1w 4w5w6w 7p8p9p 2s3s4s 5s6s 9p9p 2z").draws("1w").start();
            round.discard(1, tile("1w"));
            round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.PONG)));
            eq(T.hand("1s4s7s"), round.readyDiscards(0).get(tile("2z")), "打掉多的那張");
        });

        test("聽牌選項：自己槓掉的牌不會列在聽牌裡", () -> {
            Round round = Table.dealer(0).hand(0, "7s7s7s7s 1w2w3w 4w5w6w 7p8p9p 5s6s 9p").draws("9p").backs("2z").start();
            round.declareConcealedKong(0, tile("7s"));
            eq(T.hand("4s"), round.readyDiscards(0).get(tile("2z")), "七條都在暗槓裡，只聽四條");
        });
    }

    private static void events() {
        test("事件：打牌、碰與胡，狀態已更新且帶對的座位", () -> {
            Round round = Table.dealer(1).hand(0, "5w5w").draws("5w").start();
            List<RoundEvent> events = new ArrayList<>();
            round.setListener(event -> {
                events.add(event);
                if (event.type() == RoundEvent.Type.PONG) {
                    eq(1, round.player(0).melds().size(), "事件發生時面子已經加上去");
                }
            });
            round.discard(1, tile("5w"));
            round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.PONG)));
            eq(List.of(new RoundEvent(RoundEvent.Type.DISCARD, 1, tile("5w"), 1),
                    new RoundEvent(RoundEvent.Type.PONG, 0, tile("5w"), 1)), events, "打牌與碰");

            Round win = Table.dealer(1).hand(0, WINS_ON_3W).draws("3w").start();
            List<RoundEvent> winEvents = new ArrayList<>();
            win.setListener(winEvents::add);
            win.discard(1, tile("3w"));
            win.resolveClaims(Map.of(0, option(win, 0, Claim.Type.WIN)));
            eq(new RoundEvent(RoundEvent.Type.WIN, 0, tile("3w"), 1), winEvents.get(1), "放槍");

            Round self = Table.dealer(0).hand(0, WINS_ON_3W).draws("3w").start();
            List<RoundEvent> selfEvents = new ArrayList<>();
            self.setListener(selfEvents::add);
            self.declareSelfWin(0);
            eq(new RoundEvent(RoundEvent.Type.SELF_WIN, 0, tile("3w"), -1), selfEvents.get(0), "自摸");
        });

        test("事件：摸牌與補花都有，花牌補上來的那張才算摸到", () -> {
            Round round = Table.dealer(0).draws("1w 1f 2w").backs("3w").start();
            List<RoundEvent> events = new ArrayList<>();
            round.setListener(event -> {
                events.add(event);
                if (event.type() == RoundEvent.Type.FLOWER) {
                    check(round.player(event.seat()).flowers().contains(event.tile()), "事件發生時花牌已經亮出來");
                }
                if (event.type() == RoundEvent.Type.DRAW) {
                    eq(event.tile(), round.drawn(), "事件發生時已經摸到");
                    eq(Round.Phase.TURN, round.phase(), "事件發生時輪到他");
                }
            });
            Table.discardDrawnAndPass(round);
            eq(List.of(new RoundEvent(RoundEvent.Type.DISCARD, 0, tile("1w"), 0),
                    new RoundEvent(RoundEvent.Type.FLOWER, 1, tile("1f"), -1),
                    new RoundEvent(RoundEvent.Type.DRAW, 1, tile("3w"), -1)), events, "打牌、補花、摸牌");
        });

        test("事件：流局", () -> {
            Round round = Table.dealer(0).start();
            List<RoundEvent> events = new ArrayList<>();
            round.setListener(events::add);
            playToLastTile(round);
            Table.discardDrawnAndPass(round);
            eq(Round.Phase.FINISHED, round.phase(), "摸完牌");
            eq(RoundEvent.Type.DRAWN_GAME, events.get(events.size() - 1).type(), "最後一個事件");
        });
    }

    private static void rightmostTile() {
        test("最右邊的手牌：有剛摸的牌就是它，碰牌後是排序最後一張", () -> {
            Round round = Table.dealer(1).hand(0, "5w5w").draws("5w").start();
            eq(tile("5w"), round.rightmostTile(1), "剛摸的牌排在最右，即使排序不是最後");
            fails(IllegalStateException.class, () -> round.rightmostTile(2), "還沒輪到");

            round.discard(1, tile("5w"));
            fails(IllegalStateException.class, () -> round.rightmostTile(0), "等待宣告時沒有輪到誰出牌");

            round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.PONG)));
            List<Tile> hand = round.player(0).concealed();
            eq(hand.get(hand.size() - 1), round.rightmostTile(0), "碰牌後沒有剛摸的牌");
        });
    }

    private static void dealing() {
        test("發牌：莊家 17 張、閒家 16 張，全場 144 張", () -> {
            for (int dealer = 0; dealer < 4; dealer++) {
                Round round = Table.dealer(dealer).start();
                for (int seat = 0; seat < 4; seat++) {
                    eq(seat == dealer ? 17 : 16, round.player(seat).concealed().size(), "座位 " + seat);
                }
                eq(dealer, round.current(), "莊家先行");
                eq(Round.Phase.TURN, round.phase(), "階段");
                conserved(round);
            }
        });

        test("真實洗牌開局：花牌全部補掉、手牌張數正確", () -> {
            Random random = new Random(31);
            for (int i = 0; i < 2000; i++) {
                Player[] players = new Player[4];
                for (int seat = 0; seat < 4; seat++) {
                    players[seat] = new Player("P" + seat, seat, seat == 0, Gender.FEMALE, 1000);
                }
                Round round = new Round(players, new Wall(random), i % 4, 1, 0, message -> { });
                round.start();
                for (int seat = 0; seat < 4; seat++) {
                    check(players[seat].concealed().stream().noneMatch(Tile::isFlower), "手牌有花");
                    eq(seat == i % 4 ? 17 : 16, players[seat].concealed().size(), "手牌張數");
                    check(players[seat].flowers().stream().allMatch(Tile::isFlower), "花區混入一般牌");
                }
                conserved(round);
            }
        });

        test("門風由莊家起算逆時針為東南西北", () -> {
            Round round = Table.dealer(2).start();
            eq(1, round.seatWind(2), "莊家東");
            eq(2, round.seatWind(3), "下家南");
            eq(3, round.seatWind(0), "對家西");
            eq(4, round.seatWind(1), "上家北");
        });
    }

    private static void flowers() {
        test("開局補花：從莊家起依序由牌尾補", () -> {
            Round round = Table.dealer(0).hand(0, "1f").hand(2, "2f").backs("8w 9w").draws("7w").start();
            eq(T.hand("1f"), round.player(0).flowers(), "莊家花");
            eq(T.hand("2f"), round.player(2).flowers(), "對家花");
            eq(1, round.player(0).countInHand(tile("8w")), "莊家補到牌尾第一張");
            eq(1, round.player(2).countInHand(tile("9w")), "對家補到牌尾第二張");
            eq(17, round.player(0).concealed().size(), "莊家張數");
            eq(16, round.player(2).concealed().size(), "對家張數");
            conserved(round);
        });

        test("補花又補到花會連續補", () -> {
            Round round = Table.dealer(0).hand(0, "1f").backs("2f 3f 8w").draws("7w").start();
            eq(3, round.player(0).flowers().size(), "花數");
            eq(1, round.player(0).countInHand(tile("8w")), "最後補到的牌");
            eq(17, round.player(0).concealed().size(), "張數");
        });

        test("摸牌摸到花：由牌尾補，並標記補到的牌為剛摸的牌", () -> {
            Round round = Table.dealer(0).draws("3f").backs("7w").start();
            eq(T.hand("3f"), round.player(0).flowers(), "花");
            eq(tile("7w"), round.drawn(), "剛摸的牌");
            eq(17, round.player(0).concealed().size(), "張數");
        });

        String seven = "1f2f3f4f5f6f7f";
        String replacements = "1w2w3w4w5w6w7w";

        test("八仙過海：開局補花集滿就胡，第八張不補牌，只算 8 台", () -> {
            Round round = Table.dealer(0).hand(0, seven + "8f").backs(replacements).start();
            eq(Round.Phase.FINISHED, round.phase(), "立即結束");
            RoundResult result = round.result();
            eq(0, result.winner(), "胡牌者");
            check(result.isSelfDraw() && result.isFlowerWin(), "算自摸，而且是花牌胡");
            eq(tile("8f"), result.winTile(), "最後亮出的花");
            check(result.score().has("八仙過海"), "八仙過海 " + result.score());
            eq(8, result.score().totalTai(), "只算這一項");
            eq(15, round.player(0).concealed().size(), "第八張沒有補牌");
            conserved(round);
        });

        test("八仙過海：摸到第八張也立即胡，可以是別人還沒打牌時", () -> {
            Round round = Table.dealer(0).hand(0, seven).backs(replacements).draws("8f").start();
            eq(Round.Phase.FINISHED, round.phase(), "立即結束");
            check(round.result().isSelfDraw(), "算自摸");
            check(round.result().score().has("八仙過海"), "八仙過海");
            eq(8, round.player(0).flowers().size(), "八張花");
        });

        test("七搶一：開局自己先七張，別家亮出一張就搶走，算他放槍", () -> {
            Round round = Table.dealer(0).hand(0, seven).hand(1, "8f").backs(replacements).start();
            RoundResult result = round.result();
            eq(0, result.winner(), "七張的人胡");
            eq(1, result.from(), "一張的人放槍");
            eq(tile("8f"), result.winTile(), "被搶的花");
            check(result.isFlowerWin() && result.score().has("七搶一"), "七搶一 " + result.score());
            eq(8, result.score().totalTai(), "只算這一項");
            eq(8, round.player(0).flowers().size(), "花移給胡牌的人");
            eq(List.of(), round.player(1).flowers(), "被搶走");
            conserved(round);
        });

        test("七搶一：別家先亮出一張，自己後來湊到七張也成立", () -> {
            Round round = Table.dealer(1).hand(1, "8f").hand(0, seven).backs("8w" + replacements).start();
            eq(0, round.result().winner(), "七張的人胡");
            eq(1, round.result().from(), "一張的人放槍");
        });

        test("七搶一：行牌中別家摸到第八張，事件帶對的座位與來源", () -> {
            Round round = Table.dealer(0).hand(0, seven).backs(replacements).draws("9w 8f").start();
            eq(Round.Phase.TURN, round.phase(), "第八張還沒出現不會胡");
            List<RoundEvent> events = new ArrayList<>();
            round.setListener(events::add);
            Table.discardDrawnAndPass(round);
            eq(Round.Phase.FINISHED, round.phase(), "立即結束");
            eq(0, round.result().winner(), "七張的人胡");
            eq(1, round.result().from(), "摸到的人放槍");
            RoundEvent last = events.get(events.size() - 1);
            eq(new RoundEvent(RoundEvent.Type.WIN, 0, tile("8f"), 1), last, "事件");
            conserved(round);
        });

        test("花牌沒有七加一或八：六張加兩張、七張而第八張還在牌牆，都不會胡", () -> {
            Round split = Table.dealer(0).hand(0, "1f2f3f4f5f6f").hand(1, "7f8f").backs("1w2w3w4w5w6w7w8w").start();
            eq(Round.Phase.TURN, split.phase(), "六張加兩張");
            Round waiting = Table.dealer(0).hand(0, seven).backs(replacements).start();
            eq(Round.Phase.TURN, waiting.phase(), "七張");
            eq(7, waiting.player(0).flowers().size(), "七張花");
        });
    }

    private static void turnOrder() {
        test("沒人宣告時依 0→1→2→3→0 輪轉", () -> {
            Round round = Table.dealer(0).start();
            for (int step = 1; step <= 8; step++) {
                Table.discardDrawnAndPass(round);
                eq(step % 4, round.current(), "第 " + step + " 手");
                eq(17, round.player(round.current()).concealed().size(), "摸牌後張數");
                conserved(round);
            }
        });

        test("打牌：手牌移到牌河並進入宣告階段", () -> {
            Round round = Table.dealer(0).hand(0, "5w").draws("9w").start();
            round.discard(0, tile("5w"));
            eq(Round.Phase.CLAIMS, round.phase(), "階段");
            eq(tile("5w"), round.claimTile(), "待宣告牌");
            eq(0, round.discarder(), "打牌者");
            eq(T.hand("5w"), round.player(0).discards(), "牌河");
            eq(16, round.player(0).concealed().size(), "手牌");
            check(round.drawn() == null, "打牌後沒有剛摸的牌");
        });
    }

    private static void chow() {
        test("吃：只有下家能吃，三種組合都列出", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "1w2w4w5w").hand(2, "1w2w").hand(3, "1w2w")
                    .draws("9w").start();
            round.discard(0, tile("3w"));
            List<Claim> options = round.claimOptions(1);
            eq(3, options.size(), "組合數 " + options);
            eq(T.hand("1w2w"), options.get(0).fromHand(), "前吃");
            eq(T.hand("2w4w"), options.get(1).fromHand(), "中吃");
            eq(T.hand("4w5w"), options.get(2).fromHand(), "後吃");
            eq(List.of(), types(round, 2), "對家不能吃");
            eq(List.of(), types(round, 3), "上家不能吃");
            eq(List.of(), types(round, 0), "自己不能吃");
        });

        test("吃：選牌時點共用的牌會留下兩種組合，再點另一張才確定", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "1w2w4w5w").draws("9w").start();
            round.discard(0, tile("3w"));
            List<Claim> options = round.claimOptions(1);
            eq(new HashSet<>(T.hand("1w2w4w5w")), Claim.handTiles(options), "可以選的牌");

            eq(1, Claim.using(options, tile("1w")).size(), "只用在一種組合");
            List<Claim> shared = Claim.using(options, tile("2w"));
            eq(2, shared.size(), "二萬用在兩種組合");
            eq(new HashSet<>(T.hand("1w2w4w")), Claim.handTiles(shared), "接著只能選這些");
            eq(T.hand("2w4w"), Claim.using(shared, tile("4w")).get(0).fromHand(), "再點四萬");
        });

        test("吃：同樣的組合手上有幾張都只算一種，按了就吃不用選牌", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "1w1w2w2w").draws("9w").start();
            round.discard(0, tile("3w"));
            List<Claim> options = round.claimOptions(1);
            eq(1, options.size(), "一種組合 " + options);
            eq(T.hand("1w2w"), options.get(0).fromHand(), "前吃");
        });

        test("吃：邊界牌與不同花色不能湊順", () -> {
            Round one = Table.dealer(0).hand(0, "1w").hand(1, "2w3w").draws("9w").start();
            one.discard(0, tile("1w"));
            eq(1, one.claimOptions(1).size(), "一萬只能 123");

            Round nine = Table.dealer(0).hand(0, "9w").hand(1, "7w8w").draws("1w").start();
            nine.discard(0, tile("9w"));
            eq(1, nine.claimOptions(1).size(), "九萬只能 789");

            Round mixed = Table.dealer(0).hand(0, "3w").hand(1, "1w").draws("9w").start();
            mixed.discard(0, tile("3w"));
            eq(List.of(), types(mixed, 1), "只有一張相關的萬子不能吃");
        });

        test("吃：字牌不能吃", () -> {
            Round round = Table.dealer(0).hand(0, "1z").hand(1, "2z3z").draws("9w").start();
            round.discard(0, tile("1z"));
            check(!types(round, 1).contains(Claim.Type.CHOW), "字牌不能吃");
        });

        test("吃完：面子正確、牌河移除、輪到自己只能打牌", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "1w2w").draws("9w").start();
            round.discard(0, tile("3w"));
            round.resolveClaims(Map.of(1, option(round, 1, Claim.Type.CHOW)));
            Player player = round.player(1);
            eq(1, player.melds().size(), "面子數");
            eq(Meld.Type.CHOW, player.melds().get(0).type(), "類型");
            eq(T.hand("1w2w3w"), player.melds().get(0).tiles(), "順子由小到大");
            eq(14, player.concealed().size(), "手牌剩 14 張");
            eq(List.of(), round.player(0).discards(), "被吃的牌離開牌河");
            eq(1, round.current(), "輪到吃牌者");
            eq(Round.Phase.TURN, round.phase(), "階段");
            conserved(round);
        });
    }

    private static void pongAndKong() {
        test("碰：任何一家都能碰，打牌者不能碰自己", () -> {
            for (int seat = 1; seat < 4; seat++) {
                Round round = Table.dealer(0).hand(0, "5w").hand(seat, "5w5w").draws("9w").start();
                round.discard(0, tile("5w"));
                eq(List.of(Claim.Type.PONG), types(round, seat), "座位 " + seat);
            }
            Round self = Table.dealer(0).hand(0, "5w5w5w").draws("9w").start();
            self.discard(0, tile("5w"));
            eq(List.of(), types(self, 0), "不能碰自己打的牌");
        });

        test("只有一張不能碰，三張可槓也可碰", () -> {
            Round single = Table.dealer(0).hand(0, "5w").hand(2, "5w").draws("9w").start();
            single.discard(0, tile("5w"));
            eq(List.of(), types(single, 2), "一張不能碰");

            Round triple = Table.dealer(0).hand(0, "5w").hand(2, "5w5w5w").draws("9w").start();
            triple.discard(0, tile("5w"));
            eq(List.of(Claim.Type.KONG, Claim.Type.PONG), types(triple, 2), "三張");
        });

        test("胡、碰、槓各只有一種組合，按了就宣告不用選牌", () -> {
            Round round = Table.dealer(0).hand(0, "5w").hand(2, "5w5w5w6w7w9s9s1p2p3p4p5p6p7p8p9p").draws("9w").start();
            round.discard(0, tile("5w"));
            List<Claim> options = round.claimOptions(2);
            eq(List.of(Claim.Type.WIN, Claim.Type.KONG, Claim.Type.PONG),
                    options.stream().map(Claim::type).collect(Collectors.toList()), "每種一個 " + options);
        });

        test("下家同時可碰可吃都會列出", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "3w3w1w2w").draws("9w").start();
            round.discard(0, tile("3w"));
            eq(List.of(Claim.Type.PONG, Claim.Type.CHOW), types(round, 1), "選項");
        });
    }

    private static void priority() {
        test("碰優先於吃，即使吃的人比較近", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "1w2w").hand(3, "3w3w").draws("9w").start();
            round.discard(0, tile("3w"));
            round.resolveClaims(Map.of(1, option(round, 1, Claim.Type.CHOW), 3, option(round, 3, Claim.Type.PONG)));
            eq(3, round.current(), "碰的人行動");
            eq(0, round.player(1).melds().size(), "吃不成立");
            eq(16, round.player(1).concealed().size(), "吃的人手牌不變");
        });

        test("槓優先於吃", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "1w2w").hand(2, "3w3w3w").draws("9w").backs("9w").start();
            round.discard(0, tile("3w"));
            round.resolveClaims(Map.of(1, option(round, 1, Claim.Type.CHOW), 2, option(round, 2, Claim.Type.KONG)));
            eq(2, round.current(), "槓的人行動");
            eq(Meld.Type.OPEN_KONG, round.player(2).melds().get(0).type(), "明槓");
        });

        test("胡優先於碰與吃", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "3w3w").hand(3, WINS_ON_3W).draws("9w").start();
            round.discard(0, tile("3w"));
            round.resolveClaims(Map.of(1, option(round, 1, Claim.Type.PONG), 3, option(round, 3, Claim.Type.WIN)));
            eq(Round.Phase.FINISHED, round.phase(), "結束");
            eq(3, round.result().winner(), "胡牌者");
            eq(0, round.player(1).melds().size(), "碰不成立");
        });

        test("多人同胡：由打牌者下家起算最近的人胡", () -> {
            String other = "1w2w 4w5w6w 1p2p3p 2s3s4s 5s6s7s 5p5p";
            Round round = Table.dealer(0).hand(0, "3w").hand(1, WINS_ON_3W).hand(3, other).draws("9w").start();
            round.discard(0, tile("3w"));
            round.resolveClaims(Map.of(3, option(round, 3, Claim.Type.WIN), 1, option(round, 1, Claim.Type.WIN)));
            eq(1, round.result().winner(), "下家截胡");

            Round wrapped = Table.dealer(2).hand(2, "3w").hand(0, WINS_ON_3W).hand(3, other).draws("9w").start();
            wrapped.discard(2, tile("3w"));
            wrapped.resolveClaims(Map.of(0, option(wrapped, 0, Claim.Type.WIN), 3, option(wrapped, 3, Claim.Type.WIN)));
            eq(3, wrapped.result().winner(), "繞過座位 0 的距離計算");
        });

        test("三家同時吃、碰、胡：不管座位遠近都是胡，吃碰的人手牌不動", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "1w2w").hand(2, "3w3w").hand(3, WINS_ON_3W)
                    .draws("9w").start();
            round.discard(0, tile("3w"));
            round.resolveClaims(Map.of(
                    1, option(round, 1, Claim.Type.CHOW),
                    2, option(round, 2, Claim.Type.PONG),
                    3, option(round, 3, Claim.Type.WIN)));
            RoundResult result = round.result();
            eq(3, result.winner(), "最遠的一家胡");
            eq(0, result.from(), "放槍者");
            for (int seat : new int[] {1, 2}) {
                eq(0, round.player(seat).melds().size(), "座位 " + seat + " 宣告不成立");
                eq(16, round.player(seat).concealed().size(), "座位 " + seat + " 手牌不變");
            }
            eq(List.of(), round.player(0).discards(), "牌河移除");
            conserved(round);
        });

        test("能胡的人放棄：碰照常成立，牌局繼續", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "3w3w").hand(3, WINS_ON_3W).draws("9w").start();
            round.discard(0, tile("3w"));
            check(types(round, 3).contains(Claim.Type.WIN), "座位 3 本來可以胡");
            round.resolveClaims(Map.of(1, option(round, 1, Claim.Type.PONG)));
            eq(Round.Phase.TURN, round.phase(), "牌局繼續");
            eq(1, round.current(), "碰的人行動");
            eq(1, round.player(1).melds().size(), "碰成立");
            eq(16, round.player(3).concealed().size(), "放棄的人手牌不變");
            conserved(round);
        });

        test("碰完打出的牌，別家可以胡，放槍算打牌的人", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "3w3w 5w").hand(2, WINS_ON_5W).draws("9w").start();
            round.discard(0, tile("3w"));
            round.resolveClaims(Map.of(1, option(round, 1, Claim.Type.PONG)));
            round.discard(1, tile("5w"));
            check(types(round, 2).contains(Claim.Type.WIN), "下一家可以胡");
            round.resolveClaims(Map.of(2, option(round, 2, Claim.Type.WIN)));
            RoundResult result = round.result();
            eq(2, result.winner(), "胡牌者");
            eq(1, result.from(), "碰完打牌的人放槍");
            eq(tile("5w"), result.winTile(), "胡的牌");
            eq(Meld.Type.PONG, round.player(1).melds().get(0).type(), "碰的面子還在");
            eq(List.of(), round.player(1).discards(), "放槍的牌離開牌河");
            conserved(round);
        });

        test("吃完打出的牌，不是下家的人也可以胡", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(1, "1w2w 5w").hand(3, WINS_ON_5W).draws("9w").start();
            round.discard(0, tile("3w"));
            round.resolveClaims(Map.of(1, option(round, 1, Claim.Type.CHOW)));
            round.discard(1, tile("5w"));
            eq(List.of(Claim.Type.WIN), types(round, 3), "對面只能胡");
            round.resolveClaims(Map.of(3, option(round, 3, Claim.Type.WIN)));
            eq(3, round.result().winner(), "胡牌者");
            eq(1, round.result().from(), "吃完打牌的人放槍");
            eq(Meld.Type.CHOW, round.player(1).melds().get(0).type(), "吃的面子還在");
            conserved(round);
        });
    }

    private static void manyWaits() {
        List<Tile> nine = T.hand(NINE_WAITS);

        test("同時聽九張：聽牌結果正確", () -> {
            eq(T.hand("1w2w3w4w5w6w7w8w9w"), HandAnalyzer.waits(nine), "一到九萬");
        });

        test("同時聽九張：每一張都能胡別家打的牌，不算獨聽", () -> {
            for (Tile wait : HandAnalyzer.waits(nine)) {
                Round round = Table.dealer(3).hand(3, code(wait)).hand(0, NINE_WAITS).draws("1p").start();
                round.discard(3, wait);
                check(types(round, 0).contains(Claim.Type.WIN), wait + " 可以胡");
                round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.WIN)));
                RoundResult result = round.result();
                eq(0, result.winner(), wait + " 胡牌者");
                eq(3, result.from(), wait + " 放槍者");
                eq(wait, result.winTile(), "胡的牌");
                eq(17, round.player(0).concealed().size(), wait + " 張數");
                check(!result.score().has("獨聽"), wait + " 不是獨聽 " + result.score());
                conserved(round);
            }
        });

        test("同時聽九張：每一張都能自摸，不算獨聽", () -> {
            for (Tile wait : HandAnalyzer.waits(nine)) {
                Round round = Table.dealer(0).hand(0, NINE_WAITS).draws(code(wait)).start();
                check(round.turnOptions(0).canSelfWin(), wait + " 可以自摸");
                round.declareSelfWin(0);
                RoundResult result = round.result();
                check(result.isSelfDraw(), wait + " 自摸");
                eq(wait, result.winTile(), "胡的牌");
                check(!result.score().has("獨聽"), wait + " 不是獨聽 " + result.score());
                conserved(round);
            }
        });

        test("同時聽多張的清一色：每一張都算到清一色", () -> {
            String pure = "1w1w1w 2w3w4w5w6w7w8w 9w9w9w 2w3w4w";
            List<Tile> waits = HandAnalyzer.waits(T.hand(pure));
            check(waits.size() >= 3, "至少聽三張 " + waits);
            for (Tile wait : waits) {
                Round round = Table.dealer(3).hand(3, code(wait)).hand(0, pure).draws("1p").start();
                round.discard(3, wait);
                round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.WIN)));
                check(round.result().score().has("清一色"), wait + " 清一色 " + round.result().score());
                check(!round.result().score().has("獨聽"), wait + " 不是獨聽");
                conserved(round);
            }
        });

        test("碰完還聽九張：別家打的任何一張都能胡，不算門清", () -> {
            for (Tile wait : HandAnalyzer.waits(nine)) {
                Round round = Table.dealer(3).hand(3, "3p").hand(0, "1w1w1w2w3w4w5w6w7w8w9w9w9w 3p3p 5z")
                        .draws("1p 2p 2p " + code(wait)).start();
                round.discard(3, tile("3p"));
                round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.PONG)));
                round.discard(0, tile("5z"));
                round.resolveClaims(Map.of());
                Table.discardDrawnAndPass(round);
                Table.discardDrawnAndPass(round);
                round.discard(3, wait);
                check(types(round, 0).contains(Claim.Type.WIN), wait + " 可以胡");
                round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.WIN)));
                RoundResult result = round.result();
                eq(0, result.winner(), wait + " 胡牌者");
                check(!result.score().has("獨聽"), wait + " 不是獨聽");
                check(!result.score().has("門清"), wait + " 不是門清");
                conserved(round);
            }
        });

        test("多打一張就聽九張：聽牌選項列出全部", () -> {
            Round round = Table.dealer(0).hand(0, NINE_WAITS).draws("5z").start();
            eq(T.hand("1w2w3w4w5w6w7w8w9w"), round.readyDiscards(0).get(tile("5z")), "打東風聽九張");
        });
    }

    /** 牌的記法，給 Table 的劇本用。 */
    private static String code(Tile tile) {
        return tile.rank() + "w";
    }

    private static void afterClaim() {
        test("碰完只能打牌，不能自摸或暗槓，打完輪到下家", () -> {
            Round round = Table.dealer(0).hand(0, "5w").hand(2, "5w5w 8w8w8w8w").draws("9w 1w").start();
            round.discard(0, tile("5w"));
            round.resolveClaims(Map.of(2, option(round, 2, Claim.Type.PONG)));
            eq(TurnOptions.DISCARD_ONLY, round.turnOptions(2), "只能打牌");
            check(round.drawn() == null, "碰牌沒有摸牌");
            fails(IllegalStateException.class, () -> round.declareSelfWin(2), "碰完不能自摸");
            round.discard(2, tile("8w"));
            round.resolveClaims(Map.of());
            eq(3, round.current(), "輪到碰牌者的下家，跳過座位 1");
            conserved(round);
        });
    }

    private static void kongs() {
        test("明槓：由牌尾補牌，可以繼續行動", () -> {
            Round round = Table.dealer(0).hand(0, "5w").hand(2, "5w5w5w").draws("9w").backs("1w").start();
            int wallBefore = round.wall().size();
            round.discard(0, tile("5w"));
            round.resolveClaims(Map.of(2, option(round, 2, Claim.Type.KONG)));
            Meld meld = round.player(2).melds().get(0);
            eq(Meld.Type.OPEN_KONG, meld.type(), "類型");
            eq(4, meld.tiles().size(), "四張");
            eq(tile("1w"), round.drawn(), "補到牌尾");
            eq(2, round.current(), "輪到自己");
            eq(wallBefore - 1, round.wall().size(), "牌牆少一張");
            eq(14, round.player(2).concealed().size(), "手牌 16-3+1");
            conserved(round);
        });

        test("明槓後補到胡牌算槓上開花", () -> {
            Round round = Table.dealer(0).hand(0, "5w").hand(2, "5w5w5w 1w2w3w 7p8p9p 2s3s4s 5s6s 9p9p")
                    .draws("9w").backs("4s").start();
            round.discard(0, tile("5w"));
            round.resolveClaims(Map.of(2, option(round, 2, Claim.Type.KONG)));
            check(round.turnOptions(2).canSelfWin(), "應該可以自摸");
            round.declareSelfWin(2);
            check(round.result().score().has("槓上開花"), "槓上開花 " + round.result().score());
            check(round.result().score().has("自摸"), "明槓不是門清");
            check(round.result().isSelfDraw(), "算自摸");
        });

        test("暗槓：手上四張才能槓，補牌後還是門清", () -> {
            Round round = Table.dealer(0).hand(0, "1w1w1w 2w3w4w 7p8p9p 2s3s4s 5s6s 9p9p")
                    .draws("1w").backs("7s").start();
            eq(T.hand("1w"), round.turnOptions(0).concealedKongs(), "可暗槓");
            round.declareConcealedKong(0, tile("1w"));
            eq(Meld.Type.CONCEALED_KONG, round.player(0).melds().get(0).type(), "類型");
            eq(tile("7s"), round.drawn(), "補牌");
            eq(0, round.current(), "仍是自己");
            round.declareSelfWin(0);
            RoundResult result = round.result();
            check(result.score().has("門清一摸三"), "暗槓不破門清 " + result.score());
            check(result.score().has("槓上開花"), "槓上開花");
            check(!result.score().has("天胡"), "開過槓不算天胡");
            eq(4, result.score().totalTai(), "總台數 " + result.score());
        });

        test("只有三張不能暗槓", () -> {
            Round round = Table.dealer(0).hand(0, "1w1w1w").draws("9w").start();
            eq(List.of(), round.turnOptions(0).concealedKongs(), "不能暗槓");
            fails(IllegalStateException.class, () -> round.declareConcealedKong(0, tile("1w")), "暗槓");
        });

        Table addKong = Table.dealer(3).hand(3, "5w").hand(0, "5w5w 8w")
                .hand(1, "3w4w 6w7w8w 7p8p9p 2s3s4s 5s6s7s 9p9p")
                .draws("9w 9w 9w 9w 5w").backs("1w");

        test("加槓：無人搶槓就升級為明槓並由牌尾補牌", () -> {
            Round round = playToAddKong(addKong);
            eq(T.hand("5w"), round.turnOptions(0).addKongs(), "可加槓");
            round.declareAddKong(0, tile("5w"));
            eq(Round.Phase.CLAIMS, round.phase(), "先給人搶槓");
            check(round.isRobbing(), "搶槓中");
            eq(Meld.Type.PONG, round.player(0).melds().get(0).type(), "還沒升級");
            conserved(round);
            round.resolveClaims(Map.of());
            eq(Meld.Type.OPEN_KONG, round.player(0).melds().get(0).type(), "升級");
            eq(tile("1w"), round.drawn(), "補牌");
            eq(0, round.current(), "仍是自己");
            check(!round.isRobbing(), "搶槓結束");
            conserved(round);
        });

        test("搶槓：只能胡不能碰吃，胡了碰仍維持三張", () -> {
            Round round = playToAddKong(addKong);
            round.declareAddKong(0, tile("5w"));
            eq(List.of(Claim.Type.WIN), types(round, 1), "只能胡");
            eq(List.of(), types(round, 0), "加槓者自己不能搶");
            round.resolveClaims(Map.of(1, option(round, 1, Claim.Type.WIN)));
            RoundResult result = round.result();
            eq(1, result.winner(), "胡牌者");
            eq(0, result.from(), "加槓者放槍");
            check(result.score().has("搶槓"), "搶槓 " + result.score());
            eq(Meld.Type.PONG, round.player(0).melds().get(0).type(), "維持碰");
            eq(3, round.player(0).melds().get(0).tiles().size(), "三張");
            conserved(round);
        });
    }

    /** 座位 3 打 5w → 座位 0 碰、打 8w → 繞一圈 → 座位 0 摸到第四張 5w。 */
    private static Round playToAddKong(Table table) {
        Round round = table.start();
        round.discard(3, tile("5w"));
        round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.PONG)));
        round.discard(0, tile("8w"));
        round.resolveClaims(Map.of());
        Table.discardDrawnAndPass(round);
        Table.discardDrawnAndPass(round);
        Table.discardDrawnAndPass(round);
        eq(tile("5w"), round.drawn(), "座位 0 摸到第四張");
        eq(0, round.current(), "輪到座位 0");
        return round;
    }

    private static void winning() {
        test("胡別人的牌：牌進手牌、離開牌河、記錄放槍者", () -> {
            Round round = Table.dealer(0).hand(0, "3w").hand(2, WINS_ON_3W).draws("9w").start();
            round.discard(0, tile("3w"));
            eq(List.of(Claim.Type.WIN), types(round, 2), "可以胡");
            round.resolveClaims(Map.of(2, option(round, 2, Claim.Type.WIN)));
            RoundResult result = round.result();
            eq(2, result.winner(), "胡牌者");
            eq(0, result.from(), "放槍者");
            eq(tile("3w"), result.winTile(), "胡的牌");
            check(!result.isSelfDraw() && !result.isDrawnGame(), "類型");
            eq(17, round.player(2).concealed().size(), "胡牌者 17 張");
            eq(List.of(), round.player(0).discards(), "牌河移除");
            conserved(round);
        });

        test("自摸：手牌 17 張不重複加牌", () -> {
            Round round = Table.dealer(1).hand(1, WAITING).draws("4s").start();
            check(round.turnOptions(1).canSelfWin(), "可以自摸");
            round.declareSelfWin(1);
            RoundResult result = round.result();
            check(result.isSelfDraw(), "自摸");
            eq(17, round.player(1).concealed().size(), "張數");
            eq(tile("4s"), result.winTile(), "胡的牌");
            conserved(round);
        });

        test("沒成牌不能自摸", () -> {
            Round round = Table.dealer(0).hand(0, WAITING).draws("9w").start();
            check(!round.turnOptions(0).canSelfWin(), "不能自摸");
            fails(IllegalStateException.class, () -> round.declareSelfWin(0), "詐胡");
            eq(Round.Phase.TURN, round.phase(), "牌局繼續");
        });

        test("天胡：莊家配牌就胡", () -> {
            Round round = Table.dealer(0).hand(0, WAITING).draws("4s").start();
            round.declareSelfWin(0);
            check(round.result().score().has("天胡"), "天胡 " + round.result().score());
            check(!round.result().score().has("地胡"), "不是地胡");
        });

        test("地胡：閒家第一次摸牌就胡", () -> {
            Round round = Table.dealer(0).hand(1, WAITING).draws("9w 4s").start();
            Table.discardDrawnAndPass(round);
            round.declareSelfWin(1);
            check(round.result().score().has("地胡"), "地胡 " + round.result().score());
            check(!round.result().score().has("天胡"), "不是天胡");
        });

        test("有人吃碰過就不算地胡", () -> {
            Round round = Table.dealer(0).hand(0, "5w").hand(2, "5w5w 8w").hand(3, WAITING).draws("9w 4s").start();
            round.discard(0, tile("5w"));
            round.resolveClaims(Map.of(2, option(round, 2, Claim.Type.PONG)));
            round.discard(2, tile("8w"));
            round.resolveClaims(Map.of());
            eq(3, round.current(), "輪到座位 3");
            round.declareSelfWin(3);
            check(!round.result().score().has("地胡"), "不算地胡 " + round.result().score());
        });

        test("打過牌之後自摸不算天胡地胡", () -> {
            Round round = Table.dealer(0).hand(0, WAITING).draws("9w 1p 1p 1p 4s").start();
            for (int i = 0; i < 4; i++) {
                Table.discardDrawnAndPass(round);
            }
            eq(0, round.current(), "回到莊家");
            round.declareSelfWin(0);
            check(!round.result().score().has("天胡"), "不算天胡");
        });

        test("花牌會算進胡牌台數", () -> {
            Round round = Table.dealer(0).hand(0, WAITING).draws("1f").backs("4s").start();
            eq(T.hand("1f"), round.player(0).flowers(), "補花");
            round.declareSelfWin(0);
            check(round.result().score().has("正花 春"), "莊家東風對應春 " + round.result().score());
        });
    }

    private static void endOfWall() {
        test("流局：摸完 128 張扣掉發牌，留 16 張不摸", () -> {
            Round round = Table.dealer(0).start();
            int draws = 1;
            while (round.phase() != Round.Phase.FINISHED) {
                Table.discardDrawnAndPass(round);
                if (round.phase() != Round.Phase.FINISHED) {
                    draws++;
                }
                conserved(round);
            }
            eq(64, draws, "總摸牌次數");
            eq(Wall.RESERVED, round.wall().size(), "剩留牌");
            check(round.result().isDrawnGame(), "流局");
            eq(-1, round.result().winner(), "沒有贏家");
        });

        test("補牌也會消耗可摸張數", () -> {
            Round round = Table.dealer(0).hand(0, "5w").hand(2, "5w5w5w").draws("9w").backs("1w").start();
            int before = round.wall().liveCount();
            round.discard(0, tile("5w"));
            round.resolveClaims(Map.of(2, option(round, 2, Claim.Type.KONG)));
            eq(before - 1, round.wall().liveCount(), "可摸張數");
        });

        test("海底撈月：最後一張自摸", () -> {
            Round round = Table.dealer(0).hand(3, WAITING).lastLive("4s").start();
            playToLastTile(round);
            eq(3, round.current(), "第 64 張輪到座位 3");
            eq(tile("4s"), round.drawn(), "最後一張");
            round.declareSelfWin(3);
            check(round.result().score().has("海底撈月"), "海底 " + round.result().score());
        });

        test("河底撈魚：最後一張被打出來胡", () -> {
            Round round = Table.dealer(0).hand(0, WAITING).lastLive("4s").start();
            playToLastTile(round);
            round.discard(3, tile("4s"));
            round.resolveClaims(Map.of(0, option(round, 0, Claim.Type.WIN)));
            check(round.result().score().has("河底撈魚"), "河底 " + round.result().score());
        });

        test("最後一張打出沒人要就流局", () -> {
            Round round = Table.dealer(0).lastLive("4s").start();
            playToLastTile(round);
            Table.discardDrawnAndPass(round);
            eq(Round.Phase.FINISHED, round.phase(), "結束");
            check(round.result().isDrawnGame(), "流局");
        });

        test("牌牆摸完不能再槓，但可以碰", () -> {
            Round concealed = Table.dealer(0).hand(3, "1w1w1w").lastLive("1w").start();
            playToLastTile(concealed);
            eq(List.of(), concealed.turnOptions(3).concealedKongs(), "不能暗槓");

            Round open = Table.dealer(0).hand(0, "2w2w2w").lastLive("2w").start();
            playToLastTile(open);
            open.discard(3, tile("2w"));
            eq(List.of(Claim.Type.PONG), types(open, 0), "只能碰");
        });
    }

    private static void illegalActions() {
        test("沒輪到的人不能打牌", () -> {
            Round round = Table.dealer(0).start();
            Tile other = round.player(1).concealed().get(0);
            fails(IllegalStateException.class, () -> round.discard(1, other), "搶打");
            eq(0, round.current(), "狀態不變");
            eq(16, round.player(1).concealed().size(), "手牌不變");
        });

        test("不能打手上沒有的牌", () -> {
            Round round = Table.dealer(0).draws("9w").start();
            fails(IllegalArgumentException.class, () -> round.discard(0, tile("5w")), "打沒有的牌");
            eq(Round.Phase.TURN, round.phase(), "階段不變");
            eq(List.of(), round.player(0).discards(), "牌河不變");
        });

        test("階段不對的動作都會被拒絕", () -> {
            Round round = Table.dealer(0).draws("9w").start();
            fails(IllegalStateException.class, () -> round.resolveClaims(Map.of()), "打牌前不能結算宣告");
            fails(IllegalStateException.class, () -> round.claimOptions(1), "打牌前沒有宣告選項");
            round.discard(0, tile("9w"));
            fails(IllegalStateException.class, () -> round.discard(0, round.player(0).concealed().get(0)), "連打兩張");
            fails(IllegalStateException.class, () -> round.turnOptions(0), "宣告階段沒有回合選項");
        });

        test("沒碰過不能加槓", () -> {
            Round round = Table.dealer(0).hand(0, "5w").draws("9w").start();
            fails(IllegalStateException.class, () -> round.declareAddKong(0, tile("5w")), "加槓");
        });

        test("不合法的宣告會被拒絕且不改變牌局", () -> {
            Round round = Table.dealer(0).hand(0, "5w").hand(1, "1w").draws("9w").start();
            round.discard(0, tile("5w"));
            Claim forged = new Claim(Claim.Type.PONG, tile("5w"), T.hand("5w5w"));
            fails(IllegalArgumentException.class, () -> round.resolveClaims(Map.of(1, forged)), "偽造的碰");
            eq(Round.Phase.CLAIMS, round.phase(), "階段不變");
            eq(T.hand("5w"), round.player(0).discards(), "牌河不變");

            Claim forgedWin = new Claim(Claim.Type.WIN, tile("5w"), List.of());
            fails(IllegalArgumentException.class, () -> round.resolveClaims(Map.of(2, forgedWin)), "詐胡");
            eq(Round.Phase.CLAIMS, round.phase(), "階段不變");
        });

        test("牌局結束後不能再動作", () -> {
            Round round = Table.dealer(0).hand(0, WAITING).draws("4s").start();
            round.declareSelfWin(0);
            fails(IllegalStateException.class, () -> round.discard(0, tile("1w")), "結束後打牌");
            fails(IllegalStateException.class, () -> round.declareSelfWin(0), "重複胡牌");
        });
    }
}
