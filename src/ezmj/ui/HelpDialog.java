package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import ezmj.model.Wall;
import ezmj.rules.ScoreGuide;

/** 說明視窗。不擋住牌桌，可以邊看邊打；一局結果跳出時也還能捲動。 */
final class HelpDialog extends JDialog {

    private static final String RULES = "<h2>牌</h2>"
            + "<p>共 144 張：萬、筒、條各 1 到 9，東南西北，中發白，每種 4 張；"
            + "加上春夏秋冬梅蘭竹菊八張花牌，各 1 張。</p>"
            + "<h2>開局</h2>"
            + "<p>四人對局，第一局隨機決定莊家。每人發 16 張，莊家再摸第 17 張後先打。</p>"
            + "<p>拿到花牌會自動亮出，並從牌尾補一張。</p>"
            + "<h2>胡牌</h2>"
            + "<p>胡牌要湊成 <b>五組面子加一對眼睛</b>，共 17 張。面子是順子、刻子或槓。"
            + "沒有其他特殊胡牌牌型，也沒有最低台數，0 台一樣能胡。</p>"
            + "<p>輪到自己摸到胡牌是自摸，由三家付錢；胡別家打出的牌，只有放槍的人付錢。</p>"
            + "<h2>吃、碰、槓</h2>"
            + "<ul>"
            + "<li><b>吃</b>：只能吃上家打出的數牌，湊成順子。</li>"
            + "<li><b>碰</b>：手上有兩張相同的牌，可碰任何一家打出的牌。</li>"
            + "<li><b>明槓</b>：手上有三張相同的牌，可槓任何一家打出的牌。</li>"
            + "<li><b>暗槓</b>：輪到自己時，手上有四張相同的牌。</li>"
            + "<li><b>加槓</b>：輪到自己時，用手上的牌加到已經碰出的刻子上。別家若能胡這張牌，可以搶槓。</li>"
            + "</ul>"
            + "<p>吃、碰之後要直接打一張牌。槓牌之後從牌尾補一張。</p>"
            + "<p>同一張牌有多人宣告時，胡優先於槓、碰，槓、碰優先於吃。"
            + "多人同時要胡，只有放槍者的下家起算最近的一家能胡。</p>"
            + "<h2>流局</h2>"
            + "<p>牌牆固定留 " + Wall.RESERVED + " 張不摸，其餘摸完還沒有人胡就流局，這局沒有輸贏。</p>"
            + "<h2>莊家與圈風</h2>"
            + "<p>莊家胡牌或流局就連莊，其他情況由下家接莊。莊家的門風是東，下家起依序是南、西、北。</p>"
            + "<p>從東風圈開始，每輪完一次莊就換下一圈。</p>"
            + "<h2>牌局結束</h2>"
            + "<p>東南西北四圈打完，或有人籌碼輸光，牌局就結束，依籌碼排名。</p>";

    private static final String SCORING_INTRO = "<h2>每位付款者付多少</h2>"
            + "<p class=\"formula\">底 ＋ 台數 × 每台</p>"
            + "<p>底與每台在開始畫面選擇。台數是牌型台數的總和，再加上莊家台：</p>"
            + "<ul>"
            + "<li>胡牌者或付款者是莊家時，這筆付款加 <b>莊家 1 台</b>。</li>"
            + "<li>莊家連莊時再加 <b>連 N 拉 N</b>，也就是連莊次數 × 2 台。</li>"
            + "<li>與莊家無關的付款不加。</li>"
            + "</ul>"
            + "<h2>算算看</h2>"
            + "<p>底 100、每台 20，胡的牌型共 3 台：</p>"
            + "<ul>"
            + "<li>閒家胡閒家放槍：100 ＋ 3 × 20 ＝ 160，放槍的人付 160。</li>"
            + "<li>閒家胡莊家放槍：100 ＋ (3 ＋ 1) × 20 ＝ 180，莊家付 180。</li>"
            + "<li>連 2 的莊家自摸：100 ＋ (3 ＋ 1 ＋ 4) × 20 ＝ 260，三家各付 260。</li>"
            + "</ul>"
            + "<h2>牌型台數</h2>"
            + "<p>符合的牌型可以累加。同一手牌有多種拆法時，取台數最高的拆法。</p>"
            + "<p>下表的刻子都包含槓。暗槓算暗刻，也不影響門清。"
            + "胡別家打出的牌時，如果這張牌只能湊成刻子，這組不算暗刻。</p>";

