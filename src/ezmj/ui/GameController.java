package ezmj.ui;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
import ezmj.engine.TurnOptions;
import ezmj.model.AppSettings;
import ezmj.model.Gender;
import ezmj.model.Tile;
import ezmj.table.TableFlow;

/**
 * 串接牌桌流程與畫面，全部在 EDT 上執行。行牌的流程在 TableFlow，這裡負責演出來、顯示按鈕，
 * 再把真人的決定交回去。
 */
public final class GameController implements TableFlow.Listener {

    private static final int HUMAN_SEAT = 0;
    private static final String TURN_HINT = "輪到你了：點一張手牌打出";
    private static final int MAX_LISTEN_BUTTONS = 4;
    private static final int MAX_LISTED_WAITS = 3;

    /** 一局結束時的處理。結算完立刻呼叫，先把該存的存好；回傳的動作在胡牌大字演完之後才執行，用來顯示結果。 */
    public interface RoundEndHandler {
        Runnable onRoundEnd(GameSession session, Round round, Settlement settlement, Runnable next);
    }

    private final GameSession session;
    private final TableFlow flow;
    private final RoundEndHandler roundEndHandler;
    private final AppSettings settings;
    private final Runnable checkpoint;
    private TablePanel view;
    private boolean stopped;
    private int secondsLeft;
    private String promptHint;
    /** 目前等待的編號，分得出按鈕是不是這次等待的。 */
    private int promptId;
    private boolean usedAutoPlay;

    /**
     * proxy 是電腦代打時替真人決定的電腦。settings 隨時會被選單改變，每次要用時才讀。
     * checkpoint 在每次輪到真人決定時呼叫，用來存檔。
     */
    public GameController(GameSession session, Bot bot, Bot proxy, RoundEndHandler roundEndHandler,
                          AppSettings settings, Runnable checkpoint) {
        this.session = session;
        this.roundEndHandler = roundEndHandler;
        this.settings = settings;
        this.checkpoint = checkpoint;
        this.flow = new TableFlow(session, Set.of(HUMAN_SEAT), bot, proxy, new TableFlow.Pacing() {
            @Override
            public int turnDelayMs() {
                return settings.gameSpeed().turnDelayMs();
            }

            @Override
            public int claimDelayMs() {
                return settings.gameSpeed().claimDelayMs();
            }

            @Override
            public int waitSeconds() {
                return settings.waitSeconds();
            }
        }, GameController::after, this);
    }

    void attach(TablePanel view) {
        this.view = view;
    }

    /** 電腦代打的開關。正在等真人決定時，打開就立刻交給電腦。 */
    void toggleAutoPlay() {
        boolean on = !flow.isAutoPlay(HUMAN_SEAT);
        view.setAutoPlay(on);
        if (on) {
            usedAutoPlay = true;
        }
        flow.setAutoPlay(HUMAN_SEAT, on);
    }

    /** 這場牌局已經打完，結果視窗還沒看完。 */
    boolean isOver() {
        return session.isOver();
    }

    /** 出錯時記下的局面。 */
    String describe() {
        String state = round() == null ? "還沒開局" : "階段 " + round().phase() + "，輪到座位 " + round().current();
        return "打完 " + session.roundsPlayed() + " 局，" + state + "，電腦 " + session.config().botLevels();
    }

    /** 這場牌局是否用過電腦代打。用過的不列入榮譽榜。 */
    boolean usedAutoPlay() {
        return usedAutoPlay;
    }

    /** 這場到目前的平均思考秒數。 */
    double averageThinkSeconds() {
        return flow.thinking(HUMAN_SEAT).averageSeconds();
    }

    int thinkCount() {
        return flow.thinking(HUMAN_SEAT).count();
    }

    public void startRound() {
        flow.startRound();
    }

