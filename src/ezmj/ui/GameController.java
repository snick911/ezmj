package ezmj.ui;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.Timer;

import ezmj.bot.Bot;
import ezmj.engine.Claim;
import ezmj.engine.GameSave;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.Settlement;
import ezmj.engine.ThinkTimer;
import ezmj.engine.TurnOptions;
import ezmj.model.AppSettings;
import ezmj.model.Gender;
import ezmj.model.Tile;
import ezmj.rules.HandAnalyzer;

/**
 * 串接引擎與畫面的流程控制，全部在 EDT 上執行。
 * 電腦的動作以 Timer 延遲，真人的動作則等待按鈕或點牌事件。
 */
public final class GameController {

    private static final int HUMAN_SEAT = 0;
    private static final String TURN_HINT = "輪到你了：點一張手牌打出";
    private static final int MAX_LISTEN_BUTTONS = 4;
    private static final int MAX_LISTED_WAITS = 3;

    /** 一局結束時的處理。結算完立刻呼叫，先把該存的存好；回傳的動作在胡牌大字演完之後才執行，用來顯示結果。 */
    public interface RoundEndHandler {
        Runnable onRoundEnd(GameSession session, Round round, Settlement settlement, Runnable next);
    }

    private final GameSession session;
    private final Bot bot;
    private final Bot proxy;
    private final RoundEndHandler roundEndHandler;
    private final AppSettings settings;
    private final Runnable checkpoint;
    private final ThinkTimer thinking = new ThinkTimer();
    private TablePanel view;
    private Round round;
    private Timer timer;
    private Map<Integer, Claim> pendingClaims;
    private boolean stopped;
    /** 正在開局。這時的補花與摸牌都在發牌動畫之前，不個別演出來。 */
    private boolean starting;
    private Timer countdown;
    private int secondsLeft;
    private String promptHint;
    private Runnable promptTimeout;
    /** 每次等待真人時加一，分得出按鈕是不是這次等待的。 */
    private int promptId;
    private boolean autoPlay;
    private boolean usedAutoPlay;
    /** 聽牌模式等的牌；null 表示沒有鎖定手牌。 */
    private List<Tile> listeningWaits;

    /**
     * proxy 是電腦代打時替真人決定的電腦。settings 隨時會被選單改變，每次要用時才讀。
     * checkpoint 在每次輪到真人決定時呼叫，用來存檔。
     */
    public GameController(GameSession session, Bot bot, Bot proxy, RoundEndHandler roundEndHandler,
                          AppSettings settings, Runnable checkpoint) {
        this.session = session;
        this.bot = bot;
        this.proxy = proxy;
        this.roundEndHandler = roundEndHandler;
        this.settings = settings;
        this.checkpoint = checkpoint;
    }

    void attach(TablePanel view) {
        this.view = view;
    }

    /** 電腦代打的開關。正在等真人決定時，打開就立刻交給電腦。 */
    void toggleAutoPlay() {
        autoPlay = !autoPlay;
        view.setAutoPlay(autoPlay);
        if (autoPlay) {
            usedAutoPlay = true;
            thinking.cancel();
            checkpoint.run();
            if (promptTimeout != null) {
                advance();
            }
        }
    }

    /** 這場牌局已經打完，結果視窗還沒看完。 */
    boolean isOver() {
        return session.isOver();
    }

    /** 出錯時記下的局面。 */
    String describe() {
        String state = round == null ? "還沒開局" : "階段 " + round.phase() + "，輪到座位 " + round.current();
        return "打完 " + session.roundsPlayed() + " 局，" + state + "，電腦 " + session.config().botLevels();
    }

    /** 這場牌局是否用過電腦代打。用過的不列入榮譽榜。 */
    boolean usedAutoPlay() {
        return usedAutoPlay;
    }

    /** 這場到目前的平均思考秒數。 */
    double averageThinkSeconds() {
        return thinking.averageSeconds();
    }

    int thinkCount() {
        return thinking.count();
    }

    public void startRound() {
        listeningWaits = null;
        round = session.newRound(message -> view.appendLog(message));
        round.setListener(this::onRoundEvent);
        view.bindRound(round);
        starting = true;
        round.start();
        starting = false;
        if (settings.animationEnabled() && round.phase() != Round.Phase.FINISHED) {
            dealThenAdvance();
        } else {
            advance();
        }
    }

