package ezmj.online;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ezmj.engine.Claim;
import ezmj.engine.GameView;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundRecord;
import ezmj.engine.RoundResult;
import ezmj.engine.RoundView;
import ezmj.engine.Settlement;
import ezmj.engine.TurnOptions;
import ezmj.model.GameRecord;
import ezmj.model.Gender;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Suit;
import ezmj.model.Tile;
import ezmj.rules.ScoreItem;
import ezmj.rules.ScoreResult;

/**
 * 服務器送來的局面，翻成畫面看得懂的樣子。這裡只抄服務器的結果，不算規則。
 * 對外的座位都是「畫面座位」：0 是自己，1、2、3 依行牌順序往後，和單機一樣，服務器的座位只出現在訊息裡。
 */
public final class OnlineGame implements GameView, RoundView {

    private static final int SEATS = 4;
    /** 別家的暗牌。服務器只給張數，畫面也只畫牌背，所以任何一張都可以。 */
    public static final Tile HIDDEN = Tile.of(Suit.WAN, 1);

    /** 輪到自己的詢問。readyDiscards 是打哪張牌會聽哪些牌。 */
    public record TurnAsk(int waitId, TurnOptions options, Map<Tile, List<Tile>> readyDiscards) {
    }

    /** 可以宣告的詢問。回覆時用 options 的索引指定。 */
    public record ClaimAsk(int waitId, List<Claim> options) {
    }

    /** 一局結束時服務器說的事，其餘都放進局面了。 */
    public record RoundEnd(Settlement settlement, boolean gameOver, int nextRoundMs) {
    }

    private final Player[] players = new Player[SEATS];
    private final String[] names = {"", "", "", ""};
    private final boolean[] humans = new boolean[SEATS];
    private final boolean[] connected = new boolean[SEATS];
    private final Gender[] genders = {Gender.FEMALE, Gender.FEMALE, Gender.FEMALE, Gender.FEMALE};
    private final List<RoundRecord> history = new ArrayList<>();
    private final Map<Integer, Map<String, Object>> standings = new HashMap<>();

    private RoomInfo room;
    private int mySeat;
    private int roundNumber;
    private Round.Phase phase = Round.Phase.TURN;
    private int current;
    private int dealer;
    private int streak;
    private int roundWind = 1;
    /** 舊服務器沒有送就是 0。 */
    private int handWind;
    private int liveTiles;
    private Tile drawn;
    /** 剛摸牌的別家，他的最後一張牌要和手牌分開畫。 */
    private int drawnSeat = -1;
    private Tile claimTile;
    private int discarder = -1;
    private boolean robbing;
    private RoundResult result;
    private List<Tile> listeningWaits;
    private boolean over;
    private int finalRounds;
    private int finalDrawnGames;

    public OnlineGame() {
        for (int seat = 0; seat < SEATS; seat++) {
            players[seat] = new Player("", seat, false, Gender.FEMALE, 0);
        }
    }

    private int screen(int serverSeat) {
        return serverSeat < 0 ? -1 : Math.floorMod(serverSeat - mySeat, SEATS);
    }

    /** 名字、連線狀態與房間設定。局面不變，已經畫出來的名字會跟著換。 */
    public void applyRoom(RoomInfo info) {
        room = info;
        mySeat = info.yourSeat();
        for (RoomInfo.Seat seat : info.seats()) {
            int at = screen(seat.seat());
            names[at] = seat.name();
            humans[at] = seat.human();
            connected[at] = seat.connected();
            genders[at] = seat.gender() == null ? Gender.FEMALE : seat.gender();
        }
        for (int seat = 0; seat < SEATS; seat++) {
            Player old = players[seat];
            players[seat] = player(seat, old.concealed(), old.melds(), old.flowers(), old.discards(), old.chips());
        }
    }

    /** roundStart 與 state 的 handWind。舊服務器沒有這個欄位，不在 1 到 4 之間也一樣當成 0，畫面退回只顯示圈風。 */
    private static int handWind(Map<String, Object> message) {
        if (message.get("handWind") == null) {
            return 0;
        }
        int wind = Fields.integer(message, "handWind");
        return wind >= 1 && wind <= SEATS ? wind : 0;
    }