    private final JEditorPane page = new JEditorPane();
    private final JScrollPane scroll = new JScrollPane(page);
    private Runnable currentPage = this::showRules;

    HelpDialog(Frame owner) {
        super(owner);
        setModalExclusionType(ModalExclusionType.APPLICATION_EXCLUDE);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        page.setEditable(false);
        page.setContentType("text/html");
        page.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        page.setFont(Theme.font(Font.PLAIN, 16f));
        page.setBackground(Theme.PANEL);
        page.setForeground(Theme.TEXT);
        page.setBorder(BorderFactory.createEmptyBorder(10, 22, 16, 22));

        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        JButton close = Theme.button("關閉", Theme.BUTTON);
        close.addActionListener(e -> setVisible(false));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 10));
        bar.setBackground(Theme.PANEL_LIGHT);
        bar.add(close);
        getRootPane().setDefaultButton(close);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Theme.PANEL);
        content.add(scroll, BorderLayout.CENTER);
        content.add(bar, BorderLayout.SOUTH);
        setContentPane(content);

        setSize(720, 640);
        setMinimumSize(new Dimension(520, 400));
    }

    void showRules() {
        currentPage = this::showRules;
        showPage("基本規則", RULES);
    }

    void showScoring() {
        currentPage = this::showScoring;
        showPage("台數計算", SCORING_INTRO + scoreTables());
    }

    /** 字體大小改變後，重新排版正在顯示的內容。 */
    void refreshFonts() {
        if (isVisible()) {
            currentPage.run();
        }
    }

    private void showPage(String title, String body) {
        setTitle(title);
        page.setText("<html><head><style>" + styleSheet() + "</style></head><body>" + body + "</body></html>");
        page.setCaretPosition(0);
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
        if (!isVisible()) {
            setLocationRelativeTo(getOwner());
            setVisible(true);
        }
        toFront();
    }

    private static String scoreTables() {
        StringBuilder html = new StringBuilder();
        for (ScoreGuide.Group group : ScoreGuide.GROUPS) {
            html.append("<h3>").append(group.title()).append("</h3>");
            html.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\">");
            for (ScoreGuide.Entry entry : group.entries()) {
                html.append("<tr><td class=\"name\" width=\"").append(Theme.scaled(110)).append("\">").append(entry.name())
                        .append("</td><td class=\"tai\" width=\"").append(Theme.scaled(56)).append("\">").append(entry.tai())
                        .append(" 台</td><td>").append(entry.condition()).append("</td></tr>");
            }
            html.append("</table>");
        }
        return html.toString();
    }

    private static String styleSheet() {
        String gold = hex(Theme.GOLD);
        return "h2 { color: " + gold + "; font-size: " + Theme.scaled(20) + "pt; margin-top: 16px; margin-bottom: 4px; }"
                + "h3 { color: " + gold + "; font-size: " + Theme.scaled(16) + "pt; margin-top: 14px; margin-bottom: 2px; }"
                + "p { margin-top: 4px; margin-bottom: 6px; }"
                + "ul { margin-top: 2px; margin-bottom: 6px; margin-left: 22px; }"
                + "li { margin-bottom: 3px; }"
                + "td { vertical-align: top; border-bottom: 1px solid " + hex(Theme.PANEL_LIGHT) + "; }"
                + ".name { font-weight: bold; }"
                + ".tai { color: " + gold + "; }"
                + ".formula { color: " + gold + "; font-size: " + Theme.scaled(18) + "pt; font-weight: bold; }";
    }

    private static String hex(Color color) {
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }
}
