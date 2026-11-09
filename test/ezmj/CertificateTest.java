package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import ezmj.career.CareerProfile;
import ezmj.career.CareerSlot;
import ezmj.career.Dan;
import ezmj.career.DanCertificate;
import ezmj.career.Records;
import ezmj.engine.PlayerStats;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.SealedStore;
import ezmj.ui.CertificateArt;

/** 段位證照：九段的門檻、三家最弱等級的統計、只升不降、存讀，以及證書與個人圖像畫得出來。 */
final class CertificateTest {

    private CertificateTest() {
    }

    static void run() {
        T.section("段位證照");
        T.test("九段由低到高，對手越來越強、門檻越來越高", CertificateTest::table);
        T.test("統計依三家最弱的等級各記一份", CertificateTest::weakest);
        T.test("舊的統計沒有這一份，從 0 開始", CertificateTest::oldStats);
        T.test("門檻剛好到就算，差一點就不算", CertificateTest::thresholds);
        T.test("段位看那一段的局，可以跳段", CertificateTest::pools);
        T.test("只升不降，證書上是升段那天的成績", CertificateTest::onlyUp);
        T.test("證書上的名字", CertificateTest::names);
        T.test("證照存讀與讀不懂的項目", CertificateTest::saveLoad);
        T.test("紀錄裡的證照封存，刪除修業檔案時一起刪", CertificateTest::records);
        T.test("證書與預設圖像畫得出來、插畫有打包，上傳的圖裁成正方形", CertificateTest::art);
    }

    private static void table() {
        Dan[] dans = Dan.values();
        eq(9, dans.length, "九段");
        String[] labels = {"初段", "二段", "三段", "四段", "五段", "六段", "七段", "八段", "九段"};
        for (int i = 0; i < dans.length; i++) {
            eq(labels[i], dans[i].label(), "名稱");
            eq(i + 1, dans[i].number(), "第幾段");
            if (i == 0) {
                continue;
            }
            Dan lower = dans[i - 1];
            int was = lower.opponents() == null ? -1 : lower.opponents().ordinal();
            int now = dans[i].opponents() == null ? -1 : dans[i].opponents().ordinal();
            check(now >= was, dans[i] + " 的對手不比前一段弱");
            if (now == was) {
                check(dans[i].rounds() > lower.rounds(), dans[i] + " 局數比前一段多");
                check(dans[i].winPermille() > lower.winPermille(), dans[i] + " 胡牌率比前一段高");
                check(dans[i].selfDrawPermille() > lower.selfDrawPermille(), dans[i] + " 自摸率比前一段高");
            }
        }
        eq(Dan.FIRST, Dan.after(null), "還沒入段的下一段是初段");
        eq(Dan.SECOND, Dan.after(Dan.FIRST), "初段的下一段");
        check(Dan.after(Dan.NINTH) == null, "九段之後沒有了");
        eq("對手不限", Dan.THIRD.opponentsText(), "初段到三段");
        eq("三家都是業餘以上", Dan.FOURTH.opponentsText(), "四段到六段");
        eq("三家都是職業", Dan.SEVENTH.opponentsText(), "七段到九段");
    }

    private static void weakest() throws IOException {
        PlayerStats stats = new PlayerStats();
        for (List<BotLevel> lineup : List.of(List.of(BotLevel.PRO, BotLevel.PRO, BotLevel.PRO),
                List.of(BotLevel.PRO, BotLevel.AMATEUR, BotLevel.PRO),
                List.of(BotLevel.PRO, BotLevel.BEGINNER, BotLevel.NORMAL))) {
            stats.recordRound(ReportTest.playedGame(new GameConfig("測試", 20_000, 100, 20, lineup), 1));
        }
        eq(3, stats.total().rounds(), "總計");
        eq(1, stats.weakestAtLeast(BotLevel.PRO).rounds(), "三家都是職業");
        eq(2, stats.weakestAtLeast(BotLevel.AMATEUR).rounds(), "三家都是業餘以上");
        eq(2, stats.weakestAtLeast(BotLevel.NORMAL).rounds(), "有初學者的不算一般以上");
        eq(3, stats.weakestAtLeast(BotLevel.BEGINNER).rounds(), "初學者以上就是全部");
        eq(stats.total().wins(), stats.weakestAtLeast(BotLevel.BEGINNER).wins(), "胡牌數加起來是總計");
        eq(3, stats.against(BotLevel.PRO).rounds(), "同桌有職業照舊");

        Path file = Files.createTempDirectory("ezmj-dan").resolve("stats.properties");
        stats.save(file);
        PlayerStats loaded = PlayerStats.load(file);
        eq(1, loaded.weakestAtLeast(BotLevel.PRO).rounds(), "存讀：職業");
        eq(2, loaded.weakestAtLeast(BotLevel.AMATEUR).rounds(), "存讀：業餘以上");
    }

