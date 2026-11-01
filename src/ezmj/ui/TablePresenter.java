package ezmj.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.Timer;

import ezmj.engine.Claim;
import ezmj.engine.GameView;
import ezmj.engine.RoundEvent;
import ezmj.engine.RoundView;
import ezmj.engine.TurnOptions;
import ezmj.model.AppSettings;
import ezmj.model.Gender;
import ezmj.model.Tile;

/**
 * 把一桌牌演到畫面上：動畫、音效、語音、動作列與選牌，全部在 EDT 上執行。
 * 局面從哪裡來、決定交給誰由 Table 決定，所以單機和連線共用這一份。座位 0 一定是自己。
 */
final class TablePresenter {

    private static final int SELF_SEAT = 0;
    private static final String TURN_HINT = "輪到你了：點一張手牌打出";
    private static final int MAX_LISTEN_BUTTONS = 4;
    private static final int MAX_LISTED_WAITS = 3;

    /** 這一桌牌的局面與自己的決定。waitId 分得出決定是不是這次等待的。 */
    interface Table {
        RoundView round();

        GameView game();

        /** 自己在聽牌模式等的牌，沒有時是 null。 */
        List<Tile> listeningWaits();

        boolean isAutoPlay();

        boolean isWaiting(int waitId);

        void discard(int waitId, Tile tile);

        void declareListening(int waitId, Tile tile);

        void declareSelfWin(int waitId);

        void declareKong(int waitId, Tile tile);

        /** null 表示過。 */
        void answerClaim(int waitId, Claim claim);
    }

    private final Table table;
    private final AppSettings settings;
    private TablePanel view;
    private boolean stopped;
    private int secondsLeft;
    private String promptHint = " ";
    /** 目前等待的編號，分得出按鈕是不是這次等待的。 */
    private int promptId;

    TablePresenter(Table table, AppSettings settings) {
        this.table = table;
        this.settings = settings;
    }

    void attach(TablePanel view) {
        this.view = view;
    }

    void stop() {
        stopped = true;
    }

    boolean isStopped() {
        return stopped;
    }

    /** 由牌桌點牌事件呼叫。 */
    void humanDiscard(Tile tile) {
        table.discard(promptId, tile);
    }

    /** 演發牌，回傳要演多久。 */
    int showDeal() {
        view.refresh(table.game(), table.round());
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
            if (!table.round().player(seat).flowers().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    void event(RoundEvent event) {
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
                if (event.seat() == SELF_SEAT) {
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

    /** 這個座位喊牌的聲音。自己看環境設定，其他座位看角色的性別。 */
    private Gender voiceOf(int seat) {
        return seat == SELF_SEAT ? settings.playerGender() : table.round().player(seat).gender();
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

    /** 這個座位剛宣告聽牌。 */
    void listening(int seat) {
        if (settings.voiceEnabled()) {
            VoiceLines.play(VoiceLines.Line.LISTEN, voiceOf(seat));
        }
    }

    /** 輪到自己。readyDiscards 是打哪張牌會聽哪些牌。 */
    void askTurn(int waitId, TurnOptions options, Map<Tile, List<Tile>> readyDiscards) {
        promptId = waitId;
        showTurnActions(options, readyDiscards);
    }

    void askClaim(int waitId, List<Claim> options) {
        promptId = waitId;
        showClaimActions(options);
    }

    private void addListenButtons(List<TablePanel.ActionItem> actions, Map<Tile, List<Tile>> readyDiscards) {
        readyDiscards.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<Tile, List<Tile>> entry) -> entry.getValue().size()).reversed())
                .limit(MAX_LISTEN_BUTTONS)
                .forEach(entry -> actions.add(promptAction(
                        "打" + entry.getKey().label() + " 聽" + waitsLabel(entry.getValue()), Theme.BUTTON,
                        () -> table.declareListening(promptId, entry.getKey()))));
    }

    private static String waitsLabel(List<Tile> waits) {
        if (waits.size() > MAX_LISTED_WAITS) {
            return " " + waits.size() + " 種";
        }
        return waits.stream().map(Tile::label).collect(Collectors.joining("、"));
    }

    private void showTurnActions(TurnOptions options, Map<Tile, List<Tile>> readyDiscards) {
        List<TablePanel.ActionItem> actions = new ArrayList<>();
        if (options.canSelfWin()) {
            actions.add(promptAction("自摸", Theme.RED, () -> table.declareSelfWin(promptId)));
        }
        if (!options.concealedKongs().isEmpty() || !options.addKongs().isEmpty()) {
            actions.add(promptAction("槓", Theme.BUTTON, () -> pickTurnKong(options, readyDiscards)));
        }
        addListenButtons(actions, readyDiscards);
        view.showActions(TURN_HINT, true, actions);
        updatePromptHint(TURN_HINT);
    }

    /** 自己的回合選要槓哪張，只有一張能槓就直接槓。 */
    private void pickTurnKong(TurnOptions options, Map<Tile, List<Tile>> readyDiscards) {
        Set<Tile> tiles = new LinkedHashSet<>(options.concealedKongs());
        tiles.addAll(options.addKongs());
        if (tiles.size() == 1) {
            table.declareKong(promptId, tiles.iterator().next());
            return;
        }
        String hint = "選要槓的牌";
        view.showPick(hint, tiles, null, tile -> table.declareKong(promptId, tile),
                List.of(promptAction("取消", Theme.PANEL_LIGHT, () -> showTurnActions(options, readyDiscards))));
        updatePromptHint(hint);
    }

    /** 這次等待的按鈕。按住時倒數到了、放開才送出的話，局面已經往下走，舊按鈕不能再有作用。 */
    private TablePanel.ActionItem promptAction(String label, Color color, Runnable action) {
        int id = promptId;
        return new TablePanel.ActionItem(label, color, () -> {
            if (table.isWaiting(id)) {
                action.run();
            }
        });
    }

    void secondsLeft(int seconds) {
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
        RoundView round = table.round();
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
        table.answerClaim(promptId, claim);
    }

    /** 沒有要自己決定的事，收起按鈕。倒數的秒數會接在這段提示後面。 */
    void showIdle(String hint) {
        promptHint = status() + hint;
        view.showActions(promptHint, false, List.of());
    }

    private String status() {
        List<Tile> waits = table.listeningWaits();
        if (waits != null) {
            return "【聽牌中：等 " + waits.stream().map(Tile::label).collect(Collectors.joining("、")) + "】";
        }
        return table.isAutoPlay() ? "【電腦代打中】" : "";
    }

    static Runnable after(int delayMs, Runnable action) {
        Timer timer = new Timer(delayMs, e -> action.run());
        timer.setRepeats(false);
        timer.start();
        return timer::stop;
    }
}
