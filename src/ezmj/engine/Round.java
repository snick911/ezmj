package ezmj.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.model.Wall;
import ezmj.rules.HandAnalyzer;
import ezmj.rules.Scorer;
import ezmj.rules.WinContext;

/**
 * 一局牌的狀態機，不依賴 UI。
 * TURN：current 必須打牌或宣告；CLAIMS：等待其他家對 claimTile 宣告；FINISHED：已結束。
 */
public final class Round {

    public enum Phase { TURN, CLAIMS, FINISHED }

    private static final int SEATS = 4;
    private static final int STARTING_HAND = 16;

    private final Player[] players;
    private final Wall wall;
    private final int dealer;
    private final int roundWind;
    private final int streak;
    private final Consumer<String> log;
    private final int[] turnsTaken = new int[SEATS];
    private final List<DiscardEvent> discardHistory = new ArrayList<>();

    private Consumer<RoundEvent> listener = event -> { };
    private Phase phase = Phase.TURN;
    private int current;
    private Tile drawn;
    private Tile claimTile;
    private int discarder = -1;
    private boolean robbing;
    private boolean discardOnly;
    private boolean afterKong;
    private boolean anyClaim;
    private RoundResult result;

    public Round(Player[] players, Wall wall, int dealer, int roundWind, int streak, Consumer<String> log) {
        this.players = players;
        this.wall = wall;
        this.dealer = dealer;
        this.roundWind = roundWind;
        this.streak = streak;
        this.log = log;
        this.current = dealer;
    }

    /** 事件發生時，狀態已經更新。 */
    public void setListener(Consumer<RoundEvent> listener) {
        this.listener = listener;
    }

    /** 發牌、補花，並讓莊家摸第 17 張。 */
    public void start() {
        for (int i = 0; i < STARTING_HAND; i++) {
            for (int offset = 0; offset < SEATS; offset++) {
                players[(dealer + offset) % SEATS].addToHand(wall.drawFront());
            }
        }
        for (int offset = 0; offset < SEATS && phase != Phase.FINISHED; offset++) {
            replaceFlowers(players[(dealer + offset) % SEATS]);
        }
        if (phase != Phase.FINISHED) {
            draw(dealer, false);
        }
    }

    private void replaceFlowers(Player player) {
        while (true) {
            Tile flower = player.concealed().stream().filter(Tile::isFlower).findFirst().orElse(null);
            if (flower == null) {
                return;
            }
            player.removeFromHand(flower);
            player.addFlower(flower);
            log.accept(player.name() + " 補花 " + flower.label());
            if (wall.liveCount() <= 0) {
                finishDrawnGame();
                return;
            }
            player.addToHand(wall.drawBack());
        }
    }

    private void draw(int seat, boolean replacement) {
        current = seat;
        discardOnly = false;
        Player player = players[seat];
        if (wall.liveCount() <= 0) {
            finishDrawnGame();
            return;
        }
        Tile tile = replacement ? wall.drawBack() : wall.drawFront();
        while (tile.isFlower()) {
            player.addFlower(tile);
            log.accept(player.name() + " 補花 " + tile.label());
            if (wall.liveCount() <= 0) {
                finishDrawnGame();
                return;
            }
            tile = wall.drawBack();
        }
        player.addToHand(tile);
        drawn = tile;
        afterKong = replacement;
        phase = Phase.TURN;
    }

    public TurnOptions turnOptions(int seat) {
        requirePhase(Phase.TURN);
        if (seat != current || discardOnly) {
            return TurnOptions.DISCARD_ONLY;
        }
        Player player = players[seat];
        boolean canKong = wall.liveCount() > 0;
        List<Tile> concealedKongs = new ArrayList<>();
        List<Tile> addKongs = new ArrayList<>();
        if (canKong) {
            for (int i = 0; i < Tile.KINDS; i++) {
                if (player.countInHand(Tile.fromIndex(i)) == 4) {
                    concealedKongs.add(Tile.fromIndex(i));
                }
            }
            for (Meld meld : player.melds()) {
                if (meld.type() == Meld.Type.PONG && player.countInHand(meld.first()) > 0) {
                    addKongs.add(meld.first());
                }
            }
        }
        return new TurnOptions(HandAnalyzer.canWin(player.concealed()), concealedKongs, addKongs);
    }

    public void discard(int seat, Tile tile) {
        requireTurn(seat);
        Player player = players[seat];
        player.removeFromHand(tile);
        player.addDiscard(tile);
        discardHistory.add(new DiscardEvent(seat, tile));
        log.accept(player.name() + " 打出 " + tile.label());
        turnsTaken[seat]++;
        claimTile = tile;
        discarder = seat;
        drawn = null;
        afterKong = false;
        discardOnly = false;
        phase = Phase.CLAIMS;
        listener.accept(new RoundEvent(RoundEvent.Type.DISCARD, seat, tile, seat));
    }

