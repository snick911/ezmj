package ezmj.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.HierarchyEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.Timer;

import ezmj.engine.Achievement;

/**
 * 新達成的成就：一張金框的卡片，左邊一枚獎章，右邊寫「達成成就」、成就名稱與條件。
 * 開了動畫時，卡片彈出來、獎章像蓋章一樣落下、後面一圈金光慢慢轉、四周噴出亮點、外框亮兩下、一道反光掃過，
 * 演完停在最後的樣子；好幾個成就時一張接一張出來。關掉動畫時直接是最後的樣子。
 */
final class AchievementBanner extends JComponent {

    /** 一張接一張出來的間隔。 */
    static final int STAGGER_MS = 450;
    /** 第一張等視窗出來一下再演。 */
    private static final int FIRST_DELAY_MS = 150;
    /** 演到這時候停下來，之後是靜止的最後樣子。 */
    static final int DURATION_MS = 3000;
    private static final int FRAME_MS = 16;
    private static final int SPARKS = 14;
    /** 金色碎片的片數與飄落的時段。 */
    private static final int DUST = 22;
    private static final int DUST_FROM_MS = 350;
    private static final int DUST_TO_MS = 2600;

    private static final Color CARD_TOP = new Color(0x4A, 0x3A, 0x14);
    private static final Color CARD_BOTTOM = new Color(0x22, 0x1A, 0x08);
    private static final Color GOLD_LIGHT = new Color(0xF6, 0xDD, 0x8E);
    private static final Color MEDAL_LIGHT = new Color(0xFF, 0xF3, 0xC0);
    private static final Color MEDAL_DARK = new Color(0xA8, 0x7A, 0x1E);
    private static final Color MEDAL_RIM = new Color(0x8A, 0x62, 0x14);
    private static final Color RIBBON = new Color(0xB0, 0x2A, 0x24);
    private static final Color RIBBON_DARK = new Color(0x6E, 0x12, 0x10);

    private final String title;
    private final String description;
    private final int delayMs;
    private final Font kickerFont;
    private final Font titleFont;
    private final Font descriptionFont;
    private Timer timer;
    private long startedAt;
    /** 開始演之後過了幾毫秒；還沒輪到它時是負的。 */
    private long elapsed;

    /** index 是第幾個成就，決定什麼時候出來。animated 是環境設定的「動畫」。 */
    AchievementBanner(Achievement achievement, int index, boolean animated) {
        this(achievement.title(), achievement.description(), index, animated);
    }