    /** 接回存檔的牌局。下一局還沒開始的話就直接開局。 */
    void resume(GameSave.Loaded saved) {
        usedAutoPlay = saved.usedAutoPlay();
        flow.thinking(HUMAN_SEAT).restore(saved.thinkNanos(), saved.thinkCount());
        view.showThinkTime(averageThinkSeconds(), thinkCount());
        if (saved.round() == null) {
            startRound();
            return;
        }
        flow.setListening(HUMAN_SEAT, saved.listeningWaits());
        flow.resume(saved.round());
    }

    /** 存下目前的牌局，事件之間的狀態都是完整的，隨時可以存。 */
    void save(Path file, long seed) throws IOException {
        GameSave.save(file, seed, session, round(), usedAutoPlay, flow.listeningWaits(HUMAN_SEAT), flow.thinking(HUMAN_SEAT));
    }

    public void stop() {
        stopped = true;
        flow.stop();
    }

    public Round round() {
        return flow.round();
    }

    void restartCountdown() {
        flow.restartCountdown();
    }

    /** 由牌桌點牌事件呼叫。 */
    void humanDiscard(Tile tile) {
        flow.discard(HUMAN_SEAT, promptId, tile);
    }

    @Override
    public void log(String message) {
        view.appendLog(message);
    }

    @Override
    public void roundOpened(Round round) {
        view.bindRound(round);
    }

    /** 發牌動畫演完才開始行牌，這段時間誰都不能動作。 */
    @Override
    public int dealt(Round round) {
        if (!settings.animationEnabled() || round.phase() == Round.Phase.FINISHED) {
            return 0;
        }
        view.refresh(session, round);
        showIdle("發牌中…");
        view.animateDeal();
        if (settings.soundEnabled()) {
            for (int packet = 0; packet < TableCanvas.DEAL_PACKETS; packet++) {
                playSound(SoundEffects.Cue.DEAL, packet * TableCanvas.DEAL_INTERVAL_MS + TableCanvas.DEAL_FLIGHT_MS);
            }
            if (anyFlowerExposed()) {
                playSound(SoundEffects.Cue.FLOWER, TableCanvas.DEAL_MS);
            }
        }
        return TableCanvas.DEAL_MS;
    }

