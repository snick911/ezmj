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

/**
 * 一桌牌的行牌流程，不依賴畫面：輪到誰、等誰決定、電腦和代打什麼時候動作、倒數到了怎麼辦。
 * 哪些座位是真人在建構時決定，宣告時可以同時等好幾位真人。
 * 所有呼叫與 Scheduler 排的動作都要在同一條執行緒上，例如 EDT。
 *
 * 等宣告時，已經決定的宣告比每一位還沒決定的人能做的都大，就不再等他們：例如電腦要碰、真人只能吃。
 * 電腦、代打與報聽的人自動做的宣告，要等宣告開始 BOT_CLAIM_MS 之後才算數，真人還在想的時候不會馬上被搶走。
 *
 * 報聽：打出牌後已經聽牌，就有報聽的機會，一直到他下一次摸牌、吃碰槓或這局結束。
 * 真人由 Listener.canListen 通知，再用 declareListening(seat, listenId) 宣告；電腦與代打打完牌就自己決定。
 */
public final class TableFlow {

    private static final int SEATS = 4;
    /** 自動做的宣告在等宣告開始這麼久之後才算數。 */
    public static final int BOT_CLAIM_MS = 2_000;

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

        /** 開局的摸牌與補花發生在發牌之前，不會通知。報聽也從這裡通知，種類是 LISTEN。 */
        void event(RoundEvent event);

        /** 局面往下走了一步。 */
        void refresh(Round round);

        /** 目前不用等真人決定。seat 是輪到的座位，等宣告時是 -1。 */
        void idle(int seat);

        /** 就要開始等真人決定了，適合存檔。 */
        void checkpoint();

        void askTurn(int seat, int waitId, TurnOptions options);

        /** 這次等宣告要是被別人更大的宣告搶先，就不再等他：之後的 refresh 或 idle 就是過期了。 */
        void askClaim(int seat, int waitId, List<Claim> options);

        /** 這次等待還剩幾秒；0 表示不限時。 */
        void secondsLeft(int seconds);

        /**
         * 這位真人打出牌後聽牌了，可以報聽。listenId 交給 declareListening，
         * 到他下一次摸牌、吃碰槓或這局結束就過期，過期不另外通知。代打中不會通知，由代打自己決定。
         */
        void canListen(int seat, int listenId);

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
    private final ThinkTimer[] thinking = new ThinkTimer[SEATS];
    /** 這次等待還沒決定的座位。 */
    private final Set<Integer> waiting = new HashSet<>();
    /** 每個座位目前報聽機會的編號，沒有機會時不一定是 0，要看 Round.canDeclareListening。 */
    private final int[] listenIds = new int[SEATS];

    private Round round;
    /** 真人這次的宣告，決定了就算數。 */
    private Map<Integer, Claim> pendingClaims;
    /** 電腦、代打與報聽的人自動做的宣告，automaticCounts 之後才算數。 */
    private Map<Integer, Claim> automaticClaims;
    private boolean automaticCounts;
    /** 每次等待加一，分得出決定是不是這次等待的。 */
    private int waitId;
    private int lastListenId;
    /** 剛打出牌的座位，下一次 advance 時看他能不能報聽。 */
    private int justDiscarded = -1;
    private int secondsLeft;
    private Runnable cancelStep = () -> { };
    private Runnable cancelCountdown = () -> { };
    private Runnable cancelAutomatic = () -> { };
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
        justDiscarded = -1;
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

