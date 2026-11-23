package ezmj.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.Timer;

import ezmj.online.VoiceClip;

/**
 * 牌桌左下角說話的按鈕：圓的，白色的麥克風兩邊各兩道金色的波紋，看得出放開就廣播出去，和網頁版的 .talk-button 一樣。
 * 錄音中變紅、外面一圈紅光一明一暗、波紋一閃一閃；服務器回覆收到時往外擴散兩圈金色的波紋（broadcast）；
 * 桌上沒有別的真人時變淡（setLonely）。
 *
 * 擴散的波紋畫在圓外面，所以按鈕本身比圓大，圓的直徑是邊長除以 RING_ROOM；只有圓裡面按得到，見 contains。
 * 圖形和網頁版的 SVG 一樣畫在 48 × 48 的格子上，再縮放到圓的大小。
 */
final class TalkButton extends JButton {

    /** 按鈕的邊長是圓的直徑乘上這個，留給擴散的波紋。 */
    static final double RING_ROOM = 1.4;
    private static final Color GREEN = Theme.BUTTON;
    private static final Color RED = Theme.RED;
    private static final Color RECORDING_RIM = new Color(0xFF, 0xD9, 0xD4);
    private static final Color GLOW = new Color(229, 72, 61);
    private static final float LONELY_ALPHA = .45f;
    private static final int FRAME_MS = 33;
    /** 兩圈波紋各播多久、第二圈晚多久開始，和網頁版一樣。 */
    private static final double RING_SECONDS = 1.1;
    private static final double SECOND_RING_DELAY = .35;
    private static final double GRID = 48;
    /** 圖形佔圓的多少，和網頁版的 .talk-icon 一樣。 */
    private static final double ICON_SHARE = .8;

    private final Timer animation = new Timer(FRAME_MS, e -> tick());
    private boolean recording;
    private boolean lonely;
    /** 開始錄音或開始擴散的時間，System.nanoTime；沒有在擴散時是 -1。 */
    private long recordingSince;
    private long broadcastSince = -1;
    /** 和 Theme 的按鈕一樣，用 Tab 移過來的才畫金框。 */
    private boolean keyboardFocus;

