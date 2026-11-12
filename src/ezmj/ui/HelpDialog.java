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
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;

import ezmj.AppVersion;
import ezmj.career.CareerProfile;
import ezmj.career.CareerTitle;
import ezmj.career.Challenge;
import ezmj.career.Dan;
import ezmj.engine.Achievement;
import ezmj.engine.AchievementBook;
import ezmj.engine.PlayerStats;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.GameRecord;
import ezmj.model.HonorRoll;
import ezmj.model.Meld;
import ezmj.model.Tile;
import ezmj.model.Wall;
import ezmj.online.RoomSettings;
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
    /** 贏錢金額照底台分榜，看那一頁時上面多一排選底台，其他頁收起來。 */
    private final JPanel stakesBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
    private final JLabel stakesLabel = new JLabel("底台");
    private final JComboBox<String> stakesBox = new JComboBox<>();
    /** 選單裡依序是哪幾組底台，和 stakesBox 的項目對齊。 */
    private List<HonorRoll.Stakes> listedStakes = List.of();
    /** 選了別組底台時重畫贏錢榜；正在填選單時是 null，不要觸發。 */
    private Consumer<HonorRoll.Stakes> onStakesChosen;

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

        stakesLabel.setForeground(Theme.TEXT);
        stakesBox.addActionListener(e -> {
            int index = stakesBox.getSelectedIndex();
            if (onStakesChosen != null && index >= 0) {
                onStakesChosen.accept(listedStakes.get(index));
            }
        });
        stakesBar.setBackground(Theme.PANEL_LIGHT);
        stakesBar.add(stakesLabel);
        stakesBar.add(stakesBox);
        stakesBar.setVisible(false);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Theme.PANEL);
        content.add(stakesBar, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(bar, BorderLayout.SOUTH);
        setContentPane(content);

        setSize(780, 680);
        setMinimumSize(new Dimension(720, 420));
    }

    void showIntro() {
        currentPage = this::showIntro;
        showPage("簡介", introPage());
    }

    /** EZMJ 是什麼、連線打牌的禮儀與單機版多了什麼。網頁版說明的「簡介」寫的是同一件事，改了兩邊一起改。 */
    private static String introPage() {
        return "<h2>隨時隨地，上桌打一場</h2>"
                + "<p>EZMJ 想提供一個可以連線打麻將的地方，不受實體場地限制：不用湊一張牌桌、不用約好地點，也不用自己洗牌疊牌。"
                + "只要有網路，住在不同城市的親友也能隨時湊成一桌。「連線對戰」和網頁版連到同一台服務器，"
                + "親友用手機、平板或電腦的瀏覽器打開網頁版，就能和你同桌。</p>"
                + "<p>無須註冊帳號，取個名字就能直接開、直接玩：</p><ul>"
                + "<li><b>想和親友打</b>：在「連線對戰」開一間房間，按「複製房號」傳給大家，用房號加入就能同桌；"
                + "人數不夠，空位由電腦補滿。</li>"
                + "<li><b>想認識新牌友</b>：大廳列出正在等人的公開房間，挑一間加入就好。</li>"
                + "<li><b>不想等開局</b>：開了「隨時補位」的牌局就算已經開打，也能從大廳加入，替換一家電腦接著打。</li></ul>"
                + "<h2>每一場都是新的開始</h2>"
                + "<p>連線對戰不記帳號、不累積輸贏。上一場手氣再好或再差，下一場大家都從房間定好的籌碼重新來過，"
                + "沒有包袱，坐下來就能專心打牌。</p>"
                + "<p>雖然不留帳號，每場結束還是能留下這一場的戰績：牌局結束的視窗可以「分享結果圖」，複製之後直接貼到 LINE，"
                + "或存成圖片；也可以「存成輸贏 PDF」，把每一局的輸贏都記下來，和親友分享這一場的精彩。"
                + "連線對戰打完全場、成績亮眼的，名字還會登上網頁版大廳的「榮譽榜」。</p>"
                + "<h2>免費，沒有廣告</h2>"
                + "<p>EZMJ 完全免費，畫面上沒有廣告，也沒有任何要付費的地方，單純就是打麻將。</p>"
                + "<h2>隨時補位，開打了也能上桌</h2>"
                + "<p>連線對戰開房間時勾了「隨時補位」，開打之後電腦在打的座位，別人也能從大廳加入替換。牌局不會為了等人停下來："
                + "加入時先保留座位，等那家電腦打完這一手就換你上場，通常只要一兩手；這一局先結束的話，下一局開始時上場。"
                + "上場時全桌都會跳出「玩家 ○○ 替換電腦（××）加入」。中途加入的不列入網頁版大廳的榮譽榜，想上榜就在開局前加入。"
                + "詳細見「遊戲玩法」的「隨時補位」。</p>"
                + "<h2>牌桌禮儀</h2>"
                + "<p>網路上開局，對面坐的一樣是真人牌友，禮儀和在實體牌桌上一樣重要：</p><ul>"
                + "<li><b>最重要的一條</b>：上了桌，就盡可能打完東南西北四圈。中途離開，你的座位只能交給電腦打到最後，"
                + "同桌的人也跟著掃興；網頁版大廳的榮譽榜也只記打到結束的人。</li>"
                + "<li><b>斷線了盡快回來</b>：連線斷了會自己重連；程式被關掉的話，重開之後按「連線對戰」的「接回牌局」"
                + "就回到原本的座位，這段時間由電腦暫時代打。</li>"
                + "<li><b>出牌不拖</b>：輪到自己時盡快決定，別讓大家乾等。</li>"
                + "<li><b>說話客氣</b>：名字、房間名稱與聊天都不能有不雅的字眼。贏了不驕、輸了不惱，打得開心最重要。</li></ul>"
                + "<h2>單機也能練功</h2>"
                + "<p>這個單機版除了連線對戰，還提供<b>修業模式</b>，讓你紮紮實實地鍛鍊自己的麻將技巧：從無名小卒練起，"
                + "帶著帳上的籌碼和三家電腦拚四圈，贏來的就是闖蕩江湖的本錢；" + Challenge.line(false).size()
                + " 道挑戰關卡一關一關闖，每關最多三顆星；記牌、看聽的修練關卡磨練你的記性與眼力；帳上的籌碼越多，稱號越響亮，打得夠多、夠好，還能考取初段到九段的段位證照。</p>"
                + "<p>想輕鬆一點，就開一桌<b>練習牌局</b>：不用連網，籌碼、底台與三家電腦的等級（初學者到職業）都由你作主；"
                + "想考驗自己，可以打開專家模式。成就、統計與榮譽榜會記下你一路的成長。怎麼玩見「遊戲玩法」。</p>"
                + "<h2>想挑戰自己，就開專家模式</h2>"
                + "<p>練習牌局、修業檔案與連線開房間都可以把「專家模式」打勾，換成考驗記性與反應的打法："
                + HomePanel.escape(StartPanel.EXPERT_SUMMARY) + "，每次最多只等 " + GameConfig.EXPERT_MAX_WAIT_SECONDS
                + " 秒。規則與台數都和平常一樣，等哪幾張、誰打過什麼、牌牆還剩多少，全靠自己記。"
                + "勾選框只寫「開啟」，詳細見「遊戲玩法」的「專家模式」。</p>";
    }

    void showGameplay() {
        currentPage = this::showGameplay;
        showPage("遊戲玩法", gameplayPage());
    }

    void showRules() {
        currentPage = this::showRules;
        showPage("基本規則", rulesPage());
    }

    /** 第一次玩的人看的：有哪幾種玩法、怎麼開一場、牌桌上哪裡是什麼、怎麼打、打完與離開，以及選單。 */
    private static String gameplayPage() {
        String levels = Arrays.stream(BotLevel.values()).map(BotLevel::label).collect(Collectors.joining("、"));
        return "<h2>三種玩法</h2>"
                + "<p>首頁有三個入口：</p><ul>"
                + "<li><b>練習牌局</b>：自己決定籌碼、底台與三家電腦的等級，隨時可以開新的一場，適合熟悉規則。</li>"
                + "<li><b>修業模式</b>：建立自己的檔案，籌碼的輸贏一直累積下去，還有 " + Challenge.line(false).size()
                + " 道挑戰關卡、" + Challenge.line(true).size() + " 道記牌與看聽的修練與稱號可以拿。最多三個檔案。</li>"
                + "<li><b>連線對戰</b>：和朋友在網路上同桌，不到四人由電腦補滿。</li></ul>"
                + "<p>練習牌局、每個修業檔案的成就、統計與榮譽榜都分開記。</p>"
                + "<h2>開始一場練習牌局</h2>"
                + "<p>輸入名字（最多 " + StartPanel.MAX_NAME_LENGTH + " 字，不能有不雅的字眼），選籌碼本金、底、每台，"
                + "以及右家、對家、左家的電腦等級，按「開始練習牌局」。電腦由弱到強是" + levels
                + "，四個等級拿到的牌與規則都一樣，差別只在看多少資訊、想多少。底和每台不能大於籌碼本金。"
                + "名字沒填或不合、底台太大時，按下去會跳出提醒，改好再按。想考驗自己可以勾「專家模式」。</p>"
                + "<p>上次離開時存了檔，畫面上會有「繼續上次的練習牌局」。存檔只有一份，開新的一場會蓋掉。</p>"
                + "<h2>修業模式</h2>"
                + "<p>在空的檔案按「建立新檔案」，取名字（最多 " + CareerProfile.MAX_NAME_LENGTH + " 字，不能有不雅的字眼）、"
                + "選起始籌碼，想考驗自己可以勾「專家模式」（見下面的「專家模式」）。"
                + "名字、起始籌碼與專家模式建立之後都不能改。每個檔案的成就、統計與榮譽榜各自記錄，和練習牌局也分開。</p>"
                + "<p>進入檔案之後：</p><ul>"
                + "<li><b>一般對戰</b>：帶帳上的籌碼打四圈，每局結算完就入帳，輸贏不會重來。籌碼越多，能選的底台越大："
                + "底最多帳上的 1/" + CareerProfile.BASE_SHARE + "、每台最多 1/" + CareerProfile.TAI_SHARE + "。</li>"
                + "<li><b>挑戰關卡</b>：每關的對手與任務固定，過了前一關才開放下一關，第一次過關有獎金。"
                + "每關最多三顆星，過關之外再達成加星條件就多一顆，可以回頭再打來拿齊。</li>"
                + "<li><b>修練</b>：練記牌與看聽的小關卡，不是完整的牌局。記牌是照常打牌，打到某一輪停下來，"
                + "牌桌蓋住問剛才誰打了什麼；看聽是先看一手聽牌，蓋起來之後點出它聽哪幾種。每題限時，答對夠多就過關，"
                + "一樣有獎金與三顆星。修練自成一條線，第一關一開始就開放，和挑戰關卡互不影響。</li>"
                + "<li>籌碼少到上不了桌時可以領救濟金，會記一次破產。帳上的籌碼到過一定數目就得到稱號。</li></ul>"
                + "<p>修業模式不能用電腦代打。詳細規則在「基本規則」最後的「修業模式」。</p>"
                + "<h2>連線對戰</h2>"
                + "<p>按「連線對戰」進大廳，先在最上面填名字（最多 " + OnlinePanel.MAX_NAME_LENGTH
                + " 字，不能有不雅的字眼），名字會顯示在牌桌上。沒填名字或名字不合就按了開房間或加入，會跳出提醒。</p><ul>"
                + "<li><b>加入房間</b>：大廳的「公開的房間」列出還在等人的房間，以及開了隨時補位、正在打的牌局"
                + "（名稱後面標「牌局中」），等人的排在前面。選一間按「加入」，或連點兩下；加入「牌局中」的房間就是補位，"
                + "按了會先問一次。「隨時補位」與「專家」兩欄，房間有開就打勾。"
                + "私人房間不在列表上，要在「用房號加入」填六碼房號。</li>"
                + "<li><b>開房間</b>：選好右邊的設定按「開房間」，開房間的人是房主。按「複製房號」把房號傳給朋友，"
                + "或等大廳上的人加入；房主按「開始牌局」才開打，不到四人時空的座位由電腦補上，所以只想跟電腦打也可以直接開打。"
                + "打完一場回到房間，可以再開一場。</li></ul>"
                + "<p>開房間的設定：</p><ul>"
                + "<li><b>房間名稱</b>：最多 " + RoomSettings.MAX_NAME_LENGTH + " 字，不能有不雅的字眼，空白就用「你的名字的房間」。</li>"
                + "<li><b>私人房間</b>：勾了就不列在大廳，只能用房號加入。</li>"
                + "<li><b>隨時補位</b>：勾了「開啟」，開打之後電腦在打的座位別人也能加入替換，見下面的「隨時補位」。</li>"
                + "<li><b>籌碼本金、底、每台</b>：每個人開局時的籌碼，每位付款者付「底 ＋ 台數 × 每台」，見「台數計算」。"
                + "底和每台都不能大於籌碼本金，太大時按了會提醒。</li>"
                + "<li><b>補位電腦等級</b>：依序是房主的右家、對家、左家，各選一個等級，有真人坐的座位用不到。</li>"
                + "<li><b>等待秒數</b>：輪到你打牌，或問你要不要吃、碰、槓、胡時最多等幾秒，" + RoomSettings.MIN_WAIT_SECONDS
                + " 到 " + RoomSettings.MAX_WAIT_SECONDS + " 秒，專家模式最多 " + GameConfig.EXPERT_MAX_WAIT_SECONDS
                + " 秒（勾了專家模式，比較長的會自動改小）。時間到就替你打出最右邊的牌，還沒決定的宣告算過。</li>"
                + "<li><b>電腦速度</b>：慢、中、快，是電腦出牌前與等宣告時停多久，只影響節奏，不影響牌局結果。</li>"
                + "<li><b>專家模式</b>：勾了房間裡每個人都照專家模式打，見下面的「專家模式」。</li></ul>"
                + "<p><b>斷線與接回</b>：連線斷了會自己重連，最多試 3 分鐘，這段時間座位由電腦代打。程式被關掉的話，"
                + "重開之後按「連線對戰」的「接回牌局」，就回到原本的座位。按了「離開牌局」、牌局已經結束，"
                + "或全桌都斷線太久房間關了，就接不回來。連線牌局不記成就、統計與榮譽榜。</p>"
                + "<h2>隨時補位</h2>"
                + "<p>連線對戰開房間的設定勾了「隨時補位」的「開啟」，開打之後，電腦在打的座位別人也能中途加入替換，"
                + "人沒到齊也能先開打，晚到的親友或大廳上的牌友隨時補上。房間畫面在名稱旁標著「隨時補位」。網頁版也一樣。</p><ul>"
                + "<li><b>哪些座位能換</b>：開局時由電腦補上的座位，以及玩家中途按「離開牌局」、由電腦打到最後的座位。"
                + "斷線的玩家還會回來，他的座位替他留著，不能換。</li>"
                + "<li><b>怎麼加入</b>：大廳列表上標「牌局中」的房間，選了按「加入」，確定之後就加入；也可以用房號加入，"
                + "私人房間只能用房號。有好幾家電腦時，從房主的下家（座位號碼小的）換起。電腦都有人換了或正在等，就加不進去。</li>"
                + "<li><b>什麼時候上場</b>：加入後先替你保留座位，牌局照走，不會停下來等你。大廳下方寫著在等哪一家，"
                + "等那家電腦打完這一手（打出一張牌）就換你上場，通常只要等一兩手。這一局在那之前就結束的話，"
                + "下一局開始時上場，整局都是你打；整場在你上場前就打完的話，你會直接坐進房間，等房主開下一場。"
                + "等的時候可以按「取消」，保留的座位就讓給別人。</li>"
                + "<li><b>上場之後</b>：接著那一家的手牌、籌碼、牌河與聽牌繼續打，和斷線接回來一樣。"
                + "全桌的牌桌上方都會跳出金框的公告「玩家 ○○ 替換電腦（××）加入」，那一家旁邊跳出「補位」，"
                + "紀錄與聊天區也各留一行；換的是離開的玩家留下的座位，就寫「替換電腦（代打「××」的座位）」。</li>"
                + "<li><b>榮譽榜</b>：中途補位的人不列入網頁版大廳的榮譽榜，就算之後一直打到結束也一樣；"
                + "開局時就坐著、打到結束的人照樣列入。想上榜，就在開局前加入。</li>"
                + "<li>補位上場的人斷線，和其他人一樣由電腦代打、可以接回；按「離開牌局」的話，座位又交回電腦，別人可以再補。</li></ul>"
                + "<h2>專家模式</h2>"
                + "<p>練習牌局開始前、修業模式建立檔案時與連線對戰開房間時，都可以把「專家模式」打勾。"
                + "規則與台數都一樣，只是要記得更多：</p><ul>"
                + "<li>" + HomePanel.escape(StartPanel.EXPERT_SUMMARY) + "。打牌前沒有聽牌按鈕，打出之後已經聽牌才出現，"
                + "聽牌之後也不列出等哪些牌。</li>"
                + "<li>每次最多等 " + GameConfig.EXPERT_MAX_WAIT_SECONDS + " 秒：練習牌局照環境設定的「等待時間」，"
                + "但設得比較長或不限時也只等 " + GameConfig.EXPERT_MAX_WAIT_SECONDS + " 秒；連線開房間的等待秒數只能選 "
                + RoomSettings.MIN_WAIT_SECONDS + " 到 " + GameConfig.EXPERT_MAX_WAIT_SECONDS + "。</li>"
                + "<li>修業檔案的專家模式：起始籌碼只有 " + CareerProfile.EXPERT_STARTING_CHIPS
                + "，底台只要不大於帳上的籌碼就能選，每次等待固定 " + CareerProfile.EXPERT_WAIT_SECONDS
                + " 秒，挑戰獎金是原本的 1/" + CareerProfile.EXPERT_REWARD_SHARE + "。建立之後就不能改。</li>"
                + "<li>連線的專家模式房間：房間裡每個人都照專家模式打，大廳列表與房間畫面標著「專家」。</li></ul>"
                + "<p>完整的規則見「基本規則」的「專家模式」。</p>"
                + "<h2>牌桌畫面</h2><ul>"
                + "<li><b>下方</b>是你的手牌，剛摸到的牌放在最右邊、和其他牌隔開。上、左、右是三家，看不到他們的牌面。</li>"
                + "<li><b>中央方框</b>寫著圈風局風與牌牆還剩幾張，連莊時多一行紅字「連N拉N」。四邊是各家的門風與名字，"
                + "輪到誰，那一邊就亮金色；聽牌的人名字旁有圓圈的「聽」。</li>"
                + "<li>方框外圍是<b>牌牆</b>，再外面是各家打出的牌，剛打出的那張有紅框。</li>"
                + "<li><b>動作列</b>在最下面，寫著現在要做什麼與還剩幾秒，要你決定的時候會出現按鈕。</li>"
                + "<li><b>右側</b>是四家的門風、名字與籌碼：莊家旁有紅色圓圈的「莊」，連莊時旁邊寫連莊次數；"
                + "滑鼠停在電腦的名字上，可以看到它的打牌個性。下面依序是平均思考時間、紀錄（點一下展開，看這局發生的每件事；"
                + "專家模式只顯示最新一則）、"
                + "電腦代打、輸贏歷程（每一局誰贏誰輸）與離開牌局。</li></ul>"
                + "<h2>怎麼打</h2><ul>"
                + "<li><b>摸牌</b>是自動的；摸到花牌會自動亮出來，再從牌尾補一張。</li>"
                + "<li><b>打牌</b>：輪到你時，滑鼠移到手牌上那張會抬起來，點一下就打出去。</li>"
                + "<li><b>吃、碰、槓、胡</b>：別家打出你用得到的牌，動作列就出現對應的按鈕；不要就按「過」。"
                + "吃有好幾種吃法時，再點手牌選要用哪幾張。</li>"
                + "<li><b>自摸、暗槓、加槓</b>：輪到你時做得到就出現按鈕，不按就照常打牌。</li>"
                + "<li><b>吃、碰之後</b>要再打一張牌，提示會放大閃動提醒你。</li>"
                + "<li><b>聽牌</b>：打出某張就會聽牌時，出現半透明的「聽牌」按鈕，見「基本規則」的「聽牌」。</li>"
                + "<li><b>時間</b>：環境設定的「等待時間」可以限制每次想幾秒。時間到還沒決定，吃碰槓胡算放棄，輪到打牌就打出最右邊那張。</li>"
                + "<li><b>電腦代打</b>：練習牌局可以按右側的「電腦代打」交給電腦打，再按一次拿回來；用過的牌局不列入榮譽榜。</li></ul>"
                + "<h2>一局結束</h2>"
                + "<p>有人胡牌時跳出「胡」或「自摸」的大字，贏家的手牌推倒翻開，籌碼從付錢的人飛向贏家，各家跳出輸贏的數字。"
                + "牌摸完沒人胡就流局，由司儀宣布「流局」；莊家連莊時，下一局開始司儀會說「連莊，連二拉二」這樣的連莊數。"
                + "接著是結果視窗，列出台數、每家付多少與大家的手牌，倒數完自動進入下一局，也可以直接按按鈕。"
                + "四圈打完或有人籌碼輸光，就顯示整場的排名。台數怎麼算見「台數計算」。</p>"
                + "<h2>離開與存檔</h2>"
                + "<p>牌局中按「離開牌局」或關閉視窗，會問要不要存檔。練習牌局存了檔，回到練習牌局畫面就能繼續；"
                + "修業模式存了檔，在檔案畫面按「繼續牌局」接著打，選「結束這場」的話一般對戰已經打完的局照算，挑戰算失敗。"
                + "連線對戰不存在這台電腦上。</p>"
                + "<h2>選單</h2><ul>"
                + "<li><b>環境設定</b>：字體大小、麻將大小、等待時間、遊戲速度（電腦出牌的快慢）、解析度、"
                + "背景音樂（四首合成的曲子，加上兩首錄好的「清空牌桌」與「木板暖陽」，第一次播放要等一兩秒）、音量、音效、"
                + "語音（喊牌，以及司儀宣布流局與連莊；司儀的聲音穩重，不跟著語音風格）、我的語音（自己喊牌用女聲或男聲）、"
                + "語音風格與動畫。改了立刻生效，下次開啟也記得。"
                + "解析度比 1360 × 900 大或最大化時，整個畫面跟著等比例放大。</li>"
                + "<li><b>榮譽榜、成就、統計</b>：看自己的紀錄。進入修業檔案時看那個檔案的，其他時候看練習牌局的。"
                + "「統計」裡的「段位證照」是初段到九段的證書，可以換個人圖像、複製到 LINE 或存成圖片。</li>"
                + "<li><b>說明</b>：簡介、這一頁、基本規則、台數計算、段位證照與關於。說明視窗不擋住牌桌，可以邊看邊打。</li></ul>";
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

    /** owner 是這套紀錄屬於誰，例如「練習牌局」或「修業：阿明」，寫在標題與頁首。贏錢金額先看最近有新紀錄的底台。 */
    void showHonorRoll(HonorRoll roll, HonorRoll.Category category, String owner) {
        showHonorRoll(roll, category, owner, roll.latestStakes());
    }

    /** stakes 只有贏錢金額用得到；那組已經沒有紀錄了就換成最近有新紀錄的。 */
    private void showHonorRoll(HonorRoll roll, HonorRoll.Category category, String owner, HonorRoll.Stakes stakes) {
        HonorRoll.Stakes shown = category != HonorRoll.Category.WINNINGS ? null
                : stakes != null && roll.stakes().contains(stakes) ? stakes : roll.latestStakes();
        currentPage = () -> showHonorRoll(roll, category, owner, shown);
        showPage("榮譽榜：" + category.label() + "（" + owner + "）", ownerLine(owner) + honorPage(roll, category, shown));
        if (shown != null) {
            fillStakes(roll.stakes(), shown, chosen -> showHonorRoll(roll, category, owner, chosen));
        }
    }

    /** 列出有紀錄的底台並選好 shown。showPage 會把這排收起來，所以畫好頁面之後再叫。 */
    private void fillStakes(List<HonorRoll.Stakes> all, HonorRoll.Stakes shown, Consumer<HonorRoll.Stakes> onChosen) {
        onStakesChosen = null;
        stakesLabel.setFont(Theme.font(Font.PLAIN, 16f));
        stakesBox.setFont(Theme.font(Font.PLAIN, 16f));
        stakesBox.removeAllItems();
        for (HonorRoll.Stakes stakes : all) {
            stakesBox.addItem(stakes.label());
        }
        listedStakes = all;
        stakesBox.setSelectedIndex(all.indexOf(shown));
        onStakesChosen = onChosen;
        stakesBar.setVisible(true);
    }

    /** group 是 null 時顯示全部分類。 */
    void showAchievements(AchievementBook book, Achievement.Group group, String owner) {
        currentPage = () -> showAchievements(book, group, owner);
        showPage("成就" + (group == null ? "" : "：" + group.label()) + "（" + owner + "）",
                ownerLine(owner) + achievementPage(book, group));
    }

    /** career 是修業檔案，練習牌局傳 null。 */
    void showStats(PlayerStats stats, String owner, CareerProfile career) {
        currentPage = () -> showStats(stats, owner, career);
        showPage("我的統計（" + owner + "）", ownerLine(owner) + (career == null ? "" : careerSection(career))
                + statsPage(stats));
    }

    /** 九段的門檻與目前的成績；current 是現在的段位，還沒入段是 null。 */
    void showDans(PlayerStats stats, Dan current, String owner) {
        currentPage = () -> showDans(stats, current, owner);
        showPage("段位證照（" + owner + "）", ownerLine(owner) + dansPage(stats, current));
    }

    private static String dansPage(PlayerStats stats, Dan current) {
        StringBuilder html = new StringBuilder("<h2>段位證照</h2>"
                + "<p>局數、胡牌率、自摸率達到門檻，放槍率不超過門檻，四項都做到就升到那一段，發一張段位證書。"
                + "證書上有名字、段位、認定日期、那一段看的局數、胡牌數、自摸數、胡牌率、自摸率、放槍率、16 碼證號與個人圖像，"
                + "可以複製到 LINE 或存成圖片。"
                + "段位只升不降，之後成績掉下來也不收回，證書上的數字是升段那天的。</p>"
                + "<p>證書上的名字不能改：修業檔案是建立檔案時取的名字，練習牌局是第一次記進統計那一局的名字。</p>"
                + "<p>證號是英文與數字，例如 EZ76 L09H 4QX2 MR8T：EZ、段位、認定年份的個位數、月份（A 是一月，跳過 I，"
                + "M 是十二月）、兩碼日、六碼亂數，最後三碼是用前面的碼與名字算出來的驗證碼。證照視窗的「驗證證號…」"
                + "輸入證號與名字，對得上就會說是哪一段、哪一天認定的，名字或證號錯一個字就對不上。</p>"
                + "<p>對初學者很容易把胡牌率衝高，所以段位越高只看越強對手的局：初段到三段看全部的局，"
                + "四段到六段只看三家電腦都是業餘以上的局，七段到九段只看三家都是職業的局。"
                + "成績同時到了好幾段，直接給最高的，所以只跟職業打的人可能直接拿到七段。</p>");
        html.append("<p class=\"tai\"><b>目前：").append(current == null ? "尚未入段" : current.label()).append("</b></p>");
        html.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\"><tr class=\"head\">");
        for (String title : new String[] {"段位", "只看哪些局", "局數", "胡牌率", "自摸率", "放槍率",
            "你的局數", "胡牌率", "自摸率", "放槍率"}) {
            html.append("<td class=\"cell\">").append(title).append("</td>");
        }
        html.append("</tr>");
        for (Dan dan : Dan.values()) {
            PlayerStats.Totals pool = dan.pool(stats);
            boolean mine = dan == current;
            html.append("<tr>").append(honorCell(dan.label(), mine))
                    .append(honorCell(dan.opponentsText(), mine))
                    .append(honorCell(String.valueOf(dan.rounds()), mine))
                    .append(honorCell(CertificateDialog.permille(dan.winPermille()), mine))
                    .append(honorCell(CertificateDialog.permille(dan.selfDrawPermille()), mine))
                    .append(honorCell("≤ " + CertificateDialog.permille(dan.dealInPermille()), mine))
                    .append(honorCell(String.valueOf(pool.rounds()), mine))
                    .append(honorCell(percent(pool.winRate()), mine))
                    .append(honorCell(percent(pool.selfDrawRate()), mine))
                    .append(honorCell(percent(pool.dealInRate()), mine))
                    .append("</tr>");
        }
        return html.append("</table><p class=\"note\">門檻參考電腦互打的成績：實力相當時每家胡牌率約 22 到 24%、自摸率 5 到 7%、"
                + "放槍率 15 到 20%，職業對三家職業是胡 23.6%、自摸 6.6%、放槍 16.9%。自摸看運氣的成分大，門檻壓低。"
                + "放槍率是上限，不能超過。「三家都是某等級以上」的局是有段位證照之後才開始記的，以前的局只算在全部的局裡。"
                + "統計怎麼算，證照就怎麼算：開了電腦代打之後的局不列入，挑戰關卡與修練的局不算，連線對戰也不算。"
                + "練習牌局與每個修業檔案各有一張證照。選單「統計」→「段位證照」看證書、換個人圖像或驗證證號。</p>").toString();
    }

    private static String ownerLine(String owner) {
        return "<p class=\"note\">這是「" + escape(owner) + "」的紀錄。練習牌局與每個修業檔案各記各的。</p>";
    }

    private static String careerSection(CareerProfile career) {
        StringBuilder html = new StringBuilder("<h2>修業檔案</h2><table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\">");
        statRow(html, "帳上籌碼", career.bankroll() + "（起始 " + career.startingChips() + "，最高 " + career.peak() + "）");
        statRow(html, "稱號", escape(CareerHubPanel.titleText(career)));
        statRow(html, "破產", career.bankruptcies() + " 次");
        statRow(html, "挑戰過關", career.clearedCount(false) + " / " + Challenge.line(false).size() + " 關");
        statRow(html, "修練過關", career.clearedCount(true) + " / " + Challenge.line(true).size() + " 關");
        statRow(html, "建立日期", escape(career.created()));
        return html.append("</table>").toString();
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
                + "累計輸贏把不同底台的牌局直接加總。修業模式的挑戰關卡與修練不列入統計，只算一般對戰。</p>").toString();
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
                + "在那之前自己打完的局仍然算數，中途離開的牌局也一樣。"
                + "修業模式的挑戰關卡每局照算，整場類的成就與「打完幾場」不算；修練都不算。</p>").toString();
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

    /** 贏錢金額只看 stakes 那組底台的榜，不同底台不放在一起比；stakes 是 null 表示還沒有贏錢紀錄。 */
    private static String honorPage(HonorRoll roll, HonorRoll.Category category, HonorRoll.Stakes stakes) {
        boolean byStakes = category == HonorRoll.Category.WINNINGS;
        StringBuilder html = new StringBuilder("<h2>").append(category.label())
                .append(byStakes && stakes != null ? "（" + stakes.label() + "）" : "")
                .append(" 前 ").append(HonorRoll.SIZE).append(" 名</h2>");
        List<GameRecord> records = !byStakes ? roll.top(category) : stakes == null ? List.of() : roll.top(stakes);
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
                + "每個類別只列數值大於 0 的成績，數值相同時先達成的排前面。修業模式的挑戰關卡與修練不列入。</p>"
                + (byStakes ? "<p class=\"note\">贏錢金額照底與每台分開排名，不同底台的輸贏不放在一起比，上面的選單可以換底台。"
                        + "最多留 " + HonorRoll.MAX_STAKES + " 組底台，超過時拿掉最久沒有新紀錄的那組。</p>" : "")).toString();
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
        stakesBar.setVisible(false);
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
                + "<p>結束視窗的「分享結果圖」把這一場的成績表畫成一張圖，自己那一列有金框，按「複製圖片」再到 LINE 的對話框按 Ctrl+V 就能貼上，"
                + "也可以存成圖檔；「存成輸贏 PDF…」把每一局的輸贏存成 A4 的 PDF。</p>"
                + "<h2>每局結果</h2>"
                + "<p>結果視窗會倒數，時間到自動進入下一局：流局 10 秒，其他 60 秒；連線對戰是流局 5 秒，其他 15 秒。也可以直接按按鈕。</p>"
                + "<h2>聽牌</h2>"
                + "<p>輪到你打牌時，如果打出某張牌就會聽牌，下方會多一個半透明的「聽牌」按鈕。按下後，打出去會聽牌的牌加上金框，"
                + "其他牌蓋起來不能選，提示會列出打哪張聽哪些牌；點一張金框的牌打出就聽牌，按「取消」可以回去照常打牌。"
                + "沒按聽牌就打出去的，這一輪就不能聽牌了。</p>"
                + "<p>專家模式與連線對戰沒有打牌前的提示，照常打牌，打出之後如果已經聽牌，下方會出現「聽牌」按鈕，"
                + "一直到你下一次摸牌、吃碰槓或這局結束，中間按「過」放棄別家打出的牌不影響。"
                + "專家模式聽牌之後也不列出等哪些牌，要自己記。</p>"
                + "<p>聽牌之後自動摸牌打牌，胡的牌來了就自動胡，也不再吃、碰、槓，這一局不能取消。</p>"
                + "<p>聽牌是公開的，全桌都看得到，你的名字旁會有圓圈的「聽」。胡的時候多算「聽牌」1 台，自摸或胡別家的牌都算，"
                + "也算在牌型台數裡。電腦也會聽牌：初學者不會，其他等級看手牌與局勢決定。</p>"
                + "<h2>專家模式</h2>"
                + "<p>練習牌局開始前、修業模式建立檔案時可以勾選專家模式：" + HomePanel.escape(StartPanel.EXPERT_SUMMARY)
                + "。打牌前沒有聽牌按鈕，打出之後已經聽牌才出現；右側的紀錄只顯示最新一則，不能展開；"
                + "牌河每家只留最後打出的那一張，被吃碰槓走就不見；牌桌中間不寫剩幾張；每次最多等 "
                + GameConfig.EXPERT_MAX_WAIT_SECONDS + " 秒，環境設定的等待時間比較長或不限時也一樣。</p>"
                + "<p>連線對戰開房間時也可以勾，房間裡每個人都照專家模式打；大廳列表的「專家」欄打勾，"
                + "房間畫面標著「專家模式」。</p>"
                + "<p>專家模式的修業檔案起始籌碼只有 " + CareerProfile.EXPERT_STARTING_CHIPS
                + "，底與每台只要不大於帳上的籌碼就能選，每次等待固定 " + CareerProfile.EXPERT_WAIT_SECONDS
                + " 秒，不能調整；連最小的底 " + CareerProfile.EXPERT_RELIEF_BELOW + " 都付不起時才能領救濟金，補回 "
                + CareerProfile.EXPERT_RELIEF_TO + "；挑戰的獎金是原本的 1/" + CareerProfile.EXPERT_REWARD_SHARE
                + "，見修業模式關卡表的「專家獎金」。建立之後就不能改。</p>"
                + "<h2>電腦代打</h2>"
                + "<p>牌桌右側的「電腦代打」按鈕可以隨時開關，打開後由職業等級的電腦替你決定。"
                + "用過電腦代打的牌局不列入榮譽榜。只有練習牌局能用，連線對戰與修業模式（一般對戰、挑戰關卡與修練）都不能使用電腦代打。</p>"
                + careerRules();
    }

    /** 稱號的門檻，直接從 CareerTitle 列出來。 */
    private static String careerTitles() {
        StringBuilder html = new StringBuilder("<p>帳上的籌碼曾經到過這些數目就得到稱號，之後輸掉也不會拿走：");
        for (CareerTitle title : CareerTitle.values()) {
            html.append(title.ordinal() == 0 ? "" : "、").append(title.threshold()).append("「")
                    .append(title.label()).append("」");
        }
        return html.append("。</p>").toString();
    }

    /** 修業模式的規則與關卡表，關卡直接從 Challenge 列出來。 */
    private static String careerRules() {
        StringBuilder html = new StringBuilder("<h2>修業模式</h2>"
                + "<p>建立檔案時選好起始籌碼，之後一般對戰帶帳上的籌碼上桌打四圈，每局結算完就入帳，輸贏不會重來。"
                + "籌碼越多能選的底台越大：底最多帳上的 1/" + CareerProfile.BASE_SHARE + "、每台最多 1/"
                + CareerProfile.TAI_SHARE + "。"
                + "帳上不到 " + CareerProfile.RELIEF_BELOW + "（連最小的底都選不了）時可以領救濟金補到 "
                + CareerProfile.RELIEF_TO + "，會記一次破產。每個檔案的成就、統計與榮譽榜各自記錄，和練習牌局也分開。"
                + "勾了專家模式的檔案規則不一樣，見上面的「專家模式」。修業模式不能用電腦代打。</p>"
                + careerTitles()
                + "<p>挑戰關卡要依序過關，每關的對手、圈數、籌碼與底台都固定，不從帳上扣籌碼；第一次過關有獎金。"
                + "任務一確定成功或失敗，這場就在那一局結束。挑戰的每一局照算成就，但不算整場類的成就與榮譽榜，"
                + "也不記進統計與段位證照：挑戰的對手、籌碼與圈數都是關卡定好的。</p>"
                + "<p>每關最多三顆星：過關一顆，過關的那一刻再達成兩個加星條件各一顆，例如到那時都沒放槍、在東風圈內就過關。"
                + "已經過的關可以再挑戰，幾次拿到的星合起來算；第一次拿到第二或第三顆星，各多給過關獎金的 1/"
                + CareerProfile.STAR_BONUS_SHARE + "（專家檔案照專家獎金算）。修業檔案的畫面與檔案列表看得到拿了幾顆星。</p>");
        challengeTable(html, Challenge.line(false), "任務", "牌桌");
        html.append("<p><b>修練</b>是另外一條線，練記牌與看聽，不是完整的牌局：第一關一開始就開放，過了前一關才開下一關，"
                + "和上面的挑戰關卡互不影響。修練不記統計、成就、榮譽榜與段位證照，不能存檔，中途離開就是沒過關；"
                + "因為不是完整的牌局，獎金比較少。每題都限時，從可以作答時開始算，時間到算答錯。</p><ul>"
                + "<li><b>記牌</b>：照專家模式打一局，牌河每家只看得到最後一張，自己的牌自己打。四家各打一張算一輪，"
                + "打到第幾輪會隨機停下來，牌桌蓋住開始問：最後打出的幾張、某一家最後兩張（照先後點牌，點滿了按「確定」，"
                + "點錯可以「退一張」）、某張牌一共被打出幾張、某張牌是誰打的（點一下選項就送出）。各家照這局的門風叫，例如「東家」。</li>"
                + "<li><b>看聽</b>：先亮一手聽牌的 16 張，看牌的時間到或按「看好了」就蓋起來，從下面 34 種牌點出它聽的每一種，"
                + "再按「確定」。少點、多點都算錯。</li></ul>"
                + "<p>每題答完馬上說對錯與正解，答錯的也列出你答的。每關三顆星：過關、全部答對、平均作答秒數在門檻內；"
                + "獎金與加星獎金的算法和挑戰關卡一樣。</p>");
        challengeTable(html, Challenge.line(true), "內容", "出題");
        return html.toString();
    }

    /** 一條線的關卡表：關、名稱、任務或內容、加星條件、牌桌或出題、獎金與專家獎金。 */
    private static void challengeTable(StringBuilder html, List<Challenge> line, String goal, String setup) {
        html.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"5\"><tr class=\"head\">");
        for (String title : new String[] {"關", "名稱", goal, "加星條件", setup, "獎金", "專家獎金"}) {
            html.append("<td class=\"cell\">").append(title).append("</td>");
        }
        html.append("</tr>");
        for (Challenge challenge : line) {
            html.append("<tr>").append(honorCell(String.valueOf(challenge.number()), false))
                    .append(honorCell("<b>" + challenge.title() + "</b>", false))
                    .append(honorCell(challenge.goalText(), false))
                    .append(honorCell(String.join("<br>", challenge.starTexts().subList(1, Challenge.MAX_STARS)), false))
                    .append(colorCell(challenge.setup(), Theme.MUTED))
                    .append(honorCell(String.valueOf(challenge.reward()), false))
                    .append(honorCell(String.valueOf(challenge.reward() / CareerProfile.EXPERT_REWARD_SHARE), false))
                    .append("</tr>");
        }
        html.append("</table>");
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
