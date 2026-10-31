package ezmj.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * 牌面上的圖案：八張花牌與一條的鳥。圖案寫成向量路徑，任何尺寸都清晰。
 * 花牌畫在 100x100 的方格裡，鳥畫在 100x136 的方格裡，對應牌面的比例。
 */
final class TileArt {

    private static final Color RED = TilePainter.CRIMSON;
    private static final Color GREEN = TilePainter.JADE;
    private static final Color INK = TilePainter.INK;
    private static final float LINE = 3.6f;

    /** 一筆圖案。width 為 0 表示填滿，否則是線條粗細。 */
    private record Layer(Shape shape, Color color, float width) {
    }

    /** 春：盛開的牡丹，下方兩側是葉子。 */
    private static final Layer[] SPRING = {
        line(RED, 4f, scallop(50, 32, 22, 6, 1.5)),
        line(RED, LINE, "O 50 32 9"),
        line(RED, LINE, "M 50 23 L 50 13 M 58 37 L 66 42 M 42 37 L 34 42"),
        line(GREEN, 5.5f, "M 54 66 C 62 74 72 84 88 94"),
        line(GREEN, LINE, leaf(50, 72, 12, 56, 13) + leaf(46, 78, 8, 90, 10) + leaf(58, 84, 34, 96, 9) + leaf(70, 74, 96, 62, 11)),
    };

    /** 夏：含苞的荷花，下方是一排葉片。 */
    private static final Layer[] SUMMER = {
        line(RED, 4f, "M 54 8 C 78 12 88 30 78 46 C 70 58 40 58 26 46 C 16 36 22 22 32 20 C 38 26 40 30 42 34 C 44 24 48 14 54 8 Z"),
        line(RED, LINE, "M 42 34 C 46 44 56 48 66 44 M 54 8 C 60 18 66 30 66 44"),
        fill(GREEN, leaf(8, 56, 24, 98, 7) + leaf(30, 62, 36, 98, 7) + leaf(50, 66, 48, 98, 7)),
        line(GREEN, 4.5f, "M 58 98 C 66 86 80 80 94 86"),
    };

    /** 秋：一串果實掛在枝上，枝葉往左右伸展。 */
    private static final Layer[] AUTUMN = {
        line(GREEN, 5f, "M 92 98 C 72 84 50 70 34 40"),
        line(GREEN, 3.6f, "M 58 76 C 40 88 30 90 12 86 M 44 58 C 56 42 70 32 86 30"),
        line(GREEN, LINE, leaf(34, 40, 14, 12, 12) + leaf(36, 38, 46, 4, 12) + leaf(84, 32, 96, 16, 8)
                + leaf(32, 86, 8, 72, 9) + leaf(36, 88, 18, 100, 8)),
        line(RED, LINE, cluster(68, 62, 11, 5.6)),
    };

    /** 冬：水仙，紅花在上，長葉從球莖往外展開。 */
    private static final Layer[] WINTER = {
        line(RED, LINE, cluster(68, 18, 9.5, 5)),
        fill(GREEN, leaf(50, 76, 16, 6, 12) + leaf(52, 76, 44, 26, 9) + leaf(50, 76, 2, 62, 8)
                + leaf(50, 78, 12, 92, 8) + leaf(54, 76, 96, 40, 9)),
        line(GREEN, 4f, "M 48 76 C 40 88 50 100 66 98 C 76 96 76 84 66 78 C 60 76 56 80 58 86"),
    };

    /** 梅：一朵紅梅，下方一道帶花苞的枝條。 */
    private static final Layer[] PLUM = {
        line(RED, 4.2f, scallop(50, 30, 22, 6, 1.45)),
        line(RED, LINE, "O 50 30 11"),
        fill(RED, "O 50 30 4.6"),
        line(GREEN, 4.6f, "M 4 74 C 18 66 30 86 46 82 C 62 78 74 84 90 64"),
        line(GREEN, LINE, "O 11 60 6.4 O 26 66 5.6 O 88 48 6.8 O 72 62 6 O 38 94 4.6"
                + " M 13 66 L 18 71 M 86 55 L 84 68 M 70 68 L 68 79 M 40 89 L 42 83 M 58 80 L 68 96 M 70 80 L 82 94"),
    };

