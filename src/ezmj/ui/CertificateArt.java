package ezmj.ui;

import static ezmj.ui.ImageKit.graphics;
import static ezmj.ui.ImageKit.text;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import javax.imageio.ImageIO;

import ezmj.AppVersion;
import ezmj.career.Dan;
import ezmj.career.DanCertificate;
import ezmj.model.Suit;
import ezmj.model.Tile;
import ezmj.ui.ImageKit.Align;

/**
 * 段位證書與個人圖像。證書與大部分預設圖像由程式畫，只有兩張人像插畫是 avatars 資料夾裡的圖檔。
 * 證書是 A4 橫放的比例：米色的紙、紅框金線，左邊是圓形的個人圖像與段位緞帶，右邊是名字、段位與認定的成績，
 * 右下角蓋紅色的「雀藝認證」印。
 */
public final class CertificateArt {

    public static final int WIDTH = 1600;
    public static final int HEIGHT = 1131;
    /** 上傳的個人圖像裁成這麼大的正方形存起來。 */
    public static final int AVATAR_SIZE = 512;

    private static final Color PAPER = new Color(0xFBF6E8);
    private static final Color PAPER_EDGE = new Color(0xEEE0BE);
    private static final Color FRAME = new Color(0x8E1F1F);
    private static final Color GOLD = new Color(0xB0802A);
    private static final Color GOLD_LIGHT = new Color(0xD8B35A);
    private static final Color INK = new Color(0x2A211A);
    private static final Color MUTED_INK = new Color(0x7A6A55);
    private static final Color CRIMSON = new Color(0xB3261E);
    private static final Color SEAL = new Color(0xC23A2C);
    private static final Color STAT_FILL = new Color(0xF3E7CB);
    private static final Color SHADOW = new Color(0, 0, 0, 45);
    private static final Color AVATAR_INK = new Color(0xFBF1D6);
    private static final Color RING = new Color(255, 255, 255, 24);
    private static final double COLUMN_LEFT = 610;
    private static final double COLUMN_RIGHT = WIDTH - 140;

    /**
     * 預設的個人圖像。名稱存在證照裡，不要改；順序就是選圖時的順序，第一張是預設。
     * 有 artwork 的是 avatars 資料夾裡裁好的 512 × 512 插畫，其他的由程式畫：名字的第一個字或一張牌。
     */
    public enum Preset {
        INITIAL("名字", null, null, new Color(0xA33A2C)),
        LADY("自摸佳人", null, "lady.jpg", new Color(0x8C5A3C)),
        GENT("型男雀士", null, "gent.jpg", new Color(0x2C4A4A)),
        BIRD("一條", Tile.of(Suit.TIAO, 1), null, new Color(0x2E7D5B)),
        DOT("一筒", Tile.of(Suit.TONG, 1), null, new Color(0x2F5A8C)),
        RED("紅中", Tile.of(Suit.DRAGON, 1), null, new Color(0xB9862A)),
        GREEN("發財", Tile.of(Suit.DRAGON, 2), null, new Color(0x1F6F6B)),
        EAST("東風", Tile.of(Suit.WIND, 1), null, new Color(0x4D3F86)),
        PLUM("梅", Tile.of(Suit.FLOWER, 5), null, new Color(0xB04F78)),
        BAMBOO("竹", Tile.of(Suit.FLOWER, 7), null, new Color(0x587B2B));

        private final String label;
        private final Tile tile;
        private final String artwork;
        private final Color color;

        Preset(String label, Tile tile, String artwork, Color color) {
            this.label = label;
            this.tile = tile;
            this.artwork = artwork;
            this.color = color;
        }

        public String label() {
            return label;
        }

        /** avatars 資料夾裡的插畫檔名；程式畫的是 null。 */
        public String artwork() {
            return artwork;
        }
    }

    /** 讀過的插畫，每張只讀一次。 */
    private static final Map<Preset, BufferedImage> ARTWORK = new EnumMap<>(Preset.class);

    private CertificateArt() {
    }

    /** 證照裡記的圖像名稱；認不得或空的是第一張。 */
    public static Preset preset(String key) {
        for (Preset preset : Preset.values()) {
            if (preset.name().equals(key)) {
                return preset;
            }
        }
        return Preset.values()[0];
    }

    /** 證照上的個人圖像，正方形：選了上傳的圖而且讀得出來就用它，不然照選的預設圖。 */
    public static BufferedImage avatar(String key, String name, Path customFile, int size) {
        if (DanCertificate.CUSTOM_AVATAR.equals(key)) {
            BufferedImage custom = readCustom(customFile);
            if (custom != null) {
                return ImageKit.smooth(custom, size, size);
            }
        }
        return drawPreset(preset(key), name, size);
    }

