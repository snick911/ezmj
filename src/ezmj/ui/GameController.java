package ezmj.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.Timer;

import ezmj.bot.Bot;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.Settlement;
import ezmj.engine.TurnOptions;
import ezmj.model.AppSettings;
import ezmj.model.Tile;

/**
 * 串接引擎與畫面的流程控制，全部在 EDT 上執行。
 * 電腦的動作以 Timer 延遲，真人的動作則等待按鈕或點牌事件。
 */
public final class GameController {

    private static final int HUMAN_SEAT = 0;
    private static final int BOT_TURN_DELAY_MS = 550;
    private static final int CLAIM_DELAY_MS = 200;

    /** 一局結束時要顯示結果的畫面操作。 */
    public interface RoundEndHandler {
        void onRoundEnd(GameSession session, Round round, Settlement settlement, Runnable next);
    }

    private final GameSession session;
    private final Bot bot;
    private final RoundEndHandler roundEndHandler;
    private final AppSettings settings;
    private TablePanel view;
    private Round round;
    private Timer timer;
    private Map<Integer, Claim> pendingClaims;
    private boolean stopped;
    private Timer countdown;
    private int secondsLeft;
    private String promptHint;
    private Runnable promptTimeout;

    /** settings 隨時會被選單改變，每次要用時才讀。 */
    public GameController(GameSession session, Bot bot, RoundEndHandler roundEndHandler, AppSettings settings) {
        this.session = session;
        this.bot = bot;
        this.roundEndHandler = roundEndHandler;
        this.settings = settings;
    }

    void attach(TablePanel view) {
        this.view = view;
    }

    public void startRound() {
        round = session.newRound(message -> view.appendLog(message));
        round.setListener(this::onRoundEvent);
        view.bindRound(round);
        round.start();
        advance();
    }

    private void onRoundEvent(RoundEvent event) {
        boolean animated = settings.animationEnabled();
        if (animated) {
            view.animate(event);
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
            default:
                playSound(SoundEffects.Cue.DRAWN_GAME, 0);
                break;
        }
    }

    private void playSound(SoundEffects.Cue cue, int delayMs) {
        Timer delay = new Timer(delayMs, e -> {
            if (!stopped) {
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

    private void beginTurn() {
        int seat = round.current();
        TurnOptions options = round.turnOptions(seat);
        if (seat == HUMAN_SEAT) {
            showHumanTurn(options);
        } else {
            idle("等待 " + round.player(seat).name() + " 出牌…");
            schedule(BOT_TURN_DELAY_MS, () -> {
                bot.playTurn(round, seat, options);
                advance();
            });
        }
    }

    private void showHumanTurn(TurnOptions options) {
        List<TablePanel.ActionItem> actions = new ArrayList<>();
        if (options.canSelfWin()) {
            actions.add(new TablePanel.ActionItem("自摸", Theme.RED, () -> {
                round.declareSelfWin(HUMAN_SEAT);
                advance();
            }));
        }
        for (Tile tile : options.concealedKongs()) {
            actions.add(new TablePanel.ActionItem("暗槓 " + tile.label(), Theme.BUTTON, () -> {
                round.declareConcealedKong(HUMAN_SEAT, tile);
                advance();
            }));
        }
        for (Tile tile : options.addKongs()) {
            actions.add(new TablePanel.ActionItem("加槓 " + tile.label(), Theme.BUTTON, () -> {
                round.declareAddKong(HUMAN_SEAT, tile);
                advance();
            }));
        }
        String hint = "輪到你了：點一張手牌打出";
        view.showActions(hint, true, actions);
        beginPrompt(hint, () -> humanDiscard(round.rightmostTile(HUMAN_SEAT)));
    }

    /** 真人開始考慮；時間到還沒決定就執行 onTimeout。 */
    private void beginPrompt(String hint, Runnable onTimeout) {
        promptHint = hint;
        promptTimeout = onTimeout;
        restartCountdown();
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
        if (humanOptions.isEmpty()) {
            idle(" ");
            schedule(CLAIM_DELAY_MS, this::resolveClaims);
            return;
        }
        List<TablePanel.ActionItem> actions = new ArrayList<>();
        for (Claim claim : humanOptions) {
            actions.add(new TablePanel.ActionItem(claim.label(),
                    claim.type() == Claim.Type.WIN ? Theme.RED : Theme.BUTTON, () -> {
                pendingClaims.put(HUMAN_SEAT, claim);
                resolveClaims();
            }));
        }
        actions.add(new TablePanel.ActionItem("過", Theme.PANEL_LIGHT, this::resolveClaims));
        String hint = round.player(round.discarder()).name() + " 打出 " + round.claimTile().label() + "，要宣告嗎？";
        view.showActions(hint, false, actions);
        beginPrompt(hint, this::resolveClaims);
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
        // 有動畫時先讓胡牌的大字演完，再跳出結果
        schedule(settings.animationEnabled() ? TableCanvas.WIN_BANNER_MS : 0,
                () -> roundEndHandler.onRoundEnd(session, round, settlement, this::startRound));
    }

    private void idle(String hint) {
        view.showActions(hint, false, List.of());
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
