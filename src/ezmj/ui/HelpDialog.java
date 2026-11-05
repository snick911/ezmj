package ezmj.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;

import ezmj.AppVersion;
import ezmj.engine.Achievement;
import ezmj.engine.AchievementBook;
import ezmj.engine.PlayerStats;
import ezmj.model.BotLevel;
import ezmj.model.GameRecord;
import ezmj.model.HonorRoll;
import ezmj.model.Meld;
import ezmj.model.Tile;
import ezmj.model.Wall;
import ezmj.rules.ScoreGuide;
import ezmj.rules.ScoreResult;
import ezmj.rules.Scorer;
import ezmj.rules.WinContext;
import ezmj.table.TableFlow;

/** 說明與榮譽榜的視窗。不擋住牌桌，可以邊看邊打；一局結果跳出時也還能捲動。 */
final class HelpDialog extends JDialog {

    static final String COPYRIGHT = "© 2026 snick911";
    private static final String LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html";
    private static final String ISSUES_URL = "https://github.com/snick911/ezmj/issues";

    private static final int RULE_TILE_W = 30;
    private static final int EXAMPLE_TILE_W = 24;
    /** HTML 編輯器找圖片時，會先查文件上這個屬性裡的快取，所以牌面圖不必真的存成檔案。 */
    private static final String IMAGE_CACHE = "imageCache";

    /** 圖上的一組牌，marked 的那組會加紅框。 */
    private record TileGroup(List<Tile> tiles, boolean marked) {
    }

