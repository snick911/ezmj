package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.hand;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import ezmj.bot.ComputerBot;
import ezmj.bot.Shanten;
import ezmj.bot.SeatedBots;
import ezmj.bot.Strategy;
import ezmj.bot.TableView;
import ezmj.bot.TableView.Opponent;
import ezmj.engine.Claim;
import ezmj.engine.DiscardEvent;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.TurnOptions;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Meld;
import ezmj.model.Personality;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.rules.HandAnalyzer;

/** 向聽計算、不作弊的保證，以及每個等級該有的思考方式。 */
final class BotTest {

    private static final int PLENTY_OF_CHIPS = 100_000;

    private BotTest() {
    }

    private static int[] counts(List<Tile> tiles) {
        int[] counts = new int[Tile.KINDS];
        for (Tile each : tiles) {
            counts[each.index()]++;
        }
        return counts;
    }

    private static int shanten(String tiles, int sets) {
        return Shanten.of(counts(hand(tiles)), sets);
    }

    private static Strategy strategy(BotLevel level) {
        return Strategy.of(level, 100, 20, new Random(1));
    }

    private static Meld chow(String tiles) {
        return new Meld(Meld.Type.CHOW, hand(tiles));
    }

    /** 三家都沒動靜、牌牆還很深的桌面。 */
    private static TableView quiet(String tiles, List<Meld> melds) {
        return table(tiles, melds, emptyOpponents(), List.of(), 50);
    }

    private static List<Opponent> emptyOpponents() {
        return List.of(opponent(1, 0, ""), opponent(2, 0, ""), opponent(3, 0, ""));
    }

    /** melds 組隨便的順子當作亮出來的面子，river 是牌河。 */
    private static Opponent opponent(int seat, int melds, String river) {
        List<Meld> open = new ArrayList<>();
        for (int i = 0; i < melds; i++) {
            open.add(chow((i + 1) + "s" + (i + 2) + "s" + (i + 3) + "s"));
        }
        return new Opponent(seat, open, 0, List.of(), hand(river), PLENTY_OF_CHIPS, false);
    }

    private static TableView table(String tiles, List<Meld> melds, List<Opponent> opponents,
                                   List<DiscardEvent> history, int live) {
        return new TableView(0, 0, 0, 1, 1, live, hand(tiles), melds, List.of(), List.of(),
                opponents, history, null, -1, false, PLENTY_OF_CHIPS);
    }

    private static List<DiscardEvent> historyOf(List<Opponent> opponents) {
        List<DiscardEvent> history = new ArrayList<>();
        for (Opponent opponent : opponents) {
            for (Tile each : opponent.discards()) {
                history.add(new DiscardEvent(opponent.seat(), each));
            }
        }
        return history;
    }

    static void run() {
        T.section("電腦玩家");
        shantenValues();
        noCheating();
        beginner();
        normal();
        amateur();
        pro();
        allLevels();
        ladder();
        personalities();
        listening();
    }