    private static void oldStats() throws IOException {
        PlayerStats stats = stats("total.rounds=500", "total.wins=120", "level.PRO.rounds=500");
        eq(500, stats.total().rounds(), "總計讀得到");
        eq(0, stats.weakestAtLeast(BotLevel.BEGINNER).rounds(), "以前沒記三家最弱的等級");
        eq(Dan.FIRST, Dan.best(stats("total.rounds=50", "total.wins=8", "total.selfDraws=2")), "舊的總計算得到初段");
    }

    private static void thresholds() throws IOException {
        check(Dan.best(stats("total.rounds=49", "total.wins=20", "total.selfDraws=10")) == null, "局數不到");
        eq(Dan.FIRST, Dan.best(stats("total.rounds=50", "total.wins=8", "total.selfDraws=2")), "16% 與 4% 是初段");
        check(Dan.best(stats("total.rounds=60", "total.wins=8", "total.selfDraws=2")) == null, "胡牌率 13.3% 不到初段");
        eq(Dan.THIRD, Dan.best(stats("total.rounds=300", "total.wins=63", "total.selfDraws=15")), "剛好 21% 與 5%");
        eq(Dan.SECOND, Dan.best(stats("total.rounds=300", "total.wins=62", "total.selfDraws=15")), "胡牌率差一點");
        eq(Dan.SECOND, Dan.best(stats("total.rounds=300", "total.wins=63", "total.selfDraws=14")), "自摸率差一點");
    }

    private static void pools() throws IOException {
        PlayerStats pro = stats("total.rounds=600", "total.wins=138", "total.selfDraws=36",
                "weakest.PRO.rounds=600", "weakest.PRO.wins=138", "weakest.PRO.selfDraws=36");
        eq(Dan.SEVENTH, Dan.best(pro), "只跟職業打，23% 與 6% 直接是七段");
        check(Dan.FIFTH.isMet(pro), "職業的局也算業餘以上");
        check(!Dan.SIXTH.isMet(pro), "六段要 1000 局");

        PlayerStats mixed = stats("total.rounds=2000", "total.wins=600", "total.selfDraws=160",
                "weakest.BEGINNER.rounds=1990", "weakest.BEGINNER.wins=600", "weakest.BEGINNER.selfDraws=160",
                "weakest.PRO.rounds=10");
        eq(Dan.THIRD, Dan.best(mixed), "跟初學者打再多也只到三段");
    }

    private static void onlyUp() throws IOException {
        DanCertificate certificate = new DanCertificate();
        check(certificate.grant() == null, "一開始還沒入段");
        eq(Dan.THIRD, certificate.promote(stats("total.rounds=300", "total.wins=63", "total.selfDraws=15"), "2026-11-01"),
                "升到三段");
        DanCertificate.Grant third = certificate.grant();
        eq("2026-11-01", third.date(), "日期");
        eq(300, third.rounds(), "局數");
        eq(63, third.wins(), "胡牌數");
        eq(15, third.selfDraws(), "自摸數");

        check(certificate.promote(stats("total.rounds=400", "total.wins=60", "total.selfDraws=10"), "2026-11-02") == null,
                "成績掉下來不降段");
        eq(third, certificate.grant(), "還是原本那張");

        PlayerStats pro = stats("total.rounds=900", "total.wins=200", "total.selfDraws=50",
                "weakest.PRO.rounds=600", "weakest.PRO.wins=138", "weakest.PRO.selfDraws=36");
        eq(Dan.SEVENTH, certificate.promote(pro, "2026-11-09"), "升到七段");
        DanCertificate.Grant seventh = certificate.grant();
        eq(600, seventh.rounds(), "七段的證書寫的是三家都是職業的局數");
        eq(138, seventh.wins(), "職業局的胡牌數");
        eq("2026-11-09", seventh.date(), "新的日期");
        check(certificate.promote(pro, "2026-11-10") == null, "同一段不再發");
    }

    private static void names() {
        DanCertificate certificate = new DanCertificate();
        eq("", certificate.name(), "一開始沒有名字");
        check(certificate.fillName("  王小明 "), "第一次填");
        eq("王小明", certificate.name(), "去掉空白");
        check(!certificate.fillName("別人"), "有名字就不填");
        certificate.rename(" 阿明 ");
        eq("阿明", certificate.name(), "改名字");
        T.fails(IllegalArgumentException.class, () -> certificate.rename("   "), "空白不行");
        T.fails(IllegalArgumentException.class, () -> certificate.rename("一二三四五六七八九"), "超過 8 字不行");
        DanCertificate longName = new DanCertificate();
        longName.fillName("一二三四五六七八九十");
        eq("一二三四五六七八", longName.name(), "太長的名字截到 8 字");
    }

