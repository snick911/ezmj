package ezmj.ui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;

import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundResult;
import ezmj.engine.Settlement;
import ezmj.model.Meld;
import ezmj.model.Player;
import ezmj.model.Tile;
import ezmj.rules.ScoreItem;

/** 一局結束的結果視窗：胡牌牌型、台數明細與各家輸贏。 */
final class ResultDialog extends JDialog {

    ResultDialog(Frame owner, GameSession session, Round round, Settlement settlement, String buttonText) {
        super(owner, "本局結果", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.PANEL);
        content.setBorder(BorderFactory.createEmptyBorder(20, 26, 20, 26));

        RoundResult result = round.result();
        content.add(line(headline(round, result), Font.BOLD, 24f, true));
        content.add(Box.createVerticalStrut(12));

        if (!result.isDrawnGame()) {
            HandStrip strip = new HandStrip(round.player(result.winner()), result.winTile());
            strip.setAlignmentX(LEFT_ALIGNMENT);
            content.add(strip);
            content.add(Box.createVerticalStrut(12));

            for (ScoreItem item : result.score().items()) {
                content.add(line(item.name() + "　" + item.tai() + " 台", Font.PLAIN, 16f, false));
            }
            content.add(line("牌型合計　" + result.score().totalTai() + " 台", Font.BOLD, 16f, true));
            content.add(Box.createVerticalStrut(10));
            for (String note : settlement.notes()) {
                content.add(line(note, Font.PLAIN, 14f, false));
            }
            content.add(Box.createVerticalStrut(12));
        }

        for (int seat = 0; seat < 4; seat++) {
            Player player = session.player(seat);
            int delta = settlement.deltas()[seat];
            String sign = delta > 0 ? "+" : "";
            content.add(line(player.name() + "　" + sign + delta + "　→　" + player.chips(), Font.PLAIN, 16f, seat == 0));
        }
        content.add(Box.createVerticalStrut(16));

        JButton next = Theme.button(buttonText, Theme.RED);
        next.addActionListener(e -> dispose());
        next.setAlignmentX(LEFT_ALIGNMENT);
        content.add(next);
        getRootPane().setDefaultButton(next);

        setContentPane(content);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private static String headline(Round round, RoundResult result) {
        if (result.isDrawnGame()) {
            return "流局";
        }
        String winner = round.player(result.winner()).name();
        return result.isSelfDraw()
                ? winner + " 自摸 " + result.winTile().label()
                : winner + " 胡牌，" + round.player(result.from()).name() + " 放槍 " + result.winTile().label();
    }

    private static JLabel line(String text, int style, float size, boolean accent) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.font(style, size));
        label.setForeground(accent ? Theme.GOLD : Theme.TEXT);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    /** 胡牌者的完整牌面。 */
    private static final class HandStrip extends JComponent {
        private static final double TILE_W = 36;
        private static final double TILE_H = 50;
        private static final double GAP = 9;

        private final Player player;
        private final Tile winTile;

        HandStrip(Player player, Tile winTile) {
            this.player = player;
            this.winTile = winTile;
            int tiles = player.concealed().size();
            for (Meld meld : player.melds()) {
                tiles += meld.tiles().size();
            }
            Dimension size = new Dimension(
                    (int) (tiles * TILE_W + (player.melds().size() + 1) * GAP) + 4, (int) TILE_H + 6);
            setPreferredSize(size);
            setMaximumSize(size);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double x = 1;
            List<Tile> hand = new ArrayList<>(player.concealed());
            hand.remove(winTile);
            for (Tile tile : hand) {
                TilePainter.paintFace(g, tile, x, 2, TILE_W - 1, TILE_H);
                x += TILE_W;
            }
            x += GAP;
            TilePainter.paintFace(g, winTile, x, 2, TILE_W - 1, TILE_H);
            TilePainter.paintHighlight(g, x, 2, TILE_W - 1, TILE_H, Theme.RED);
            x += TILE_W;
            for (Meld meld : player.melds()) {
                x += GAP;
                for (Tile tile : meld.tiles()) {
                    TilePainter.paintFace(g, tile, x, 2, TILE_W - 1, TILE_H);
                    x += TILE_W;
                }
            }
            g.dispose();
        }
    }
}