    private static void listening() {
        // 兩面聽三萬、六萬；單騎聽四萬
        String twoSided = "4w5w 1p2p3p 4p5p6p 7p8p9p 1s2s3s 9s9s";
        String single = "4w 1p2p3p 4p5p6p 7p8p9p 1s2s3s 5s6s7s";
        test("報聽：初學者不報；一般只看聽幾種，保守的單騎不報，愛衝一聽就報", () -> {
            check(!strategy(BotLevel.BEGINNER).declareListening(quiet(twoSided, List.of()), waits(twoSided)), "初學者");
            check(strategy(BotLevel.NORMAL).declareListening(quiet(twoSided, List.of()), waits(twoSided)), "一般：兩面就報");
            check(!Strategy.of(BotLevel.NORMAL, Personality.CAUTIOUS, 100, 20, new Random(1))
                    .declareListening(quiet(single, List.of()), waits(single)), "保守的單騎不報");
            check(Strategy.of(BotLevel.NORMAL, Personality.BOLD, 100, 20, new Random(1))
                    .declareListening(quiet(single, List.of()), waits(single)), "愛衝一聽就報");
        });
        test("報聽：業餘數還剩幾張，張數夠才報", () -> {
            check(strategy(BotLevel.AMATEUR).declareListening(quiet(twoSided, List.of()), waits(twoSided)), "兩面八張");
            check(!strategy(BotLevel.AMATEUR).declareListening(quiet(single, List.of()), waits(single)), "單騎只剩三張");
        });
        test("報聽：職業看到別家已經報聽，報了就不能防守，要更多張才報", () -> {
            // 三萬被打掉三張，剩下一張三萬加四張六萬
            List<Opponent> calm = List.of(opponent(1, 0, "3w3w3w"), opponent(2, 0, ""), opponent(3, 0, ""));
            check(strategy(BotLevel.PRO).declareListening(table(twoSided, List.of(), calm, historyOf(calm), 50), waits(twoSided)),
                    "沒人威脅時五張就報");
            Opponent listening = new Opponent(2, List.of(), 0, List.of(), List.of(), PLENTY_OF_CHIPS, true);
            List<Opponent> tense = List.of(opponent(1, 0, "3w3w3w"), listening, opponent(3, 0, ""));
            check(!strategy(BotLevel.PRO).declareListening(table(twoSided, List.of(), tense, historyOf(tense), 50), waits(twoSided)),
                    "別家報聽了，五張不夠");
        });
    }

    private static List<Tile> waits(String tiles) {
        return HandAnalyzer.waits(hand(tiles), List.of());
    }

    /** 一手要吃碰的選擇。 */
    private record ClaimChoice(TableView view, List<Claim> options) {
    }

    private static void collectDecisions(List<TableView> turns, List<ClaimChoice> claims) {
        ComputerBot[] bots = new ComputerBot[4];
        for (int seat = 0; seat < 4; seat++) {
            bots[seat] = new ComputerBot(BotLevel.AMATEUR, 100, 20, new Random(seat));
        }
        Random random = new Random(11);
        for (int r = 0; r < 60; r++) {
            GameSession session = new GameSession(new GameConfig("測試", 100_000, 100, 20, BotLevel.AMATEUR), random);
            Round round = session.newRound(message -> { });
            round.start();
            while (round.phase() != Round.Phase.FINISHED) {
                if (round.phase() == Round.Phase.TURN) {
                    int seat = round.current();
                    TurnOptions options = round.turnOptions(seat);
                    if (!options.canSelfWin()) {
                        turns.add(TableView.of(round, seat));
                    }
                    bots[seat].playTurn(round, seat, options);
                } else {
                    Map<Integer, Claim> decisions = new HashMap<>();
                    for (int seat = 0; seat < 4; seat++) {
                        List<Claim> options = round.claimOptions(seat);
                        if (!options.isEmpty()) {
                            if (options.stream().noneMatch(claim -> claim.type() == Claim.Type.WIN)) {
                                claims.add(new ClaimChoice(TableView.of(round, seat), options));
                            }
                            Claim claim = bots[seat].chooseClaim(round, seat, options);
                            if (claim != null) {
                                decisions.put(seat, claim);
                            }
                        }
                    }
                    round.resolveClaims(decisions);
                }
            }
        }
    }

    /** 同樣的局面，兩個策略有幾次決定不一樣。 */
    private static int differences(Strategy a, Strategy b, List<TableView> turns, List<ClaimChoice> claims) {
        int count = 0;
        for (TableView view : turns) {
            count += a.chooseDiscard(view).equals(b.chooseDiscard(view)) ? 0 : 1;
        }
        for (ClaimChoice choice : claims) {
            count += Objects.equals(a.chooseClaim(choice.view(), choice.options()),
                    b.chooseClaim(choice.view(), choice.options())) ? 0 : 1;
        }
        return count;
    }