    public void applyRoundStart(Map<String, Object> message) {
        roundNumber = Fields.integer(message, "number");
        dealer = screen(Fields.integer(message, "dealer"));
        roundWind = Fields.integer(message, "roundWind");
        handWind = handWind(message);
        streak = Fields.integer(message, "streak");
        phase = Round.Phase.TURN;
        current = dealer;
        drawn = null;
        drawnSeat = -1;
        claimTile = null;
        discarder = -1;
        robbing = false;
        result = null;
        listeningWaits = null;
        for (int seat = 0; seat < SEATS; seat++) {
            players[seat] = player(seat, List.of(), List.of(), List.of(), List.of(), players[seat].chips());
        }
    }

    /** 這個座位的局面。events 是上一份局面之後發生的事件，只用來判斷剛摸牌的是誰。 */
    public void applyState(Map<String, Object> state, List<RoundEvent> events) {
        mySeat = Fields.integer(state, "seat");
        phase = Round.Phase.valueOf(Fields.string(state, "phase"));
        current = screen(Fields.integer(state, "current"));
        dealer = screen(Fields.integer(state, "dealer"));
        streak = Fields.integer(state, "streak");
        roundWind = Fields.integer(state, "roundWind");
        handWind = handWind(state);
        liveTiles = Fields.integer(state, "liveTiles");
        drawn = Fields.tileOrNull(state, "drawn");
        claimTile = Fields.tileOrNull(state, "claimTile");
        discarder = screen(Fields.integer(state, "discarder"));
        robbing = Fields.bool(state, "robbing");
        listeningWaits = Fields.tilesOrNull(state, "listeningWaits");
        for (RoundEvent event : events) {
            if (event.type() == RoundEvent.Type.DRAW) {
                drawnSeat = event.seat();
            } else if (event.type() != RoundEvent.Type.FLOWER) {
                drawnSeat = -1;
            }
        }

        players[0] = player(0, Fields.tiles(state, "hand"), Fields.melds(state, "melds"), Fields.tiles(state, "flowers"),
                Fields.tiles(state, "discards"), Fields.integer(state, "chips"));
        for (Map<String, Object> opponent : Fields.objects(state, "opponents")) {
            int seat = screen(Fields.integer(opponent, "seat"));
            // 暗槓放在前面，最後一組才會是剛吃碰槓的，動畫要飛到那裡
            List<Meld> melds = new ArrayList<>();
            for (int kong = 0; kong < Fields.integer(opponent, "concealedKongs"); kong++) {
                melds.add(new Meld(Meld.Type.CONCEALED_KONG, Collections.nCopies(4, HIDDEN)));
            }
            melds.addAll(Fields.melds(opponent, "melds"));
            players[seat] = player(seat, Collections.nCopies(Fields.integer(opponent, "handCount"), HIDDEN), melds,
                    Fields.tiles(opponent, "flowers"), Fields.tiles(opponent, "discards"), Fields.integer(opponent, "chips"));
        }
    }

    /** 這局打完，四家的牌都攤開。陣列依服務器的座位排列，放進來時要轉成畫面座位。 */
    public RoundEnd applyRoundEnd(Map<String, Object> message) {
        boolean drawnGame = Fields.bool(message, "drawnGame");
        if (drawnGame) {
            result = RoundResult.drawnGame();
        } else {
            List<ScoreItem> items = new ArrayList<>();
            for (Map<String, Object> item : Fields.objects(message, "items")) {
                items.add(new ScoreItem(Fields.string(item, "name"), Fields.integer(item, "tai")));
            }
            result = new RoundResult(screen(Fields.integer(message, "winner")), screen(Fields.integer(message, "from")),
                    Fields.tile(message, "winTile"), new ScoreResult(items));
        }
        phase = Round.Phase.FINISHED;
        drawn = null;
        drawnSeat = -1;
        claimTile = null;
        robbing = false;

        int[] chips = bySeat(Fields.integers(message, "chips"));
        for (Map<String, Object> hand : Fields.objects(message, "hands")) {
            int seat = screen(Fields.integer(hand, "seat"));
            players[seat] = player(seat, Fields.tiles(hand, "hand"), Fields.melds(hand, "melds"),
                    Fields.tiles(hand, "flowers"), players[seat].discards(), chips[seat]);
        }
        int[] deltas = bySeat(Fields.integers(message, "deltas"));
        Settlement settlement = new Settlement(deltas, bySeat(Fields.integers(message, "tais")));
        history.add(new RoundRecord(roundNumber > 0 ? roundNumber : history.size() + 1, roundWind, dealer, streak, result, deltas));
        return new RoundEnd(settlement, Fields.bool(message, "gameOver"), Fields.integer(message, "nextRoundMs"));
    }

