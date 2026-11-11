package ezmj;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ezmj.bot.ComputerBot;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.report.PdfWriter;
import ezmj.ui.GameReport;

/** 牌局結束的結果圖與輸贏歷程 PDF：大小、頁數，以及 PDF 的目錄指得到每個物件。 */
final class ReportTest {

    /** 和 GameReport 的版面一樣：第一頁放得下 9 列、之後每頁 14 列，最後一列是合計。 */
    private static final int FIRST_PAGE_ROWS = 9;
    private static final int NEXT_PAGE_ROWS = 14;
    private static final LocalDateTime FINISHED = LocalDateTime.of(2026, 11, 9, 21, 30);

    private ReportTest() {
    }

    static void run() {
        T.section("結果圖與輸贏 PDF");
        T.test("PDF 的目錄指得到每個物件、標題是中文", ReportTest::pdfStructure);
        T.test("結果圖是 1080 × 1350，自己那一列有金框", ReportTest::shareImage);
        T.test("一頁放得下就一頁，放不下接到下一頁", ReportTest::pageCount);
        T.test("檔名用牌局結束的時間", ReportTest::fileNames);
    }

    private static void pdfStructure() {
        PdfWriter writer = new PdfWriter(842, 595);
        byte[] fakeJpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        writer.addPage(fakeJpeg, 10, 20);
        writer.addPage(fakeJpeg, 10, 20);
        byte[] pdf = writer.toBytes("EZMJ 輸贏歷程", "D:20261109213000");
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        checkXref(text, 2);
        T.check(text.contains("/Title <FEFF0045005A004D004A00208F388D0F6B777A0B>"), "標題用 UTF-16BE");
    }

    private static void shareImage() {
        GameSession game = playedGame(1);
        BufferedImage image = new GameReport(game, null, "", FINISHED).shareImage();
        T.eq(GameReport.IMAGE_W, image.getWidth(), "寬");
        T.eq(GameReport.IMAGE_H, image.getHeight(), "高");

        // 照籌碼排名，籌碼一樣時座位小的在前面，所以排在自己（座位 0）前面的是籌碼比較多的
        int mine = 0;
        for (int seat = 1; seat < 4; seat++) {
            if (game.player(seat).chips() > game.player(0).chips()) {
                mine++;
            }
        }
        int x = (int) (GameReport.SHARE_TABLE_X - GameReport.MINE_OUTSET);
        for (int row = 0; row < 4; row++) {
            int y = (int) (GameReport.shareRowTop(row) + GameReport.SHARE_ROW_H / 2);
            T.eq(row == mine, isGold(image.getRGB(x, y)),
                    "第 " + (row + 1) + " 列" + (row == mine ? "是自己，要有金框" : "不是自己，不該有金框"));
        }
    }

    /** 和牌桌的金色 #E8C05A 差不多。 */
    private static boolean isGold(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return Math.abs(r - 0xE8) < 16 && Math.abs(g - 0xC0) < 16 && Math.abs(b - 0x5A) < 16;
    }

    private static void pageCount() {
        GameSession few = playedGame(3);
        T.check(few.history().size() + 1 <= FIRST_PAGE_ROWS, "三局加合計放得進第一頁");
        checkPdf(few, 1);

        GameSession whole = playedGame(Integer.MAX_VALUE);
        int rows = whole.history().size() + 1;
        T.check(rows > FIRST_PAGE_ROWS, "打完一整場要第二頁");
        checkPdf(whole, 1 + (rows - FIRST_PAGE_ROWS + NEXT_PAGE_ROWS - 1) / NEXT_PAGE_ROWS);
    }

    private static void fileNames() {
        GameReport report = new GameReport(playedGame(1), "挑戰成功：初試啼聲", "週五牌咖", FINISHED);
        T.eq("ezmj-結果-20261109-2130.png", report.imageFileName(), "圖檔名");
        T.eq("ezmj-輸贏歷程-20261109-2130.pdf", report.pdfFileName(), "PDF 檔名");
        T.eq("2026/11/09 21:30", report.when(), "時間");
    }

    private static void checkPdf(GameSession game, int pages) {
        String text = new String(new GameReport(game, null, "房間", FINISHED).pdf(), StandardCharsets.ISO_8859_1);
        checkXref(text, pages);
        T.check(text.contains("/Width " + GameReport.PAGE_W + " /Height " + GameReport.PAGE_H), "每頁是 A4 150 dpi 的圖");
        T.check(text.contains("/CreationDate (D:20261109213000)"), "建立時間");
        int jpegs = 0;
        for (int at = text.indexOf("stream\n"); at >= 0; at = text.indexOf("stream\n", at + 1)) {
            if (text.startsWith("ÿØ", at + "stream\n".length())) {
                jpegs++;
            }
        }
        T.eq(pages, jpegs, "每頁一張 JPEG");
    }

    /** startxref 指到 xref，xref 的每一筆都指到「n 0 obj」，頁數和 /Count 對得上。 */
    private static void checkXref(String text, int pages) {
        T.check(text.startsWith("%PDF-1.4\n"), "檔頭");
        T.check(text.endsWith("%%EOF\n"), "檔尾");
        Matcher start = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(text);
        T.check(start.find(), "有 startxref");
        int xref = Integer.parseInt(start.group(1));
        T.check(text.startsWith("xref\n0 ", xref), "startxref 指到 xref");
        Matcher entries = Pattern.compile("(\\d{10}) 00000 n \n").matcher(text.substring(xref));
        int objects = 0;
        while (entries.find()) {
            objects++;
            int offset = Integer.parseInt(entries.group(1));
            T.check(text.startsWith(objects + " 0 obj\n", offset), "第 " + objects + " 個物件的位置");
        }
        T.eq(3 + pages * 3, objects, "物件數");
        T.check(text.contains("/Count " + pages + " >>"), "頁數 " + pages);
    }

    /** 電腦打四家，打 rounds 局或整場打完為止。 */
    private static GameSession playedGame(int rounds) {
        return playedGame(new GameConfig("測試", 20_000, 100, 20, BotLevel.NORMAL), rounds);
    }

    /** 照 config 開一場，四家都由一般電腦打，打 rounds 局或整場打完為止。 */
    static GameSession playedGame(GameConfig config, int rounds) {
        Random random = new Random(7);
        GameSession session = new GameSession(config, random);
        ComputerBot[] bots = new ComputerBot[4];
        for (int i = 0; i < 4; i++) {
            bots[i] = new ComputerBot(BotLevel.NORMAL, config.basePay(), config.taiPay(), new Random(11 + i));
        }
        for (int played = 0; played < rounds && !session.isOver(); played++) {
            Round round = session.newRound(message -> { });
            round.start();
            while (round.phase() != Round.Phase.FINISHED) {
                if (round.phase() == Round.Phase.TURN) {
                    int seat = round.current();
                    bots[seat].playTurn(round, seat, round.turnOptions(seat));
                } else {
                    Map<Integer, Claim> decisions = new HashMap<>();
                    for (int seat = 0; seat < 4; seat++) {
                        List<Claim> options = round.claimOptions(seat);
                        if (!options.isEmpty()) {
                            Claim claim = bots[seat].chooseClaim(round, seat, options);
                            if (claim != null) {
                                decisions.put(seat, claim);
                            }
                        }
                    }
                    round.resolveClaims(decisions);
                }
            }
            session.settle(round.result());
        }
        return session;
    }
}