    /** 只換籌碼與圈風，其他都一樣。opponents 三家同樣的籌碼。 */
    private static TableView withStanding(TableView view, int roundWind, int chips, int opponentChips) {
        List<Opponent> opponents = new ArrayList<>();
        for (Opponent each : view.opponents()) {
            opponents.add(new Opponent(each.seat(), each.openMelds(), each.concealedKongs(), each.flowers(), each.discards(),
                    opponentChips, each.listening()));
        }
        return new TableView(view.seat(), view.dealer(), view.streak(), roundWind, view.seatWind(), view.liveTiles(),
                view.hand(), view.melds(), view.flowers(), view.discards(), opponents, view.history(),
                view.claimTile(), view.discarder(), view.robbing(), chips);
    }

    private static void personalities() {
        List<TableView> turns = new ArrayList<>();
        List<ClaimChoice> claims = new ArrayList<>();
        collectDecisions(turns, claims);

        test("個性：一般以上的等級，每種個性都會改變一些決定", () -> {
            for (BotLevel level : List.of(BotLevel.NORMAL, BotLevel.AMATEUR, BotLevel.PRO)) {
                Strategy balanced = Strategy.of(level, Personality.BALANCED, 100, 20, new Random(1));
                for (Personality personality : List.of(Personality.BOLD, Personality.CAUTIOUS, Personality.GREEDY)) {
                    Strategy other = Strategy.of(level, personality, 100, 20, new Random(1));
                    check(differences(balanced, other, turns, claims) > 0, level + personality.label() + " 和穩健完全一樣");
                }
            }
        });

        test("個性：預設是穩健，初學者沒有個性之分", () -> {
            eq(0, differences(strategy(BotLevel.PRO), Strategy.of(BotLevel.PRO, Personality.BALANCED, 100, 20, new Random(1)),
                    turns, claims), "職業預設穩健");
            eq(0, differences(strategy(BotLevel.BEGINNER), Strategy.of(BotLevel.BEGINNER, Personality.BOLD, 100, 20, new Random(1)),
                    turns, claims), "初學者");
        });

        test("籌碼局勢：職業最後一圈領先、落後、籌碼快沒了，打法都會變", () -> {
            Strategy pro = strategy(BotLevel.PRO);
            int differs = 0;
            int differsLeading = 0;
            int differsTrailing = 0;
            for (TableView view : turns) {
                Tile base = pro.chooseDiscard(withStanding(view, 4, 20_000, 20_000));
                differsLeading += base.equals(pro.chooseDiscard(withStanding(view, 4, 30_000, 20_000))) ? 0 : 1;
                differsTrailing += base.equals(pro.chooseDiscard(withStanding(view, 4, 10_000, 20_000))) ? 0 : 1;
                differs += pro.chooseDiscard(withStanding(view, 1, 20_000, 20_000))
                        .equals(pro.chooseDiscard(withStanding(view, 1, 500, 20_000))) ? 0 : 1;
            }
            check(differsLeading > 0, "領先要變保守");
            check(differsTrailing > 0, "落後要變積極");
            check(differs > 0, "籌碼快沒了要保命");
        });

        test("籌碼局勢：差距不大或還沒到最後一圈，職業照原本打", () -> {
            Strategy pro = strategy(BotLevel.PRO);
            for (TableView view : turns) {
                eq(pro.chooseDiscard(withStanding(view, 2, 20_000, 20_000)),
                        pro.chooseDiscard(withStanding(view, 2, 30_000, 20_000)), "第二圈領先");
                eq(pro.chooseDiscard(withStanding(view, 4, 20_000, 20_000)),
                        pro.chooseDiscard(withStanding(view, 4, 20_100, 20_000)), "最後一圈小贏");
            }
        });

        test("每家電腦都有個性，真人的位置固定是穩健，同一個種子結果一樣", () -> {
            Set<Personality> seen = new HashSet<>();
            for (long seed = 0; seed < 40; seed++) {
                GameSession session = new GameSession(new GameConfig("測試", 100_000, 100, 20, BotLevel.PRO), new Random(seed));
                GameSession again = new GameSession(new GameConfig("測試", 100_000, 100, 20, BotLevel.PRO), new Random(seed));
                eq(Personality.BALANCED, session.personality(0), "真人");
                for (int seat = 1; seat < 4; seat++) {
                    seen.add(session.personality(seat));
                    eq(session.personality(seat), again.personality(seat), "同一個種子");
                }
            }
            eq(Personality.values().length, seen.size(), "四種個性都會出現");
        });
    }