    /** 蘭：細長的葉片彎成弧線，葉間開著紅花。 */
    private static final Layer[] ORCHID = {
        line(GREEN, 4.6f, "M 58 98 C 52 82 40 62 24 58 C 14 56 8 64 6 76"),
        line(GREEN, 4.6f, "M 58 98 C 40 72 42 34 62 8 C 78 10 90 20 96 40"),
        line(GREEN, 3.6f, "M 60 98 C 62 88 66 80 68 74 M 44 36 C 34 40 26 48 22 56"),
        fill(RED, "O 66 36 8.6 O 74 52 9 O 62 62 7"),
    };

    /** 竹：三枝分節的竹子，葉子往兩側垂下。 */
    private static final Layer[] BAMBOO = {
        line(GREEN, 5f, "M 40 98 L 42 72 M 43 67 L 45 44 M 46 39 L 48 20"
                + " M 54 98 L 56 66 M 57 61 L 59 32 M 60 27 L 62 4"
                + " M 68 98 L 69 78 M 70 73 L 71 52"),
        fill(GREEN, leaf(42, 48, 8, 34, 9) + leaf(40, 58, 4, 58, 9) + leaf(39, 70, 8, 84, 9) + leaf(38, 82, 18, 98, 8)
                + leaf(62, 20, 90, 22, 9) + leaf(72, 34, 98, 54, 9) + leaf(72, 50, 90, 72, 8)),
    };

    /** 菊：一朵圓簇的紅菊，枝葉橫在下方。 */
    private static final Layer[] CHRYSANTHEMUM = {
        line(RED, 3.8f, cluster(58, 28, 15, 7.6)),
        line(GREEN, 4.6f, "M 6 72 C 24 70 42 84 60 92 C 66 86 76 76 96 72"),
        line(GREEN, LINE, leaf(26, 74, 16, 42, 13) + leaf(36, 82, 12, 96, 10) + leaf(46, 86, 42, 62, 8) + leaf(74, 80, 98, 68, 8)),
    };

    /** 一條：紅色的鳥立在彎曲的枝上，尾羽往下垂。 */
    private static final Layer[] BIRD = {
        // 頭上的羽角
        fill(RED, "M 14 12 C 24 24 36 28 50 28 C 64 28 76 24 88 10 C 86 28 74 40 50 42 C 28 40 16 28 14 12 Z"),
        // 身體，留出羽毛紋路
        fill(RED, "M 36 34 C 24 50 22 70 14 92 C 34 92 62 84 68 62 C 72 50 68 40 64 34 Z"),
        line(TilePainter.IVORY, 2.6f, "M 36 58 C 42 62 50 62 58 58 M 32 68 C 40 72 50 72 60 66 M 28 78 C 36 82 46 80 54 76 M 44 48 L 44 84 M 54 46 L 54 78"),
        // 大眼睛
        fill(TilePainter.IVORY, "O 50 32 15"),
        line(RED, 4f, "O 50 32 13"),
        fill(RED, "O 50 32 5.4"),
        // 枝條與尾羽
        line(INK, 4.6f, "M 4 100 C 30 96 56 98 72 82 C 80 74 84 66 92 66 C 100 66 100 76 92 76"),
        line(INK, 3f, "M 4 100 L 12 94 M 8 104 L 18 98 M 14 108 L 24 100"),
        line(GREEN, 3.8f, "M 40 98 C 44 112 52 124 60 132 M 32 100 C 32 112 34 120 38 126 M 48 98 C 54 106 60 112 68 116 M 24 100 C 22 108 22 114 24 120"),
    };

    private static final Layer[][] FLOWERS = {SPRING, SUMMER, AUTUMN, WINTER, PLUM, ORCHID, BAMBOO, CHRYSANTHEMUM};

    private TileArt() {
    }

    /** 花牌的圖案，rank 為 1-8，畫在指定範圍的中央。 */
    static void paintFlower(Graphics2D g, int rank, double x, double y, double w, double h) {
        paint(g, FLOWERS[rank - 1], 100, 100, x, y, w, h);
    }

    /** 一條的鳥。 */
    static void paintBird(Graphics2D g, double x, double y, double w, double h) {
        paint(g, BIRD, 100, 136, x, y, w, h);
    }