    /** 接回存檔的牌局。下一局還沒開始的話就直接開局。 */
    void resume(GameSave.Loaded saved) {
        usedAutoPlay = saved.usedAutoPlay();
        thinking.restore(saved.thinkNanos(), saved.thinkCount());
        view.showThinkTime(thinking.averageSeconds(), thinking.count());
        if (saved.round() == null) {
            startRound();
            return;
        }
        listeningWaits = saved.listeningWaits();
        round = saved.round();
        round.setLog(message -> view.appendLog(message));
        round.setListener(this::onRoundEvent);
        view.bindRound(round);
        view.appendLog("接續上次的牌局");
        advance();
    }

    /** 存下目前的牌局，事件之間的狀態都是完整的，隨時可以存。 */
    void save(Path file, long seed) throws IOException {
        GameSave.save(file, seed, session, round, usedAutoPlay, listeningWaits, thinking);
    }

    /** 發牌動畫演完才開始行牌，這段時間誰都不能動作。 */
    private void dealThenAdvance() {
        view.refresh(session, round);
        idle("發牌中…");
        view.animateDeal();
        if (settings.soundEnabled()) {
            for (int packet = 0; packet < TableCanvas.DEAL_PACKETS; packet++) {
                playSound(SoundEffects.Cue.DEAL, packet * TableCanvas.DEAL_INTERVAL_MS + TableCanvas.DEAL_FLIGHT_MS);
            }
            if (anyFlowerExposed()) {
                playSound(SoundEffects.Cue.FLOWER, TableCanvas.DEAL_MS);
            }
        }
        schedule(TableCanvas.DEAL_MS, this::advance);
    }

