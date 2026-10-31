package ezmj.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

import ezmj.model.Suit;
import ezmj.model.Tile;

/** 以向量繪製麻將牌，不需要圖片素材，任何尺寸都清晰。 */
public final class TilePainter {

    static final Color IVORY = new Color(0xFB, 0xF7, 0xEA);
    private static final Color IVORY_SHADE = new Color(0xE6, 0xDE, 0xC6);
    private static final Color IVORY_SIDE = new Color(0xCF, 0xC5, 0xA6);
    private static final Color EDGE = new Color(0x8A, 0x80, 0x66, 150);
    private static final Color SHADOW = new Color(0, 0, 0, 42);
    private static final Color GLOSS = new Color(255, 255, 255, 190);
    private static final Color DIM = new Color(0, 0, 0, 45);
    private static final double DEPTH = .13;
    private static final int CACHE_LIMIT = 800;
    private static final Color BACK = new Color(0x1F, 0x7A, 0x5C);
    private static final Color BACK_DARK = new Color(0x12, 0x55, 0x3F);
    private static final Color MASK = new Color(0x0B, 0x1A, 0x15, 170);
    static final Color INK = new Color(0x15, 0x1B, 0x2E);
    static final Color CRIMSON = new Color(0xB5, 0x20, 0x2A);
    static final Color JADE = new Color(0x1A, 0x6E, 0x3C);

    /** 牌面上的數字，五萬寫成大寫的「伍」。 */
    private static final String[] WAN_NUMBERS = {"一", "二", "三", "四", "伍", "六", "七", "八", "九"};

    /** 筒與條的點位，座標為牌面內 0-1 的比例。 */
    private static final double[][][] TONG_PIPS = {
        {{.5, .5}},
        {{.5, .25}, {.5, .75}},
        {{.22, .17}, {.5, .5}, {.78, .83}},
        {{.25, .26}, {.75, .26}, {.25, .74}, {.75, .74}},
        {{.22, .2}, {.78, .2}, {.5, .5}, {.22, .8}, {.78, .8}},
        {{.27, .15}, {.73, .15}, {.27, .55}, {.73, .55}, {.27, .85}, {.73, .85}},
        {{.18, .11}, {.5, .21}, {.82, .31}, {.27, .6}, {.73, .6}, {.27, .87}, {.73, .87}},
        {{.29, .12}, {.71, .12}, {.29, .37}, {.71, .37}, {.29, .63}, {.71, .63}, {.29, .88}, {.71, .88}},
        {{.17, .17}, {.5, .17}, {.83, .17}, {.17, .5}, {.5, .5}, {.83, .5}, {.17, .83}, {.5, .83}, {.83, .83}},
    };

    /** 各點數的筒子半徑，以牌面寬度為比例。 */
    private static final double[] TONG_RADIUS = {.47, .29, .22, .22, .2, .2, .17, .165, .155};

    /** 每個點位的顏色：G 綠、N 墨、R 紅。 */
    private static final String[] TONG_INKS = {
        "R", "GN", "GRN", "GNNG", "GNRNG", "GGRRRR", "GGGRRRR", "NNNNNNNN", "NNNRRRGGG",
    };

    /** 一條是鳥、八條是 WM 圖形，其他點數才用這張表。 */
    private static final double[][][] TIAO_PIPS = {
        {},
        {{.5, .25}, {.5, .75}},
        {{.5, .25}, {.25, .75}, {.75, .75}},
        {{.25, .25}, {.75, .25}, {.25, .75}, {.75, .75}},
        {{.2, .25}, {.8, .25}, {.5, .5}, {.2, .75}, {.8, .75}},
        {{.2, .25}, {.5, .25}, {.8, .25}, {.2, .75}, {.5, .75}, {.8, .75}},
        {{.5, .17}, {.2, .5}, {.5, .5}, {.8, .5}, {.2, .83}, {.5, .83}, {.8, .83}},
        {},
        {{.2, .17}, {.5, .17}, {.8, .17}, {.2, .5}, {.5, .5}, {.8, .5}, {.2, .83}, {.5, .83}, {.8, .83}},
    };

    private static final String[] TIAO_INKS = {
        "", "NN", "GNG", "GNNG", "GNRNG", "NNNGGG", "RGNGGNG", "", "GRNGRNGRN",
    };

    /** 八條上半部的 W，座標為牌面內 0-1 的比例；下半部的 M 是它的上下翻轉。 */
    private static final double[][] EIGHT_W = {{.02, .02}, {.1, .42}, {.5, .08}, {.9, .42}, {.98, .02}};