    TalkButton() {
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                keyboardFocus = e.getCause().name().startsWith("TRAVERSAL");
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                keyboardFocus = false;
                repaint();
            }
        });
        setLonely(false);
    }

    /** 邊長，跟著字體大小設定。圓是 60，外面留給波紋。 */
    static int buttonSize() {
        return (int) Math.round(Theme.scaled(60) * RING_ROOM);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(buttonSize(), buttonSize());
    }

    void setRecording(boolean on) {
        if (on && !recording) {
            recordingSince = System.nanoTime();
        }
        recording = on;
        animate();
    }

    boolean isRecording() {
        return recording;
    }

    /** 桌上沒有別的真人：變淡，滑鼠提示改寫沒有人聽。 */
    void setLonely(boolean on) {
        lonely = on;
        String tip = on ? VoiceClip.NOBODY_TEXT : VoiceClip.TALK_TIP;
        setToolTipText(tip);
        getAccessibleContext().setAccessibleName(tip);
        repaint();
    }

    boolean isLonely() {
        return lonely;
    }

    /** 服務器回覆收到了：往外擴散兩圈金色的波紋。連著送的話從頭再來。 */
    void broadcast() {
        broadcastSince = System.nanoTime();
        animate();
    }

    /** 正在擴散波紋。 */
    boolean isBroadcasting() {
        return broadcastSince >= 0;
    }

    private void animate() {
        if (!animation.isRunning()) {
            animation.start();
        }
        repaint();
    }

    private void tick() {
        if (broadcastSince >= 0 && seconds(broadcastSince) > SECOND_RING_DELAY + RING_SECONDS) {
            broadcastSince = -1;
        }
        if (!recording && broadcastSince < 0) {
            animation.stop();
        }
        repaint();
    }

    private static double seconds(long since) {
        return (System.nanoTime() - since) / 1e9;
    }

    private double diameter() {
        return Math.min(getWidth(), getHeight()) / RING_ROOM;
    }

    /** 只有圓裡面按得到，移到圓外面放開就是取消。 */
    @Override
    public boolean contains(int x, int y) {
        double r = diameter() / 2;
        double dx = x - getWidth() / 2.0;
        double dy = y - getHeight() / 2.0;
        return dx * dx + dy * dy <= r * r;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double cx = getWidth() / 2.0;
        double cy = getHeight() / 2.0;
        double d = diameter();
        double r = d / 2;
        paintRings(g, cx, cy, r);
        if (lonely) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, LONELY_ALPHA));
        }
        if (recording) {
            double pulse = (1 - Math.cos(seconds(recordingSince) * 2 * Math.PI)) / 2;
            double glow = r + Theme.scaled(7);
            g.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), (int) Math.round(110 * pulse)));
            g.fill(new Ellipse2D.Double(cx - glow, cy - glow, glow * 2, glow * 2));
        } else {
            g.setColor(new Color(0, 0, 0, 80));
            g.fill(new Ellipse2D.Double(cx - r, cy - r + Theme.scaled(2), d, d));
        }
        ButtonModel model = getModel();
        boolean pressed = model.isArmed() && model.isPressed();
        boolean hover = model.isRollover();
        Color base = recording ? RED : GREEN;
        Color fill = pressed && !recording ? Theme.mix(base, Color.BLACK, .22)
                : hover && !recording ? Theme.mix(base, Color.WHITE, .14) : base;
        Ellipse2D circle = new Ellipse2D.Double(cx - r, cy - r, d, d);
        g.setPaint(new GradientPaint(0, (float) (cy - r), Theme.mix(fill, Color.WHITE, .09), 0, (float) (cy + r),
                Theme.mix(fill, Color.BLACK, .1)));
        g.fill(circle);
        float rim = Math.max(1.5f, Theme.scaled(2));
        g.setStroke(new BasicStroke(rim));
        g.setColor(recording ? RECORDING_RIM : Theme.GOLD);
        g.draw(new Ellipse2D.Double(cx - r + rim / 2, cy - r + rim / 2, d - rim, d - rim));
        paintIcon(g, cx, cy, d);
        if (keyboardFocus && isFocusOwner()) {
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(Theme.GOLD);
            g.setStroke(new BasicStroke(rim));
            double focus = r + Theme.scaled(4);
            g.draw(new Ellipse2D.Double(cx - focus, cy - focus, focus * 2, focus * 2));
        }
        g.dispose();
    }

    /** 擴散的波紋：從圓的邊往外長到按鈕的邊，越來越淡，第二圈晚一點開始。 */
    private void paintRings(Graphics2D graphics, double cx, double cy, double r) {
        if (broadcastSince < 0) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        double elapsed = seconds(broadcastSince);
        g.setStroke(new BasicStroke(Math.max(2f, Theme.scaled(3))));
        for (double delay : new double[] {0, SECOND_RING_DELAY}) {
            double p = (elapsed - delay) / RING_SECONDS;
            if (p < 0 || p > 1) {
                continue;
            }
            double eased = 1 - (1 - p) * (1 - p);
            double ring = r * (1 + (RING_ROOM - 1) * eased) - Theme.scaled(2);
            int alpha = (int) Math.round(240 * (1 - p));
            g.setColor(new Color(Theme.GOLD.getRed(), Theme.GOLD.getGreen(), Theme.GOLD.getBlue(), alpha));
            g.draw(new Ellipse2D.Double(cx - ring, cy - ring, ring * 2, ring * 2));
        }
        g.dispose();
    }

    /** 麥克風與兩邊的波紋，座標和網頁版 TablePanel.talkIcon 的 SVG 一樣。錄音中波紋變白、一閃一閃。 */
    private void paintIcon(Graphics2D graphics, double cx, double cy, double d) {
        Graphics2D g = (Graphics2D) graphics.create();
        double box = d * ICON_SHARE;
        g.translate(cx - box / 2, cy - box / 2);
        g.scale(box / GRID, box / GRID);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(19.5, 8, 9, 17, 9, 9));
        g.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(15, 12, 18, 18, 180, 180, Arc2D.OPEN));
        g.draw(new Line2D.Double(24, 30, 24, 36));
        g.draw(new Line2D.Double(19, 36, 29, 36));
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double t = recording ? seconds(recordingSince) : 0;
        waveColor(g, t);
        // 裡面那一圈：半徑 8，經過 (37, 15) 與 (37, 27)，左邊對稱
        g.draw(new Arc2D.Double(31.71 - 8, 13, 16, 16, -48.6, 97.2, Arc2D.OPEN));
        g.draw(new Arc2D.Double(16.29 - 8, 13, 16, 16, 131.4, 97.2, Arc2D.OPEN));
        waveColor(g, t - .25);
        // 外面那一圈：半徑 14，經過 (41.5, 11) 與 (41.5, 31)
        g.draw(new Arc2D.Double(31.70 - 14, 7, 28, 28, -45.6, 91.2, Arc2D.OPEN));
        g.draw(new Arc2D.Double(16.30 - 14, 7, 28, 28, 134.4, 91.2, Arc2D.OPEN));
        g.dispose();
    }

    /** 平常是金色；錄音中是白色，每秒暗一次，和網頁版的 wave-blink 一樣暗到 0.2。 */
    private void waveColor(Graphics2D g, double t) {
        if (!recording) {
            g.setColor(Theme.GOLD);
            return;
        }
        double dim = (1 - Math.cos(Math.max(0, t) * 2 * Math.PI)) / 2;
        g.setColor(new Color(255, 255, 255, (int) Math.round(255 * (1 - .8 * dim))));
    }
}
