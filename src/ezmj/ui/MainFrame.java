package ezmj.ui;

import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import ezmj.AppVersion;
import ezmj.bot.PlaceholderBot;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.Settlement;
import ezmj.model.GameConfig;
import ezmj.model.Player;

/** 主視窗：以 CardLayout 切換開始畫面與牌桌。 */
public final class MainFrame extends JFrame {

    private static final String CARD_START = "start";
    private static final String CARD_TABLE = "table";

    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final StartPanel startPanel = new StartPanel(this::startGame);
    private TablePanel tablePanel;
    private GameController controller;

    public MainFrame() {
        super("台灣十六張麻將 v" + AppVersion.get());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setIconImages(List.of(TilePainter.appIcon(32), TilePainter.appIcon(64)));
        root.add(startPanel, CARD_START);
        setContentPane(root);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1360, screen.width), Math.min(900, screen.height - 40));
        setMinimumSize(new Dimension(1100, 720));
        setLocationRelativeTo(null);
    }

    /** 開始一場新牌局。 */
    public void startGame(GameConfig config) {
        GameSession session = new GameSession(config, new Random());
        controller = new GameController(session, new PlaceholderBot(), this::handleRoundEnd);
        if (tablePanel != null) {
            root.remove(tablePanel);
        }
        GameController current = controller;
        tablePanel = new TablePanel(tile -> current.humanDiscard(tile), this::confirmLeave);
        controller.attach(tablePanel);
        root.add(tablePanel, CARD_TABLE);
        cards.show(root, CARD_TABLE);
        controller.startRound();
    }

    public GameController controller() {
        return controller;
    }

    private void handleRoundEnd(GameSession session, Round round, Settlement settlement, Runnable next) {
        boolean over = session.isOver();
        new ResultDialog(this, session, round, settlement, over ? "查看最終排名" : "下一局").setVisible(true);
        if (controller == null) {
            return;
        }
        if (over) {
            showFinalRanking(session);
            returnToStart();
        } else {
            next.run();
        }
    }

    private void showFinalRanking(GameSession session) {
        Player[] ranking = {session.player(0), session.player(1), session.player(2), session.player(3)};
        Arrays.sort(ranking, Comparator.comparingInt(Player::chips).reversed());
        String text = Arrays.stream(ranking)
                .map(player -> player.name() + "：" + player.chips())
                .collect(Collectors.joining("\n"));
        JOptionPane.showMessageDialog(this, text, "牌局結束", JOptionPane.INFORMATION_MESSAGE);
    }

    private void confirmLeave() {
        int answer = JOptionPane.showConfirmDialog(this, "確定要離開這場牌局嗎？", "離開牌局",
                JOptionPane.YES_NO_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            returnToStart();
        }
    }

    private void returnToStart() {
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        cards.show(root, CARD_START);
        startPanel.focusName();
    }
}
