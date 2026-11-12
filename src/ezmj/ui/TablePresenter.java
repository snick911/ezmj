package ezmj.ui;

import java.awt.Color;
import java.util.ArrayList;
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
import ezmj.model.BambooCall;
import ezmj.model.Gender;
import ezmj.model.Tile;

/**
 * 把一桌牌演到畫面上：動畫、音效、語音、動作列與選牌，全部在 EDT 上執行。
 * 局面從哪裡來、決定交給誰由 Table 決定，所以單機和連線共用這一份。座位 0 一定是自己。
 */
final class TablePresenter {

    private static final int SELF_SEAT = 0;
    private static final String TURN_HINT = "輪到你了：點一張手牌打出";
    /** 報聽選牌的提示最多列幾種打法，再多就只說幾種。 */
    private static final int MAX_LISTED_DISCARDS = 4;
    /** 每種打法最多列幾張聽的牌，再多就只說幾種。 */
    private static final int MAX_LISTED_WAITS = 3;

    /** 這一桌牌的局面與自己的決定。waitId 分得出決定是不是這次等待的。 */
    interface Table {
        RoundView round();

        GameView game();

        /** 自己報聽後等的牌，沒有報聽時是 null。 */
        List<Tile> listeningWaits();

        /** 專家模式：聽牌中不列出等哪些牌。 */
        boolean isExpert();

        boolean isAutoPlay();

        boolean isWaiting(int waitId);

        void discard(int waitId, Tile tile);

        /** 打出這張牌並報聽，這張牌要是 askTurn 給的 readyDiscards 裡的。 */
        void discardAndListen(int waitId, Tile tile);

        /** 報聽，listenId 是 canListen 給的編號，過期了就沒有作用。 */
        void declareListening(int listenId);

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
    /** 這次輪到自己時，打哪張牌會聽哪些牌；空的就沒有聽牌按鈕。 */
    private Map<Tile, List<Tile>> readyDiscards = Map.of();
    /** 可以報聽時的編號，沒有時是 0。報聽的按鈕不屬於哪一次等待，等別人打牌或宣告時都在。 */
    private int listenOffer;
    /** 目前動作列上的按鈕，不含報聽。報聽的按鈕出現或消失時用來重畫；選牌或輪到自己時是 null。 */
    private List<TablePanel.ActionItem> shownActions;
    /** 正在問自己要不要吃碰槓胡。 */
    private boolean claimAsked;
    /** 問自己的時候被別人吃碰槓走了，下一次閒著的提示先說一聲。 */
    private String takenNote;
    /** 自己剛吃、碰或槓，接下來要打一張牌，提示要明顯。 */
    private String claimedVerb;

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

    /**
     * 先擲骰子再從牌牆發牌，回傳要演多久。骰子停下來之前提示寫「擲骰子…」，之後寫「發牌中…」，
     * 這段時間裡提示被換掉了就不再改回來。announceStreak 時，連莊的局在擲骰子前先跳出「連莊　連 N 拉 N」的大字。
     */
    int showDeal(boolean announceStreak) {
        view.refresh(table.game(), table.round());
        int streak = announceStreak ? table.round().streak() : 0;
        int lead = TableCanvas.dealLeadMs(streak);
        view.animateDeal(streak);
        if (lead > 0) {
            showIdle("連莊：連" + streak + "拉" + streak);
            String streakHint = promptHint;
            after(lead, () -> {
                if (!stopped && streakHint.equals(promptHint)) {
                    showDiceHint();
                }
            });
        } else {
            showDiceHint();
        }
        if (settings.soundEnabled()) {
            for (int bounce : TableCanvas.DICE_BOUNCE_MS) {
                playSound(SoundEffects.Cue.DEAL, lead + bounce);
            }
            for (int packet = 0; packet < TableCanvas.DEAL_PACKETS; packet++) {
                playSound(SoundEffects.Cue.DEAL,
                        lead + TableCanvas.DICE_MS + packet * TableCanvas.DEAL_INTERVAL_MS + TableCanvas.DEAL_FLIGHT_MS);
            }
            if (anyFlowerExposed()) {
                playSound(SoundEffects.Cue.FLOWER, lead + TableCanvas.DICE_MS + TableCanvas.DEAL_MS);
            }
        }
        return lead + TableCanvas.DICE_MS + TableCanvas.DEAL_MS;
    }

