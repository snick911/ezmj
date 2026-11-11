package ezmj.ui;

import static ezmj.ui.ImageKit.graphics;
import static ezmj.ui.ImageKit.roundStroke;
import static ezmj.ui.ImageKit.text;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import ezmj.AppVersion;
import ezmj.engine.GameView;
import ezmj.engine.RoundRecord;
import ezmj.engine.RoundResult;
import ezmj.model.GameRecord;
import ezmj.model.Player;
import ezmj.report.PdfWriter;
import ezmj.ui.ImageKit.Align;

/**
 * 牌局結束後的結果圖與輸贏歷程 PDF，畫法和網頁版一樣。
 * 結果圖是直的 4:5，照牌桌的配色，給傳到 LINE 這類 App；PDF 是白底的 A4 橫放，每頁一張圖，一頁放不下就往下一頁接。
 * 房號不寫上去：房間打完還在，私人房的房號傳出去別人就能加入。單機與連線共用，自己一律是座位 0。
 */
public final class GameReport {

    public static final int IMAGE_W = 1080;
    public static final int IMAGE_H = 1350;
    /** A4 橫放，150 dpi。 */
    public static final int PAGE_W = 1754;
    public static final int PAGE_H = 1240;
    private static final int PDF_W = 842;
    private static final int PDF_H = 595;
    private static final float JPEG_QUALITY = .9f;
    private static final int SEATS = 4;

    private static final Color[] MEDALS = {new Color(0xE8C05A), new Color(0xCFD8DC), new Color(0xD08C4E),
        new Color(0x7C948A)};
    private static final Color MEDAL_INK = new Color(0x1B1B1B);
    private static final Color MINE_ROW = new Color(232, 192, 90, 56);
    private static final Color SHARE_PANEL = new Color(0, 0, 0, 77);
    private static final Color SHARE_RULE = new Color(255, 255, 255, 26);
    private static final Color GOLD_FAINT = new Color(232, 192, 90, 89);
    /** 印出來的顏色：贏是紅字、輸是綠字，和看盤的習慣一樣。 */
    private static final Color INK = new Color(0x1E2A25);
    private static final Color GRAY = new Color(0x66766F);
    private static final Color RULE = new Color(0xC9D3CE);
    private static final Color STRIPE = new Color(0xF3F6F4);
    private static final Color HEAD = new Color(0xE3EBE7);
    private static final Color MINE = new Color(0xFBF1D6);
    private static final Color WIN = new Color(0xB3261E);
    private static final Color LOSS = new Color(0x1B6E44);

    private static final double MARGIN = 72;
    private static final double BOTTOM = PAGE_H - 80;
    private static final double ROW_H = 64;
    private static final double HEAD_H = 46;
    private static final double RANK_ROW_H = 46;
    /** 每局輸贏的欄寬，加起來是頁寬扣掉左右邊界。依序是局、圈風與莊家、結果、台、四家、我的累計。 */
    private static final double[] ROUND_COLUMNS = {70, 250, 560, 70, 130, 130, 130, 130, 140};
    private static final double[] RANK_COLUMNS = {90, 420, 150, 150, 150, 220, 220};
    private static final double RANK_TOP = 226;
    private static final double FIRST_TABLE_TOP = 514;
    private static final double NEXT_TABLE_TOP = 174;

    /** 結果圖上成績表的左緣，右邊也留一樣寬。 */
    public static final double SHARE_TABLE_X = 66;
    private static final double SHARE_TABLE_TOP = 372;
    private static final double SHARE_HEAD_H = 76;
    public static final double SHARE_ROW_H = 172;
    /** 自己那一列的金框比表格左右各寬這麼多。 */
    public static final double MINE_OUTSET = 8;
    /** 名次與玩家、胡牌、自摸、放槍、輸贏、籌碼，加起來是圖寬扣掉左右的 SHARE_TABLE_X。 */
    private static final double[] SHARE_COLUMNS = {360, 80, 80, 80, 180, 168};
    /** 數字放不下時縮到這麼小，比名字縮得多，數字不能截掉。 */
    private static final double SHARE_MIN_NUMBER = 20;
    private static final Align[] SHARE_ALIGNS = {Align.LEFT, Align.CENTER, Align.CENTER, Align.CENTER, Align.RIGHT,
        Align.RIGHT};