    /** 半徑為 1 的筒子：圓盤挖出五片花瓣。 */
    private static final Shape TONG_PIP = tongPip();

    private TilePainter() {
    }

    /** 牌的正面在目前座標裡的位置，與厚度延伸的方向。 */
    private record Body(double x, double y, double w, double h, double dx, double dy, double arc) {
    }

    /**
     * 牌面內縮一點，留出厚度。厚度與光線都以畫面為準：不管這張牌屬於哪一家、轉了幾度，
     * 都是光從左上照下來、厚度露在右下，整桌看起來才像同一張桌子。
     */
    private static Body body(Graphics2D g, double x, double y, double w, double h) {
        double depth = Math.min(w, h) * DEPTH;
        double[] local = fromScreen(g, .3 * depth, depth);
        double faceW = w - Math.abs(local[0]);
        double faceH = h - Math.abs(local[1]);
        return new Body(x + Math.max(0, -local[0]), y + Math.max(0, -local[1]), faceW, faceH,
                local[0], local[1], faceW * .2);
    }

    /** 把畫面上的方向換算成目前座標裡的方向。 */
    private static double[] fromScreen(Graphics2D g, double screenX, double screenY) {
        AffineTransform t = g.getTransform();
        double angle = Math.atan2(t.getShearY(), t.getScaleX());
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new double[] {cos * screenX + sin * screenY, -sin * screenX + cos * screenY};
    }

    private static RoundRectangle2D shape(Body b, double shift) {
        return new RoundRectangle2D.Double(b.x + b.dx * shift, b.y + b.dy * shift, b.w, b.h, b.arc, b.arc);
    }

    /** 畫好的牌，tile 為 null 表示牌背。尺寸是實際像素，quarter 是轉了幾個 90 度。 */
    private record CacheKey(Tile tile, int w, int h, int quarter) {
    }

    /** 同一種牌只畫一次，之後直接貼圖，整桌重畫才快得起來。只在畫面執行緒使用。 */
    private static final Map<CacheKey, BufferedImage> CACHE = new LinkedHashMap<>(256, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, BufferedImage> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    static void paintFace(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        paintTile(g, tile, x, y, w, h);
    }

    static void paintBack(Graphics2D g, double x, double y, double w, double h) {
        paintTile(g, null, x, y, w, h);
    }

    /** 轉正或轉 90 度的牌走快取；飛行中轉到一半的牌角度太多種，就直接畫。 */
    private static void paintTile(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        AffineTransform t = g.getTransform();
        int quarter = quarterTurns(t);
        if (quarter < 0) {
            drawTile(g, tile, x, y, w, h);
            return;
        }
        Rectangle2D box = t.createTransformedShape(new Rectangle2D.Double(x, y, w, h)).getBounds2D();
        CacheKey key = new CacheKey(tile, (int) Math.round(box.getWidth()), (int) Math.round(box.getHeight()), quarter);
        int margin = margin(key);
        BufferedImage image = CACHE.get(key);
        if (image == null) {
            image = new BufferedImage(key.w() + margin * 2, key.h() + margin * 2, BufferedImage.TYPE_INT_ARGB);
            Graphics2D painter = image.createGraphics();
            painter.setRenderingHints(g.getRenderingHints());
            AffineTransform toImage = AffineTransform.getTranslateInstance(margin - box.getX(), margin - box.getY());
            toImage.concatenate(t);
            painter.setTransform(toImage);
            drawTile(painter, tile, x, y, w, h);
            painter.dispose();
            CACHE.put(key, image);
        }
        g.setTransform(new AffineTransform());
        g.drawImage(image, (int) Math.round(box.getX()) - margin, (int) Math.round(box.getY()) - margin, null);
        g.setTransform(t);
    }

    /** 陰影會超出牌的範圍，圖要多留這麼多像素。 */
    private static int margin(CacheKey key) {
        return (int) (Math.max(key.w(), key.h()) * .2) + 3;
    }

    /** 轉了幾個 90 度；不是剛好的直角時回傳 -1。 */
    private static int quarterTurns(AffineTransform t) {
        boolean straight = Math.abs(t.getShearX()) < 1e-6 && Math.abs(t.getShearY()) < 1e-6;
        boolean sideways = Math.abs(t.getScaleX()) < 1e-6 && Math.abs(t.getScaleY()) < 1e-6;
        if (!straight && !sideways) {
            return -1;
        }
        return Math.floorMod((int) Math.round(Math.atan2(t.getShearY(), t.getScaleX()) / (Math.PI / 2)), 4);
    }

    private static void drawTile(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        // 小圓圈與細線不對齊像素，縮小時形狀才不會歪
        Object strokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        if (tile == null) {
            paintBody(g, body(g, x, y, w, h), BACK, BACK_DARK, BACK_DARK, IVORY_SHADE);
        } else {
            drawFace(g, tile, x, y, w, h);
        }
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, strokeControl);
    }