    private boolean anyFlowerExposed() {
        for (int seat = 0; seat < 4; seat++) {
            if (!round().player(seat).flowers().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void event(RoundEvent event) {
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
        return seat == HUMAN_SEAT ? settings.playerGender() : round().player(seat).gender();
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

    @Override
    public void refresh(Round round) {
        view.refresh(session, round);
    }

    @Override
    public void idle(int seat) {
        showIdle(seat < 0 || seat == HUMAN_SEAT ? " " : "等待 " + round().player(seat).name() + " 出牌…");
    }

    @Override
    public void checkpoint() {
        checkpoint.run();
    }

    @Override
    public void askTurn(int seat, int waitId, TurnOptions options) {
        promptId = waitId;
        showTurnActions(options);
    }

    @Override
    public void askClaim(int seat, int waitId, List<Claim> options) {
        promptId = waitId;
        showClaimActions(options);
    }

    @Override
    public void listening(int seat) {
        if (settings.voiceEnabled()) {
            VoiceLines.play(VoiceLines.Line.LISTEN, voiceOf(seat));
        }
    }

    @Override
    public void thinkingFinished(int seat) {
        view.showThinkTime(averageThinkSeconds(), thinkCount());
    }

    @Override
    public void roundFinished(Round round, Settlement settlement) {
        showIdle(" ");
        view.refresh(session, round);
        Runnable showResult = roundEndHandler.onRoundEnd(session, round, settlement, this::startRound);
        // 有動畫時先讓胡牌的大字演完，再跳出結果
        after(settings.animationEnabled() ? TableCanvas.WIN_BANNER_MS : 0, () -> {
            if (!stopped) {
                showResult.run();
            }
        });
    }

    private void addListenButtons(List<TablePanel.ActionItem> actions) {
        round().readyDiscards(HUMAN_SEAT).entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Tile, List<Tile>> entry) -> entry.getValue().size()).reversed())
                .limit(MAX_LISTEN_BUTTONS)
                .forEach(entry -> actions.add(promptAction(
                        "打" + entry.getKey().label() + " 聽" + waitsLabel(entry.getValue()), Theme.BUTTON,
                        () -> flow.declareListening(HUMAN_SEAT, promptId, entry.getKey()))));
    }

    private static String waitsLabel(List<Tile> waits) {
        if (waits.size() > MAX_LISTED_WAITS) {
            return " " + waits.size() + " 種";
        }
        return waits.stream().map(Tile::label).collect(Collectors.joining("、"));
    }

    private void showTurnActions(TurnOptions options) {
        List<TablePanel.ActionItem> actions = new ArrayList<>();
        if (options.canSelfWin()) {
            actions.add(promptAction("自摸", Theme.RED, () -> flow.declareSelfWin(HUMAN_SEAT, promptId)));
        }
        if (!options.concealedKongs().isEmpty() || !options.addKongs().isEmpty()) {
            actions.add(promptAction("槓", Theme.BUTTON, () -> pickTurnKong(options)));
        }
        addListenButtons(actions);
        view.showActions(TURN_HINT, true, actions);
        updatePromptHint(TURN_HINT);
    }

    /** 自己的回合選要槓哪張，只有一張能槓就直接槓。 */
    private void pickTurnKong(TurnOptions options) {
        Set<Tile> tiles = new LinkedHashSet<>(options.concealedKongs());
        tiles.addAll(options.addKongs());
        if (tiles.size() == 1) {
            flow.declareKong(HUMAN_SEAT, promptId, tiles.iterator().next());
            return;
        }
        String hint = "選要槓的牌";
        view.showPick(hint, tiles, null, tile -> flow.declareKong(HUMAN_SEAT, promptId, tile),
                List.of(promptAction("取消", Theme.PANEL_LIGHT, () -> showTurnActions(options))));
        updatePromptHint(hint);
    }

    /** 這次等待的按鈕。按住時倒數到了、放開才送出的話，局面已經往下走，舊按鈕不能再有作用。 */
    private TablePanel.ActionItem promptAction(String label, Color color, Runnable action) {
        int id = promptId;
        return new TablePanel.ActionItem(label, color, () -> {
            if (flow.isWaiting(HUMAN_SEAT, id)) {
                action.run();
            }
        });
    }

    @Override
    public void secondsLeft(int seconds) {
        secondsLeft = seconds;
        showPromptHint();
    }

    private void showPromptHint() {
        view.setHint(secondsLeft > 0 ? promptHint + "（剩 " + secondsLeft + " 秒）" : promptHint);
    }

    /** 換提示文字但不重新計時：進出選牌不能多賺時間。 */
    private void updatePromptHint(String hint) {
        promptHint = hint;
        showPromptHint();
    }

    private String claimHint() {
        Round round = round();
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
        actions.add(promptAction("過", Theme.PANEL_LIGHT, () -> declareClaim(null)));
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

    private void declareClaim(Claim claim) {
        flow.answerClaim(HUMAN_SEAT, promptId, claim);
    }

    private void showIdle(String hint) {
        view.showActions(status() + hint, false, List.of());
    }

    private String status() {
        List<Tile> waits = flow.listeningWaits(HUMAN_SEAT);
        if (waits != null) {
            return "【聽牌中：等 " + waits.stream().map(Tile::label).collect(Collectors.joining("、")) + "】";
        }
        return flow.isAutoPlay(HUMAN_SEAT) ? "【電腦代打中】" : "";
    }

    private static Runnable after(int delayMs, Runnable action) {
        Timer timer = new Timer(delayMs, e -> action.run());
        timer.setRepeats(false);
        timer.start();
        return timer::stop;
    }
}