    private final GameView game;
    private final String headline;
    private final String roomName;
    private final LocalDateTime finished;

    /** headline 是牌局結束視窗最上面那一行，null 時照結束原因寫；roomName 是房間名稱，單機或沒有名稱是空字串。 */
    public GameReport(GameView game, String headline, String roomName, LocalDateTime finished) {
        this.game = game;
        this.headline = headline == null ? FinalDialog.headline(FinalDialog.ranking(game)) : headline;
        this.roomName = roomName.trim();
        this.finished = finished;
    }

    /** 例如 2026/11/06 21:30。 */
    public String when() {
        return finished.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));
    }

    private String stamp() {
        return finished.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
    }

    public String imageFileName() {
        return "ezmj-結果-" + stamp() + ".png";
    }

    public String pdfFileName() {
        return "ezmj-輸贏歷程-" + stamp() + ".pdf";
    }

    private String infoLine() {
        return "底 " + grouped(game.basePay()) + "／每台 " + grouped(game.taiPay()) + "　共打 " + game.roundsPlayed()
                + " 局，流局 " + game.drawnGames() + " 次";
    }

    private static String footer() {
        return "EZMJ 電腦版 " + AppVersion.get();
    }

    // ---- 結果圖 ----

    /** 這一場的成績表，欄位和牌局結束視窗的表格一樣。自己那一列有金色的底、粗金框與金色名字，金框比表格左右各寬 MINE_OUTSET。 */
    public BufferedImage shareImage() {
        BufferedImage image = new BufferedImage(IMAGE_W, IMAGE_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(IMAGE_W / 2.0, IMAGE_H * .4), (float) (IMAGE_H * .8),
                new float[] {0f, 1f}, new Color[] {Theme.FELT, Theme.FELT_DARK}));
        g.fillRect(0, 0, IMAGE_W, IMAGE_H);
        g.setColor(Theme.GOLD);
        g.setStroke(roundStroke(4));
        g.draw(new RoundRectangle2D.Double(28, 28, IMAGE_W - 56, IMAGE_H - 56, 40, 40));
        g.setColor(GOLD_FAINT);
        g.setStroke(roundStroke(2));
        g.draw(new RoundRectangle2D.Double(42, 42, IMAGE_W - 84, IMAGE_H - 84, 30, 30));

        Player[] ranking = FinalDialog.ranking(game);
        int[] ranks = FinalDialog.ranks(ranking);
        double middle = IMAGE_W / 2.0;
        g.setColor(Theme.GOLD);
        text(g, "EZMJ 台灣十六張麻將", true, 58, 40, IMAGE_W - 160, middle, 128, Align.CENTER);
        g.setColor(Theme.TEXT);
        text(g, headline, true, 42, 28, IMAGE_W - 180, middle, 205, Align.CENTER);
        g.setColor(Theme.MUTED);
        text(g, infoLine(), false, 28, 20, IMAGE_W - 180, middle, 262, Align.CENTER);
        text(g, when() + (roomName.isEmpty() ? "" : "　" + roomName), false, 28, 20, IMAGE_W - 180, middle, 304,
                Align.CENTER);

        paintShareTable(g, ranking, ranks);
        g.setColor(Theme.MUTED);
        text(g, "胡牌次數包含自摸", false, 26, 20, IMAGE_W - 180, middle, shareRowTop(SEATS) + 44, Align.CENTER);
        text(g, footer(), false, 26, 18, IMAGE_W - 180, middle, IMAGE_H - 82, Align.CENTER);
        g.dispose();
        return image;
    }

    /** 結果圖上第 index 列（0 是第一名）的上緣；index 是 4 時是表格的下緣。 */
    public static double shareRowTop(int index) {
        return SHARE_TABLE_TOP + SHARE_HEAD_H + index * SHARE_ROW_H;
    }

    private void paintShareTable(Graphics2D g, Player[] ranking, int[] ranks) {
        double width = sum(SHARE_COLUMNS);
        g.setColor(SHARE_PANEL);
        g.fill(new RoundRectangle2D.Double(SHARE_TABLE_X, SHARE_TABLE_TOP, width, shareRowTop(SEATS) - SHARE_TABLE_TOP,
                30, 30));
        String[] heads = {"名次　玩家", "胡牌", "自摸", "放槍", "輸贏", "籌碼"};
        g.setColor(Theme.MUTED);
        for (int i = 0; i < heads.length; i++) {
            cell(g, heads[i], true, 28, 20, SHARE_TABLE_X, SHARE_COLUMNS, i, SHARE_ALIGNS[i],
                    SHARE_TABLE_TOP + SHARE_HEAD_H / 2);
        }
        g.setColor(GOLD_FAINT);
        g.fill(new Rectangle2D.Double(SHARE_TABLE_X, shareRowTop(0) - 1, width, 2));
        for (int i = 0; i < ranking.length; i++) {
            // 自己那一列有金框，上下就不畫分隔線
            if (i + 1 < ranking.length && ranking[i].seat() != 0 && ranking[i + 1].seat() != 0) {
                g.setColor(SHARE_RULE);
                g.fill(new Rectangle2D.Double(SHARE_TABLE_X + 20, shareRowTop(i + 1) - 1, width - 40, 2));
            }
            paintShareRow(g, ranking[i], ranks[i], shareRowTop(i));
        }
    }

    private void paintShareRow(Graphics2D g, Player player, int rank, double top) {
        boolean mine = player.seat() == 0;
        GameRecord record = game.record(player.seat(), "");
        double middle = top + SHARE_ROW_H / 2;
        if (mine) {
            RoundRectangle2D frame = new RoundRectangle2D.Double(SHARE_TABLE_X - MINE_OUTSET, top + 6,
                    sum(SHARE_COLUMNS) + MINE_OUTSET * 2, SHARE_ROW_H - 12, 28, 28);
            g.setColor(MINE_ROW);
            g.fill(frame);
            g.setColor(Theme.GOLD);
            g.setStroke(roundStroke(5));
            g.draw(frame);
        }

        double cx = SHARE_TABLE_X + 12 + 36;
        g.setColor(MEDALS[Math.min(rank, MEDALS.length) - 1]);
        g.fill(new Ellipse2D.Double(cx - 36, middle - 36, 72, 72));
        g.setColor(MEDAL_INK);
        text(g, String.valueOf(rank), true, 42, 42, 64, cx, middle + 2, Align.CENTER);

        double nameX = cx + 36 + 20;
        double nameMax = SHARE_TABLE_X + SHARE_COLUMNS[0] - 12 - nameX;
        g.setColor(mine ? Theme.GOLD : Theme.TEXT);
        text(g, player.name(), true, 44, 26, nameMax, nameX, middle, Align.LEFT);

        g.setColor(Theme.TEXT);
        int[] counts = {record.wins(), record.selfDraws(), record.dealIns()};
        for (int i = 0; i < counts.length; i++) {
            cell(g, String.valueOf(counts[i]), mine, 44, SHARE_MIN_NUMBER, SHARE_TABLE_X, SHARE_COLUMNS, 1 + i,
                    Align.CENTER, middle);
        }
        g.setColor(ResultDialog.moneyColor(record.winnings()));
        cell(g, signed(record.winnings()), true, 42, SHARE_MIN_NUMBER, SHARE_TABLE_X, SHARE_COLUMNS, 4, Align.RIGHT,
                middle);
        g.setColor(Theme.TEXT);
        cell(g, grouped(player.chips()), mine, 36, SHARE_MIN_NUMBER, SHARE_TABLE_X, SHARE_COLUMNS, 5, Align.RIGHT,
                middle);
    }

    // ---- 輸贏歷程 PDF ----

    /** 整份 PDF。每頁先畫在同一張圖上，轉成 JPEG 再換下一頁。 */
    public byte[] pdf() {
        List<RoundRecord> history = game.history();
        int firstRows = rowsFrom(FIRST_TABLE_TOP);
        int nextRows = rowsFrom(NEXT_TABLE_TOP);
        // 最後一列是合計
        int rows = history.size() + 1;
        int pageCount = rows <= firstRows ? 1 : 1 + (rows - firstRows + nextRows - 1) / nextRows;

        BufferedImage image = new BufferedImage(PAGE_W, PAGE_H, BufferedImage.TYPE_INT_RGB);
        PdfWriter pdf = new PdfWriter(PDF_W, PDF_H);
        int[] totals = new int[SEATS];
        int next = 0;
        for (int page = 0; page < pageCount; page++) {
            Graphics2D g = graphics(image);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, PAGE_W, PAGE_H);
            paintPageFrame(g, page, pageCount);
            double y;
            if (page == 0) {
                paintRankTable(g);
                section(g, "每局輸贏", FIRST_TABLE_TOP);
                y = FIRST_TABLE_TOP;
            } else {
                section(g, "每局輸贏（續）", NEXT_TABLE_TOP);
                y = NEXT_TABLE_TOP;
            }
            y = paintRoundHeader(g, y);
            int capacity = page == 0 ? firstRows : nextRows;
            for (int row = 0; row < capacity && next <= history.size(); row++, next++) {
                if (next == history.size()) {
                    paintTotals(g, y, totals);
                } else {
                    RoundRecord record = history.get(next);
                    for (int seat = 0; seat < SEATS; seat++) {
                        totals[seat] += record.deltas()[seat];
                    }
                    paintRound(g, y, record, totals[0], row % 2 == 1);
                }
                y += ROW_H;
            }
            g.dispose();
            pdf.addPage(jpeg(image), PAGE_W, PAGE_H);
        }
        return pdf.toBytes("EZMJ 輸贏歷程 " + when(),
                "D:" + finished.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
    }

    /** 表頭下面放得下幾列。 */
    private static int rowsFrom(double tableTop) {
        return (int) ((BOTTOM - tableTop - HEAD_H) / ROW_H);
    }

    /** 每頁都有的標題、時間與頁碼。 */
    private void paintPageFrame(Graphics2D g, int page, int pageCount) {
        g.setColor(INK);
        text(g, "EZMJ 台灣十六張麻將　輸贏歷程", true, 36, 36, 1100, MARGIN, 86, Align.LEFT);
        g.setColor(GRAY);
        text(g, "牌局結束 " + when(), false, 20, 20, 400, PAGE_W - MARGIN, 90, Align.RIGHT);
        g.setColor(INK);
        g.fill(new Rectangle2D.Double(MARGIN, 114, PAGE_W - MARGIN * 2, 2));
        g.setColor(GRAY);
        text(g, "第 " + (page + 1) + " 頁，共 " + pageCount + " 頁", false, 18, 18, 300, PAGE_W / 2.0, PAGE_H - 40,
                Align.CENTER);
        text(g, footer(), false, 18, 14, 500, PAGE_W - MARGIN, PAGE_H - 40, Align.RIGHT);
    }

    /** 表格上面的小標題，tableTop 是表格的上緣。 */
    private static void section(Graphics2D g, String title, double tableTop) {
        g.setColor(INK);
        text(g, title, true, 24, 18, PAGE_W - MARGIN * 2, MARGIN, tableTop - 22, Align.LEFT);
    }

    /** 第一頁上面：牌局的設定與最終排名。 */
    private void paintRankTable(Graphics2D g) {
        g.setColor(GRAY);
        text(g, infoLine() + (roomName.isEmpty() ? "" : "　房間：" + roomName), false, 22, 16, PAGE_W - MARGIN * 2,
                MARGIN, 150, Align.LEFT);
        section(g, "最終排名　" + headline, RANK_TOP);

        double y = RANK_TOP;
        String[] heads = {"名次", "玩家", "胡牌", "自摸", "放槍", "輸贏", "籌碼"};
        Align[] aligns = {Align.CENTER, Align.LEFT, Align.CENTER, Align.CENTER, Align.CENTER, Align.RIGHT, Align.RIGHT};
        g.setColor(HEAD);
        g.fill(new Rectangle2D.Double(MARGIN, y, sum(RANK_COLUMNS), HEAD_H));
        g.setColor(INK);
        cells(g, heads, aligns, RANK_COLUMNS, y + HEAD_H / 2, true, 20);
        y += HEAD_H;

        Player[] ranking = FinalDialog.ranking(game);
        int[] ranks = FinalDialog.ranks(ranking);
        for (int i = 0; i < ranking.length; i++) {
            Player player = ranking[i];
            GameRecord record = game.record(player.seat(), "");
            if (player.seat() == 0) {
                g.setColor(MINE);
                g.fill(new Rectangle2D.Double(MARGIN, y, sum(RANK_COLUMNS), RANK_ROW_H));
            }
            g.setColor(RULE);
            g.fill(new Rectangle2D.Double(MARGIN, y + RANK_ROW_H - 1, sum(RANK_COLUMNS), 1));
            // 輸贏那一欄另外上色，先空著
            String[] values = {String.valueOf(ranks[i]), player.name() + (player.seat() == 0 ? "（我）" : ""),
                String.valueOf(record.wins()), String.valueOf(record.selfDraws()), String.valueOf(record.dealIns()),
                "", grouped(player.chips())};
            g.setColor(INK);
            cells(g, values, aligns, RANK_COLUMNS, y + RANK_ROW_H / 2, player.seat() == 0, 22);
            g.setColor(printMoneyColor(record.winnings()));
            cell(g, signed(record.winnings()), true, 22, RANK_COLUMNS, 5, Align.RIGHT, y + RANK_ROW_H / 2);
            y += RANK_ROW_H;
        }
    }

    private double paintRoundHeader(Graphics2D g, double y) {
        List<String> heads = new ArrayList<>(List.of("局", "圈風・莊家", "結果與牌型", "台"));
        for (int seat = 0; seat < SEATS; seat++) {
            heads.add(game.player(seat).name());
        }
        heads.add("我的累計");
        g.setColor(HEAD);
        g.fill(new Rectangle2D.Double(MARGIN, y, sum(ROUND_COLUMNS), HEAD_H));
        g.setColor(INK);
        cells(g, heads.toArray(new String[0]), roundAligns(true), ROUND_COLUMNS, y + HEAD_H / 2, true, 20);
        return y + HEAD_H;
    }

    private static Align[] roundAligns(boolean header) {
        Align money = header ? Align.CENTER : Align.RIGHT;
        return new Align[] {Align.CENTER, Align.LEFT, Align.LEFT, Align.CENTER, money, money, money, money, money};
    }

    private void paintRound(Graphics2D g, double y, RoundRecord record, int running, boolean stripe) {
        double width = sum(ROUND_COLUMNS);
        if (stripe) {
            g.setColor(STRIPE);
            g.fill(new Rectangle2D.Double(MARGIN, y, width, ROW_H));
        }
        g.setColor(RULE);
        g.fill(new Rectangle2D.Double(MARGIN, y + ROW_H - 1, width, 1));
        RoundResult result = record.result();
        double middle = y + ROW_H / 2;
        Align[] aligns = roundAligns(false);
        g.setColor(INK);
        cell(g, String.valueOf(record.number()), false, 22, ROUND_COLUMNS, 0, aligns[0], middle);
        cell(g, HistoryDialog.windText(game, record), false, 22, ROUND_COLUMNS, 1, aligns[1], middle);
        cell(g, result.isDrawnGame() ? "—" : String.valueOf(result.score().totalTai()), false, 22, ROUND_COLUMNS, 3,
                aligns[3], middle);
        String score = HistoryDialog.scoreText(result);
        if (score == null) {
            cell(g, HistoryDialog.resultText(game, result), false, 22, ROUND_COLUMNS, 2, aligns[2], middle);
        } else {
            cell(g, HistoryDialog.resultText(game, result), false, 22, ROUND_COLUMNS, 2, aligns[2], y + 22);
            g.setColor(GRAY);
            cell(g, score, false, 17, ROUND_COLUMNS, 2, aligns[2], y + 46);
        }
        for (int seat = 0; seat < SEATS; seat++) {
            int delta = record.deltas()[seat];
            g.setColor(printMoneyColor(delta));
            cell(g, signed(delta), seat == 0, 22, ROUND_COLUMNS, 4 + seat, aligns[4 + seat], middle);
        }
        g.setColor(printMoneyColor(running));
        cell(g, signed(running), false, 22, ROUND_COLUMNS, 8, aligns[8], middle);
    }

    private void paintTotals(Graphics2D g, double y, int[] totals) {
        g.setColor(HEAD);
        g.fill(new Rectangle2D.Double(MARGIN, y, sum(ROUND_COLUMNS), ROW_H));
        double middle = y + ROW_H / 2;
        g.setColor(INK);
        cell(g, "合計", true, 22, ROUND_COLUMNS, 1, Align.LEFT, middle);
        for (int seat = 0; seat < SEATS; seat++) {
            g.setColor(printMoneyColor(totals[seat]));
            cell(g, signed(totals[seat]), true, 22, ROUND_COLUMNS, 4 + seat, Align.RIGHT, middle);
        }
        g.setColor(printMoneyColor(totals[0]));
        cell(g, signed(totals[0]), true, 22, ROUND_COLUMNS, 8, Align.RIGHT, middle);
    }

    private static Color printMoneyColor(int delta) {
        return delta == 0 ? GRAY : delta > 0 ? WIN : LOSS;
    }

    /** 一整列，顏色用目前的。 */
    private static void cells(Graphics2D g, String[] values, Align[] aligns, double[] columns, double middle,
                              boolean bold, double size) {
        for (int i = 0; i < values.length; i++) {
            cell(g, values[i], bold, size, columns, i, aligns[i], middle);
        }
    }

    /** PDF 表格第 column 欄的一格。 */
    private static void cell(Graphics2D g, String value, boolean bold, double size, double[] columns, int column,
                             Align align, double middle) {
        cell(g, value, bold, size, size * .75, MARGIN, columns, column, align, middle);
    }

    /** 從 tableLeft 開始的表格第 column 欄的一格，左右各留 12 像素。放不下就縮小字，縮到 minSize 還放不下就截掉。 */
    private static void cell(Graphics2D g, String value, boolean bold, double size, double minSize, double tableLeft,
                             double[] columns, int column, Align align, double middle) {
        double left = tableLeft;
        for (int i = 0; i < column; i++) {
            left += columns[i];
        }
        double pad = 12;
        double width = columns[column] - pad * 2;
        double x = align == Align.LEFT ? left + pad : align == Align.RIGHT ? left + columns[column] - pad
                : left + columns[column] / 2;
        text(g, value, bold, size, minSize, width, x, middle, align);
    }

    private static double sum(double[] columns) {
        double total = 0;
        for (double column : columns) {
            total += column;
        }
        return total;
    }

    // ---- 共用 ----

    /** baseline 的 RGB JPEG，PdfWriter 直接貼進頁面。 */
    private static byte[] jpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** 千分位，例如 1,234,567。 */
    static String grouped(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** 贏的前面加 +，例如 +12,340。 */
    static String signed(int value) {
        return (value > 0 ? "+" : "") + grouped(value);
    }
}