    private int[] bySeat(List<Integer> values) {
        if (values.size() != SEATS) {
            throw new IllegalArgumentException("要有四家的資料：" + values);
        }
        int[] seats = new int[SEATS];
        for (int serverSeat = 0; serverSeat < SEATS; serverSeat++) {
            seats[screen(serverSeat)] = values.get(serverSeat);
        }
        return seats;
    }

    public void applyGameOver(Map<String, Object> message) {
        over = true;
        finalRounds = Fields.integer(message, "rounds");
        finalDrawnGames = Fields.integer(message, "drawnGames");
        for (Map<String, Object> standing : Fields.objects(message, "standings")) {
            standings.put(screen(Fields.integer(standing, "seat")), standing);
        }
    }

    /** 整場打完，有最後的成績了。 */
    public boolean isOver() {
        return over;
    }

    /** 服務器不轉發引擎的 log，紀錄的文字從事件自己組。沒有要記的事件是 null。 */
    public String describe(RoundEvent event) {
        String who = event.seat() < 0 ? "" : names[event.seat()];
        switch (event.type()) {
            case DISCARD:        return who + " 打出 " + event.tile().label();
            case CHOW:           return who + " 吃 " + event.tile().label();
            case PONG:           return who + " 碰 " + event.tile().label();
            case KONG:           return who + " 槓 " + event.tile().label();
            case CONCEALED_KONG: return who + " 暗槓";
            case ADD_KONG:       return who + " 加槓 " + event.tile().label();
            case FLOWER:         return who + " 補花 " + event.tile().label();
            case DRAWN_GAME:     return "流局";
            case SELF_WIN:
                return result != null && result.isFlowerWin() ? who + " " + flowerWinName() : who + " 自摸 " + event.tile().label();
            case WIN:
                return result != null && result.isFlowerWin() ? who + " " + flowerWinName()
                        : who + " 胡 " + names[event.from()] + " 的 " + event.tile().label();
            default:             return null;
        }
    }

    private String flowerWinName() {
        return result.score().items().get(0).name();
    }

    /** 服務器的事件翻成畫面座位。別家摸的牌看不到，用暗牌代替。 */
    public RoundEvent parseEvent(Map<String, Object> message) {
        RoundEvent.Type type = RoundEvent.Type.valueOf(Fields.string(message, "kind"));
        Tile tile = Fields.tileOrNull(message, "tile");
        return new RoundEvent(type, screen(Fields.integer(message, "seat")),
                type == RoundEvent.Type.DRAW && tile == null ? HIDDEN : tile, screen(Fields.integer(message, "from")));
    }

    public static TurnAsk parseTurn(Map<String, Object> message) {
        Map<Tile, List<Tile>> ready = new LinkedHashMap<>();
        Map<String, Object> discards = Fields.object(message, "readyDiscards");
        for (String code : discards.keySet()) {
            ready.put(Fields.tile(code), Fields.tiles(discards, code));
        }
        return new TurnAsk(Fields.integer(message, "waitId"), new TurnOptions(Fields.bool(message, "canSelfWin"),
                Fields.tiles(message, "concealedKongs"), Fields.tiles(message, "addKongs")), ready);
    }

