package ezmj.ui;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** 畫要存成圖檔的東西（結果圖、PDF 的頁面、段位證書）共用的小工具。 */
final class ImageKit {

    /** 文字的 x 是哪一端。 */
    enum Align {
        LEFT, CENTER, RIGHT
    }

    private ImageKit() {
    }

    /** 開好反鋸齒的畫筆，用完要 dispose。 */
    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    static BasicStroke roundStroke(float width) {
        return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    /**
     * 畫一行字，(x, y) 是 align 那一端、垂直置中。放不下 maxWidth 就縮小字，縮到 minSize 還放不下就截掉、加刪節號。
     * 回傳實際畫出來的寬度。
     */
    static double text(Graphics2D g, String text, Font font, double minSize, double maxWidth, double x, double y,
                       Align align) {
        g.setFont(font);
        double width = width(g, text);
        String shown = text;
        if (width > maxWidth) {
            g.setFont(font.deriveFont((float) Math.max(minSize, font.getSize2D() * maxWidth / width)));
            if (width(g, text) > maxWidth) {
                shown = clipped(g, text, maxWidth);
            }
        }
        double shownWidth = width(g, shown);
        double left = align == Align.LEFT ? x : align == Align.RIGHT ? x - shownWidth : x - shownWidth / 2;
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(shown, (float) left, (float) (y + (metrics.getAscent() - metrics.getDescent()) / 2.0));
        return shownWidth;
    }

    /** 一般的字型，粗體或不粗。 */
    static double text(Graphics2D g, String text, boolean bold, double size, double minSize, double maxWidth, double x,
                       double y, Align align) {
        return text(g, text, Theme.fixedFont(bold ? Font.BOLD : Font.PLAIN, (float) size), minSize, maxWidth, x, y,
                align);
    }

    static double width(Graphics2D g, String text) {
        return g.getFont().getStringBounds(text, g.getFontRenderContext()).getWidth();
    }

    /** 用目前的字型，從後面一個字一個字拿掉，直到加上刪節號放得下。 */
    private static String clipped(Graphics2D g, String text, double maxWidth) {
        int end = text.length();
        while (end > 0) {
            end--;
            // 不要切在一個字的兩半中間
            if (end > 0 && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            String candidate = text.substring(0, end) + "…";
            if (width(g, candidate) <= maxWidth) {
                return candidate;
            }
        }
        return "…";
    }

    /** 縮放圖片。縮小時每次縮一半，最後一步才縮到剛好，一次縮太多字會糊。 */
    static BufferedImage smooth(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();
        while (w != width || h != height) {
            w = w / 2 >= width ? w / 2 : width;
            h = h / 2 >= height ? h / 2 : height;
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        }
        return current;
    }
}