    private static void saveLoad() throws IOException {
        Path dir = Files.createTempDirectory("ezmj-dan");
        DanCertificate certificate = new DanCertificate();
        certificate.fillName("王小明");
        certificate.setAvatar("BIRD");
        certificate.promote(stats("total.rounds=300", "total.wins=63", "total.selfDraws=15"), "2026-11-01");
        Path file = dir.resolve("certificate.properties");
        certificate.save(file);
        DanCertificate loaded = DanCertificate.load(file);
        eq(certificate.grant(), loaded.grant(), "段位與成績");
        eq("王小明", loaded.name(), "名字");
        eq("BIRD", loaded.avatar(), "圖像");

        check(DanCertificate.load(dir.resolve("沒有這個檔.properties")).grant() == null, "沒有檔案就是還沒入段");
        Path odd = dir.resolve("odd.properties");
        Files.writeString(odd, "dan=TENTH\nname=Ming\nmore=1\n", StandardCharsets.ISO_8859_1);
        DanCertificate unknown = DanCertificate.load(odd);
        check(unknown.grant() == null, "認不得的段位當作還沒入段");
        Files.writeString(odd, "dan=SECOND\ndate=2026-11-01\nrounds=abc\nwins=-3\n", StandardCharsets.ISO_8859_1);
        DanCertificate.Grant broken = DanCertificate.load(odd).grant();
        eq(Dan.SECOND, broken.dan(), "段位讀得到");
        eq(0, broken.rounds(), "讀不懂的數字是 0");
        eq(0, broken.wins(), "負的數字是 0");
    }

    private static void records() throws IOException {
        Path home = Files.createTempDirectory("ezmj-home");
        CareerSlot slot = CareerSlot.all(home).get(0);
        slot.create(new CareerProfile("阿明", 20_000, "2026-11-09"));
        Records records = slot.loadRecords();
        records.certificate().fillName("阿明");
        records.saveCertificate();
        Path certificateFile = records.avatarFile().resolveSibling("certificate.properties");
        check(SealedStore.isSealed(certificateFile), "證照是封存的");
        eq("阿明", slot.loadRecords().certificate().name(), "讀得回來");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", records.avatarFile().toFile());

        slot.delete();
        check(!Files.exists(certificateFile), "證照刪了");
        check(!Files.exists(records.avatarFile()), "個人圖像也刪了");
    }

    private static void art() {
        DanCertificate.Grant grant = new DanCertificate.Grant(Dan.SEVENTH, "2026-11-09", 600, 138, 36);
        BufferedImage avatar = CertificateArt.avatar("BIRD", "王小明", null, CertificateArt.AVATAR_SIZE);
        eq(CertificateArt.AVATAR_SIZE, avatar.getWidth(), "圖像寬");
        BufferedImage certificate = CertificateArt.certificate(grant, "王小明", avatar);
        eq(CertificateArt.WIDTH, certificate.getWidth(), "證書寬");
        eq(CertificateArt.HEIGHT, certificate.getHeight(), "證書高");
        eq(CertificateArt.serial(grant), CertificateArt.serial(new DanCertificate.Grant(Dan.SEVENTH, "2026-11-09", 600, 138,
                36)), "同一張證書編號一樣");
        check(CertificateArt.serial(grant).startsWith("EZMJ-7-20261109-"), "編號有段位與日期");
        eq("ezmj-段位證書-七段-20261109.png", CertificateArt.fileName(grant), "檔名");

        BufferedImage initial = CertificateArt.drawPreset(CertificateArt.Preset.INITIAL, "王小明", 64);
        for (CertificateArt.Preset preset : CertificateArt.Preset.values()) {
            BufferedImage image = CertificateArt.drawPreset(preset, "王小明", 64);
            eq(64, image.getWidth(), preset + " 的大小");
            check(image.getRGB(32, 32) != image.getRGB(2, 2), preset + " 中間有畫東西");
            if (preset.artwork() != null) {
                check(CertificateArt.class.getResource("avatars/" + preset.artwork()) != null,
                        preset + " 的插畫有打包進去");
                check(image.getRGB(32, 32) != initial.getRGB(32, 32), preset + " 讀得到插畫，不是退回名字");
            }
        }
        eq(CertificateArt.Preset.INITIAL, CertificateArt.preset("沒有這張"), "認不得的圖像用第一張");
        BufferedImage fallback = CertificateArt.avatar(DanCertificate.CUSTOM_AVATAR, "王小明", Path.of("沒有這張.png"), 32);
        eq(32, fallback.getWidth(), "上傳的圖不見了就用預設的");

        BufferedImage wide = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        BufferedImage square = CertificateArt.squareCrop(wide);
        eq(CertificateArt.AVATAR_SIZE, square.getWidth(), "裁好的寬");
        eq(CertificateArt.AVATAR_SIZE, square.getHeight(), "裁好的高");
    }

    /** 用幾行 Properties 做出一份統計。 */
    private static PlayerStats stats(String... lines) throws IOException {
        Path file = Files.createTempDirectory("ezmj-dan").resolve("stats.properties");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
        return PlayerStats.load(file);
    }
}