    public static ClaimAsk parseClaim(Map<String, Object> message) {
        List<Claim> options = new ArrayList<>();
        for (Map<String, Object> option : Fields.objects(message, "options")) {
            options.add(new Claim(Claim.Type.valueOf(Fields.string(option, "kind")), Fields.tile(option, "tile"),
                    Fields.tiles(option, "fromHand")));
        }
        return new ClaimAsk(Fields.integer(message, "waitId"), options);
    }

    public RoomInfo room() {
        return room;
    }

    public List<Tile> listeningWaits() {
        return listeningWaits;
    }

    /** 斷線的人名字後面標出來，全桌都看得到。 */
    private Player player(int seat, List<Tile> hand, List<Meld> melds, List<Tile> flowers, List<Tile> discards, int chips) {
        String name = humans[seat] && !connected[seat] && room != null && room.playing() ? names[seat] + "（斷線）" : names[seat];
        Player player = new Player(name, seat, humans[seat], genders[seat], chips);
        hand.forEach(player::addToHand);
        melds.forEach(player::addMeld);
        flowers.forEach(player::addFlower);
        discards.forEach(player::addDiscard);
        return player;
    }

    @Override
    public Player player(int seat) {
        return players[seat];
    }

    @Override
    public int basePay() {
        return room.settings().basePay();
    }

    @Override
    public int taiPay() {
        return room.settings().taiPay();
    }

    @Override
    public String traits(int seat) {
        if (seat == 0) {
            return null;
        }
        if (!humans[seat]) {
            return "電腦";
        }
        return connected[seat] ? "玩家" : "斷線中，由電腦代打";
    }

    /** 服務器在整場結束時才給成績，那之前都是 0。 */
    @Override
    public GameRecord record(int seat, String date) {
        Map<String, Object> standing = standings.get(seat);
        RoomSettings settings = room.settings();
        if (standing == null) {
            return new GameRecord(names[seat], date, 0, 0, 0, 0, settings.botLevels(), settings.basePay(), settings.taiPay());
        }
        return new GameRecord(Fields.string(standing, "name"), date, Fields.integer(standing, "wins"),
                Fields.integer(standing, "selfDraws"), Fields.integer(standing, "winnings"),
                Fields.integer(standing, "dealIns"), settings.botLevels(), settings.basePay(), settings.taiPay());
    }

    @Override
    public int roundsPlayed() {
        return over ? finalRounds : history.size();
    }

    @Override
    public int drawnGames() {
        return over ? finalDrawnGames : (int) history.stream().filter(record -> record.result().isDrawnGame()).count();
    }

    @Override
    public List<RoundRecord> history() {
        return List.copyOf(history);
    }

    @Override
    public Round.Phase phase() {
        return phase;
    }

    @Override
    public int current() {
        return current;
    }

    /** 別家摸的牌看不到，只知道他有一張剛摸的牌。 */
    @Override
    public Tile drawn() {
        if (current == 0) {
            return drawn;
        }
        return phase == Round.Phase.TURN && current == drawnSeat ? HIDDEN : null;
    }

    @Override
    public Tile claimTile() {
        return claimTile;
    }

    @Override
    public int discarder() {
        return discarder;
    }

    @Override
    public boolean isRobbing() {
        return robbing;
    }

    @Override
    public int dealer() {
        return dealer;
    }

    @Override
    public int roundWind() {
        return roundWind;
    }

    @Override
    public int handWind() {
        return handWind;
    }

    @Override
    public int streak() {
        return streak;
    }

    /** 門風跟著莊家走，服務器只給自己的，別家的從座位順序就知道。 */
    @Override
    public int seatWind(int seat) {
        return Math.floorMod(seat - dealer, SEATS) + 1;
    }

    @Override
    public int liveTiles() {
        return liveTiles;
    }

    @Override
    public RoundResult result() {
        return result;
    }

    @Override
    public String toString() {
        return "第 " + roundNumber + " 局，階段 " + phase + "，輪到畫面座位 " + current + "，服務器座位 " + mySeat
                + "，房間 " + (room == null ? "無" : room.code()) + Arrays.toString(names);
    }
}
