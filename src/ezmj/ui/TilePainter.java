package ezmj.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

import ezmj.model.Suit;
import ezmj.model.Tile;

/** 以向量繪製麻將牌，不需要圖片素材，任何尺寸都清晰。 */
public final class TilePainter {

    private static final Color IVORY = new Color(0xFB, 0xF7, 0xEA);
    private static final Color IVORY_SHADE = new Color(0xE6, 0xDE, 0xC6);
    private static final Color EDGE = new Color(0x8A, 0x80, 0x66);
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

    static void paintFace(Graphics2D g, Tile tile, double x, double y, double w, double h) {
        paintBody(g, x, y, w, h, IVORY, IVORY_SHADE);
        double px = x + w * .1;
        double py = y + h * .08;
        double pw = w * .8;
        double ph = h * .8;
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

    static void paintBack(Graphics2D g, double x, double y, double w, double h) {
        paintBody(g, x, y, w, h, BACK, BACK_DARK);
    }

    static void paintHighlight(Graphics2D g, double x, double y, double w, double h, Color color) {
        double arc = w * .22;
        g.setColor(color);
        g.setStroke(new BasicStroke((float) Math.max(2, w * .07)));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
    }

    private static void paintBody(Graphics2D g, double x, double y, double w, double h, Color top, Color bottom) {
        double arc = w * .22;
        double depth = h * .05;
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new RoundRectangle2D.Double(x + 1, y + 2, w, h, arc, arc));
        g.setColor(bottom.darker());
        g.fill(new RoundRectangle2D.Double(x, y + depth, w, h - depth, arc, arc));
        g.setPaint(new GradientPaint((float) x, (float) y, top, (float) x, (float) (y + h), bottom));
        g.fill(new RoundRectangle2D.Double(x, y, w, h - depth, arc, arc));
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
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
        g.drawString(text, tx, ty);
    }
}