    private static void drawFace(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        Body b = body(g, x, y, w, h);
        paintBody(g, b, IVORY, IVORY_SHADE, IVORY_SIDE, BACK_DARK);
        double px = b.x + b.w * .1;
        double py = b.y + b.h * .08;
        double pw = b.w * .8;
        double ph = b.h * .84;
        switch (tile.suit()) {
            case WAN:
                paintWan(g, tile, px, py, pw, ph);
                break;
            case TONG:
                paintTong(g, tile, px, py, pw, ph);
                break;
            case TIAO:
                paintTiao(g, tile, px, py, pw, ph);
                break;
            case FLOWER:
                paintFlower(g, tile, px, py, pw, ph);
                break;
            default:
                paintHonor(g, tile, px, py, pw, ph);
                break;
        }
    }

    /** 視窗與執行檔共用的圖示：一張紅中。 */
    public static BufferedImage appIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double h = size * .96;
        double w = h * .76;
        paintFace(g, Tile.of(Suit.DRAGON, 1), (size - w) / 2, (size - h) / 2, w, h);
        g.dispose();
        return image;
    }

    /** 蓋在牌上的半透明遮罩，連厚度一起蓋住，表示這張牌現在不能選。 */
    static void paintMask(Graphics2D g, double x, double y, double w, double h) {
        Body b = body(g, x, y, w, h);
        Area area = new Area(shape(b, 0));
        area.add(new Area(shape(b, 1)));
        g.setColor(MASK);
        g.fill(area);
    }

    static void paintHighlight(Graphics2D g, double x, double y, double w, double h, Color color) {
        Body b = body(g, x, y, w, h);
        g.setColor(color);
        g.setStroke(new BasicStroke((float) Math.max(2, w * .07)));
        g.draw(shape(b, 0));
    }

    /** 由下往上疊：陰影、底層、側面，最後是有光影的牌面與邊緣高光。 */
    private static void paintBody(Graphics2D g, Body b, Color top, Color bottom, Color side, Color base) {
        g.setColor(SHADOW);
        g.fill(shape(b, 1.7));
        g.fill(shape(b, 1.35));
        g.setColor(base);
        g.fill(shape(b, 1));
        g.fill(shape(b, .75));
        g.setColor(side);
        g.fill(shape(b, .5));
        g.fill(shape(b, .25));

        double reach = Math.max(b.w, b.h) * .6;
        double[] light = fromScreen(g, -.64 * reach, -.77 * reach);
        float centerX = (float) (b.x + b.w / 2);
        float centerY = (float) (b.y + b.h / 2);
        float lightX = centerX + (float) light[0];
        float lightY = centerY + (float) light[1];
        float darkX = centerX - (float) light[0];
        float darkY = centerY - (float) light[1];
        g.setPaint(new GradientPaint(lightX, lightY, top, darkX, darkY, bottom));
        g.fill(shape(b, 0));

        float stroke = (float) Math.max(1, b.w * .035);
        double inset = stroke / 2;
        g.setStroke(new BasicStroke(stroke));
        g.setPaint(new GradientPaint(lightX, lightY, GLOSS, darkX, darkY, DIM));
        g.draw(new RoundRectangle2D.Double(b.x + inset, b.y + inset, b.w - stroke, b.h - stroke, b.arc, b.arc));
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(.8f));
        g.draw(shape(b, 0));
    }

    private static void paintWan(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        drawCentered(g, WAN_NUMBERS[tile.rank() - 1], x, y, w, h * .46, INK, Theme.brushFont((float) (h * .44)));
        drawCentered(g, "萬", x, y + h * .46, w, h * .54, CRIMSON, Theme.brushFont((float) (h * .54)));
    }

    private static Color ink(char code) {
        return code == 'R' ? CRIMSON : code == 'G' ? JADE : INK;
    }

    private static Shape tongPip() {
        Path2D.Double petal = new Path2D.Double();
        petal.moveTo(0, -.14);
        petal.curveTo(.12, -.28, .34, -.48, .27, -.74);
        petal.quadTo(0, -.9, -.27, -.74);
        petal.curveTo(-.34, -.48, -.12, -.28, 0, -.14);
        petal.closePath();
        Path2D.Double pip = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        pip.append(new Ellipse2D.Double(-1, -1, 2, 2), false);
        for (int i = 0; i < 5; i++) {
            pip.append(AffineTransform.getRotateInstance(i * 2 * Math.PI / 5).createTransformedShape(petal), false);
        }
        return pip;
    }

    private static void fillTongPip(Graphics2D g, double cx, double cy, double radius, Color color) {
        AffineTransform place = AffineTransform.getTranslateInstance(cx, cy);
        place.scale(radius, radius);
        g.setColor(color);
        g.fill(place.createTransformedShape(TONG_PIP));
    }

    private static void paintTong(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        double radius = w * TONG_RADIUS[tile.rank() - 1];
        if (tile.rank() == 1) {
            paintOneTong(g, x + w / 2, y + h / 2, radius);
            return;
        }
        double[][] pips = TONG_PIPS[tile.rank() - 1];
        String inks = TONG_INKS[tile.rank() - 1];
        for (int i = 0; i < pips.length; i++) {
            fillTongPip(g, x + pips[i][0] * w, y + pips[i][1] * h, radius, ink(inks.charAt(i)));
        }
    }

    /** 一筒：外圈綠環，中間一圈花瓣，花心是紅色的筒子。 */
    private static void paintOneTong(Graphics2D g, double cx, double cy, double radius) {
        g.setColor(JADE);
        g.setStroke(new BasicStroke((float) (radius * .12)));
        g.draw(new Ellipse2D.Double(cx - radius * .95, cy - radius * .95, radius * 1.9, radius * 1.9));
        g.setStroke(new BasicStroke((float) (radius * .05)));
        g.draw(new Ellipse2D.Double(cx - radius * .36, cy - radius * .36, radius * .72, radius * .72));
        g.setStroke(new BasicStroke((float) (radius * .065)));
        Ellipse2D loop = new Ellipse2D.Double(-radius * .1, -radius * .79, radius * .2, radius * .34);
        for (int i = 0; i < 12; i++) {
            AffineTransform turn = AffineTransform.getTranslateInstance(cx, cy);
            turn.rotate(i * Math.PI / 6);
            g.draw(turn.createTransformedShape(loop));
        }
        fillTongPip(g, cx, cy, radius * .31, CRIMSON);
    }

    private static void paintTiao(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        if (tile.rank() == 1) {
            TileArt.paintBird(g, x, y, w, h);
            return;
        }
        if (tile.rank() == 8) {
            paintEightTiao(g, x, y, w, h);
            return;
        }
        double[][] pips = TIAO_PIPS[tile.rank() - 1];
        String inks = TIAO_INKS[tile.rank() - 1];
        double length = h * (tile.rank() <= 6 ? .42 : .29);
        double ring = length / 7.1;
        for (int i = 0; i < pips.length; i++) {
            double cx = x + pips[i][0] * w;
            double cy = y + pips[i][1] * h;
            g.setColor(ink(inks.charAt(i)));
            paintChain(g, cx, cy - length / 2 + ring, cx, cy + length / 2 - ring, 4, ring);
        }
    }

    /** 八條：上半部綠色的 W，下半部墨色的 M，都由小圓圈串成。 */
    private static void paintEightTiao(Graphics2D g, double x, double y, double w, double h) {
        double ring = w * .07;
        double left = x + ring;
        double top = y + ring;
        double spanW = w - ring * 2;
        double spanH = h - ring * 2;
        for (int leg = 0; leg + 1 < EIGHT_W.length; leg++) {
            double x0 = left + EIGHT_W[leg][0] * spanW;
            double x1 = left + EIGHT_W[leg + 1][0] * spanW;
            double y0 = EIGHT_W[leg][1] * spanH;
            double y1 = EIGHT_W[leg + 1][1] * spanH;
            // 每段長短不同，依長度決定圓圈數，圓圈才會一個挨一個
            int rings = (int) Math.round(Math.hypot(x1 - x0, y1 - y0) / (ring * 1.7)) + 1;
            g.setColor(JADE);
            paintChain(g, x0, top + y0, x1, top + y1, rings, ring);
            g.setColor(INK);
            paintChain(g, x0, top + spanH - y0, x1, top + spanH - y1, rings, ring);
        }
    }

    /** 由 (x0, y0) 到 (x1, y1) 串起一列空心小圓，那就是一根竹條。 */
    private static void paintChain(Graphics2D g, double x0, double y0, double x1, double y1, int rings, double radius) {
        g.setStroke(new BasicStroke((float) Math.max(.7, radius * .52)));
        for (int i = 0; i < rings; i++) {
            double t = i / (double) (rings - 1);
            double cx = x0 + (x1 - x0) * t;
            double cy = y0 + (y1 - y0) * t;
            g.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
        }
    }

    private static void paintHonor(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        if (tile.suit() == Suit.DRAGON && tile.rank() == 3) {
            paintWhiteDragon(g, x, y, w, h);
            return;
        }
        Color color = tile.suit() == Suit.WIND ? INK : tile.rank() == 1 ? CRIMSON : JADE;
        drawCentered(g, tile.label(), x, y, w, h, color, Theme.brushFont((float) (h * .68)));
    }

    /** 白板：墨色的外框，框上有一列白色花紋，中間留白。 */
    private static void paintWhiteDragon(Graphics2D g, double x, double y, double w, double h) {
        double frameW = w * .9;
        double frameH = h * .86;
        double left = x + (w - frameW) / 2;
        double top = y + (h - frameH) / 2;
        double band = frameW * .2;
        Path2D.Double frame = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        frame.append(new Rectangle2D.Double(left, top, frameW, frameH), false);
        frame.append(new Rectangle2D.Double(left + band, top + band, frameW - band * 2, frameH - band * 2), false);
        g.setColor(INK);
        g.fill(frame);
        g.setColor(IVORY);
        g.setStroke(new BasicStroke((float) Math.max(.6, band * .13)));
        for (int i = 0; i < 2; i++) {
            double cx = left + frameW * (i == 0 ? .34 : .66);
            paintScroll(g, cx, top + band / 2, band * .36);
            paintScroll(g, cx, top + frameH - band / 2, band * .36);
        }
        for (int i = 0; i < 4; i++) {
            double cy = top + band + (frameH - band * 2) * (i + .5) / 4;
            paintScroll(g, left + band / 2, cy, band * .36);
            paintScroll(g, left + frameW - band / 2, cy, band * .36);
        }
    }

    /** 白板框上的一個花紋：兩道相背的弧線。 */
    private static void paintScroll(Graphics2D g, double cx, double cy, double size) {
        g.draw(new QuadCurve2D.Double(cx - size, cy - size, cx + size * .5, cy, cx - size, cy + size));
        g.draw(new QuadCurve2D.Double(cx + size, cy - size, cx - size * .5, cy, cx + size, cy + size));
    }

    /** 春夏秋冬是數字在左、紅字在右；梅蘭竹菊是墨字在左、紅數字在右。 */
    private static void paintFlower(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        String seat = String.valueOf((tile.rank() - 1) % 4 + 1);
        double headH = h * .27;
        Font number = new Font(Font.DIALOG, Font.BOLD, 1).deriveFont((float) (h * .21));
        Font name = Theme.brushFont((float) (h * .25));
        if (tile.rank() <= 4) {
            drawCentered(g, seat, x, y, w * .4, headH, INK, number);
            drawCentered(g, tile.label(), x + w * .45, y, w * .55, headH, CRIMSON, name);
        } else {
            drawCentered(g, tile.label(), x, y, w * .55, headH, INK, name);
            drawCentered(g, seat, x + w * .6, y, w * .4, headH, CRIMSON, number);
        }
        TileArt.paintFlower(g, tile.rank(), x, y + headH, w, h - headH);
    }

    private static void drawCentered(Graphics2D g, String text, double x, double y, double w, double h,
                                     Color color, Font font) {
        double size = font.getSize2D();
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics();
        float tx = (float) (x + (w - metrics.stringWidth(text)) / 2);
        float ty = (float) (y + (h - metrics.getHeight()) / 2 + metrics.getAscent());
        // 先畫一道淡淡的亮邊，字看起來像刻在牌面上
        float carve = (float) Math.max(.5, size * .025);
        double[] offset = fromScreen(g, carve, carve);
        g.setColor(GLOSS);
        g.drawString(text, tx + (float) offset[0], ty + (float) offset[1]);
        g.setColor(color);
        g.drawString(text, tx, ty);
    }
}