    /** 畫面上最右邊的手牌：剛摸的牌排在最右，沒有的話就是排序後的最後一張。 */
    public Tile rightmostTile(int seat) {
        requireTurn(seat);
        List<Tile> hand = players[seat].concealed();
        return drawn != null ? drawn : hand.get(hand.size() - 1);
    }

    public void declareSelfWin(int seat) {
        requireTurn(seat);
        if (!turnOptions(seat).canSelfWin()) {
            throw new IllegalStateException("不能自摸");
        }
        finishWin(seat, -1, drawn, false);
    }

    public void declareConcealedKong(int seat, Tile tile) {
        requireTurn(seat);
        if (!turnOptions(seat).concealedKongs().contains(tile)) {
            throw new IllegalStateException("不能暗槓 " + tile);
        }
        Player player = players[seat];
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            player.removeFromHand(tile);
            tiles.add(tile);
        }
        player.addMeld(new Meld(Meld.Type.CONCEALED_KONG, tiles));
        log.accept(player.name() + " 暗槓");
        anyClaim = true;
        listener.accept(new RoundEvent(RoundEvent.Type.CONCEALED_KONG, seat, null, -1));
        draw(seat, true);
    }

    /** 加槓先進入 CLAIMS，讓其他家有機會搶槓。 */
    public void declareAddKong(int seat, Tile tile) {
        requireTurn(seat);
        if (!turnOptions(seat).addKongs().contains(tile)) {
            throw new IllegalStateException("不能加槓 " + tile);
        }
        Player player = players[seat];
        player.removeFromHand(tile);
        log.accept(player.name() + " 加槓 " + tile.label());
        anyClaim = true;
        claimTile = tile;
        discarder = seat;
        robbing = true;
        drawn = null;
        phase = Phase.CLAIMS;
        listener.accept(new RoundEvent(RoundEvent.Type.ADD_KONG, seat, tile, -1));
    }

    public List<Claim> claimOptions(int seat) {
        requirePhase(Phase.CLAIMS);
        List<Claim> claims = new ArrayList<>();
        if (seat == discarder) {
            return claims;
        }
        Player player = players[seat];
        Tile tile = claimTile;

        List<Tile> withTile = new ArrayList<>(player.concealed());
        withTile.add(tile);
        if (HandAnalyzer.canWin(withTile)) {
            claims.add(new Claim(Claim.Type.WIN, tile, List.of()));
        }
        if (robbing) {
            return claims;
        }

        int same = player.countInHand(tile);
        if (same == 3 && wall.liveCount() > 0) {
            claims.add(new Claim(Claim.Type.KONG, tile, List.of(tile, tile, tile)));
        }
        if (same >= 2) {
            claims.add(new Claim(Claim.Type.PONG, tile, List.of(tile, tile)));
        }
        if (seat == (discarder + 1) % SEATS && tile.isNumber()) {
            addChow(claims, player, tile, -2, -1);
            addChow(claims, player, tile, -1, 1);
            addChow(claims, player, tile, 1, 2);
        }
        return claims;
    }

    private static void addChow(List<Claim> claims, Player player, Tile tile, int offsetA, int offsetB) {
        int rankA = tile.rank() + offsetA;
        int rankB = tile.rank() + offsetB;
        if (rankA < 1 || rankB > 9) {
            return;
        }
        Tile a = Tile.of(tile.suit(), rankA);
        Tile b = Tile.of(tile.suit(), rankB);
        if (player.countInHand(a) > 0 && player.countInHand(b) > 0) {
            claims.add(new Claim(Claim.Type.CHOW, tile, List.of(a, b)));
        }
    }

    /**
     * 依優先序處理各家宣告：胡 > 槓、碰 > 吃；同為胡牌時由放槍者的下家起算最近者優先（截胡）。
     * 沒有列在 decisions 裡的座位視為不宣告。
     */
    public void resolveClaims(Map<Integer, Claim> decisions) {
        requirePhase(Phase.CLAIMS);
        for (Map.Entry<Integer, Claim> decision : decisions.entrySet()) {
            if (!claimOptions(decision.getKey()).contains(decision.getValue())) {
                throw new IllegalArgumentException("座位 " + decision.getKey() + " 不能宣告 " + decision.getValue());
            }
        }
        int winnerSeat = -1;
        Claim winner = null;
        for (int distance = 1; distance < SEATS; distance++) {
            int seat = (discarder + distance) % SEATS;
            Claim claim = decisions.get(seat);
            if (claim != null && (winner == null || priority(claim) < priority(winner))) {
                winner = claim;
                winnerSeat = seat;
            }
        }

        if (winner == null) {
            passClaims();
        } else if (winner.type() == Claim.Type.WIN) {
            boolean robbed = robbing;
            if (!robbed) {
                players[discarder].removeLastDiscard();
            }
            finishWin(winnerSeat, discarder, claimTile, robbed);
        } else {
            applyMeldClaim(winnerSeat, winner);
        }
    }

    private static int priority(Claim claim) {
        return claim.type() == Claim.Type.KONG ? Claim.Type.PONG.ordinal() : claim.type().ordinal();
    }

    private void passClaims() {
        int previous = discarder;
        Tile tile = claimTile;
        boolean wasRobbing = robbing;
        claimTile = null;
        robbing = false;
        if (wasRobbing) {
            players[previous].upgradePongToKong(tile);
            draw(previous, true);
        } else {
            draw((previous + 1) % SEATS, false);
        }
    }

    private void applyMeldClaim(int seat, Claim claim) {
        Player player = players[seat];
        players[discarder].removeLastDiscard();
        for (Tile tile : claim.fromHand()) {
            player.removeFromHand(tile);
        }
        List<Tile> tiles = new ArrayList<>(claim.fromHand());
        tiles.add(claim.tile());
        tiles.sort(Comparator.naturalOrder());
        anyClaim = true;
        claimTile = null;
        log.accept(player.name() + " " + claim.verb() + " " + claim.tile().label());
        switch (claim.type()) {
            case KONG:
                player.addMeld(new Meld(Meld.Type.OPEN_KONG, tiles));
                listener.accept(new RoundEvent(RoundEvent.Type.KONG, seat, claim.tile(), discarder));
                draw(seat, true);
                break;
            case PONG:
                player.addMeld(new Meld(Meld.Type.PONG, tiles));
                takeTurnWithoutDraw(seat);
                listener.accept(new RoundEvent(RoundEvent.Type.PONG, seat, claim.tile(), discarder));
                break;
            default:
                player.addMeld(new Meld(Meld.Type.CHOW, tiles));
                takeTurnWithoutDraw(seat);
                listener.accept(new RoundEvent(RoundEvent.Type.CHOW, seat, claim.tile(), discarder));
                break;
        }
    }

    private void takeTurnWithoutDraw(int seat) {
        current = seat;
        drawn = null;
        discardOnly = true;
        phase = Phase.TURN;
    }

    private void finishWin(int seat, int from, Tile tile, boolean robbedKong) {
        Player player = players[seat];
        if (from >= 0) {
            player.addToHand(tile);
        }
        boolean selfDraw = from < 0;
        boolean firstDraw = selfDraw && turnsTaken[seat] == 0 && !anyClaim;
        WinContext context = new WinContext(player.concealed(), player.melds(), player.flowers(), tile,
                selfDraw, roundWind, seatWind(seat), robbedKong, afterKong, wall.liveCount() == 0,
                firstDraw && seat == dealer, firstDraw && seat != dealer);
        result = new RoundResult(seat, from, tile, Scorer.score(context));
        drawn = null;
        claimTile = null;
        robbing = false;
        phase = Phase.FINISHED;
        log.accept(selfDraw ? player.name() + " 自摸 " + tile.label()
                : player.name() + " 胡 " + players[from].name() + " 的 " + tile.label());
        listener.accept(new RoundEvent(selfDraw ? RoundEvent.Type.SELF_WIN : RoundEvent.Type.WIN, seat, tile, from));
    }

    private void finishDrawnGame() {
        result = RoundResult.drawnGame();
        drawn = null;
        claimTile = null;
        phase = Phase.FINISHED;
        log.accept("流局");
        listener.accept(new RoundEvent(RoundEvent.Type.DRAWN_GAME, -1, null, -1));
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("目前階段是 " + phase + "，不是 " + expected);
        }
    }

    private void requireTurn(int seat) {
        requirePhase(Phase.TURN);
        if (seat != current) {
            throw new IllegalStateException("還沒輪到座位 " + seat);
        }
    }

    /** 門風 1-4；莊家固定為東。 */
    public int seatWind(int seat) {
        return (seat - dealer + SEATS) % SEATS + 1;
    }

    public Phase phase() {
        return phase;
    }

    public int current() {
        return current;
    }

    public Tile drawn() {
        return drawn;
    }

    public Tile claimTile() {
        return claimTile;
    }

    public int discarder() {
        return discarder;
    }

    public boolean isRobbing() {
        return robbing;
    }

    public int dealer() {
        return dealer;
    }

    public int roundWind() {
        return roundWind;
    }

    public int streak() {
        return streak;
    }

    public List<DiscardEvent> discardHistory() {
        return Collections.unmodifiableList(discardHistory);
    }

    public RoundResult result() {
        return result;
    }

    public Player player(int seat) {
        return players[seat];
    }

    public Wall wall() {
        return wall;
    }
}
