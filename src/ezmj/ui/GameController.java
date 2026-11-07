package ezmj.ui;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ezmj.bot.Bot;
import ezmj.career.Records;
import ezmj.engine.Claim;
import ezmj.engine.GameSave;
import ezmj.engine.GameSession;
import ezmj.engine.GameView;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.Settlement;
import ezmj.engine.TurnOptions;
import ezmj.engine.WallRing;
import ezmj.model.AppSettings;
import ezmj.model.Dice;
import ezmj.model.Tile;
import ezmj.table.TableFlow;

/**
 * 單機牌局：串接牌桌流程與畫面，全部在 EDT 上執行。行牌的流程在 TableFlow，演出來在 TablePresenter，
 * 這裡負責把兩邊接起來，再把真人的決定交回去。
 */
public final class GameController implements TableFlow.Listener, TablePresenter.Table {

    private static final int HUMAN_SEAT = 0;

    /** 一局結束時的處理。結算完立刻呼叫，先把該存的存好；回傳的動作在胡牌大字演完之後才執行，用來顯示結果。 */
    public interface RoundEndHandler {
        Runnable onRoundEnd(GameSession session, Round round, Settlement settlement, Runnable next);
    }

    private final GameSession session;
    private final TableFlow flow;
    private final TablePresenter presenter;
    private final RoundEndHandler roundEndHandler;
    private final AppSettings settings;
    private final Runnable checkpoint;
    private final Random diceRandom = new Random();
    private TablePanel view;
    private boolean usedAutoPlay;
    /** 正在接回存檔的一局，這局的骰子不寫進紀錄。 */
    private boolean resuming;

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
        // 單機才換說法，連線先照舊
        this.presenter = new TablePresenter(this, settings, true);
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
        }, TablePresenter::after, this);
    }

    void attach(TablePanel view) {
        this.view = view;
        presenter.attach(view);
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
        resuming = true;
        flow.resume(saved.round());
        resuming = false;
    }

    /** 存下目前的牌局，事件之間的狀態都是完整的，隨時可以存。存在那套紀錄的資料夾，封存起來。 */
    void save(Records records, long seed) throws IOException {
        records.saveGame(seed, session, round(), usedAutoPlay, flow.thinking(HUMAN_SEAT));
    }

    public void stop() {
        presenter.stop();
        flow.stop();
    }

    @Override
    public Round round() {
        return flow.round();
    }

    @Override
    public GameView game() {
        return session;
    }

    @Override
    public List<Tile> listeningWaits() {
        return flow.listeningWaits(HUMAN_SEAT);
    }

    @Override
    public boolean isAutoPlay() {
        return flow.isAutoPlay(HUMAN_SEAT);
    }

    @Override
    public boolean isWaiting(int waitId) {
        return flow.isWaiting(HUMAN_SEAT, waitId);
    }

    @Override
    public void discard(int waitId, Tile tile) {
        flow.discard(HUMAN_SEAT, waitId, tile);
    }

    @Override
    public void declareListening(int listenId) {
        flow.declareListening(HUMAN_SEAT, listenId);
    }

    @Override
    public void declareSelfWin(int waitId) {
        flow.declareSelfWin(HUMAN_SEAT, waitId);
    }

    @Override
    public void declareKong(int waitId, Tile tile) {
        flow.declareKong(HUMAN_SEAT, waitId, tile);
    }

    @Override
    public void answerClaim(int waitId, Claim claim) {
        flow.answerClaim(HUMAN_SEAT, waitId, claim);
    }

    void restartCountdown() {
        flow.restartCountdown();
    }

    /** 由牌桌點牌事件呼叫。 */
    void humanDiscard(Tile tile) {
        presenter.humanDiscard(tile);
    }

    @Override
    public void log(String message) {
        view.appendLog(message);
    }

    /**
     * 每局開門的骰子另外擲，不動牌局的亂數，同一個種子打出來的牌才一樣。擲骰子在發牌之前，所以紀錄寫在補花前面。
     * 接回存檔時骰子沒有存，重新擲一次，只是牌牆開門的位置可能和存檔前不同。
     */
    @Override
    public void roundOpened(Round round) {
        Dice dice = Dice.roll(diceRandom);
        view.bindRound(round, dice);
        if (!resuming) {
            view.appendLog(WallRing.announce(round, dice));
        }
    }

    /** 骰子與發牌的動畫演完才開始行牌，這段時間誰都不能動作。 */
    @Override
    public int dealt(Round round) {
        if (!settings.animationEnabled() || round.phase() == Round.Phase.FINISHED) {
            return 0;
        }
        return presenter.showDeal();
    }

    @Override
    public void event(RoundEvent event) {
        presenter.event(event);
    }

    @Override
    public void refresh(Round round) {
        view.refresh(session, round);
    }

    @Override
    public void idle(int seat) {
        presenter.showIdle(seat < 0 || seat == HUMAN_SEAT ? " " : "等待 " + round().player(seat).name() + " 出牌…");
    }

    @Override
    public void checkpoint() {
        checkpoint.run();
    }

    @Override
    public void askTurn(int seat, int waitId, TurnOptions options) {
        presenter.askTurn(waitId, options);
    }

    @Override
    public void askClaim(int seat, int waitId, List<Claim> options) {
        presenter.askClaim(waitId, options);
    }

    @Override
    public void canListen(int seat, int listenId) {
        presenter.canListen(listenId);
    }

    @Override
    public void thinkingFinished(int seat) {
        view.showThinkTime(averageThinkSeconds(), thinkCount());
    }

    @Override
    public void roundFinished(Round round, Settlement settlement) {
        presenter.showIdle(" ");
        view.refresh(session, round);
        Runnable showResult = roundEndHandler.onRoundEnd(session, round, settlement, this::startRound);
        // 有動畫時先讓胡牌的大字演完，再跳出結果；大胡演得比較久
        TablePresenter.after(settings.animationEnabled() ? TableCanvas.endBannerMs(round.result()) : 0, () -> {
            if (!presenter.isStopped()) {
                showResult.run();
            }
        });
    }

    @Override
    public void secondsLeft(int seconds) {
        presenter.secondsLeft(seconds);
    }
}
