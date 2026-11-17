package ezmj.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * 胡與自摸按鈕外面的火焰。分成按鈕後面與前面兩次畫：
 * 後面是一圈一明一暗的熱氣光暈，和三層火舌（深紅的外焰、橘黃的中焰、白黃的火心）從按鈕上緣與兩側竄出來，
 * 火舌的根部被按鈕蓋住，看起來是從按鈕邊上燒起來；前面是按鈕邊上一道發亮的橘邊，與一顆顆往上飄、越飄越暗的火星。
 * 全部照時間算，沒有亂數，同一個時間畫出來都一樣。
 */
final class FlameArt {

    private static final float[] STOPS = {0f, .4f, 1f};
    private static final Color[] HALO = {new Color(255, 90, 20, 70), new Color(255, 120, 30, 45),
        new Color(220, 40, 10, 0)};
    private static final Color[] OUTER = {new Color(225, 55, 18, 225), new Color(248, 105, 24, 170),
        new Color(190, 25, 8, 0)};
    private static final Color[] MIDDLE = {new Color(255, 150, 35, 245), new Color(255, 185, 55, 210),
        new Color(255, 120, 20, 0)};
    private static final Color[] CORE = {new Color(255, 253, 230, 250), new Color(255, 232, 140, 225),
        new Color(255, 195, 80, 0)};
    private static final Color EMBER_HOT = new Color(255, 236, 150);
    private static final Color EMBER_COOL = new Color(235, 70, 20);
    private static final int EMBERS = 14;
    private static final double GOLDEN = .6180339887;

    private FlameArt() {
    }

    /**
     * 按鈕後面的光暈與火舌。button 是按鈕在這個座標裡的位置，arc 是按鈕圓角的直徑，t 是燒了幾秒。
     * up 是上緣的火最高竄多高，side 是兩側往外留了多寬。
     */
    static void paintBehind(Graphics2D g, Rectangle2D button, double arc, double t, double up, double side) {
        paintHeat(g, button, arc, t, side);
        // 外焰先畫一次又寬又淡的，當作火的邊緣糊開的光
        paintTongues(g, button, t, up * 1.08, side * 1.1, 1.7, HALO, 0);
        double[] scales = {1, .7, .42};
        Color[][] layers = {OUTER, MIDDLE, CORE};
        for (int layer = 0; layer < layers.length; layer++) {
            paintTongues(g, button, t, up * scales[layer], side * scales[layer], Math.sqrt(scales[layer]),
                    layers[layer], layer * 31);
        }
    }

    /** 按鈕前面：邊上一道一明一暗的橘邊，與往上飄的火星。 */
    static void paintFront(Graphics2D g, Rectangle2D button, double arc, double t, double up) {
        double pulse = .5 + .5 * Math.sin(t * 6.2);
        float width = (float) Math.max(1.2, button.getHeight() * .045);
        g.setStroke(new BasicStroke(width));
        g.setColor(new Color(255, 175, 70, (int) (110 + 90 * pulse)));
        double inset = width / 2;
        g.draw(new RoundRectangle2D.Double(button.getX() + inset, button.getY() + inset,
                button.getWidth() - width, button.getHeight() - width, arc, arc));
        paintEmbers(g, button, t, up);
    }

    /** 熱氣：按鈕外面幾圈越外越淡的橘光，整圈一起一明一暗。 */
    private static void paintHeat(Graphics2D g, Rectangle2D b, double arc, double t, double side) {
        double pulse = .7 + .3 * Math.sin(t * 5.1) * Math.sin(t * 2.3 + 1);
        int rings = 5;
        for (int ring = rings; ring >= 1; ring--) {
            double grow = side * .75 * ring / rings;
            g.setColor(new Color(255, 105, 25, (int) (30 * pulse * (rings + 1 - ring) / rings)));
            g.fill(new RoundRectangle2D.Double(b.getX() - grow, b.getY() - grow * .8, b.getWidth() + grow * 2,
                    b.getHeight() + grow * 1.6, arc + grow * 2, arc + grow * 2));
        }
    }