    /**
     * 一局結束、輸贏算好之後呼叫。贏家的手牌在胡牌時就推倒了；這裡讓籌碼在胡牌大字快演完時從付錢的人飛向贏家，
     * 各家跳出輸贏，計分表的數字跟著滾，籌碼落地時有聲音。回傳要等多久才跳出結果，沒有動畫是 0。
     */
    int showSettlement(int[] deltas) {
        if (!settings.animationEnabled()) {
            return 0;
        }
        RoundView round = table.round();
        if (TableCanvas.moneyMoved(deltas)) {
            int start = TableCanvas.chipsStartMs(round.result());
            int[] chips = new int[deltas.length];
            for (int seat = 0; seat < chips.length; seat++) {
                chips[seat] = table.game().player(seat).chips();
            }
            view.animateSettlement(deltas, chips, start);
            if (settings.soundEnabled()) {
                for (int chip = 0; chip < TableCanvas.CHIPS_PER_PAYER; chip++) {
                    playSound(SoundEffects.Cue.DEAL,
                            start + TableCanvas.CHIP_FLIGHT_MS + chip * TableCanvas.CHIP_GAP_MS);
                }
            }
        }
        return TableCanvas.endAnimationMs(round.result(), deltas);
    }

    /** 骰子丟出去：提示寫「擲骰子…」，骰子停下來後改成「發牌中…」。 */
    private void showDiceHint() {
        showIdle("擲骰子…");
        String diceHint = promptHint;
        after(TableCanvas.DICE_MS, () -> {
            if (!stopped && diceHint.equals(promptHint)) {
                showIdle("發牌中…");
            }
        });
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
        track(event);
        // 有沒有動畫都要讓牌桌知道，專家模式靠這個決定棄牌要不要顯示
        view.noteEvent(event);
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
            case LISTEN:
                // 喊「聽牌」就夠了
                break;
            default:
                playSound(SoundEffects.Cue.DRAWN_GAME, 0);
                break;
        }
    }

    /**
     * 跟著事件收起過期的東西：自己摸牌、吃碰槓或這局結束，報聽的機會就沒了；自己打出牌，打牌的提示就收起。
     * 問自己要不要宣告的時候被別人吃碰槓走，記下來讓之後的提示說一聲。
     */
    private void track(RoundEvent event) {
        boolean self = event.seat() == SELF_SEAT;
        switch (event.type()) {
            case DISCARD:
                claimAsked = false;
                if (self) {
                    claimedVerb = null;
                    view.setDiscardPrompt(false);
                }
                break;
            case CHOW:
            case PONG:
            case KONG:
                if (self) {
                    claimedVerb = event.type() == RoundEvent.Type.CHOW ? "吃" : event.type() == RoundEvent.Type.PONG ? "碰" : "槓";
                    listenOffer = 0;
                } else if (claimAsked) {
                    takenNote = table.round().player(event.seat()).name() + " "
                            + (event.type() == RoundEvent.Type.CHOW ? "吃" : event.type() == RoundEvent.Type.PONG ? "碰" : "槓")
                            + "走了 " + event.tile().label();
                }
                claimAsked = false;
                break;
            case DRAW:
                if (self) {
                    listenOffer = 0;
                }
                break;
            case LISTEN:
                if (self) {
                    listenOffer = 0;
                    if (claimAsked) {
                        // 報聽之後由電腦替你決定要不要胡，宣告的按鈕不用了
                        claimAsked = false;
                        showIdle("等待其他玩家決定…");
                    } else {
                        refreshListenButton();
                    }
                }
                break;
            case WIN:
            case SELF_WIN:
            case DRAWN_GAME:
                listenOffer = 0;
                claimedVerb = null;
                claimAsked = false;
                view.setDiscardPrompt(false);
                break;
            default:
                break;
        }
    }

    /** 這個座位喊牌的聲音。自己看環境設定，其他座位看角色的性別。 */
    private Gender voiceOf(int seat) {
        return seat == SELF_SEAT ? settings.playerGender() : table.round().player(seat).gender();
    }