    private static void paint(Graphics2D g, Layer[] layers, double artW, double artH,
                              double x, double y, double w, double h) {
        double scale = Math.min(w / artW, h / artH);
        AffineTransform saved = g.getTransform();
        g.translate(x + (w - artW * scale) / 2, y + (h - artH * scale) / 2);
        g.scale(scale, scale);
        for (Layer layer : layers) {
            g.setColor(layer.color);
            if (layer.width > 0) {
                g.setStroke(new BasicStroke(layer.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(layer.shape);
            } else {
                g.fill(layer.shape);
            }
        }
        g.setTransform(saved);
    }

    private static Layer line(Color color, float width, String spec) {
        return new Layer(path(spec), color, width);
    }

    private static Layer fill(Color color, String spec) {
        return new Layer(path(spec), color, 0);
    }

    /** 花朵的外框：一圈向外鼓起的花瓣。 */
    private static String scallop(double cx, double cy, double r, int petals, double bulge) {
        StringBuilder spec = new StringBuilder();
        double step = 2 * Math.PI / petals;
        for (int i = 0; i <= petals; i++) {
            double angle = -Math.PI / 2 + i * step;
            double px = cx + r * Math.cos(angle);
            double py = cy + r * Math.sin(angle);
            if (i == 0) {
                spec.append("M ").append(px).append(' ').append(py);
            } else {
                double mid = angle - step / 2;
                spec.append(" Q ").append(cx + r * bulge * Math.cos(mid)).append(' ').append(cy + r * bulge * Math.sin(mid))
                        .append(' ').append(px).append(' ').append(py);
            }
        }
        return spec.toString();
    }

    /** 一片葉子，從 (x0, y0) 長到 (x1, y1)，width 是最寬處。 */
    private static String leaf(double x0, double y0, double x1, double y1, double width) {
        double nx = -(y1 - y0);
        double ny = x1 - x0;
        double length = Math.hypot(nx, ny);
        double ox = nx / length * width;
        double oy = ny / length * width;
        double mx = (x0 + x1) / 2;
        double my = (y0 + y1) / 2;
        return " M " + x0 + " " + y0 + " Q " + (mx + ox) + " " + (my + oy) + " " + x1 + " " + y1
                + " Q " + (mx - ox) + " " + (my - oy) + " " + x0 + " " + y0 + " Z ";
    }

    /** 一簇小圓：中間一顆，外面圍六顆。 */
    private static String cluster(double cx, double cy, double ring, double r) {
        StringBuilder spec = new StringBuilder("O " + cx + " " + cy + " " + r);
        for (int i = 0; i < 6; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 3;
            spec.append(" O ").append(cx + ring * Math.cos(angle)).append(' ').append(cy + ring * Math.sin(angle))
                    .append(' ').append(r);
        }
        return spec.toString();
    }

    /** 解析路徑：M 移動、L 直線、Q 二次曲線、C 三次曲線、Z 封閉、O 圓（圓心與半徑）。 */
    private static Shape path(String spec) {
        Path2D.Double path = new Path2D.Double();
        String[] t = spec.trim().split("\\s+");
        int i = 0;
        while (i < t.length) {
            switch (t[i++]) {
                case "M":
                    path.moveTo(n(t, i), n(t, i + 1));
                    i += 2;
                    break;
                case "L":
                    path.lineTo(n(t, i), n(t, i + 1));
                    i += 2;
                    break;
                case "Q":
                    path.quadTo(n(t, i), n(t, i + 1), n(t, i + 2), n(t, i + 3));
                    i += 4;
                    break;
                case "C":
                    path.curveTo(n(t, i), n(t, i + 1), n(t, i + 2), n(t, i + 3), n(t, i + 4), n(t, i + 5));
                    i += 6;
                    break;
                case "O":
                    double r = n(t, i + 2);
                    path.append(new Ellipse2D.Double(n(t, i) - r, n(t, i + 1) - r, r * 2, r * 2), false);
                    i += 3;
                    break;
                case "Z":
                    path.closePath();
                    break;
                default:
                    throw new IllegalArgumentException("無法解析路徑: " + spec);
            }
        }
        return path;
    }

    private static double n(String[] tokens, int index) {
        return Double.parseDouble(tokens[index]);
    }
}