    private final JEditorPane page = new JEditorPane();
    private final JScrollPane scroll = new JScrollPane(page);
    private final Hashtable<URL, Image> images = new Hashtable<>();
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
        page.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
                openInBrowser(event.getURL());
            }
        });

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

        setSize(780, 680);
        setMinimumSize(new Dimension(720, 420));
    }

    void showRules() {
        currentPage = this::showRules;
        showPage("基本規則", rulesPage());
    }

    void showScoring() {
        currentPage = this::showScoring;
        showPage("台數計算", scoringIntro() + scoreTables());
    }

    void showAbout() {
        currentPage = this::showAbout;
        showPage("關於", aboutPage());
    }

    /**
     * AGPL 第 5 條的「適當的法律聲明」：著作權、不提供擔保、可以照這個授權散布，以及去哪裡看授權條款。
     * 原版的畫面有顯示，改過的版本就要照樣顯示，不要拿掉。原始碼的網址是作者決定不放的，
     * 散布改過的版本時，照 AGPL 要提供它自己的原始碼。
     */
    private static String aboutPage() {
        return "<h2>EZMJ 台灣十六張麻將 " + AppVersion.get() + "</h2>"
                + "<p>Copyright " + COPYRIGHT + "</p>"
                + "<p>這個程式是自由軟體：你可以依照自由軟體基金會發布的 GNU Affero 通用公眾授權條款第 3 版（AGPL-3.0）使用、修改與散布。</p>"
                + "<p>這個程式不提供任何擔保，連適售性或特定用途的適用性都沒有。詳細內容請看授權條款，以英文原文為準。</p>"
                + "<p>授權條款的全文附在程式裡：免安裝版與安裝程式在程式資料夾的 LICENSE.txt，jar 在 META-INF/LICENSE。"
                + "網路上也看得到：" + link(LICENSE_URL) + "</p>"
                + "<p class=\"note\">散布修改過的版本，或架成服務給別人用，都要用同樣的授權公開完整的原始碼，這段聲明也要留著。</p>"
                + "<h2>商業授權</h2>"
                + "<p>不想公開原始碼的商業用途，可以向作者取得商業授權，請到 GitHub Issues 開一則標題寫「商業授權」的 issue："
                + link(ISSUES_URL) + "</p>"
                + "<h2>其他部分的授權</h2>"
                + "<p>免安裝版與安裝程式內附的 Java 執行環境不是這個專案寫的，授權是 GPL-2.0 with Classpath Exception，"
                + "說明在程式資料夾的 runtime\\legal。</p>";
    }

    /** 網址直接顯示出來，開不了瀏覽器時也能自己複製。 */
    private static String link(String url) {
        return "<a href=\"" + url + "\">" + url + "</a>";
    }

    /** 開不了就算了，網址就寫在頁面上。 */
    private static void openInBrowser(URL url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(url.toURI());
            }
        } catch (IOException | URISyntaxException | UnsupportedOperationException | SecurityException e) {
            // 網址就寫在頁面上
        }
    }

    void showHonorRoll(HonorRoll roll, HonorRoll.Category category) {
        currentPage = () -> showHonorRoll(roll, category);
        showPage("榮譽榜：" + category.label(), honorPage(roll, category));
    }

    /** group 是 null 時顯示全部分類。 */
    void showAchievements(AchievementBook book, Achievement.Group group) {
        currentPage = () -> showAchievements(book, group);
        showPage("成就" + (group == null ? "" : "：" + group.label()), achievementPage(book, group));
    }

    void showStats(PlayerStats stats) {
        currentPage = () -> showStats(stats);
        showPage("我的統計", statsPage(stats));
    }

    private static String statsPage(PlayerStats stats) {
        PlayerStats.Totals total = stats.total();
        if (total.rounds() == 0) {
            return "<h2>我的統計</h2><p>還沒有紀錄。打完一局，成績就會累計在這裡。</p>";
        }
        StringBuilder html = new StringBuilder("<h2>總計</h2><table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\">");
        statRow(html, "局數", total.rounds() + " 局，流局 " + total.drawnGames() + " 次");
        statRow(html, "胡牌率", percent(total.winRate()) + "（" + total.wins() + " 次）");
        statRow(html, "自摸率", percent(total.selfDrawRate()) + "（" + total.selfDraws() + " 次）");
        statRow(html, "放槍率", percent(total.dealInRate()) + "（" + total.dealIns() + " 次）");
        statRow(html, "胡牌平均台數", String.format(Locale.ROOT, "%.2f 台，最高 %d 台", total.averageTai(), total.bestTai()));
        statRow(html, "累計輸贏", total.winnings() + "（每局 " + String.format(Locale.ROOT, "%+.0f", (double) total.winnings() / total.rounds()) + "）");
        statRow(html, "打完的牌局", total.games() + " 場，第一名 " + total.firstPlaces() + " 場"
                + (total.games() == 0 ? "" : String.format(Locale.ROOT, "，平均第 %.2f 名", total.averagePlace())));
        html.append("</table><h2>依電腦等級</h2><table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\"><tr class=\"head\">");
        for (String title : new String[] {"同桌有", "局數", "胡牌率", "自摸率", "放槍率", "平均台數", "場數", "第一名"}) {
            html.append("<td class=\"cell\">").append(title).append("</td>");
        }
        html.append("</tr>");
        for (BotLevel level : BotLevel.values()) {
            PlayerStats.Totals row = stats.against(level);
            html.append("<tr>").append(honorCell(level.label(), false))
                    .append(honorCell(String.valueOf(row.rounds()), false))
                    .append(honorCell(percent(row.winRate()), false))
                    .append(honorCell(percent(row.selfDrawRate()), false))
                    .append(honorCell(percent(row.dealInRate()), false))
                    .append(honorCell(String.format(Locale.ROOT, "%.2f", row.averageTai()), false))
                    .append(honorCell(String.valueOf(row.games()), false))
                    .append(honorCell(String.valueOf(row.firstPlaces()), false))
                    .append("</tr>");
        }
        return html.append("</table><p class=\"note\">同桌有兩種等級時，兩邊都會算，所以局數加起來會超過總計。"
                + "平均台數不含莊家台。開了電腦代打之後，這場剩下的局不列入。"
                + "累計輸贏把不同底台的牌局直接加總。</p>").toString();
    }

    private static void statRow(StringBuilder html, String title, String value) {
        html.append("<tr>").append(colorCell(title, Theme.MUTED)).append(honorCell(value, false)).append("</tr>");
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    private static String achievementPage(AchievementBook book, Achievement.Group only) {
        StringBuilder html = new StringBuilder("<p class=\"tai\"><b>已達成 ").append(book.unlockedCount()).append(" / ")
                .append(Achievement.values().length).append(" 項</b></p>");
        for (Achievement.Group group : Achievement.Group.values()) {
            if (only == null || only == group) {
                appendAchievementGroup(html, book, group);
            }
        }
        return html.append("<p class=\"note\">都是看你自己的表現。開了電腦代打之後，這場剩下的局與整場類成就都不列入；"
                + "在那之前自己打完的局仍然算數，中途離開的牌局也一樣。</p>").toString();
    }

    private static void appendAchievementGroup(StringBuilder html, AchievementBook book, Achievement.Group group) {
        html.append("<h2>").append(group.label()).append("</h2>")
                .append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\"><tr class=\"head\">");
        html.append("<td class=\"cell\" width=\"5%\"></td><td class=\"cell\" width=\"24%\">名稱</td>")
                .append("<td class=\"cell\">條件</td><td class=\"cell\" width=\"18%\">達成</td></tr>");
        for (Achievement achievement : Achievement.values()) {
            if (achievement.group() != group) {
                continue;
            }
            boolean done = book.isUnlocked(achievement);
            Color color = done ? Theme.GOLD : Theme.MUTED;
            html.append("<tr>")
                    .append(colorCell(done ? "★" : "☆", color))
                    .append(colorCell("<b>" + achievement.title() + "</b>", color))
                    .append(colorCell(achievement.description(), color))
                    .append(colorCell(achievementStatus(book, achievement), color))
                    .append("</tr>");
        }
        html.append("</table>");
    }

    /** 這個 HTML 元件不支援一格套兩個 class，顏色要直接寫在文字上。 */
    private static String colorCell(String text, Color color) {
        return "<td class=\"cell\"><font color=\"" + hex(color) + "\">" + text + "</font></td>";
    }

    /** 達成的日期；還沒達成的累計成就顯示進度。 */
    private static String achievementStatus(AchievementBook book, Achievement achievement) {
        if (book.isUnlocked(achievement)) {
            return escape(book.unlockedDate(achievement));
        }
        return achievement.counter() == null ? "" : book.count(achievement.counter()) + " / " + achievement.target();
    }

    private static String honorPage(HonorRoll roll, HonorRoll.Category category) {
        StringBuilder html = new StringBuilder("<h2>").append(category.label()).append(" 前 ")
                .append(HonorRoll.SIZE).append(" 名</h2>");
        List<GameRecord> records = roll.top(category);
        if (records.isEmpty()) {
            return html.append("<p>還沒有紀錄。牌局打到結束，成績就會記錄在這裡。</p>").toString();
        }
        html.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\"><tr class=\"head\">");
        for (String title : new String[] {"名次", "姓名", "胡牌", "自摸", "贏錢", "放槍", "電腦", "底／台", "日期"}) {
            html.append("<td class=\"cell\">").append(title).append("</td>");
        }
        html.append("</tr>");
        for (int i = 0; i < records.size(); i++) {
            GameRecord record = records.get(i);
            html.append("<tr>")
                    .append(honorCell(String.valueOf(i + 1), false))
                    .append(honorCell(escape(record.name()), false))
                    .append(honorCell(String.valueOf(record.wins()), category == HonorRoll.Category.WINS))
                    .append(honorCell(String.valueOf(record.selfDraws()), category == HonorRoll.Category.SELF_DRAWS))
                    .append(honorCell(String.valueOf(record.winnings()), category == HonorRoll.Category.WINNINGS))
                    .append(honorCell(String.valueOf(record.dealIns()), category == HonorRoll.Category.DEAL_INS))
                    .append(honorCell(record.botLevelsLabel(), false))
                    .append(honorCell(record.basePay() + "／" + record.taiPay(), false))
                    .append(honorCell(escape(record.date()), false))
                    .append("</tr>");
        }
        return html.append("</table><p class=\"note\">胡牌數包含自摸。贏錢是結束時的籌碼減去本金。"
                + "每個類別只列數值大於 0 的成績，數值相同時先達成的排前面。</p>").toString();
    }

    private static String honorCell(String text, boolean highlighted) {
        return colorCell(text, highlighted ? Theme.GOLD : Theme.TEXT);
    }

    /** 姓名是玩家自己輸入的，可能有 HTML 的特殊字元。 */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 字體大小改變後，重新排版正在顯示的內容。 */
    void refreshFonts() {
        if (isVisible()) {
            currentPage.run();
        }
    }

    private void showPage(String title, String body) {
        setTitle(title);
        page.getDocument().putProperty(IMAGE_CACHE, images);
        page.setText("<html><head><style>" + styleSheet() + "</style></head><body>" + body + "</body></html>");
        page.setCaretPosition(0);
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
        if (!isVisible()) {
            setLocationRelativeTo(getOwner());
            setVisible(true);
        }
        toFront();
    }

    private String rulesPage() {
        images.clear();
        return "<h2>牌</h2>"
                + "<p>共 144 張。下面每種 4 張：</p>"
                + "<p>萬　" + tiles("1w2w3w4w5w6w7w8w9w") + "</p>"
                + "<p>筒　" + tiles("1p2p3p4p5p6p7p8p9p") + "</p>"
                + "<p>條　" + tiles("1s2s3s4s5s6s7s8s9s") + "</p>"
                + "<p>字　" + tiles("1z2z3z4z 5z6z7z") + "</p>"
                + "<p>另外有八張花牌，各 1 張：</p>"
                + "<p>花　" + tiles("1f2f3f4f 5f6f7f8f") + "</p>"
                + "<h2>開局</h2>"
                + "<p>四人對局，第一局隨機決定莊家。每人發 16 張，莊家再摸第 17 張後先打。</p>"
                + "<p>拿到花牌會自動亮出，並從牌尾補一張。</p>"
                + "<p>八張花牌都亮出來時，一家集滿八張（八仙過海）或一家七張、別家一張（七搶一）就立即胡，不看手牌，開局補花時也一樣。"
                + "八仙過海算自摸，三家付錢；七搶一拿走別家那張花，算那家放槍，只有他付錢。兩種都是 8 台，再依規則加莊家台。</p>"
                + "<h2>胡牌</h2>"
                + "<p>胡牌要湊成 <b>五組面子加一對眼睛</b>，共 17 張。面子是順子、刻子或槓。"
                + "沒有其他特殊胡牌牌型，也沒有最低台數，0 台一樣能胡。</p>"
                + "<p>" + tiles(ScoreGuide.WINNING_HAND) + "</p>"
                + "<p class=\"note\">上面這手牌依序是兩組順子、三組刻子，最後一對是眼睛。</p>"
                + "<p>輪到自己摸到胡牌是自摸，由三家付錢；胡別家打出的牌，只有放槍的人付錢。</p>"
                + "<h2>吃、碰、槓</h2>"
                + "<p class=\"note\">紅框是別家打出或自己要加上去的那張牌。</p>"
                + "<p><b>吃</b>：只能吃上家打出的數牌，湊成順子。</p>"
                + "<p>" + claim("1w2w", "3w") + "</p>"
                + "<p><b>碰</b>：手上有兩張相同的牌，可碰任何一家打出的牌。</p>"
                + "<p>" + claim("5p5p", "5p") + "</p>"
                + "<p><b>明槓</b>：手上有三張相同的牌，可槓任何一家打出的牌。</p>"
                + "<p>" + claim("9s9s9s", "9s") + "</p>"
                + "<p><b>暗槓</b>：輪到自己時，手上有四張相同的牌。</p>"
                + "<p>" + tiles("1z1z1z1z") + "</p>"
                + "<p><b>加槓</b>：輪到自己時，用手上的牌加到已經碰出的刻子上。別家若能胡這張牌，可以搶槓。</p>"
                + "<p>" + claim("7w7w7w", "7w") + "</p>"
                + "<p>吃、碰之後要直接打一張牌。槓牌之後從牌尾補一張。</p>"
                + "<p>只有一種選擇時，按下吃、碰、槓就直接宣告；胡與自摸也是按了就胡。"
                + "吃有多種組合、或自己回合有多張牌能槓時才進入選牌：能用的手牌維持原色，不能選的會蓋上遮罩，點手牌就決定。"
                + "吃牌時點共用的牌後再選一張。按「取消」可以回去。</p>"
                + "<p>同一張牌有多人宣告時，胡優先於槓、碰，槓、碰優先於吃。"
                + "多人同時要胡，只有放槍者的下家起算最近的一家能胡。</p>"
                + "<p>已經有人決定的宣告比還在考慮的人能做的都大時，就不再等他。例如你只能吃、電腦要碰，"
                + "電腦等 " + TableFlow.BOT_CLAIM_MS / 1000 + " 秒就先碰走，不用等你按。你能胡、或能做的和電腦一樣大時，照舊等你。</p>"
                + "<h2>流局</h2>"
                + "<p>牌牆固定留 " + Wall.RESERVED + " 張不摸，其餘摸完還沒有人胡就流局，這局沒有輸贏。</p>"
                + "<h2>莊家與圈風</h2>"
                + "<p>莊家胡牌或流局就連莊，其他情況由下家接莊。莊家的門風是東，下家起依序是南、西、北。</p>"
                + "<p>從東風圈開始，每輪完一次莊就換下一圈。</p>"
                + "<h2>牌局結束</h2>"
                + "<p>東南西北四圈打完，或有人籌碼輸光，牌局就結束，依籌碼排名，並列出四家的胡牌、自摸、放槍與輸贏。</p>"
                + "<h2>每局結果</h2>"
                + "<p>結果視窗會倒數，時間到自動進入下一局：流局 10 秒，其他 60 秒；連線對戰是流局 5 秒，其他 15 秒。也可以直接按按鈕。</p>"
                + "<h2>報聽</h2>"
                + "<p>照常打牌，打出之後如果已經聽牌，下方會出現「聽牌」按鈕。這個機會一直到你下一次摸牌、吃碰槓或這局結束，"
                + "放棄別家打出的牌不影響。按下就報聽：之後自動摸牌打牌，胡的牌來了就自動胡，也不再吃、碰、槓，這一局不能取消。</p>"
                + "<p>報聽是公開的，全桌都看得到，你的名字旁會有圓圈的「聽」。胡的時候多算「聽牌」1 台，自摸或胡別家的牌都算，"
                + "也算在牌型台數裡。電腦也會報聽：初學者不報，其他等級看手牌與局勢決定。</p>"
                + "<h2>電腦代打</h2>"
                + "<p>牌桌右側的「電腦代打」按鈕可以隨時開關，打開後由職業等級的電腦替你決定。"
                + "用過電腦代打的牌局不列入榮譽榜。連線對戰不能使用電腦代打。</p>";
    }

    /** 手上的牌，加上紅框的那張，等於亮出來的面子。 */
    private String claim(String hand, String tile) {
        List<Tile> set = new ArrayList<>(Tile.parseAll(hand));
        set.addAll(Tile.parseAll(tile));
        set.sort(null);
        return tiles(hand) + "　＋　"
                + image(List.of(new TileGroup(Tile.parseAll(tile), true)), RULE_TILE_W)
                + "　→　" + image(List.of(new TileGroup(set, false)), RULE_TILE_W);
    }

    /** 空白隔開的每一段會畫成一組。 */
    private String tiles(String spec) {
        List<TileGroup> groups = Arrays.stream(spec.split(" "))
                .map(part -> new TileGroup(Tile.parseAll(part), false))
                .collect(Collectors.toList());
        return image(groups, RULE_TILE_W);
    }

    private static String scoringIntro() {
        return "<h2>每位付款者付多少</h2>"
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
                + "胡別家打出的牌時，如果這張牌只能湊成刻子，這組不算暗刻。</p>"
                + "<p class=\"note\">每個牌型下面都有一手範例：紅框是胡的那張牌，"
                + "最左邊是亮出的面子，最右邊是花牌。「這手共幾台」是實際計算的結果，可能包含其他一起成立的牌型。</p>";
    }

    private String scoreTables() {
        images.clear();
        StringBuilder html = new StringBuilder();
        for (ScoreGuide.Group group : ScoreGuide.GROUPS) {
            html.append("<h3>").append(group.title()).append("</h3>");
            // 文字與牌面圖分成兩個表格，寬的牌面圖才不會把各分類的欄位撐得不一樣寬
            for (ScoreGuide.Entry entry : group.entries()) {
                html.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\"><tr><td class=\"name\" width=\"")
                        .append(Theme.scaled(110)).append("\">").append(entry.name())
                        .append("</td><td class=\"tai\" width=\"").append(Theme.scaled(56)).append("\">").append(entry.tai())
                        .append(" 台</td><td>").append(entry.condition()).append("</td></tr></table>");
                html.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\"><tr><td class=\"example\">");
                if (entry.example() == null) {
                    html.append(image(List.of(new TileGroup(Tile.parseAll("1f2f3f4f5f6f7f8f"), false)), EXAMPLE_TILE_W));
                } else {
                    html.append(handImage(entry.example()))
                            .append("<div class=\"note\">").append(caption(entry.example())).append("</div>");
                }
                html.append("</td></tr></table>");
            }
        }
        return html.toString();
    }

    private String handImage(WinContext hand) {
        List<Tile> concealed = new ArrayList<>(hand.concealed());
        concealed.remove(hand.winTile());
        concealed.sort(null);
        List<TileGroup> groups = new ArrayList<>();
        for (Meld meld : hand.melds()) {
            groups.add(new TileGroup(meld.tiles(), false));
        }
        if (!concealed.isEmpty()) {
            groups.add(new TileGroup(concealed, false));
        }
        groups.add(new TileGroup(List.of(hand.winTile()), true));
        if (!hand.flowers().isEmpty()) {
            groups.add(new TileGroup(hand.flowers(), false));
        }
        return image(groups, EXAMPLE_TILE_W);
    }

    private static String caption(WinContext hand) {
        ScoreResult score = Scorer.score(hand);
        String items = score.items().stream()
                .map(item -> item.name() + " " + item.tai() + " 台")
                .collect(Collectors.joining("、"));
        return Tile.windName(hand.roundWind()) + "風圈、門風" + Tile.windName(hand.seatWind()) + "，"
                + (hand.selfDraw() ? "自摸 " : "胡別家打出的 ") + hand.winTile().label()
                + "。這手共 " + score.totalTai() + " 台：" + items;
    }

    /** 把牌畫成圖放進快取，回傳引用這張圖的 HTML。 */
    private String image(List<TileGroup> groups, int tileW) {
        int tileH = (int) Math.round(tileW * 1.38);
        int gap = Math.round(tileW * .4f);
        int count = groups.stream().mapToInt(group -> group.tiles().size()).sum();
        int width = count * tileW + (groups.size() - 1) * gap + 4;
        int height = tileH + 6;

        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = picture.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double x = 2;
        for (TileGroup group : groups) {
            for (Tile tile : group.tiles()) {
                TilePainter.paintFace(g, tile, x, 2, tileW - 1, tileH);
                if (group.marked()) {
                    TilePainter.paintHighlight(g, x, 2, tileW - 1, tileH, Theme.RED);
                }
                x += tileW;
            }
            x += gap;
        }
        g.dispose();

        try {
            URL url = new URL("file:/ezmj-help/" + images.size() + ".png");
            images.put(url, picture);
            return "<img src=\"" + url + "\" width=\"" + width + "\" height=\"" + height + "\" align=\"middle\">";
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String styleSheet() {
        String gold = hex(Theme.GOLD);
        return "h2 { color: " + gold + "; font-size: " + Theme.scaled(20) + "pt; margin-top: 16px; margin-bottom: 4px; }"
                + "h3 { color: " + gold + "; font-size: " + Theme.scaled(16) + "pt; margin-top: 14px; margin-bottom: 2px; }"
                + "p { margin-top: 4px; margin-bottom: 6px; }"
                + "a { color: " + gold + "; }"
                + "ul { margin-top: 2px; margin-bottom: 6px; margin-left: 22px; }"
                + "li { margin-bottom: 3px; }"
                + "td { vertical-align: top; }"
                + "td.example { border-bottom: 1px solid " + hex(Theme.PANEL_LIGHT) + "; padding-bottom: 10px; }"
                + "td.cell { border-bottom: 1px solid " + hex(Theme.PANEL_LIGHT) + "; }"
                + ".head { font-weight: bold; color: " + hex(Theme.MUTED) + "; }"
                + ".name { font-weight: bold; }"
                + ".tai { color: " + gold + "; }"
                + ".note { color: " + hex(Theme.MUTED) + "; font-size: " + Theme.scaled(13) + "pt; }"
                + ".formula { color: " + gold + "; font-size: " + Theme.scaled(18) + "pt; font-weight: bold; }";
    }

    private static String hex(Color color) {
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }
}