    /** 接回存檔的一局。誰報聽了、誰還有報聽的機會都在 Round 裡，還有機會的真人會再收到 canListen。 */
    public void resume(Round saved) {
        justDiscarded = -1;
        round = saved;
        round.setLog(table::log);
        round.setListener(this::onRoundEvent);
        table.roundOpened(round);
        table.log("接續上次的牌局");
        for (int seat : people) {
            if (round.canDeclareListening(seat) && !autoPlay[seat]) {
                listenIds[seat] = ++lastListenId;
                table.canListen(seat, listenIds[seat]);
            }
        }
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

    /** 報聽的人等的牌，沒有報聽是 null。 */
    public List<Tile> listeningWaits(int seat) {
        return round == null ? null : round.listeningWaits(seat);
    }

    /** 這位真人目前報聽機會的編號，沒有機會是 0。重連時用來補送 canListen。 */
    public int listenOffer(int seat) {
        return round != null && round.canDeclareListening(seat) && people.contains(seat) && !autoPlay[seat]
                ? listenIds[seat] : 0;
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
        if (round != null && round.canDeclareListening(seat) && proxy.declareListening(round, seat)) {
            declareListening(seat, listenIds[seat]);
        }
        if (!waiting.contains(seat)) {
            return;
        }
        if (round.phase() == Round.Phase.TURN) {
            advance();
            return;
        }
        stopWaitingFor(seat);
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

    /** 打出這張牌並立刻報聽：單機的聽牌按鈕先選牌再打，舊的連線客戶端也用這個。 */
    public void declareListening(int seat, int id, Tile tile) {
        if (!isWaiting(seat, id)) {
            return;
        }
        if (!round.readyDiscards(seat).containsKey(tile)) {
            throw new IllegalArgumentException("座位 " + seat + " 打 " + tile + " 不會聽牌");
        }
        round.discard(seat, tile);
        round.declareListening(seat);
        advance();
    }

    /**
     * 報聽。listenId 是 canListen 給的編號，過期了（已經摸牌、吃碰槓或這局結束）就忽略。
     * 正在等他宣告的話，改成自動決定：能胡就胡，其他都放棄；已經決定要吃碰槓的也取消。
     */
    public void declareListening(int seat, int listenId) {
        if (round == null || listenId != listenIds[seat] || !round.canDeclareListening(seat)) {
            return;
        }
        round.declareListening(seat);
        table.refresh(round);
        if (pendingClaims == null) {
            return;
        }
        for (Map<Integer, Claim> claims : List.of(pendingClaims, automaticClaims)) {
            Claim decided = claims.get(seat);
            if (decided != null && decided.type() != Claim.Type.WIN) {
                claims.remove(seat);
            }
        }
        if (waiting.contains(seat)) {
            thinking[seat].cancel();
            stopWaitingFor(seat);
        }
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

    /** 對這張牌的決定，null 表示過。等的人都決定了就立刻處理，還沒決定的人都比不過已經決定的也是。 */
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
        } else {
            resolveIfDecided();
        }
    }

    private void onRoundEvent(RoundEvent event) {
        if (event.type() == RoundEvent.Type.DISCARD) {
            justDiscarded = event.seat();
        }
        if (!starting || (event.type() != RoundEvent.Type.DRAW && event.type() != RoundEvent.Type.FLOWER)) {
            table.event(event);
        }
    }

    private void advance() {
        endWait();
        if (stopped) {
            return;
        }
        offerListening();
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

    /** 剛打出牌、已經聽牌的人：電腦與代打自己決定要不要報聽，真人就通知他可以報。 */
    private void offerListening() {
        int seat = justDiscarded;
        justDiscarded = -1;
        if (seat < 0 || !round.canDeclareListening(seat)) {
            return;
        }
        listenIds[seat] = ++lastListenId;
        boolean person = people.contains(seat);
        if (!person || autoPlay[seat]) {
            if ((person ? proxy : bot).declareListening(round, seat)) {
                round.declareListening(seat);
            }
        } else {
            table.canListen(seat, listenIds[seat]);
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
        if (round.isListening(seat)) {
            table.idle(seat);
            schedule(pacing.turnDelayMs(), () -> playListeningTurn(seat, options));
        } else if (!people.contains(seat)) {
            table.idle(seat);
            schedule(pacing.turnDelayMs(), () -> {
                bot.playTurn(round, seat, options);
                advance();
            });
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

    /** 報聽之後：摸到胡的牌就自摸，其餘摸到什麼打什麼。電腦報聽也一樣。 */
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
        automaticClaims = new HashMap<>();
        automaticCounts = false;
        Map<Integer, List<Claim>> asks = new HashMap<>();
        for (int seat = 0; seat < SEATS; seat++) {
            List<Claim> options = round.claimOptions(seat);
            if (options.isEmpty()) {
                continue;
            }
            if (!people.contains(seat)) {
                Claim claim = bot.chooseClaim(round, seat, options);
                if (claim != null) {
                    automaticClaims.put(seat, claim);
                }
            } else if (round.isListening(seat) || autoPlay[seat]) {
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
        if (!automaticClaims.isEmpty()) {
            int id = waitId;
            cancelAutomatic = scheduler.after(BOT_CLAIM_MS, () -> {
                if (!stopped && id == waitId && !waiting.isEmpty()) {
                    automaticCounts = true;
                    resolveIfDecided();
                }
            });
        }
    }

    /** 報聽的人只胡不吃碰槓；代打就照電腦的判斷。 */
    private void decideAutomatically(int seat, List<Claim> options) {
        Claim claim = round.isListening(seat)
                ? options.stream().filter(option -> option.type() == Claim.Type.WIN).findFirst().orElse(null)
                : proxy.chooseClaim(round, seat, options);
        if (claim != null) {
            automaticClaims.put(seat, claim);
        }
    }

    /** 等宣告時這位真人改成自動決定，例如打開代打或報聽。 */
    private void stopWaitingFor(int seat) {
        decideAutomatically(seat, round.claimOptions(seat));
        waiting.remove(seat);
        if (waiting.isEmpty()) {
            endWait();
            resolveClaimsAfterDelay();
        } else {
            resolveIfDecided();
        }
    }

    /**
     * 已經決定的宣告比每一位還沒決定的人能做的都大，就不再等他們，直接處理。
     * 大小只看種類：胡 > 槓、碰 > 吃；同樣是胡就照舊等，不管誰比較近。
     */
    private void resolveIfDecided() {
        if (waiting.isEmpty() || round.phase() != Round.Phase.CLAIMS) {
            return;
        }
        int best = Integer.MAX_VALUE;
        for (Claim claim : pendingClaims.values()) {
            best = Math.min(best, rank(claim));
        }
        if (automaticCounts) {
            for (Claim claim : automaticClaims.values()) {
                best = Math.min(best, rank(claim));
            }
        }
        for (int seat : waiting) {
            for (Claim option : round.claimOptions(seat)) {
                if (rank(option) <= best) {
                    return;
                }
            }
        }
        // 被搶先的人沒有做決定，這次不算一次思考
        for (int seat : waiting) {
            thinking[seat].cancel();
        }
        resolveClaims();
    }

    /** 越小越大。槓和碰一樣大，因為不會有兩家同時能碰或槓同一張牌。 */
    private static int rank(Claim claim) {
        return claim.type() == Claim.Type.KONG ? Claim.Type.PONG.ordinal() : claim.type().ordinal();
    }

    private void resolveClaimsAfterDelay() {
        table.idle(-1);
        schedule(pacing.claimDelayMs(), this::resolveClaims);
    }

    /** 自動的宣告最後一定算數，不管過了多久。 */
    private void resolveClaims() {
        Map<Integer, Claim> decisions = new HashMap<>(automaticClaims);
        decisions.putAll(pendingClaims);
        pendingClaims = null;
        automaticClaims = null;
        round.resolveClaims(decisions);
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
        cancelAutomatic.run();
        cancelAutomatic = () -> { };
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
