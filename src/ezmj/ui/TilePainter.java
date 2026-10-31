package ezmj.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

import ezmj.model.Suit;
import ezmj.model.Tile;

/** 以向量繪製麻將牌，不需要圖片素材，任何尺寸都清晰。 */
public final class TilePainter {

    private static final Color IVORY = new Color(0xFB, 0xF7, 0xEA);
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
    private static final Color INK = new Color(0x1C, 0x2A, 0x4A);
    private static final Color CRIMSON = new Color(0xB8, 0x24, 0x24);
    private static final Color JADE = new Color(0x13, 0x7A, 0x4A);
    private static final Color BLUE = new Color(0x1F, 0x4E, 0x9C);

    /** 筒與條的點位，座標為牌面內 0-1 的比例。 */
    private static final double[][][] TONG_PIPS = {
        {{.5, .5}},
        {{.5, .27}, {.5, .73}},
        {{.2, .2}, {.5, .5}, {.8, .8}},
        {{.27, .27}, {.73, .27}, {.27, .73}, {.73, .73}},
        {{.25, .22}, {.75, .22}, {.5, .5}, {.25, .78}, {.75, .78}},
        {{.3, .2}, {.7, .2}, {.3, .5}, {.7, .5}, {.3, .8}, {.7, .8}},
        {{.2, .12}, {.5, .22}, {.8, .32}, {.3, .58}, {.7, .58}, {.3, .85}, {.7, .85}},
        {{.3, .13}, {.7, .13}, {.3, .38}, {.7, .38}, {.3, .63}, {.7, .63}, {.3, .88}, {.7, .88}},
        {{.2, .18}, {.5, .18}, {.8, .18}, {.2, .5}, {.5, .5}, {.8, .5}, {.2, .82}, {.5, .82}, {.8, .82}},
    };

    private static final double[][][] TIAO_PIPS = {
        {{.5, .5}},
        {{.5, .25}, {.5, .75}},
        {{.5, .25}, {.25, .75}, {.75, .75}},
        {{.25, .25}, {.75, .25}, {.25, .75}, {.75, .75}},
        {{.2, .25}, {.8, .25}, {.5, .5}, {.2, .75}, {.8, .75}},
        {{.2, .25}, {.5, .25}, {.8, .25}, {.2, .75}, {.5, .75}, {.8, .75}},
        {{.5, .17}, {.2, .5}, {.5, .5}, {.8, .5}, {.2, .83}, {.5, .83}, {.8, .83}},
        {{.2, .17}, {.5, .17}, {.8, .17}, {.3, .5}, {.7, .5}, {.2, .83}, {.5, .83}, {.8, .83}},
        {{.2, .17}, {.5, .17}, {.8, .17}, {.2, .5}, {.5, .5}, {.8, .5}, {.2, .83}, {.5, .83}, {.8, .83}},
    };

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
        if (tile == null) {
            paintBody(g, body(g, x, y, w, h), BACK, BACK_DARK, BACK_DARK, IVORY_SHADE);
        } else {
            drawFace(g, tile, x, y, w, h);
        }
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
        drawCentered(g, Tile.numberName(tile.rank()), x, y + h * .02, w, h * .46, INK, h * .42);
        drawCentered(g, "萬", x, y + h * .5, w, h * .48, CRIMSON, h * .46);
    }

    private static void paintTong(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        double[][] pips = TONG_PIPS[tile.rank() - 1];
        double radius = tile.rank() == 1 ? w * .4 : Math.min(w, h) * (tile.rank() <= 5 ? .2 : .15);
        for (int i = 0; i < pips.length; i++) {
            Color color = (tile.rank() + i) % 3 == 0 ? CRIMSON : (tile.rank() + i) % 3 == 1 ? BLUE : JADE;
            double cx = x + pips[i][0] * w;
            double cy = y + pips[i][1] * h;
            g.setColor(color);
            g.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
            g.setColor(IVORY);
            double inner = radius * .55;
            g.fill(new Ellipse2D.Double(cx - inner, cy - inner, inner * 2, inner * 2));
            g.setColor(color);
            double core = radius * .28;
            g.fill(new Ellipse2D.Double(cx - core, cy - core, core * 2, core * 2));
        }
    }

    private static void paintTiao(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        double[][] pips = TIAO_PIPS[tile.rank() - 1];
        double stickH = tile.rank() == 1 ? h * .8 : h * (tile.rank() <= 6 ? .4 : .27);
        double stickW = tile.rank() == 1 ? w * .28 : w * .15;
        for (int i = 0; i < pips.length; i++) {
            double cx = x + pips[i][0] * w;
            double cy = y + pips[i][1] * h;
            g.setColor(i % 4 == 3 || tile.rank() == 1 ? CRIMSON : JADE);
            g.fill(new RoundRectangle2D.Double(cx - stickW / 2, cy - stickH / 2, stickW, stickH, stickW, stickW));
            g.setColor(IVORY);
            g.fill(new RoundRectangle2D.Double(cx - stickW * .75, cy - stickH * .04, stickW * 1.5, stickH * .08, 1, 1));
        }
    }

    private static void paintHonor(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        if (tile.suit() == Suit.DRAGON && tile.rank() == 3) {
            g.setColor(BLUE);
            g.setStroke(new BasicStroke((float) Math.max(1.5, w * .07)));
            g.draw(new RoundRectangle2D.Double(x + w * .12, y + h * .14, w * .76, h * .72, w * .12, w * .12));
            return;
        }
        Color color = tile.suit() == Suit.WIND ? INK : tile.rank() == 1 ? CRIMSON : JADE;
        drawCentered(g, tile.label(), x, y, w, h, color, h * .62);
    }

    private static void paintFlower(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        Color color = tile.rank() <= 4 ? new Color(0xC2, 0x5A, 0x10) : new Color(0x7A, 0x2E, 0x9C);
        drawCentered(g, tile.label(), x, y + h * .12, w, h * .86, color, h * .56);
        int seat = (tile.rank() - 1) % 4 + 1;
        drawCentered(g, String.valueOf(seat), x, y - h * .02, w * .4, h * .26, color, h * .22);
    }

    private static void drawCentered(Graphics2D g, String text, double x, double y, double w, double h,
                                     Color color, double size) {
        g.setFont(Theme.fixedFont(Font.BOLD, (float) size));
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