    private static void shantenValues() {
        test("向聽：胡牌 -1、聽牌 0、差一張 1", () -> {
            eq(-1, shanten("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p", 5), "胡牌");
            eq(0, shanten("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s 9p9p", 5), "聽牌");
            eq(1, shanten("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s 9p9p 1z", 5), "差一張");
        });
        test("向聽：亮出面子後只算剩下要湊的", () -> {
            eq(0, shanten("5s6s 9p9p", 1), "單缺一組");
            eq(0, shanten("9p", 0), "單騎");
            eq(-1, shanten("9p9p", 0), "只差眼睛就胡");
        });
        test("向聽與胡牌判定、聽牌判定一致", () -> {
            Random random = new Random(77);
            List<Tile> deck = new ArrayList<>();
            for (int i = 0; i < Tile.KINDS; i++) {
                deck.addAll(Collections.nCopies(4, Tile.fromIndex(i)));
            }
            for (int round = 0; round < 3000; round++) {
                Collections.shuffle(deck, random);
                int sets = random.nextInt(6);
                List<Tile> tiles = new ArrayList<>(deck.subList(0, 3 * sets + 2));
                if (round % 2 == 0) {
                    tiles = winningHand(random, sets);
                }
                int[] full = counts(tiles);
                eq(HandAnalyzer.canWin(tiles), Shanten.of(full, sets) == -1, "胡牌判定 " + tiles);
                List<Tile> reduced = new ArrayList<>(tiles);
                reduced.remove(random.nextInt(reduced.size()));
                int[] less = counts(reduced);
                int value = Shanten.of(less, sets);
                boolean holdsFour = false;
                for (int count : less) {
                    holdsFour |= count == 4;
                }
                if (!HandAnalyzer.waits(reduced).isEmpty()) {
                    eq(0, value, "聽牌 " + reduced);
                } else if (!holdsFour) {
                    check(value != 0, "向聽 0 卻沒有聽的牌 " + reduced);
                }
            }
        });
    }

