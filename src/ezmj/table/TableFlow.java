package ezmj.table;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ezmj.bot.Bot;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.Settlement;
import ezmj.engine.ThinkTimer;
import ezmj.engine.TurnOptions;
import ezmj.model.Tile;
import ezmj.rules.HandAnalyzer;

/**
 * 一桌牌的行牌流程，不依賴畫面：輪到誰、等誰決定、電腦和代打什麼時候動作、倒數到了怎麼辦。
 * 哪些座位是真人在建構時決定，宣告時可以同時等好幾位真人。
 * 所有呼叫與 Scheduler 排的動作都要在同一條執行緒上，例如 EDT。
 */
public final class TableFlow {

    private static final int SEATS = 4;

    /** 排一個之後才執行的動作，回傳的動作用來取消。 */
    public interface Scheduler {
        Runnable after(int delayMs, Runnable action);
    }

    /** 節奏設定，牌局中隨時可能改變，每次要用時才讀。waitSeconds 不大於 0 表示不限時。 */
    public interface Pacing {
        int turnDelayMs();

        int claimDelayMs();

        int waitSeconds();
    }

    /** 流程往外通知的事，由畫面或之後的連線實作。 */
    public interface Listener {
        void log(String message);

        /** 這局的 Round 準備好了，還沒發牌或是剛從存檔接回。 */
        void roundOpened(Round round);

        /** 牌發完了。回傳要等多少毫秒才開始行牌，用來演發牌。 */
        int dealt(Round round);

        /** 開局的摸牌與補花發生在發牌之前，不會通知。 */
        void event(RoundEvent event);

        /** 局面往下走了一步。 */
        void refresh(Round round);

        /** 目前不用等真人決定。seat 是輪到的座位，等宣告時是 -1。 */
        void idle(int seat);

        /** 就要開始等真人決定了，適合存檔。 */
        void checkpoint();

        void askTurn(int seat, int waitId, TurnOptions options);

        void askClaim(int seat, int waitId, List<Claim> options);

        /** 這次等待還剩幾秒；0 表示不限時。 */
        void secondsLeft(int seconds);

        /** 這個座位剛打牌宣告聽牌。 */
        void listening(int seat);

        void thinkingFinished(int seat);

        void roundFinished(Round round, Settlement settlement);
    }

    private final GameSession session;
    private final Set<Integer> people;
    private final Bot bot;
    private final Bot proxy;
    private final Pacing pacing;
    private final Scheduler scheduler;
    private final Listener table;
    private final boolean[] autoPlay = new boolean[SEATS];
    /** 聽牌模式等的牌；沒有列出的座位表示沒有鎖定手牌。 */
    private final Map<Integer, List<Tile>> listeningWaits = new HashMap<>();
    private final ThinkTimer[] thinking = new ThinkTimer[SEATS];
    /** 這次等待還沒決定的座位。 */
    private final Set<Integer> waiting = new HashSet<>();

    private Round round;
    private Map<Integer, Claim> pendingClaims;
    /** 每次等待加一，分得出決定是不是這次等待的。 */
    private int waitId;
    private int secondsLeft;
    private Runnable cancelStep = () -> { };
    private Runnable cancelCountdown = () -> { };
    /** 正在開局。這時的補花與摸牌都在發牌之前，不個別通知。 */
    private boolean starting;
    private boolean stopped;

    /** bot 替不是真人的座位決定；proxy 替代打中的真人決定。 */
    public TableFlow(GameSession session, Set<Integer> people, Bot bot, Bot proxy, Pacing pacing,
                     Scheduler scheduler, Listener table) {
        this.session = session;
        this.people = Set.copyOf(people);
        this.bot = bot;
        this.proxy = proxy;
        this.pacing = pacing;
        this.scheduler = scheduler;
        this.table = table;
        Arrays.setAll(thinking, seat -> new ThinkTimer());
    }

    public void startRound() {
        listeningWaits.clear();
        round = session.newRound(table::log);
        round.setListener(this::onRoundEvent);
        table.roundOpened(round);
        starting = true;
        round.start();
        starting = false;
        int dealMs = table.dealt(round);
        if (dealMs > 0) {
            schedule(dealMs, this::advance);
        } else {
            advance();
        }
    }

    /** 接回存檔的一局。聽牌的狀態要先用 setListening 放好。 */
    public void resume(Round saved) {
        round = saved;
        round.setLog(table::log);
        round.setListener(this::onRoundEvent);
        table.roundOpened(round);
        table.log("接續上次的牌局");
        advance();
    }

    public void stop() {
        stopped = true;
        cancelStep.run();
        endWait();
    }

    public Round round() {
        return round;
    }

    public boolean isAutoPlay(int seat) {
        return autoPlay[seat];
    }