    private boolean anyFlowerExposed() {
        for (int seat = 0; seat < 4; seat++) {
            if (!round.player(seat).flowers().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void onRoundEvent(RoundEvent event) {
        if (starting && (event.type() == RoundEvent.Type.DRAW || event.type() == RoundEvent.Type.FLOWER)) {
            return;
        }
        boolean animated = settings.animationEnabled();
        if (animated) {
            view.animate(event);
        }
        if (settings.voiceEnabled()) {
            speak(event);
        }
        if (!settings.soundEnabled()) {
            return;
        }
        // 有動畫時，牌落地才出聲
        switch (event.type()) {
            case DISCARD:
                playSound(SoundEffects.Cue.DISCARD, animated ? TableCanvas.DISCARD_FLIGHT_MS : 0);
                break;
            case CHOW:
                playSound(SoundEffects.Cue.CHOW, animated ? TableCanvas.CLAIM_FLIGHT_MS : 0);
                break;
            case PONG:
                playSound(SoundEffects.Cue.PONG, animated ? TableCanvas.CLAIM_FLIGHT_MS : 0);
                break;
            case KONG:
                playSound(SoundEffects.Cue.KONG, animated ? TableCanvas.CLAIM_FLIGHT_MS : 0);
                break;
            case CONCEALED_KONG:
            case ADD_KONG:
                playSound(SoundEffects.Cue.KONG, 0);
                break;
            case WIN:
            case SELF_WIN:
                playSound(SoundEffects.Cue.WIN, 0);
                break;
            case DRAW:
                // 別家摸牌每巡都有，只有自己摸牌才出聲
                if (event.seat() == HUMAN_SEAT) {
                    playSound(SoundEffects.Cue.DRAW, animated ? TableCanvas.DRAW_FLIGHT_MS : 0);
                }
                break;
            case FLOWER:
                playSound(SoundEffects.Cue.FLOWER, 0);
                break;
            default:
                playSound(SoundEffects.Cue.DRAWN_GAME, 0);
                break;
        }
    }

    /** 這個座位喊牌的聲音。真人看環境設定，電腦看角色的性別。 */
    private Gender voiceOf(int seat) {
        return seat == HUMAN_SEAT ? settings.playerGender() : round.player(seat).gender();
    }

    /** 喊牌和大字同時出現，打牌時喊出打的是什麼牌。用出聲那一家的聲音；流局沒有特定的人，用女聲。 */
    private void speak(RoundEvent event) {
        Gender gender = event.seat() < 0 ? Gender.FEMALE : voiceOf(event.seat());
        switch (event.type()) {
            case DISCARD:
                VoiceLines.play(event.tile(), gender);
                break;
            case CHOW:
                VoiceLines.play(VoiceLines.Line.CHOW, gender);
                break;
            case PONG:
                VoiceLines.play(VoiceLines.Line.PONG, gender);
                break;
            case KONG:
            case CONCEALED_KONG:
            case ADD_KONG:
                VoiceLines.play(VoiceLines.Line.KONG, gender);
                break;
            case WIN:
                VoiceLines.play(VoiceLines.Line.WIN, gender);
                break;
            case SELF_WIN:
                VoiceLines.play(VoiceLines.Line.SELF_WIN, gender);
                break;
            case DRAWN_GAME:
                VoiceLines.play(VoiceLines.Line.DRAWN_GAME, gender);
                break;
            default:
                break;
        }
    }

    private void playSound(SoundEffects.Cue cue, int delayMs) {
        Timer delay = new Timer(delayMs, e -> {
            // 排好之後才關掉音效的，也不要再響
            if (!stopped && settings.soundEnabled()) {
                SoundEffects.play(cue);
            }
        });
        delay.setRepeats(false);
        delay.start();
    }

    public void stop() {
        stopped = true;
        if (timer != null) {
            timer.stop();
        }
        endPrompt();
    }

    public Round round() {
        return round;
    }

    private void advance() {
        endPrompt();
        if (stopped) {
            return;
        }
        finishThinkingIfDone();
        view.refresh(session, round);
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
     * 吃碰槓之後還是輪到真人，要打出牌才算想完；加槓等別家搶槓時也還沒完。
     * 其他情況代表真人已經打牌或胡了，過牌在 passClaim 就算完。
     */
    private void finishThinkingIfDone() {
        boolean stillHuman = round.phase() == Round.Phase.TURN && round.current() == HUMAN_SEAT;
        if (thinking.isRunning() && !stillHuman && !round.isRobbing()) {
            finishThinking();
        }
    }

    private void finishThinking() {
        thinking.finish(System.nanoTime());
        view.showThinkTime(thinking.averageSeconds(), thinking.count());
    }

    private void beginTurn() {
        int seat = round.current();
        TurnOptions options = round.turnOptions(seat);
        if (seat != HUMAN_SEAT) {
            idle("等待 " + round.player(seat).name() + " 出牌…");
            schedule(settings.gameSpeed().turnDelayMs(), () -> {
                bot.playTurn(round, seat, options);
                advance();
            });
        } else if (listeningWaits != null) {
            idle(" ");
            schedule(settings.gameSpeed().turnDelayMs(), () -> playListeningTurn(options));
        } else if (autoPlay) {
            idle(" ");
            schedule(settings.gameSpeed().turnDelayMs(), () -> playProxyTurn(options));
        } else {
            showHumanTurn(options);
        }
    }

    /** 聽牌模式：摸到胡的牌就自摸，其餘摸到什麼打什麼。 */
    private void playListeningTurn(TurnOptions options) {
        if (options.canSelfWin()) {
            round.declareSelfWin(HUMAN_SEAT);
        } else {
            round.discard(HUMAN_SEAT, round.rightmostTile(HUMAN_SEAT));
        }
        advance();
    }

    /** 等待的這段時間裡代打被關掉的話，就還給真人。 */
    private void playProxyTurn(TurnOptions options) {
        if (autoPlay) {
            proxy.playTurn(round, HUMAN_SEAT, options);
        }
        advance();
    }

    /** 打出這張牌並鎖定手牌，之後不能再吃碰槓。 */
    private void declareListening(Tile tile) {
        round.discard(HUMAN_SEAT, tile);
        listeningWaits = HandAnalyzer.waits(round.player(HUMAN_SEAT).concealed(), round.player(HUMAN_SEAT).melds());
        if (settings.voiceEnabled()) {
            VoiceLines.play(VoiceLines.Line.LISTEN, voiceOf(HUMAN_SEAT));
        }
        advance();
    }

    private void addListenButtons(List<TablePanel.ActionItem> actions) {
        round.readyDiscards(HUMAN_SEAT).entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Tile, List<Tile>> entry) -> entry.getValue().size()).reversed())
                .limit(MAX_LISTEN_BUTTONS)
                .forEach(entry -> actions.add(promptAction(
                        "打" + entry.getKey().label() + " 聽" + waitsLabel(entry.getValue()), Theme.BUTTON,
                        () -> declareListening(entry.getKey()))));
    }

    private static String waitsLabel(List<Tile> waits) {
        if (waits.size() > MAX_LISTED_WAITS) {
            return " " + waits.size() + " 種";
        }
        return waits.stream().map(Tile::label).collect(Collectors.joining("、"));
    }

    private void showHumanTurn(TurnOptions options) {
        checkpoint.run();
        thinking.start(System.nanoTime());
        beginPrompt(TURN_HINT, () -> humanDiscard(round.rightmostTile(HUMAN_SEAT)));
        showTurnActions(options);
    }

    private void showTurnActions(TurnOptions options) {
        List<TablePanel.ActionItem> actions = new ArrayList<>();
        if (options.canSelfWin()) {
            actions.add(promptAction("自摸", Theme.RED, () -> {
                round.declareSelfWin(HUMAN_SEAT);
                advance();
            }));
        }
        if (!options.concealedKongs().isEmpty() || !options.addKongs().isEmpty()) {
            actions.add(promptAction("槓", Theme.BUTTON, () -> pickTurnKong(options)));
        }
        addListenButtons(actions);
        view.showActions(TURN_HINT, true, actions);
        updatePromptHint(TURN_HINT);
    }

    /** 自己的回合選要槓哪張，只有一張能槓就直接槓。手上四張的是暗槓，已經碰出來的是加槓，同一張牌不會兩種都能做。 */
    private void pickTurnKong(TurnOptions options) {
        Set<Tile> tiles = new LinkedHashSet<>(options.concealedKongs());
        tiles.addAll(options.addKongs());
        if (tiles.size() == 1) {
            declareTurnKong(options, tiles.iterator().next());
            return;
        }
        String hint = "選要槓的牌";
        view.showPick(hint, tiles, null, tile -> declareTurnKong(options, tile),
                List.of(promptAction("取消", Theme.PANEL_LIGHT, () -> showTurnActions(options))));
        updatePromptHint(hint);
    }

    private void declareTurnKong(TurnOptions options, Tile tile) {
        if (options.concealedKongs().contains(tile)) {
            round.declareConcealedKong(HUMAN_SEAT, tile);
        } else {
            round.declareAddKong(HUMAN_SEAT, tile);
        }
        advance();
    }

    /** 真人開始考慮；時間到還沒決定就執行 onTimeout。 */
    private void beginPrompt(String hint, Runnable onTimeout) {
        promptId++;
        promptHint = hint;
        promptTimeout = onTimeout;
        restartCountdown();
    }

    /** 這次等待的按鈕。按住時倒數到了、放開才送出的話，局面已經往下走，舊按鈕不能再有作用。 */
    private TablePanel.ActionItem promptAction(String label, Color color, Runnable action) {
        int id = promptId;
        return new TablePanel.ActionItem(label, color, () -> {
            if (promptTimeout != null && promptId == id) {
                action.run();
            }
        });
    }

    /** 真人已經決定，或局面已經往下走。 */
    private void endPrompt() {
        promptTimeout = null;
        stopCountdown();
    }

    /** 從頭計時目前的等待；等待時間設定改變時也會呼叫。 */
    void restartCountdown() {
        stopCountdown();
        if (promptTimeout == null) {
            return;
        }
        secondsLeft = settings.waitSeconds();
        if (secondsLeft <= 0) {
            view.setHint(promptHint);
            return;
        }
        showSecondsLeft();
        countdown = new Timer(1000, e -> {
            secondsLeft--;
            if (secondsLeft > 0) {
                showSecondsLeft();
            } else {
                promptTimeout.run();
            }
        });
        countdown.start();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
    }

    private void showSecondsLeft() {
        view.setHint(promptHint + "（剩 " + secondsLeft + " 秒）");
    }

    /** 換提示文字但不重新計時：進出選牌不能多賺時間。 */
    private void updatePromptHint(String hint) {
        promptHint = hint;
        if (countdown != null) {
            showSecondsLeft();
        } else {
            view.setHint(hint);
        }
    }

    /** 由牌桌點牌事件呼叫。 */
    void humanDiscard(Tile tile) {
        if (round.phase() == Round.Phase.TURN && round.current() == HUMAN_SEAT) {
            round.discard(HUMAN_SEAT, tile);
            advance();
        }
    }

    private void beginClaims() {
        pendingClaims = new HashMap<>();
        for (int seat = 1; seat < 4; seat++) {
            List<Claim> options = round.claimOptions(seat);
            if (!options.isEmpty()) {
                Claim claim = bot.chooseClaim(round, seat, options);
                if (claim != null) {
                    pendingClaims.put(seat, claim);
                }
            }
        }
        List<Claim> humanOptions = round.claimOptions(HUMAN_SEAT);
        if (!humanOptions.isEmpty() && (listeningWaits != null || autoPlay)) {
            Claim claim = automatedClaim(humanOptions);
            if (claim != null) {
                pendingClaims.put(HUMAN_SEAT, claim);
            }
            humanOptions = List.of();
        }
        if (humanOptions.isEmpty()) {
            idle(" ");
            schedule(settings.gameSpeed().claimDelayMs(), this::resolveClaims);
            return;
        }
        checkpoint.run();
        thinking.start(System.nanoTime());
        beginPrompt(claimHint() + "，要宣告嗎？", this::passClaim);
        showClaimActions(humanOptions);
    }

    private String claimHint() {
        return round.player(round.discarder()).name() + (round.isRobbing() ? " 加槓 " : " 打出 ") + round.claimTile().label();
    }

    /** 每種宣告一個按鈕。只有一種組合就按了直接宣告，胡、碰、槓都是這樣；吃有多種組合才去選牌。 */
    private void showClaimActions(List<Claim> options) {
        List<TablePanel.ActionItem> actions = new ArrayList<>();
        for (Claim.Type type : Claim.Type.values()) {
            List<Claim> ofType = options.stream().filter(claim -> claim.type() == type).collect(Collectors.toList());
            if (ofType.isEmpty()) {
                continue;
            }
            Claim first = ofType.get(0);
            actions.add(promptAction(first.verb(), type == Claim.Type.WIN ? Theme.RED : Theme.BUTTON,
                    ofType.size() == 1 ? () -> declareClaim(first) : () -> pickClaim(options, ofType, null)));
        }
        actions.add(promptAction("過", Theme.PANEL_LIGHT, this::passClaim));
        String hint = claimHint() + "，要宣告嗎？";
        view.showActions(hint, false, actions);
        updatePromptHint(hint);
    }

    /** 選宣告要用的手牌。點的牌只符合一種組合就宣告；吃牌還有其他組合時留著這張，再選一張。 */
    private void pickClaim(List<Claim> options, List<Claim> claims, Tile first) {
        Set<Tile> tiles = Claim.handTiles(claims);
        tiles.remove(first);
        String hint = claimHint() + (first == null ? "：選要" + claims.get(0).verb() + "的牌" : "：再選一張");
        view.showPick(hint, tiles, first, tile -> {
            List<Claim> matching = Claim.using(claims, tile);
            if (matching.size() == 1) {
                declareClaim(matching.get(0));
            } else {
                pickClaim(options, matching, tile);
            }
        }, List.of(promptAction("取消", Theme.PANEL_LIGHT, () -> showClaimActions(options))));
        updatePromptHint(hint);
    }

    /** 過牌就算想完了，下一家就算是自己，摸牌後也是新的一次。 */
    private void passClaim() {
        finishThinking();
        resolveClaims();
    }

    private void declareClaim(Claim claim) {
        pendingClaims.put(HUMAN_SEAT, claim);
        resolveClaims();
    }

    /** 聽牌模式只胡不吃碰槓；代打就照電腦的判斷。 */
    private Claim automatedClaim(List<Claim> options) {
        if (listeningWaits == null) {
            return proxy.chooseClaim(round, HUMAN_SEAT, options);
        }
        return options.stream().filter(claim -> claim.type() == Claim.Type.WIN).findFirst().orElse(null);
    }

    private void resolveClaims() {
        round.resolveClaims(pendingClaims);
        pendingClaims = null;
        advance();
    }

    private void finishRound() {
        idle(" ");
        Settlement settlement = session.settle(round.result());
        view.refresh(session, round);
        Runnable showResult = roundEndHandler.onRoundEnd(session, round, settlement, this::startRound);
        // 有動畫時先讓胡牌的大字演完，再跳出結果
        schedule(settings.animationEnabled() ? TableCanvas.WIN_BANNER_MS : 0, showResult);
    }

    private void idle(String hint) {
        view.showActions(status() + hint, false, List.of());
    }

    private String status() {
        if (listeningWaits != null) {
            return "【聽牌中：等 " + listeningWaits.stream().map(Tile::label).collect(Collectors.joining("、")) + "】";
        }
        return autoPlay ? "【電腦代打中】" : "";
    }

    private void schedule(int delayMs, Runnable action) {
        timer = new Timer(delayMs, e -> {
            if (!stopped) {
                action.run();
            }
        });
        timer.setRepeats(false);
        timer.start();
    }
}