    /** 隨機湊一副真的胡牌手牌，確保胡牌案例夠多。 */
    private static List<Tile> winningHand(Random random, int sets) {
        int[] counts = new int[Tile.KINDS];
        List<Tile> tiles = new ArrayList<>();
        int pair = random.nextInt(Tile.KINDS);
        counts[pair] += 2;
        for (int i = 0; i < sets; i++) {
            while (true) {
                if (random.nextBoolean()) {
                    int triplet = random.nextInt(Tile.KINDS);
                    if (counts[triplet] <= 1) {
                        counts[triplet] += 3;
                        break;
                    }
                } else {
                    int first = random.nextInt(27);
                    if (first % 9 <= 6 && counts[first] < 4 && counts[first + 1] < 4 && counts[first + 2] < 4) {
                        counts[first]++;
                        counts[first + 1]++;
                        counts[first + 2]++;
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < Tile.KINDS; i++) {
            tiles.addAll(Collections.nCopies(counts[i], Tile.fromIndex(i)));
        }
        return tiles;
    }

    private static void noCheating() {
        test("視野只有合法資訊：TableView 與策略介面都碰不到 Round、Player、Wall", () -> {
            for (RecordComponent component : TableView.class.getRecordComponents()) {
                String type = component.getGenericType().getTypeName();
                check(!type.contains("Round") && !type.contains("Player") && !type.contains("Wall"),
                        "TableView 不可持有 " + type);
            }
            for (RecordComponent component : Opponent.class.getRecordComponents()) {
                check(!component.getName().contains("concealed") || component.getType() == int.class,
                        "別家的暗牌只能知道數量: " + component.getName());
            }
            for (Method method : Strategy.class.getDeclaredMethods()) {
                for (Class<?> parameter : method.getParameterTypes()) {
                    check(parameter != Round.class && parameter != Player.class, "策略不可以拿到 " + parameter);
                }
            }
        });

        test("別家暗槓看不到是什麼牌，也不會從剩餘張數扣掉", () -> {
            Round round = Table.dealer(1).hand(1, "1w1w1w1w").draws("2w").start();
            round.declareConcealedKong(1, tile("1w"));
            TableView view = TableView.of(round, 0);
            Opponent konger = view.opponents().get(0);
            eq(1, konger.seat(), "座位");
            eq(1, konger.concealedKongs(), "暗槓數");
            check(konger.openMelds().isEmpty(), "暗槓不可以出現在亮牌裡");
            eq(4, view.unseen()[tile("1w").index()], "暗槓的牌仍算沒看過");
        });

        for (BotLevel level : BotLevel.values()) {
            test(level + "：調換別家手牌後，決定完全不變", () -> {
                Random random = new Random(1000 + level.ordinal());
                ComputerBot bot = new ComputerBot(BotLevel.NORMAL, 100, 20, random);
                int compared = 0;
                for (int game = 0; game < 40; game++) {
                    GameSession session = new GameSession(new GameConfig("測試", 1_000_000, 100, 20, level), random);
                    Round round = session.newRound(message -> { });
                    round.start();
                    while (round.phase() != Round.Phase.FINISHED) {
                        if (round.phase() == Round.Phase.TURN) {
                            int seat = round.current();
                            compared += sameAfterSwap(round, seat, level, random);
                            bot.playTurn(round, seat, round.turnOptions(seat));
                        } else {
                            Map<Integer, Claim> decisions = new HashMap<>();
                            for (int seat = 0; seat < 4; seat++) {
                                List<Claim> options = round.claimOptions(seat);
                                if (!options.isEmpty()) {
                                    compared += sameAfterSwap(round, seat, level, random);
                                    Claim claim = bot.chooseClaim(round, seat, options);
                                    if (claim != null) {
                                        decisions.put(seat, claim);
                                    }
                                }
                            }
                            round.resolveClaims(decisions);
                        }
                    }
                }
                check(compared > 500, "比對次數太少: " + compared);
            });
        }
    }

    /** 把另外兩家的暗牌換一張：對這個座位來說桌面沒有任何變化，決定就必須一樣。 */
    private static int sameAfterSwap(Round round, int seat, BotLevel level, Random random) {
        Player a = round.player((seat + 1) % 4);
        Player b = round.player((seat + 2) % 4);
        Tile fromA = a.concealed().get(random.nextInt(a.concealed().size()));
        Tile fromB = b.concealed().get(random.nextInt(b.concealed().size()));
        if (fromA.equals(fromB)) {
            return 0;
        }
        TableView before = TableView.of(round, seat);
        Object decisionBefore = decide(round, seat, level, before);
        a.removeFromHand(fromA);
        b.removeFromHand(fromB);
        a.addToHand(fromB);
        b.addToHand(fromA);
        TableView after = TableView.of(round, seat);
        Object decisionAfter = decide(round, seat, level, after);
        a.removeFromHand(fromB);
        b.removeFromHand(fromA);
        a.addToHand(fromA);
        b.addToHand(fromB);
        eq(before, after, "視野不應該因別家暗牌改變");
        eq(decisionBefore, decisionAfter, "決定不應該因別家暗牌改變");
        return 1;
    }

    private static Object decide(Round round, int seat, BotLevel level, TableView view) {
        Strategy strategy = strategy(level);
        if (round.phase() == Round.Phase.TURN) {
            return strategy.chooseDiscard(view);
        }
        List<Claim> melds = new ArrayList<>(round.claimOptions(seat));
        melds.removeIf(claim -> claim.type() == Claim.Type.WIN);
        if (melds.isEmpty()) {
            return "沒有選項";
        }
        Claim claim = strategy.chooseClaim(view, melds);
        return claim == null ? "不宣告" : claim;
    }

    private static final String SCATTERED = "1w4w7w 2p5p8p 3s5s9s 9w9p 1s6s 2w6p 4s 7s";

    private static void beginner() {
        test("初學者只看手牌：牌河、亮牌、牌牆怎麼變，決定都一樣", () -> {
            Tile plain = strategy(BotLevel.BEGINNER).chooseDiscard(quiet(SCATTERED, List.of()));
            List<Opponent> busy = List.of(opponent(1, 4, "5s5s"), opponent(2, 3, "5s"), opponent(3, 0, "1w"));
            Tile busyTable = strategy(BotLevel.BEGINNER).chooseDiscard(table(SCATTERED, List.of(), busy, historyOf(busy), 3));
            eq(plain, busyTable, "桌面不同時的決定");
        });
        test("初學者先打孤張，碰得到就碰，即使拆了聽牌", () -> {
            Tile thrown = strategy(BotLevel.BEGINNER).chooseDiscard(quiet("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s 9p9p 1z", List.of()));
            eq(tile("1z"), thrown, "孤張字牌");
            Claim pong = new Claim(Claim.Type.PONG, tile("9p"), hand("9p9p"));
            TableView view = quiet("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s 9p9p", List.of());
            eq(pong, strategy(BotLevel.BEGINNER).chooseClaim(view, List.of(pong)), "見碰就碰");
        });
    }

    private static void normal() {
        test("三家各用自己的等級：同一手牌，初學者會碰、一般不碰，換座位也一樣", () -> {
            for (int seat = 1; seat <= 3; seat++) {
                List<BotLevel> beginnerHere = new ArrayList<>(List.of(BotLevel.NORMAL, BotLevel.NORMAL, BotLevel.NORMAL));
                beginnerHere.set(seat - 1, BotLevel.BEGINNER);
                List<BotLevel> normalHere = new ArrayList<>(List.of(BotLevel.BEGINNER, BotLevel.BEGINNER, BotLevel.BEGINNER));
                normalHere.set(seat - 1, BotLevel.NORMAL);

                Round round = Table.dealer(0).hand(0, "9p").hand(seat, "1w2w3w4w5w6w7p8p9p2s3s4s5s6s9p9p").draws("1z").start();
                round.discard(0, tile("9p"));
                List<Claim> pong = new ArrayList<>(round.claimOptions(seat));
                pong.removeIf(claim -> claim.type() != Claim.Type.PONG);
                check(new SeatedBots(beginnerHere, 100, 20, new Random(1)).chooseClaim(round, seat, pong) != null,
                        "座位 " + seat + " 是初學者");
                check(new SeatedBots(normalHere, 100, 20, new Random(1)).chooseClaim(round, seat, pong) == null,
                        "座位 " + seat + " 是一般");
            }
        });

        test("一般不會拆掉已經聽牌的手牌去碰", () -> {
            Claim pong = new Claim(Claim.Type.PONG, tile("9p"), hand("9p9p"));
            TableView view = quiet("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s 9p9p", List.of());
            check(strategy(BotLevel.NORMAL).chooseClaim(view, List.of(pong)) == null, "聽牌時不碰");
        });
        test("一般碰了會變快就碰", () -> {
            Claim pong = new Claim(Claim.Type.PONG, tile("9p"), hand("9p9p"));
            TableView view = quiet("1w2w3w 4w5w6w 7p8p 2s3s4s 5s5s 9p9p 1z", List.of());
            eq(pong, strategy(BotLevel.NORMAL).chooseClaim(view, List.of(pong)), "變快就碰");
        });
        test("一般不會退向聽：打掉不影響聽牌的牌", () -> {
            eq(tile("1z"), strategy(BotLevel.NORMAL).chooseDiscard(quiet("1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s 9p9p 1z", List.of())), "打字牌");
        });
        test("一般不看牌河：聽的牌全部打光了也不知道", () -> {
            String tiles = "1p1p 2w4w6w";
            List<Meld> melds = List.of(chow("1s2s3s"), chow("4s5s6s"), chow("7s8s9s"), chow("1s2s3s"));
            List<Opponent> dead3 = List.of(opponent(1, 0, "3w3w3w3w"), opponent(2, 0, ""), opponent(3, 0, ""));
            List<Opponent> dead5 = List.of(opponent(1, 0, "5w5w5w5w"), opponent(2, 0, ""), opponent(3, 0, ""));
            Tile first = strategy(BotLevel.NORMAL).chooseDiscard(table(tiles, melds, dead3, historyOf(dead3), 30));
            Tile second = strategy(BotLevel.NORMAL).chooseDiscard(table(tiles, melds, dead5, historyOf(dead5), 30));
            eq(first, second, "看不到牌河，兩種桌面決定相同");
        });
    }

    private static void amateur() {
        test("業餘會算剩餘張數：不聽已經打光的牌", () -> {
            String tiles = "1p1p 2w4w6w";
            List<Meld> melds = List.of(chow("1s2s3s"), chow("4s5s6s"), chow("7s8s9s"), chow("1s2s3s"));
            List<Opponent> dead3 = List.of(opponent(1, 0, "3w3w3w3w"), opponent(2, 0, ""), opponent(3, 0, ""));
            List<Opponent> dead5 = List.of(opponent(1, 0, "5w5w5w5w"), opponent(2, 0, ""), opponent(3, 0, ""));
            eq(tile("2w"), strategy(BotLevel.AMATEUR).chooseDiscard(table(tiles, melds, dead3, historyOf(dead3), 40)), "3w 打光了，改聽 5w");
            eq(tile("6w"), strategy(BotLevel.AMATEUR).chooseDiscard(table(tiles, melds, dead5, historyOf(dead5), 40)), "5w 打光了，改聽 3w");
        });
        test("業餘看到有人快聽而手牌還遠時打現物，一般不會", () -> {
            List<Opponent> threat = List.of(opponent(1, 3, "5p"), opponent(2, 0, ""), opponent(3, 0, ""));
            TableView view = table(SCATTERED, List.of(), threat, historyOf(threat), 20);
            eq(tile("5p"), strategy(BotLevel.AMATEUR).chooseDiscard(view), "打現物");
            check(!tile("5p").equals(strategy(BotLevel.NORMAL).chooseDiscard(view)), "一般不防守");
        });
        test("業餘沒有人威脅時專心做牌", () -> {
            List<Opponent> calm = List.of(opponent(1, 0, "5p"), opponent(2, 0, ""), opponent(3, 0, ""));
            TableView view = table(SCATTERED, List.of(), calm, historyOf(calm), 50);
            check(!tile("5p").equals(strategy(BotLevel.AMATEUR).chooseDiscard(view)), "沒有威脅不必打現物");
        });
    }

    private static void pro() {
        test("職業在胡不了又有人快聽時棄胡，打對方打過的牌", () -> {
            List<Opponent> threat = List.of(opponent(1, 4, "5p"), opponent(2, 3, "5p"), opponent(3, 3, "5p"));
            TableView view = table(SCATTERED, List.of(), threat, historyOf(threat), 6);
            eq(tile("5p"), strategy(BotLevel.PRO).chooseDiscard(view), "棄胡打現物");
        });
        test("職業自己聽牌又還早時不會被嚇到拆牌", () -> {
            String tenpai = "1w2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s 9p9p 5w";
            List<Opponent> threat = List.of(opponent(1, 2, "1z"), opponent(2, 0, ""), opponent(3, 0, ""));
            eq(tile("5w"), strategy(BotLevel.PRO).chooseDiscard(table(tenpai, List.of(), threat, historyOf(threat), 50)), "留住聽牌");
        });
        test("職業會利用別家打牌的時間差：剛打過而對手沒胡的牌就算安全", () -> {
            List<Opponent> threat = List.of(opponent(1, 4, "1z"), opponent(2, 0, ""), opponent(3, 0, ""));
            List<DiscardEvent> history = new ArrayList<>();
            history.add(new DiscardEvent(1, tile("1z")));
            history.add(new DiscardEvent(2, tile("5p")));
            history.add(new DiscardEvent(3, tile("3z")));
            TableView passed = table(SCATTERED, List.of(), threat, history, 6);
            eq(tile("5p"), strategy(BotLevel.PRO).chooseDiscard(passed), "別家打過他沒胡");
        });
        test("職業會看對手的花色：亮的都是條子、牌河沒有條子時，條子比較危險", () -> {
            Opponent flush = new Opponent(1, List.of(chow("1s2s3s"), chow("4s5s6s"), chow("7s8s9s")), 0, List.of(),
                    hand("1w2w3w4w5w6p7p8p"), PLENTY_OF_CHIPS, false);
            List<Opponent> opponents = List.of(flush, opponent(2, 0, ""), opponent(3, 0, ""));
            TableView view = table("2s 4p 6w 1z2z", List.of(chow("1p2p3p"), chow("7w8w9w"), chow("4w5w6w"), chow("7p8p9p")),
                    opponents, historyOf(opponents), 6);
            check(!tile("2s").equals(strategy(BotLevel.PRO).chooseDiscard(view)), "不打可能放槍給一色的牌");
        });
    }

    private static void allLevels() {
        for (BotLevel level : BotLevel.values()) {
            test(level + "：能自摸就自摸，別人放槍就胡", () -> {
                ComputerBot bot = new ComputerBot(level, 100, 20, new Random(3));
                Round selfWin = Table.dealer(0).hand(0, "1w2w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p").draws("3w").start();
                bot.playTurn(selfWin, 0, selfWin.turnOptions(0));
                eq(Round.Phase.FINISHED, selfWin.phase(), "自摸");
                eq(0, selfWin.result().winner(), "贏家");

                Round ron = Table.dealer(0).hand(1, "1w2w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p").draws("3w").start();
                ron.discard(0, tile("3w"));
                List<Claim> options = ron.claimOptions(1);
                eq(Claim.Type.WIN, bot.chooseClaim(ron, 1, options).type(), "胡牌");
            });
            test(level + "：所有選項都合法，一場下來輸贏總和為零", () -> {
                Map<BotLevel, Tournament.Score> scores =
                        Tournament.play(new BotLevel[] {level, level, level, level}, 300, 8 + level.ordinal());
                eq(0L, scores.get(level).money, "總和");
            });
        }
    }

    private static void ladder() {
        test("等級由弱到強：同桌對打時成績符合順序", () -> {
            Map<BotLevel, Tournament.Score> scores = Tournament.play(BotLevel.values(), 8000, 2026);
            scores.forEach((level, score) -> System.out.println("  " + level + "：" + score));
            double beginner = scores.get(BotLevel.BEGINNER).moneyPerRound();
            double normal = scores.get(BotLevel.NORMAL).moneyPerRound();
            double amateur = scores.get(BotLevel.AMATEUR).moneyPerRound();
            double pro = scores.get(BotLevel.PRO).moneyPerRound();
            check(beginner < normal, "初學者應該輸給一般");
            check(normal < amateur, "一般應該輸給業餘");
            check(amateur < pro, "業餘應該輸給職業");
        });
    }
}