    public List<Tile> listeningWaits(int seat) {
        return listeningWaits.get(seat);
    }

    public void setListening(int seat, List<Tile> waits) {
        listeningWaits.put(seat, waits);
    }

    public ThinkTimer thinking(int seat) {
        return thinking[seat];
    }

    /** 這個決定還算數：正在等這個座位，而且是這次等待。 */
    public boolean isWaiting(int seat, int id) {
        return id == waitId && waiting.contains(seat);
    }

    /** 代打的開關。正在等這個座位決定時，打開就立刻交給電腦；只等他一家宣告的話，照一般的停頓往下走。 */
    public void setAutoPlay(int seat, boolean on) {
        autoPlay[seat] = on;
        if (!on) {
            return;
        }
        thinking[seat].cancel();
        table.checkpoint();
        if (!waiting.contains(seat)) {
            return;
        }
        if (round.phase() == Round.Phase.TURN) {
            advance();
            return;
        }
        decideAutomatically(seat, round.claimOptions(seat));
        waiting.remove(seat);
        if (waiting.isEmpty()) {
            endWait();
            resolveClaimsAfterDelay();
        }
    }

    /** 從頭計時目前的等待；等待時間設定改變時也會呼叫。 */
    public void restartCountdown() {
        cancelCountdown.run();
        cancelCountdown = () -> { };
        if (waiting.isEmpty()) {
            return;
        }
        secondsLeft = Math.max(pacing.waitSeconds(), 0);
        table.secondsLeft(secondsLeft);
        if (secondsLeft > 0) {
            tickCountdown();
        }
    }

    /** 以下的決定不是這次等待的就忽略，例如倒數到了才送到的。不合規則則丟出例外，局面不變。 */
    public void discard(int seat, int id, Tile tile) {
        if (isWaiting(seat, id)) {
            round.discard(seat, tile);
            advance();
        }
    }

    /** 打出這張牌並鎖定手牌，之後不能再吃碰槓。 */
    public void declareListening(int seat, int id, Tile tile) {
        if (!isWaiting(seat, id)) {
            return;
        }
        if (!round.readyDiscards(seat).containsKey(tile)) {
            throw new IllegalArgumentException("座位 " + seat + " 打 " + tile + " 不會聽牌");
        }
        round.discard(seat, tile);
        listeningWaits.put(seat, HandAnalyzer.waits(round.player(seat).concealed(), round.player(seat).melds()));
        table.listening(seat);
        advance();
    }

    public void declareSelfWin(int seat, int id) {
        if (isWaiting(seat, id)) {
            round.declareSelfWin(seat);
            advance();
        }
    }

    /** 手上四張的是暗槓，已經碰出來的是加槓，同一張牌不會兩種都能做。 */
    public void declareKong(int seat, int id, Tile tile) {
        if (!isWaiting(seat, id)) {
            return;
        }
        if (round.turnOptions(seat).concealedKongs().contains(tile)) {
            round.declareConcealedKong(seat, tile);
        } else {
            round.declareAddKong(seat, tile);
        }
        advance();
    }

    /** 對這張牌的決定，null 表示過。等的人都決定了就立刻處理。 */
    public void answerClaim(int seat, int id, Claim claim) {
        if (!isWaiting(seat, id)) {
            return;
        }
        if (claim == null) {
            // 過牌就算想完了，下一家就算是自己，摸牌後也是新的一次
            finishThinking(seat);
        } else if (round.claimOptions(seat).contains(claim)) {
            pendingClaims.put(seat, claim);
        } else {
            throw new IllegalArgumentException("座位 " + seat + " 不能宣告 " + claim);
        }
        waiting.remove(seat);
        if (waiting.isEmpty()) {
            resolveClaims();
        }
    }

    private void onRoundEvent(RoundEvent event) {
        if (!starting || (event.type() != RoundEvent.Type.DRAW && event.type() != RoundEvent.Type.FLOWER)) {
            table.event(event);
        }
    }

    private void advance() {
        endWait();
        if (stopped) {
            return;
        }
        finishThinkingIfDone();
        table.refresh(round);
        switch (round.phase()) {
            case FINISHED:
                finishRound();
                break;
            case TURN:
                beginTurn();
                break;
            default:
                beginClaims();
                break;
        }
    }

    /**
     * 吃碰槓之後還是輪到自己，要打出牌才算想完；加槓等別家搶槓時也還沒完。
     * 其他情況代表已經打牌或胡了，過牌在 answerClaim 就算完。
     */
    private void finishThinkingIfDone() {
        for (int seat = 0; seat < SEATS; seat++) {
            boolean stillTheirs = round.phase() == Round.Phase.TURN && round.current() == seat;
            if (thinking[seat].isRunning() && !stillTheirs && !round.isRobbing()) {
                finishThinking(seat);
            }
        }
    }