    AchievementBanner(String title, String description, int index, boolean animated) {
        this.title = title;
        this.description = description;
        this.delayMs = FIRST_DELAY_MS + index * STAGGER_MS;
        kickerFont = Theme.font(Font.BOLD, 13f).deriveFont(Map.of(TextAttribute.TRACKING, .25f));
        titleFont = Theme.font(Font.BOLD, 20f);
        descriptionFont = Theme.font(Font.PLAIN, 14f);
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setToolTipText(title + "：" + description);
        if (!animated) {
            elapsed = DURATION_MS;
            return;
        }
        elapsed = -delayMs;
        // 畫面真的出來才開始算時間，視窗還在排版時不演
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && timer == null) {
                start();
            }
        });
    }

    private void start() {
        startedAt = System.nanoTime();
        timer = new Timer(FRAME_MS, e -> {
            elapsed = (System.nanoTime() - startedAt) / 1_000_000 - delayMs;
            if (elapsed >= DURATION_MS) {
                elapsed = DURATION_MS;
                ((Timer) e.getSource()).stop();
            }
            repaint();
        });
        timer.start();
    }

    /** 停在第幾毫秒的樣子，不再動；畫面預覽用。 */
    void freezeAt(long ms) {
        if (timer != null) {
            timer.stop();
        }
        timer = new Timer(FRAME_MS, e -> { });
        elapsed = ms;
        repaint();
    }

    @Override
    public void removeNotify() {
        if (timer != null) {
            timer.stop();
        }
        super.removeNotify();
    }

    private int pad() {
        return Theme.scaled(10);
    }

    private int medalArea() {
        return Theme.scaled(84);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics kicker = getFontMetrics(kickerFont);
        FontMetrics big = getFontMetrics(titleFont);
        FontMetrics small = getFontMetrics(descriptionFont);
        int text = Math.max(kicker.stringWidth("達成成就"), Math.max(big.stringWidth(title), small.stringWidth(description)));
        int width = Math.max(Theme.scaled(400), 2 * pad() + medalArea() + text + Theme.scaled(28));
        return new Dimension(width, Theme.scaled(84) + 2 * pad());
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (elapsed < 0) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double t = elapsed;
        int pad = pad();
        double cardW = getPreferredSize().width - 2.0 * pad;
        double cardH = getPreferredSize().height - 2.0 * pad;
        double arc = Theme.scaled(14);
        RoundRectangle2D.Double card = new RoundRectangle2D.Double(pad, pad, cardW, cardH, arc, arc);

        // 卡片彈出來：從小放大、稍微超過再回來，同時淡入
        double pop = clamp(t / 420);
        double scale = .7 + .3 * easeOutBack(pop);
        float alpha = (float) clamp(t / 220);
        AffineTransform saved = g.getTransform();
        double cx = pad + cardW / 2;
        double cy = pad + cardH / 2;
        g.translate(cx, cy);
        g.scale(scale, scale);
        g.translate(-cx, -cy);
        Composite composite = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

        paintGlow(g, card, t);
        g.setPaint(new java.awt.GradientPaint(0, pad, CARD_TOP, 0, (float) (pad + cardH), CARD_BOTTOM));
        g.fill(card);

        double medalX = pad + medalArea() / 2.0;
        double medalY = pad + cardH / 2;
        Shape clip = g.getClip();
        g.clip(card);
        paintRays(g, medalX, medalY, t);
        paintShine(g, card, t);
        paintDust(g, card, t);
        g.setClip(clip);

        g.setColor(Theme.GOLD);
        g.setStroke(new BasicStroke(Math.max(1.5f, Theme.scaled(2))));
        g.draw(card);
        double inset = Theme.scaled(4);
        g.setColor(new Color(Theme.GOLD.getRed(), Theme.GOLD.getGreen(), Theme.GOLD.getBlue(), 90));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(pad + inset, pad + inset, cardW - 2 * inset, cardH - 2 * inset,
                arc - inset, arc - inset));

        paintMedal(g, medalX, medalY, t);
        paintText(g, pad + medalArea(), pad, cardH, t);
        g.setComposite(composite);
        g.setTransform(saved);

        // 亮點不受卡片的框限制，噴到卡片外面一點
        paintSparks(g, medalX, medalY, t);
        g.dispose();
    }

    /** 外框亮兩下，之後留一點點光。 */
    private void paintGlow(Graphics2D g, RoundRectangle2D.Double card, double t) {
        double pulse = t < 300 ? 0 : t < 1500 ? Math.pow(Math.sin((t - 300) / 1200 * 2 * Math.PI - Math.PI / 2) * .5 + .5, 2)
                : 0;
        double strength = Math.max(pulse, t >= 300 ? .18 : 0);
        if (strength <= 0) {
            return;
        }
        for (int i = 3; i >= 1; i--) {
            int width = Theme.scaled(i * 3);
            g.setColor(new Color(Theme.GOLD.getRed(), Theme.GOLD.getGreen(), Theme.GOLD.getBlue(),
                    (int) (strength * 70 / i)));
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(card);
        }
    }

    /** 獎章後面一圈金光，一開始很亮，慢慢轉、慢慢淡成背景。 */
    private void paintRays(Graphics2D g, double x, double y, double t) {
        double grow = clamp((t - 120) / 380);
        if (grow <= 0) {
            return;
        }
        double bright = t < 500 ? grow : t < 1700 ? 1 - .65 * clamp((t - 500) / 1200) : .35;
        double radius = Theme.scaled(64) * (.5 + .5 * easeOut(grow));
        double angle = t * .0006;
        int rays = 14;
        Path2D.Double shape = new Path2D.Double();
        for (int i = 0; i < rays; i++) {
            double a = angle + i * 2 * Math.PI / rays;
            double half = Math.PI / rays * .45;
            shape.moveTo(x, y);
            shape.lineTo(x + Math.cos(a - half) * radius, y + Math.sin(a - half) * radius);
            shape.lineTo(x + Math.cos(a + half) * radius, y + Math.sin(a + half) * radius);
            shape.closePath();
        }
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) radius, new float[] {0f, .35f, 1f},
                new Color[] {gold((int) (200 * bright)), gold((int) (120 * bright)), gold(0)}));
        g.fill(shape);
        // 獎章周圍一團柔光
        double halo = Theme.scaled(40);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) halo, new float[] {0f, 1f},
                new Color[] {new Color(255, 236, 170, (int) (150 * bright)), new Color(255, 236, 170, 0)}));
        g.fill(new Ellipse2D.Double(x - halo, y - halo, halo * 2, halo * 2));
    }

    /** 一道斜斜的反光從左掃到右。 */
    private void paintShine(Graphics2D g, RoundRectangle2D.Double card, double t) {
        double p = (t - 600) / 800;
        if (p <= 0 || p >= 1) {
            return;
        }
        double band = Theme.scaled(70);
        double x = card.x - band + (card.width + 2 * band) * p;
        float left = (float) (x - band / 2);
        float right = (float) (x + band / 2);
        g.setPaint(new LinearGradientPaint(left, (float) card.y, right, (float) (card.y + card.height * .4),
                new float[] {0f, .5f, 1f},
                new Color[] {new Color(255, 255, 255, 0), new Color(255, 250, 225, 95), new Color(255, 255, 255, 0)}));
        Path2D.Double stripe = new Path2D.Double();
        double slant = card.height * .5;
        stripe.moveTo(left + slant, card.y);
        stripe.lineTo(right + slant, card.y);
        stripe.lineTo(right - slant, card.y + card.height);
        stripe.lineTo(left - slant, card.y + card.height);
        stripe.closePath();
        g.fill(stripe);
    }

    /** 獎章：紅色緞帶的兩條尾巴、金色的圓牌、外圈與內環、中間一顆星。像蓋章一樣從大落到原本的大小。 */
    private void paintMedal(Graphics2D g, double x, double y, double t) {
        double p = clamp((t - 80) / 340);
        if (p <= 0) {
            return;
        }
        double r = Theme.scaled(24);
        double drop = 1 + .7 * (1 - easeOutBack(p));
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.scale(drop, drop);
        Composite composite = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(p * 2)));

        // 緞帶的兩條尾巴，從獎章後面往下斜出去，末端剪一個缺口
        for (int side = -1; side <= 1; side += 2) {
            Path2D.Double tail = new Path2D.Double();
            double top = r * .25;
            double bottom = r * 1.32;
            double inner = side * r * .12;
            double outer = side * r * .78;
            tail.moveTo(inner, top);
            tail.lineTo(side * r * .52, top - r * .2);
            tail.lineTo(outer + side * r * .2, bottom);
            tail.lineTo((outer + inner) / 2 + side * r * .12, bottom - r * .22);
            tail.lineTo(inner + side * r * .05, bottom);
            tail.closePath();
            g.setPaint(new java.awt.GradientPaint(0, (float) top, RIBBON, 0, (float) bottom, RIBBON_DARK));
            g.fill(tail);
        }
        Ellipse2D.Double disc = new Ellipse2D.Double(-r, -r, 2 * r, 2 * r);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new Ellipse2D.Double(-r + 1.5, -r + 2.5, 2 * r, 2 * r));
        g.setPaint(new RadialGradientPaint(new Point2D.Double(-r * .35, -r * .38), (float) (r * 1.6),
                new float[] {0f, .5f, 1f}, new Color[] {MEDAL_LIGHT, Theme.GOLD, MEDAL_DARK}));
        g.fill(disc);
        g.setColor(MEDAL_RIM);
        g.setStroke(new BasicStroke((float) Math.max(1.5, r * .09)));
        g.draw(disc);
        double ring = r * .78;
        g.setColor(new Color(MEDAL_RIM.getRed(), MEDAL_RIM.getGreen(), MEDAL_RIM.getBlue(), 170));
        g.setStroke(new BasicStroke((float) Math.max(1, r * .05)));
        g.draw(new Ellipse2D.Double(-ring, -ring, 2 * ring, 2 * ring));
        Shape star = star(0, r * .04, r * .5, r * .21);
        g.setPaint(new java.awt.GradientPaint(0, (float) (-r * .5), new Color(0xFF, 0xF8, 0xDC), 0, (float) (r * .5),
                new Color(0xD9, 0xA8, 0x3A)));
        g.fill(star);
        g.setColor(MEDAL_RIM);
        g.setStroke(new BasicStroke((float) Math.max(1, r * .05), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(star);
        // 左上角的反光
        g.setColor(new Color(255, 255, 255, 140));
        g.setStroke(new BasicStroke((float) Math.max(1, r * .1), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Arc2D.Double(-r * .78, -r * .78, r * 1.56, r * 1.56, 110, 55,
                java.awt.geom.Arc2D.OPEN));
        g.setComposite(composite);
        g.setTransform(saved);
    }

    /** 「達成成就」、名稱與條件，從右邊滑進來。 */
    private void paintText(Graphics2D g, double left, double top, double height, double t) {
        double p = clamp((t - 220) / 360);
        if (p <= 0) {
            return;
        }
        double slide = (1 - easeOut(p)) * Theme.scaled(18);
        Composite composite = g.getComposite();
        float base = composite instanceof AlphaComposite ? ((AlphaComposite) composite).getAlpha() : 1f;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (base * p)));
        FontMetrics kicker = g.getFontMetrics(kickerFont);
        FontMetrics big = g.getFontMetrics(titleFont);
        FontMetrics small = g.getFontMetrics(descriptionFont);
        double lines = kicker.getHeight() + big.getHeight() + small.getHeight();
        double y = top + (height - lines) / 2;
        float x = (float) (left + slide);
        g.setFont(kickerFont);
        g.setColor(Theme.GOLD);
        g.drawString("達成成就", x, (float) (y + kicker.getAscent()));
        y += kicker.getHeight();
        g.setFont(titleFont);
        g.setColor(new Color(0, 0, 0, 120));
        g.drawString(title, x + 1.5f, (float) (y + big.getAscent() + 1.5));
        g.setColor(GOLD_LIGHT);
        g.drawString(title, x, (float) (y + big.getAscent()));
        y += big.getHeight();
        g.setFont(descriptionFont);
        g.setColor(Theme.TEXT);
        g.drawString(description, x, (float) (y + small.getAscent()));
        g.setComposite(composite);
    }

    /**
     * 從獎章往四周噴出去的四角亮點，越飛越小、越淡，每顆外面一團小光暈。
     * 卡片是橫的，所以往上下飛得比較近；往左飛的也近一點，不會被視窗的邊切掉。
     */
    private void paintSparks(Graphics2D g, double x, double y, double t) {
        double p = (t - 180) / 1100;
        if (p <= 0 || p >= 1) {
            return;
        }
        double distance = Theme.scaled(92) * easeOut(p);
        int seed = title.hashCode();
        for (int i = 0; i < SPARKS; i++) {
            double jitter = ((seed >> (i % 16)) & 7) / 7.0;
            double a = i * 2 * Math.PI / SPARKS + jitter * .4;
            double reach = distance * (.6 + .5 * jitter) * (Math.cos(a) < 0 ? .45 : 1);
            double sx = x + Math.cos(a) * reach;
            double sy = y + Math.sin(a) * reach * .45;
            double size = Theme.scaled(10) * (1 - p * .8) * (.7 + .5 * jitter);
            double fade = 1 - p;
            double halo = size * 1.4;
            g.setPaint(new RadialGradientPaint(new Point2D.Double(sx, sy), (float) halo, new float[] {0f, 1f},
                    new Color[] {new Color(255, 220, 120, (int) (120 * fade)), new Color(255, 220, 120, 0)}));
            g.fill(new Ellipse2D.Double(sx - halo, sy - halo, halo * 2, halo * 2));
            g.setColor(new Color(255, 248, 215, (int) (255 * fade)));
            g.fill(star(sx, sy, size, size * .2, 4));
        }
    }

    /** 卡片裡面飄下來的金色碎片，一片片轉著落下、慢慢淡掉。 */
    private void paintDust(Graphics2D g, RoundRectangle2D.Double card, double t) {
        if (t < DUST_FROM_MS || t > DUST_TO_MS) {
            return;
        }
        int seed = title.hashCode() * 31 + 7;
        for (int i = 0; i < DUST; i++) {
            double start = DUST_FROM_MS + i * 55;
            double life = (t - start) / 1100;
            if (life <= 0 || life >= 1) {
                continue;
            }
            int bits = Integer.rotateLeft(seed, i * 5);
            double column = ((bits & 0xFF) / 255.0 * .8 + .18);
            double sway = Math.sin(life * 6 + i) * Theme.scaled(6);
            double px = card.x + card.width * column + sway;
            double py = card.y - Theme.scaled(4) + (card.height + Theme.scaled(8)) * life;
            double w = Theme.scaled(5);
            double h = w * .5;
            AffineTransform saved = g.getTransform();
            g.translate(px, py);
            g.rotate(life * 9 + i);
            g.scale(1, Math.abs(Math.cos(life * 8 + i)) * .8 + .2);
            Color color = (bits & 0x100) == 0 ? GOLD_LIGHT : Theme.GOLD;
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (230 * (1 - life))));
            g.fill(new java.awt.geom.Rectangle2D.Double(-w / 2, -h / 2, w, h));
            g.setTransform(saved);
        }
    }

    private static Color gold(int alpha) {
        return new Color(Theme.GOLD.getRed(), Theme.GOLD.getGreen(), Theme.GOLD.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Shape star(double x, double y, double outer, double inner) {
        return star(x, y, outer, inner, 5);
    }

    /** points 個角的星，尖端朝上。 */
    private static Shape star(double x, double y, double outer, double inner, int points) {
        Path2D.Double star = new Path2D.Double();
        for (int i = 0; i < points * 2; i++) {
            double radius = i % 2 == 0 ? outer : inner;
            double a = -Math.PI / 2 + i * Math.PI / points;
            double px = x + Math.cos(a) * radius;
            double py = y + Math.sin(a) * radius;
            if (i == 0) {
                star.moveTo(px, py);
            } else {
                star.lineTo(px, py);
            }
        }
        star.closePath();
        return star;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double easeOut(double p) {
        return 1 - Math.pow(1 - p, 3);
    }

    /** 稍微超過再彈回來。 */
    private static double easeOutBack(double p) {
        double c = 1.70158;
        return 1 + (c + 1) * Math.pow(p - 1, 3) + c * Math.pow(p - 1, 2);
    }
}