    /**
     * 一層火舌：上緣一排往上竄、兩端短一點；兩個上角各一條往外斜的大火舌；兩側各三條越上面越往外飄。
     * fatness 是火舌寬度的比例，seed 讓每一層的節奏不一樣。
     */
    private static void paintTongues(Graphics2D g, Rectangle2D b, double t, double up, double side, double fatness,
                                     Color[] colors, int seed) {
        double x = b.getX();
        double y = b.getY();
        double w = b.getWidth();
        double h = b.getHeight();
        double spacing = Math.max(4, h * .3);
        int count = Math.max(4, (int) Math.round(w / spacing));
        double step = w / count;
        double base = y + h * .2;
        for (int i = 0; i < count; i++) {
            double u = (i + .5) / count;
            double f = flicker(t, i + seed);
            double envelope = .72 + .28 * Math.sin(Math.PI * u);
            // 單數的矮一點，高高低低交錯
            double length = up * envelope * (i % 2 == 0 ? .55 + .45 * f : .4 + .35 * f) + h * .2;
            double width = step * 2 * fatness;
            double sway = Math.sin(t * 5.3 + (i + seed) * 1.9) * width * .6 + (f - .5) * width * .3;
            double jitter = Math.sin((i + seed) * 12.9898) * step * .18;
            tongue(g, x + step * (i + .5) + jitter, base, 0, width, length, sway, colors);
        }
        // 兩個上角往外斜
        for (int dir = -1; dir <= 1; dir += 2) {
            double f = flicker(t, seed + 50 + dir * 7);
            double cornerX = dir < 0 ? x + h * .15 : x + w - h * .15;
            double length = up * (.75 + .3 * f) + h * .25;
            tongue(g, cornerX, base, dir * Math.toRadians(24 + 8 * f), h * .55 * fatness, length,
                    Math.sin(t * 6.1 + seed + dir) * h * .12, colors);
        }
        // 兩側：從下往上三條，越上面越長、越往外斜
        for (int dir = -1; dir <= 1; dir += 2) {
            double edge = dir < 0 ? x + 2 : x + w - 2;
            for (int j = 0; j < 3; j++) {
                double f = flicker(t, seed + 20 + j * 3 + dir * 11);
                double baseY = y + h * (.85 - .3 * j);
                double angle = dir * Math.toRadians(62 - 12 * j + 10 * (f - .5));
                double length = side * (1.05 + .25 * j) * (.6 + .5 * f) + h * .12;
                tongue(g, edge, baseY, angle, h * (.42 + .06 * j) * fatness, length,
                        Math.sin(t * 7.3 + j + seed) * h * .08, colors);
            }
        }
    }

    /** 0 到 1 的忽大忽小，三個頻率疊起來，看不出規律。 */
    private static double flicker(double t, double seed) {
        double v = .55 * Math.sin(t * 8.7 + seed * 1.37) + .3 * Math.sin(t * 13.9 + seed * 2.71)
                + .15 * Math.sin(t * 23.3 + seed * .53);
        return .5 + .5 * v;
    }

    /**
     * 一條火舌：根部中心在 (baseX, baseY)、寬 width，往 angle 的方向長 length（0 是正上方，正的往右斜），
     * 尖端往旁邊偏 sway。根部圓、往上收成彎彎的尖，顏色從根部往尖端變淡。
     */
    private static void tongue(Graphics2D g, double baseX, double baseY, double angle, double width, double length,
                               double sway, Color[] colors) {
        if (length < 1 || width < .5) {
            return;
        }
        AffineTransform saved = g.getTransform();
        g.translate(baseX, baseY);
        g.rotate(angle);
        double half = width / 2;
        // 下半鼓起來、上半收成往旁邊彎的細尖，像一滴倒過來的水
        Path2D.Double shape = new Path2D.Double();
        shape.moveTo(-half, 0);
        shape.curveTo(-half * 1.3, -length * .32, -half * .3 + sway * .25, -length * .72, sway, -length);
        shape.curveTo(half * .3 + sway * .55, -length * .72, half * 1.3, -length * .32, half, 0);
        shape.quadTo(0, half * .7, -half, 0);
        shape.closePath();
        g.setPaint(new LinearGradientPaint(0f, (float) (half * .35), (float) sway, (float) -length, STOPS, colors));
        g.fill(shape);
        g.setTransform(saved);
    }

    /**
     * 火星：從按鈕上緣冒出來，邊飄邊左右晃，飄到 up 那麼高就熄了；一開始是亮黃、越飄越紅越淡。
     * 每一顆有自己的週期，每一輪從上緣不同的地方冒出來。
     */
    private static void paintEmbers(Graphics2D g, Rectangle2D b, double t, double up) {
        Composite saved = g.getComposite();
        for (int i = 0; i < EMBERS; i++) {
            double period = .9 + frac(i * .373) * .9;
            double cycle = t / period + i * GOLDEN;
            double phase = frac(cycle);
            double spot = frac(i * .381 + Math.floor(cycle) * GOLDEN);
            double x = b.getX() + b.getWidth() * (.06 + .88 * spot)
                    + Math.sin(t * 3.1 + i * 2.3) * b.getHeight() * .14 * phase;
            double y = b.getY() + b.getHeight() * .05 - phase * (up * 1.05);
            double alpha = phase < .15 ? phase / .15 : (1 - phase) / .85;
            double r = Math.max(.8, b.getHeight() * (.04 + .03 * frac(i * .77)) * (1 - .45 * phase));
            Color color = Theme.mix(EMBER_HOT, EMBER_COOL, phase);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, alpha))));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (r * 3),
                    new float[] {0f, 1f}, new Color[] {new Color(color.getRed(), color.getGreen(), color.getBlue(), 120),
                        new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)}));
            g.fill(new Ellipse2D.Double(x - r * 3, y - r * 3, r * 6, r * 6));
            g.setColor(Theme.mix(color, Color.WHITE, .35 * (1 - phase)));
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        }
        g.setComposite(saved);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }
}