    /** 這個座位的條子喊「X條」還是「X索」。自己一律喊條，其他人照名字，同一個人每次都一樣。 */
    private BambooCall bambooCallOf(int seat) {
        return seat == SELF_SEAT ? BambooCall.TIAO : BambooCall.forName(table.round().player(seat).name());
    }

    /**
     * 連莊的局開始時，司儀說「連莊，連N拉N」，和「連莊」的大字同時；沒有動畫時也說。語音關著時不說。
     */
    void announceStreak(int streak) {
        if (streak > 0 && settings.voiceEnabled()) {
            VoiceLines.announceStreak(streak);
        }
    }

    /** 喊牌和大字同時出現，打牌時喊出打的是什麼牌。用出聲那一家的聲音；流局沒有特定的人，由司儀宣布。 */
    private void speak(RoundEvent event) {
        Gender gender = event.seat() < 0 ? Gender.FEMALE : voiceOf(event.seat());
        switch (event.type()) {
            case DISCARD:
                VoiceLines.play(event.tile(), gender, bambooCallOf(event.seat()));
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
                VoiceLines.announceDrawnGame();
                break;
            case LISTEN:
                VoiceLines.play(VoiceLines.Line.LISTEN, gender);
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

    /**
     * 輪到自己。readyDiscards 是打哪張牌會聽哪些牌，有的話多一個半透明的聽牌按鈕，按了選要打的牌，打出就報聽。
     * 沒有給的話（還沒改好的連線），打牌後聽牌了會由 canListen 再問要不要報聽。
     */
    void askTurn(int waitId, TurnOptions options, Map<Tile, List<Tile>> readyDiscards) {
        promptId = waitId;
        this.readyDiscards = readyDiscards;
        claimAsked = false;
        showTurnActions(options);
    }

    void askClaim(int waitId, List<Claim> options) {
        promptId = waitId;
        showClaimActions(options);
    }

    /** 打出牌後聽牌了，可以報聽。按鈕一直留著，直到自己摸牌、吃碰槓、報聽或這局結束。 */
    void canListen(int listenId) {
        listenOffer = listenId;
        refreshListenButton();
    }

    /** 吃碰槓之後要打一張牌，常常忘記，所以提示放大、變金色並閃動，手牌外框也亮起來。 */
    private void showTurnActions(TurnOptions options) {
        List<TablePanel.ActionItem> actions = new ArrayList<>();
        if (options.canSelfWin()) {
            actions.add(promptAction("自摸", Theme.RED, () -> table.declareSelfWin(promptId)));
        }
        if (!options.concealedKongs().isEmpty() || !options.addKongs().isEmpty()) {
            actions.add(promptAction("槓", Theme.BUTTON, () -> pickTurnKong(options)));
        }
        if (!readyDiscards.isEmpty()) {
            actions.add(promptAction("聽牌", Theme.RED, () -> pickListenDiscard(options), true));
        }
        String hint = claimedVerb == null ? TURN_HINT : claimedVerb + "完了，要打一張牌出去：點一張手牌";
        shownActions = null;
        view.showActions(hint, true, actions);
        view.setDiscardPrompt(claimedVerb != null);
        updatePromptHint(hint);
    }

    /** 按了聽牌：只有打出去會聽牌的牌能點，加上金框，其他蓋上遮罩；點了就打出並報聽。 */
    private void pickListenDiscard(TurnOptions options) {
        String hint = "聽牌：點一張金框的牌打出　" + listenChoices();
        shownActions = null;
        view.showMarkedPick(hint, readyDiscards.keySet(), tile -> table.discardAndListen(promptId, tile),
                List.of(promptAction("取消", Theme.PANEL_LIGHT, () -> showTurnActions(options))));
        updatePromptHint(hint);
    }

    /** 打哪張聽哪些牌，例如「打一萬聽三條、六條　打九筒聽 5 種」；打法太多就只說幾種。 */
    private String listenChoices() {
        if (readyDiscards.size() > MAX_LISTED_DISCARDS) {
            return "（" + readyDiscards.size() + " 種打法）";
        }
        return readyDiscards.entrySet().stream()
                .map(entry -> "打" + entry.getKey().label() + "聽" + waitsLabel(entry.getValue()))
                .collect(Collectors.joining("　"));
    }

    private static String waitsLabel(List<Tile> waits) {
        if (waits.size() > MAX_LISTED_WAITS) {
            return " " + waits.size() + " 種";
        }
        return waits.stream().map(Tile::label).collect(Collectors.joining("、"));
    }

    /** 自己的回合選要槓哪張，只有一張能槓就直接槓。 */
    private void pickTurnKong(TurnOptions options) {
        Set<Tile> tiles = new LinkedHashSet<>(options.concealedKongs());
        tiles.addAll(options.addKongs());
        if (tiles.size() == 1) {
            table.declareKong(promptId, tiles.iterator().next());
            return;
        }
        String hint = "選要槓的牌";
        shownActions = null;
        view.showPick(hint, tiles, null, tile -> table.declareKong(promptId, tile),
                List.of(promptAction("取消", Theme.PANEL_LIGHT, () -> showTurnActions(options))));
        updatePromptHint(hint);
    }

    /** 動作列換成這些按鈕，可以報聽的話後面再加報聽。 */
    private void show(String hint, List<TablePanel.ActionItem> actions) {
        shownActions = actions;
        view.showActions(hint, false, withListen(actions));
    }

    private List<TablePanel.ActionItem> withListen(List<TablePanel.ActionItem> actions) {
        if (listenOffer == 0) {
            return actions;
        }
        List<TablePanel.ActionItem> all = new ArrayList<>(actions);
        all.add(new TablePanel.ActionItem("聽牌", Theme.RED, this::declareListening));
        return all;
    }

    /** 按了就先收起按鈕，不會按兩次；真的成立要等事件。 */
    private void declareListening() {
        int id = listenOffer;
        listenOffer = 0;
        refreshListenButton();
        if (id != 0) {
            table.declareListening(id);
        }
    }

    /** 報聽的按鈕出現或消失。選牌或輪到自己時動作列不動，回到一般的按鈕時就會跟著出現。 */
    private void refreshListenButton() {
        if (shownActions != null) {
            view.showActions(promptHint, false, withListen(shownActions));
            showPromptHint();
        }
    }

    /** 這次等待的按鈕。按住時倒數到了、放開才送出的話，局面已經往下走，舊按鈕不能再有作用。 */
    private TablePanel.ActionItem promptAction(String label, Color color, Runnable action) {
        return promptAction(label, color, action, false);
    }

    /** translucent 的按鈕半透明，表示可以用但不一定要按。 */
    private TablePanel.ActionItem promptAction(String label, Color color, Runnable action, boolean translucent) {
        int id = promptId;
        return new TablePanel.ActionItem(label, color, () -> {
            if (table.isWaiting(id)) {
                action.run();
            }
        }, translucent);
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
        claimAsked = true;
        view.setDiscardPrompt(false);
        show(hint, actions);
        updatePromptHint(hint);
    }

    /** 選宣告要用的手牌。點的牌只符合一種組合就宣告；吃牌還有其他組合時留著這張，再選一張。 */
    private void pickClaim(List<Claim> options, List<Claim> claims, Tile first) {
        Set<Tile> tiles = Claim.handTiles(claims);
        tiles.remove(first);
        String hint = claimHint() + (first == null ? "：選要" + claims.get(0).verb() + "的牌" : "：再選一張");
        shownActions = null;
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

    /** 按了「過」是自己放棄，之後別家吃碰就不是被搶走，不用提示。宣告了卻被更大的搶走照舊提示。 */
    private void declareClaim(Claim claim) {
        if (claim == null) {
            claimAsked = false;
        }
        table.answerClaim(promptId, claim);
    }

    /** 沒有要自己決定的事，收起按鈕，可以報聽的話留著報聽。倒數的秒數會接在這段提示後面。 */
    void showIdle(String hint) {
        String note = takenNote;
        takenNote = null;
        promptHint = status() + (note == null ? "" : note + "　") + hint;
        view.setDiscardPrompt(false);
        show(promptHint, List.of());
    }

    private String status() {
        List<Tile> waits = table.listeningWaits();
        if (waits != null) {
            // 專家模式等哪些牌要自己記
            return table.isExpert() ? "【聽牌中】"
                    : "【聽牌中：等 " + waits.stream().map(Tile::label).collect(Collectors.joining("、")) + "】";
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