    /** 上傳的圖；沒有或讀不出來是 null。 */
    static BufferedImage readCustom(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            return image == null ? null : squareCrop(image);
        } catch (IOException | RuntimeException e) {
            ErrorReporter.note("讀取個人圖像", e);
            return null;
        }
    }

    /** 上傳的圖從中間裁成正方形，縮放成 AVATAR_SIZE。 */
    public static BufferedImage squareCrop(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        g.drawImage(source, -(source.getWidth() - side) / 2, -(source.getHeight() - side) / 2, null);
        g.dispose();
        return ImageKit.smooth(square, AVATAR_SIZE, AVATAR_SIZE);
    }

    /** 名字的第一個字；沒有名字是「雀」。 */
    static String initial(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "雀";
        }
        String first = new String(Character.toChars(trimmed.codePointAt(0)));
        return first.toUpperCase(Locale.ROOT);
    }

    /** 預設圖像：插畫就縮放到這個大小；其他是有底色的方塊，中間是名字的第一個字或一張微微斜放的牌。 */
    public static BufferedImage drawPreset(Preset preset, String name, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        if (preset.artwork != null) {
            BufferedImage artwork = artwork(preset);
            if (artwork != null) {
                g.drawImage(ImageKit.smooth(artwork, size, size), 0, 0, null);
                g.dispose();
                return image;
            }
            // 插畫讀不出來就畫名字，不讓證書少一張圖
            g.dispose();
            return drawPreset(Preset.INITIAL, name, size);
        }
        double s = size;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(s * .35, s * .3), (float) (s * .95),
                new float[] {0f, 1f}, new Color[] {mix(preset.color, Color.WHITE, .22), mix(preset.color, Color.BLACK, .38)}));
        g.fill(new Rectangle2D.Double(0, 0, s, s));
        g.setColor(RING);
        g.setStroke(new BasicStroke((float) (s * .012)));
        for (double r = s * .2; r < s * .75; r += s * .09) {
            g.draw(new Ellipse2D.Double(s / 2 - r, s / 2 - r, r * 2, r * 2));
        }
        if (preset.tile == null) {
            g.setColor(AVATAR_INK);
            text(g, initial(name), Theme.brushFont((float) (s * .56)), s * .3, s * .8, s / 2, s / 2 + s * .02, Align.CENTER);
        } else {
            double h = s * .62;
            double w = h * .76;
            g.setColor(SHADOW);
            g.fill(new Ellipse2D.Double(s / 2 - w * .7, s / 2 + h * .36, w * 1.4, h * .2));
            AffineTransform saved = g.getTransform();
            g.translate(s / 2, s / 2 - s * .02);
            g.rotate(Math.toRadians(-8));
            TilePainter.paintFace(g, preset.tile, -w / 2, -h / 2, w, h);
            g.setTransform(saved);
        }
        g.dispose();
        return image;
    }

    /** 程式裡附的插畫；讀不出來是 null，原因記在 error.log。 */
    private static synchronized BufferedImage artwork(Preset preset) {
        BufferedImage cached = ARTWORK.get(preset);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = CertificateArt.class.getResourceAsStream("avatars/" + preset.artwork)) {
            BufferedImage image = in == null ? null : ImageIO.read(in);
            if (image == null) {
                throw new IOException("找不到或看不懂 avatars/" + preset.artwork);
            }
            ARTWORK.put(preset, image);
            return image;
        } catch (IOException | RuntimeException e) {
            ErrorReporter.note("讀取預設的個人圖像", e);
            return null;
        }
    }

    /** 圓形的圖，邊緣反鋸齒。 */
    static BufferedImage circular(BufferedImage source, int diameter) {
        BufferedImage out = new BufferedImage(diameter, diameter, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(out);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(0, 0, diameter, diameter));
        g.setComposite(AlphaComposite.SrcIn);
        g.drawImage(ImageKit.smooth(source, diameter, diameter), 0, 0, null);
        g.dispose();
        return out;
    }

    /** 例如 EZMJ-7-20261109-3F2A。由段位、日期與成績算出來，同一張證書每次都一樣。 */
    public static String serial(DanCertificate.Grant grant) {
        int hash = Objects.hash(grant.dan().name(), grant.date(), grant.rounds(), grant.wins(), grant.selfDraws());
        return "EZMJ-" + grant.dan().number() + "-" + grant.date().replace("-", "") + "-"
                + String.format(Locale.ROOT, "%04X", hash & 0xFFFF);
    }

    /** 例如 ezmj-段位證書-七段-20261109.png。 */
    public static String fileName(DanCertificate.Grant grant) {
        return "ezmj-段位證書-" + grant.dan().label() + "-" + grant.date().replace("-", "") + ".png";
    }

    /** 2026-11-09 寫成「2026 年 11 月 9 日」；看不懂就照原樣。 */
    static String longDate(String date) {
        String[] parts = date.split("-");
        try {
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) + " 年 " + Integer.parseInt(parts[1]) + " 月 "
                        + Integer.parseInt(parts[2]) + " 日";
            }
        } catch (NumberFormatException e) {
            // 照原樣寫
        }
        return date;
    }

    static String percent(double rate) {
        return String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }

    // ---- 證書 ----

    /** 整張證書。avatar 是正方形的個人圖像，name 是證書上的名字。 */
    public static BufferedImage certificate(DanCertificate.Grant grant, String name, BufferedImage avatar) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image);
        paintPaper(g);
        paintFrame(g);

        double middle = WIDTH / 2.0;
        g.setColor(GOLD);
        text(g, "EZMJ 台灣十六張麻將", false, 30, 22, 900, middle, 150, Align.CENTER);
        g.setColor(INK);
        spaced(g, "麻將段位證書", Theme.brushFont(92f), middle, 232, 22);
        divider(g, middle, 300, 280);

        paintAvatar(g, avatar, 345, 585, 185);
        paintRibbon(g, grant.dan().label(), 345, 838);

        String shownName = name == null || name.isBlank() ? "雀友" : name.trim();
        g.setColor(INK);
        text(g, shownName, Theme.fixedFont(Font.BOLD, 64f), 40, COLUMN_RIGHT - COLUMN_LEFT, COLUMN_LEFT, 392, Align.LEFT);
        g.setColor(GOLD_LIGHT);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Line2D.Double(COLUMN_LEFT, 436, COLUMN_RIGHT, 436));
        g.setColor(INK);
        text(g, "於 EZMJ 台灣十六張麻將對局中成績優異，特此認定為", false, 30, 22, COLUMN_RIGHT - COLUMN_LEFT, COLUMN_LEFT,
                470, Align.LEFT);

        double columnMiddle = (COLUMN_LEFT + COLUMN_RIGHT) / 2;
        g.setColor(CRIMSON);
        double danWidth = spaced(g, grant.dan().label(), Theme.brushFont(170f), columnMiddle, 600, 40);
        flourish(g, columnMiddle, 600, danWidth / 2);

        paintStats(g, grant, 742);

        g.setColor(INK);
        text(g, "認定日期　" + longDate(grant.date()), false, 30, 22, 620, COLUMN_LEFT, 960, Align.LEFT);
        g.setColor(MUTED_INK);
        text(g, "證書編號　" + serial(grant), false, 22, 16, 620, COLUMN_LEFT, 1008, Align.LEFT);
        paintSeal(g, 1350, 976, 148, serial(grant).hashCode());

        g.setColor(MUTED_INK);
        text(g, "EZMJ 電腦版 " + AppVersion.get(), false, 18, 14, 600, middle, HEIGHT - 86, Align.CENTER);
        g.dispose();
        return image;
    }

    private static void paintPaper(Graphics2D g) {
        g.setPaint(new RadialGradientPaint(new Point2D.Double(WIDTH / 2.0, HEIGHT * .45), (float) (WIDTH * .62),
                new float[] {0f, .55f, 1f}, new Color[] {PAPER, PAPER, PAPER_EDGE}));
        g.fill(new Rectangle2D.Double(0, 0, WIDTH, HEIGHT));
    }

    /** 外面一圈紅框、兩道金線，四個角各有一個金色的角花。 */
    private static void paintFrame(Graphics2D g) {
        g.setColor(FRAME);
        g.setStroke(new BasicStroke(18f));
        g.draw(new RoundRectangle2D.Double(30, 30, WIDTH - 60, HEIGHT - 60, 10, 10));
        g.setColor(GOLD_LIGHT);
        g.setStroke(new BasicStroke(3f));
        g.draw(new Rectangle2D.Double(52, 52, WIDTH - 104, HEIGHT - 104));
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new Rectangle2D.Double(62, 62, WIDTH - 124, HEIGHT - 124));
        double inset = 78;
        double arm = 70;
        for (int corner = 0; corner < 4; corner++) {
            double x = corner % 2 == 0 ? inset : WIDTH - inset;
            double y = corner < 2 ? inset : HEIGHT - inset;
            double dx = corner % 2 == 0 ? 1 : -1;
            double dy = corner < 2 ? 1 : -1;
            g.setColor(GOLD);
            g.setStroke(ImageKit.roundStroke(3f));
            g.draw(new Line2D.Double(x, y, x + dx * arm, y));
            g.draw(new Line2D.Double(x, y, x, y + dy * arm));
            diamond(g, x, y, 11, GOLD_LIGHT);
        }
    }

    /** 金色的分隔線，中間一顆菱形；halfWidth 是從中間到一端多遠。 */
    private static void divider(Graphics2D g, double cx, double y, double halfWidth) {
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Line2D.Double(cx - halfWidth, y, cx - 26, y));
        g.draw(new Line2D.Double(cx + 26, y, cx + halfWidth, y));
        diamond(g, cx, y, 9, GOLD_LIGHT);
    }

    /** 段位大字兩邊的金線，外端各一顆菱形；textHalf 是字寬的一半。 */
    private static void flourish(Graphics2D g, double cx, double y, double textHalf) {
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(2f));
        double inner = textHalf + 34;
        double outer = textHalf + 150;
        g.draw(new Line2D.Double(cx - outer, y, cx - inner, y));
        g.draw(new Line2D.Double(cx + inner, y, cx + outer, y));
        diamond(g, cx - outer, y, 7, GOLD_LIGHT);
        diamond(g, cx + outer, y, 7, GOLD_LIGHT);
    }

    private static void diamond(Graphics2D g, double cx, double cy, double r, Color color) {
        Path2D.Double shape = new Path2D.Double();
        shape.moveTo(cx, cy - r);
        shape.lineTo(cx + r, cy);
        shape.lineTo(cx, cy + r);
        shape.lineTo(cx - r, cy);
        shape.closePath();
        g.setColor(color);
        g.fill(shape);
    }

    /** 字距拉開的一行字，cx 置中、cy 垂直置中，回傳整行的寬度。 */
    private static double spaced(Graphics2D g, String text, Font font, double cx, double cy, double spacing) {
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        int[] codePoints = text.codePoints().toArray();
        double total = spacing * (codePoints.length - 1);
        for (int codePoint : codePoints) {
            total += ImageKit.width(g, new String(Character.toChars(codePoint)));
        }
        double x = cx - total / 2;
        float baseline = (float) (cy + (metrics.getAscent() - metrics.getDescent()) / 2.0);
        for (int codePoint : codePoints) {
            String glyph = new String(Character.toChars(codePoint));
            g.drawString(glyph, (float) x, baseline);
            x += ImageKit.width(g, glyph) + spacing;
        }
        return total;
    }

    /** 圓形的個人圖像，外面一圈金環。 */
    private static void paintAvatar(Graphics2D g, BufferedImage avatar, double cx, double cy, double r) {
        double ring = 14;
        g.setColor(SHADOW);
        g.fill(new Ellipse2D.Double(cx - r - ring + 4, cy - r - ring + 9, (r + ring) * 2, (r + ring) * 2));
        g.setColor(GOLD_LIGHT);
        g.fill(new Ellipse2D.Double(cx - r - ring, cy - r - ring, (r + ring) * 2, (r + ring) * 2));
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(cx - r - ring, cy - r - ring, (r + ring) * 2, (r + ring) * 2));
        int diameter = (int) Math.round(r * 2);
        g.drawImage(circular(avatar, diameter), (int) Math.round(cx - r), (int) Math.round(cy - r), null);
        g.setColor(FRAME);
        g.setStroke(new BasicStroke(3f));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    /** 圖像下面的紅色緞帶，寫著段位。 */
    private static void paintRibbon(Graphics2D g, String label, double cx, double cy) {
        double w = 250;
        double h = 66;
        double notch = 22;
        Path2D.Double tail = new Path2D.Double();
        for (int side = -1; side <= 1; side += 2) {
            double outer = cx + side * (w / 2 + 34);
            double inner = cx + side * (w / 2 - 6);
            tail.moveTo(inner, cy - h / 2 + 12);
            tail.lineTo(outer, cy - h / 2 + 12);
            tail.lineTo(outer - side * notch, cy + 6);
            tail.lineTo(outer, cy + h / 2 + 12);
            tail.lineTo(inner, cy + h / 2 + 12);
            tail.closePath();
        }
        g.setColor(mix(FRAME, Color.BLACK, .25));
        g.fill(tail);
        g.setColor(FRAME);
        g.fill(new RoundRectangle2D.Double(cx - w / 2, cy - h / 2, w, h, 10, 10));
        g.setColor(GOLD_LIGHT);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(cx - w / 2 + 6, cy - h / 2 + 6, w - 12, h - 12, 6, 6));
        g.setColor(AVATAR_INK);
        spaced(g, label, Theme.brushFont(40f), cx, cy, 14);
    }

    /** 認定的成績：一排五格，上面寫這一段看的是哪些局。 */
    private static void paintStats(Graphics2D g, DanCertificate.Grant grant, double top) {
        Dan dan = grant.dan();
        g.setColor(MUTED_INK);
        text(g, "認定成績　" + dan.opponentsText() + "的局", false, 24, 18, COLUMN_RIGHT - COLUMN_LEFT, COLUMN_LEFT,
                top - 30, Align.LEFT);
        double height = 132;
        double width = COLUMN_RIGHT - COLUMN_LEFT;
        g.setColor(STAT_FILL);
        g.fill(new RoundRectangle2D.Double(COLUMN_LEFT, top, width, height, 18, 18));
        g.setColor(GOLD_LIGHT);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(COLUMN_LEFT, top, width, height, 18, 18));
        String[] labels = {"局數", "胡牌數", "自摸數", "胡牌率", "自摸率"};
        String[] values = {GameReport.grouped(grant.rounds()), GameReport.grouped(grant.wins()),
            GameReport.grouped(grant.selfDraws()), percent(grant.winRate()), percent(grant.selfDrawRate())};
        double cell = width / labels.length;
        for (int i = 0; i < labels.length; i++) {
            double cx = COLUMN_LEFT + cell * (i + .5);
            if (i > 0) {
                g.setColor(GOLD_LIGHT);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new Line2D.Double(COLUMN_LEFT + cell * i, top + 22, COLUMN_LEFT + cell * i, top + height - 22));
            }
            g.setColor(MUTED_INK);
            text(g, labels[i], false, 24, 18, cell - 20, cx, top + 40, Align.CENTER);
            g.setColor(INK);
            text(g, values[i], true, 44, 26, cell - 20, cx, top + 92, Align.CENTER);
        }
    }

    /** 紅色方印，四個字由右而左、由上而下讀作「雀藝認證」；印泥不均的小白點每張證書固定。 */
    private static void paintSeal(Graphics2D g, double cx, double cy, double size, long seed) {
        AffineTransform saved = g.getTransform();
        g.translate(cx, cy);
        g.rotate(Math.toRadians(-6));
        double half = size / 2;
        g.setColor(SEAL);
        g.fill(new RoundRectangle2D.Double(-half, -half, size, size, size * .1, size * .1));
        g.setColor(PAPER);
        g.setStroke(new BasicStroke((float) (size * .03)));
        double inset = size * .07;
        g.draw(new RoundRectangle2D.Double(-half + inset, -half + inset, size - inset * 2, size - inset * 2,
                size * .06, size * .06));
        Font font = Theme.brushFont((float) (size * .34));
        double column = size * .2;
        double row = size * .2;
        text(g, "雀", font, size * .2, size * .4, column, -row, Align.CENTER);
        text(g, "藝", font, size * .2, size * .4, column, row, Align.CENTER);
        text(g, "認", font, size * .2, size * .4, -column, -row, Align.CENTER);
        text(g, "證", font, size * .2, size * .4, -column, row, Align.CENTER);
        Random random = new Random(seed);
        for (int i = 0; i < 90; i++) {
            double r = size * (.003 + random.nextDouble() * .01);
            double x = (random.nextDouble() - .5) * size * .96;
            double y = (random.nextDouble() - .5) * size * .96;
            g.setColor(new Color(PAPER.getRed(), PAPER.getGreen(), PAPER.getBlue(), 70 + random.nextInt(110)));
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
        g.setTransform(saved);
    }

    /** 把 base 往 toward 調 amount（0 到 1）。 */
    private static Color mix(Color base, Color toward, double amount) {
        return new Color((int) Math.round(base.getRed() + (toward.getRed() - base.getRed()) * amount),
                (int) Math.round(base.getGreen() + (toward.getGreen() - base.getGreen()) * amount),
                (int) Math.round(base.getBlue() + (toward.getBlue() - base.getBlue()) * amount));
    }
}