    private void finishThinking(int seat) {
        thinking[seat].finish(System.nanoTime());
        table.thinkingFinished(seat);
    }

    private void beginTurn() {
        int seat = round.current();
        TurnOptions options = round.turnOptions(seat);
        if (!people.contains(seat)) {
            table.idle(seat);
            schedule(pacing.turnDelayMs(), () -> {
                bot.playTurn(round, seat, options);
                advance();
            });
        } else if (listeningWaits.get(seat) != null) {
            table.idle(seat);
            schedule(pacing.turnDelayMs(), () -> playListeningTurn(seat, options));
        } else if (autoPlay[seat]) {
            table.idle(seat);
            schedule(pacing.turnDelayMs(), () -> playProxyTurn(seat, options));
        } else {
            table.checkpoint();
            thinking[seat].start(System.nanoTime());
            beginWait(Set.of(seat));
            table.askTurn(seat, waitId, options);
            restartCountdown();
        }
    }

    /** 聽牌模式：摸到胡的牌就自摸，其餘摸到什麼打什麼。 */
    private void playListeningTurn(int seat, TurnOptions options) {
        if (options.canSelfWin()) {
            round.declareSelfWin(seat);
        } else {
            round.discard(seat, round.rightmostTile(seat));
        }
        advance();
    }

    /** 等待的這段時間裡代打被關掉的話，就還給真人。 */
    private void playProxyTurn(int seat, TurnOptions options) {
        if (autoPlay[seat]) {
            proxy.playTurn(round, seat, options);
        }
        advance();
    }

    private void beginClaims() {
        pendingClaims = new HashMap<>();
        Map<Integer, List<Claim>> asks = new HashMap<>();
        for (int seat = 0; seat < SEATS; seat++) {
            List<Claim> options = round.claimOptions(seat);
            if (options.isEmpty()) {
                continue;
            }
            if (!people.contains(seat)) {
                Claim claim = bot.chooseClaim(round, seat, options);
                if (claim != null) {
                    pendingClaims.put(seat, claim);
                }
            } else if (listeningWaits.get(seat) != null || autoPlay[seat]) {
                decideAutomatically(seat, options);
            } else {
                asks.put(seat, options);
            }
        }
        if (asks.isEmpty()) {
            resolveClaimsAfterDelay();
            return;
        }
        table.checkpoint();
        for (int seat : asks.keySet()) {
            thinking[seat].start(System.nanoTime());
        }
        beginWait(asks.keySet());
        for (Map.Entry<Integer, List<Claim>> ask : asks.entrySet()) {
            table.askClaim(ask.getKey(), waitId, ask.getValue());
        }
        restartCountdown();
    }

    /** 聽牌模式只胡不吃碰槓；代打就照電腦的判斷。 */
    private void decideAutomatically(int seat, List<Claim> options) {
        Claim claim = listeningWaits.get(seat) == null
                ? proxy.chooseClaim(round, seat, options)
                : options.stream().filter(option -> option.type() == Claim.Type.WIN).findFirst().orElse(null);
        if (claim != null) {
            pendingClaims.put(seat, claim);
        }
    }

    private void resolveClaimsAfterDelay() {
        table.idle(-1);
        schedule(pacing.claimDelayMs(), this::resolveClaims);
    }

    private void resolveClaims() {
        round.resolveClaims(pendingClaims);
        pendingClaims = null;
        advance();
    }

    private void finishRound() {
        table.roundFinished(round, session.settle(round.result()));
    }

    private void beginWait(Set<Integer> seats) {
        waitId++;
        waiting.addAll(seats);
    }

    private void endWait() {
        waiting.clear();
        cancelCountdown.run();
        cancelCountdown = () -> { };
    }

    private void tickCountdown() {
        cancelCountdown = scheduler.after(1000, () -> {
            secondsLeft--;
            if (secondsLeft > 0) {
                table.secondsLeft(secondsLeft);
                tickCountdown();
            } else {
                onWaitTimeout();
            }
        });
    }

    /** 時間到：輪到的人打出最右邊的牌，還沒決定宣告的都算過。 */
    private void onWaitTimeout() {
        if (round.phase() == Round.Phase.TURN) {
            int seat = round.current();
            round.discard(seat, round.rightmostTile(seat));
            advance();
        } else {
            waiting.forEach(this::finishThinking);
            resolveClaims();
        }
    }

    private void schedule(int delayMs, Runnable action) {
        cancelStep = scheduler.after(delayMs, () -> {
            if (!stopped) {
                action.run();
            }
        });
    }
}
