package ezmj.ui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import ezmj.career.Challenge;
import ezmj.career.MemoryQuiz;
import ezmj.career.WaitsQuiz;
import ezmj.model.Tile;

/**
 * 修練的作答：蓋在牌桌上，一題一題問。每題限時，逾時算錯；答完馬上說對錯與正解，再接下一題。
 * 看聽每題先亮牌 viewSeconds 秒（可以按「看好了」提早），牌蓋起來才開始作答、開始計時；
 * 記牌直接作答，點牌題照順序點，選擇題點一下就送出。最後一頁是成績，過關的獎金由 settle 算好寫上去。
 * 中途關掉視窗就是放棄，這關不算。
 */
final class DrillDialog extends JDialog {

    /** 這次修練的成績：答對幾題、共幾題、平均作答秒數（逾時照限時算）。 */
    record Outcome(int correct, int total, double averageSeconds) {
    }

    /** 一題：看聽是一手牌，記牌是一題問答。 */
    record Item(WaitsQuiz.Hand hand, MemoryQuiz.Question question) {
        static Item of(WaitsQuiz.Hand hand) {
            return new Item(hand, null);
        }

        static Item of(MemoryQuiz.Question question) {
            return new Item(null, question);
        }
    }

    private static final int TICK_MS = 100;
    private static final int FEEDBACK_MS = 2_200;
    private static final int HAND_TILE = 36;
    private static final int ANSWER_TILE = 34;
    private static final int CARD_WIDTH = 640;
    /** 主視窗的最小大小扣掉外框與選單列，大約這麼大。 */
    private static final int MIN_WIDTH = 1084;
    private static final int MIN_HEIGHT = 655;

    private final Challenge challenge;
    private final List<Item> items;
    private final Function<Outcome, String> settle;
    /** 每一頁至少 CARD_WIDTH 寬，換頁時卡片不會忽大忽小。 */
    private final JPanel card = new JPanel() {
        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            Insets insets = getInsets();
            return new Dimension(Math.max(size.width, Theme.scaled(CARD_WIDTH) + insets.left + insets.right), size.height);
        }
    };
    private final JLabel countdown = ResultDialog.line(" ", Font.BOLD, 20f, Theme.GOLD);
    private final TilePicker picker = new TilePicker();
    private final List<Double> seconds = new ArrayList<>();
    private Timer timer;
    private int index;
    private int correct;
    private long answerStart;
    private boolean answering;
    /** 正在看第幾題的對錯；-1 是不在看對錯。按「下一題」與自動換題只換一次。 */
    private int feedbackFor = -1;
    private Outcome outcome;

    /** settle 在答完最後一題時呼叫一次，回傳要寫在成績頁的話（過關、星、獎金）。 */
    DrillDialog(JFrame owner, Challenge challenge, List<Item> items, Function<Outcome, String> settle) {
        super(owner, challenge.label() + "　" + challenge.title(), ModalityType.APPLICATION_MODAL);
        this.challenge = challenge;
        this.items = List.copyOf(items);
        this.settle = settle;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmQuit();
            }
        });

        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.GOLD, 1),
                BorderFactory.createEmptyBorder(18, 28, 22, 28)));
        JPanel felt = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                StartPanel.paintFelt(this, graphics);
            }
        };
        felt.add(card);
        setContentPane(FitScroll.wrap(felt));
        picker.onChange(this::pickerChanged);

        // 蓋住主視窗的牌桌，視窗小也照主視窗的大小；主視窗不在螢幕上（例如檢查畫面時）就用最小的視窗大小
        if (owner.isShowing()) {
            Rectangle area = owner.getContentPane().getBounds();
            Point at = owner.getContentPane().getLocationOnScreen();
            setBounds(at.x, at.y, Math.max(area.width, MIN_WIDTH), Math.max(area.height, MIN_HEIGHT));
        } else {
            setSize(MIN_WIDTH, MIN_HEIGHT);
        }
        start(0);
    }

    /** 作答完的成績；中途放棄是 null。 */
    Outcome outcome() {
        return outcome;
    }

    private void start(int next) {
        index = next;
        if (index >= items.size()) {
            finish();
            return;
        }
        Item item = items.get(index);
        if (item.hand() != null) {
            showHand(item);
        } else {
            showQuestion(item);
        }
    }

    // ---- 每一頁 ----

    private void header(String prompt) {
        card.removeAll();
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
        top.setOpaque(false);
        top.setAlignmentX(LEFT_ALIGNMENT);
        top.add(ResultDialog.line(challenge.label() + "　" + challenge.title(), Font.BOLD, 22f, Theme.GOLD));
        top.add(Box.createHorizontalGlue());
        top.add(Box.createHorizontalStrut(24));
        // 倒數的寬度照最長的字固定，秒數從兩位數變一位數時不會跳，也不會被截掉
        countdown.setPreferredSize(null);
        countdown.setText("作答　剩 99 秒");
        Dimension wide = countdown.getPreferredSize();
        countdown.setPreferredSize(wide);
        countdown.setMinimumSize(wide);
        countdown.setMaximumSize(wide);
        countdown.setHorizontalAlignment(SwingConstants.RIGHT);
        countdown.setText(" ");
        top.add(countdown);
        card.add(top);
        card.add(Box.createVerticalStrut(4));
        card.add(ResultDialog.line("第 " + (index + 1) + " / " + items.size() + " 題", Font.PLAIN, 14f, Theme.MUTED));
        card.add(Box.createVerticalStrut(12));
        JLabel question = ResultDialog.line("", Font.BOLD, 18f, Theme.TEXT);
        question.setText(HomePanel.wrapHtml(HomePanel.escape(prompt), Theme.scaled(CARD_WIDTH)));
        card.add(question);
        card.add(Box.createVerticalStrut(12));
    }

    /** 看聽：先亮牌，時間到或按「看好了」就蓋起來作答。 */
    private void showHand(Item item) {
        header("這手牌聽哪幾種？牌蓋起來之後，從下面點出全部聽的牌，按「確定」。");
        TileRow hand = new TileRow(HAND_TILE, Theme.scaled(CARD_WIDTH + 120));
        hand.show(item.hand().tiles(), 0, false);
        card.add(hand);
        card.add(Box.createVerticalStrut(14));
        picker.onChange(this::pickerChanged);
        picker.reset(TilePicker.Mode.SET, 0);
        picker.setEnabled(false);
        picker.setAlignmentX(LEFT_ALIGNMENT);
        card.add(picker);
        card.add(Box.createVerticalStrut(14));
        JButton ready = Theme.button("看好了", Theme.RED);
        ready.addActionListener(e -> answerHand(item, hand));
        card.add(ShareDialog.buttonRow(ready));
        getRootPane().setDefaultButton(ready);
        refresh();
        countDown(challenge.drill().viewSeconds(), "看牌", () -> answerHand(item, hand));
    }

    private void answerHand(Item item, TileRow hand) {
        if (answering) {
            return;
        }
        stopTimer();
        hand.show(item.hand().tiles(), 0, true);
        picker.setEnabled(true);
        JButton submit = Theme.button("確定", Theme.RED);
        submit.addActionListener(e -> judge(item, picker.picked(), -1, false));
        JButton clear = Theme.button("清除", Theme.BUTTON);
        clear.addActionListener(e -> picker.clear());
        replaceButtons(ShareDialog.buttonRow(submit, clear));
        getRootPane().setDefaultButton(submit);
        beginAnswer(item);
    }

    /** 記牌：點牌題照順序點，選擇題點一下就送出。 */
    private void showQuestion(Item item) {
        MemoryQuiz.Question question = item.question();
        header(question.prompt());
        if (question.answer() == MemoryQuiz.Answer.TILES) {
            TileRow answer = new TileRow(ANSWER_TILE, 0);
            answer.show(List.of(), question.length(), false);
            card.add(answer);
            card.add(Box.createVerticalStrut(12));
            picker.onChange(() -> {
                answer.show(picker.picked(), question.length(), false);
                pickerChanged();
            });
            picker.reset(TilePicker.Mode.SEQUENCE, question.length());
            picker.setEnabled(true);
            picker.setAlignmentX(LEFT_ALIGNMENT);
            card.add(picker);
            card.add(Box.createVerticalStrut(14));
            JButton submit = Theme.button("確定", Theme.RED);
            submit.addActionListener(e -> judge(item, picker.picked(), -1, false));
            JButton undo = Theme.button("退一張", Theme.BUTTON);
            undo.addActionListener(e -> picker.undo());
            JButton clear = Theme.button("清除", Theme.BUTTON);
            clear.addActionListener(e -> picker.clear());
            card.add(ShareDialog.buttonRow(submit, undo, clear));
            getRootPane().setDefaultButton(submit);
        } else {
            List<JComponent> buttons = new ArrayList<>();
            for (int i = 0; i < question.choices().size(); i++) {
                int choice = i;
                JButton button = Theme.button(question.choices().get(i), Theme.BUTTON);
                button.addActionListener(e -> judge(item, null, choice, false));
                buttons.add(button);
            }
            card.add(ShareDialog.buttonRow(buttons.toArray(new JComponent[0])));
            getRootPane().setDefaultButton(null);
        }
        refresh();
        beginAnswer(item);
    }

    private void beginAnswer(Item item) {
        answering = true;
        answerStart = System.nanoTime();
        pickerChanged();
        countDown(challenge.drill().answerSeconds(), "作答", () -> judge(item, null, -1, true));
    }

    /** 點牌題沒點滿、看聽一張都沒選時，「確定」按不下去。 */
    private void pickerChanged() {
        JButton submit = getRootPane().getDefaultButton();
        if (submit == null || !answering || !"確定".equals(submit.getText())) {
            return;
        }
        Item item = items.get(index);
        boolean ready = item.hand() != null ? !picker.picked().isEmpty() : picker.full();
        submit.setEnabled(ready);
    }

    /** 判對錯、記下秒數，然後秀正解。timeout 時 picked 與 choice 都不算。 */
    private void judge(Item item, List<Tile> picked, int choice, boolean timeout) {
        if (!answering) {
            return;
        }
        answering = false;
        stopTimer();
        double limit = challenge.drill().answerSeconds();
        double used = timeout ? limit : Math.min(limit, (System.nanoTime() - answerStart) / 1e9);
        seconds.add(used);
        boolean right;
        if (timeout) {
            right = false;
        } else if (item.hand() != null) {
            right = item.hand().correct(picked);
        } else if (item.question().answer() == MemoryQuiz.Answer.TILES) {
            right = item.question().correctTiles(picked);
        } else {
            right = item.question().correctChoice(choice);
        }
        if (right) {
            correct++;
        }
        showFeedback(item, right, timeout, picked, choice);
    }

    /** 答錯時把自己答的也列出來，和正解對照；逾時沒有答案就不列。 */
    private void showFeedback(Item item, boolean right, boolean timeout, List<Tile> picked, int choice) {
        boolean showMine = !right && !timeout;
        card.removeAll();
        card.add(ResultDialog.line(challenge.label() + "　" + challenge.title(), Font.BOLD, 22f, Theme.GOLD));
        card.add(Box.createVerticalStrut(4));
        card.add(ResultDialog.line("第 " + (index + 1) + " / " + items.size() + " 題", Font.PLAIN, 14f, Theme.MUTED));
        card.add(Box.createVerticalStrut(14));
        String verdict = right ? "答對！" : timeout ? "時間到，算答錯" : "答錯了";
        card.add(ResultDialog.line(verdict, Font.BOLD, 26f, right ? Theme.GOLD : Theme.TEXT));
        card.add(Box.createVerticalStrut(10));
        if (item.hand() != null) {
            card.add(ResultDialog.line("這手牌：", Font.PLAIN, 15f, Theme.MUTED));
            TileRow hand = new TileRow(HAND_TILE, Theme.scaled(CARD_WIDTH + 120));
            hand.show(item.hand().tiles(), 0, false);
            card.add(hand);
            card.add(Box.createVerticalStrut(8));
            card.add(ResultDialog.line("聽 " + item.hand().waits().size() + " 種：", Font.PLAIN, 15f, Theme.MUTED));
            TileRow waits = new TileRow(HAND_TILE, 0);
            waits.show(item.hand().waits(), 0, false);
            card.add(waits);
            if (showMine) {
                card.add(Box.createVerticalStrut(8));
                card.add(ResultDialog.line("你選的：", Font.PLAIN, 15f, Theme.MUTED));
                TileRow mine = new TileRow(ANSWER_TILE, Theme.scaled(CARD_WIDTH + 120));
                mine.show(sorted(picked), 0, false);
                card.add(mine);
            }
        } else {
            MemoryQuiz.Question question = item.question();
            card.add(ResultDialog.line(question.prompt(), Font.PLAIN, 15f, Theme.MUTED));
            card.add(Box.createVerticalStrut(6));
            card.add(ResultDialog.line("正解：" + question.solution(), Font.BOLD, 18f, Theme.TEXT));
            if (question.answer() == MemoryQuiz.Answer.TILES) {
                TileRow tiles = new TileRow(ANSWER_TILE, 0);
                tiles.show(question.tiles(), 0, false);
                card.add(Box.createVerticalStrut(6));
                card.add(tiles);
                if (showMine) {
                    card.add(Box.createVerticalStrut(8));
                    card.add(ResultDialog.line("你點的：", Font.PLAIN, 15f, Theme.MUTED));
                    TileRow mine = new TileRow(ANSWER_TILE, 0);
                    mine.show(picked, 0, false);
                    card.add(mine);
                }
            } else if (showMine && choice >= 0) {
                card.add(Box.createVerticalStrut(4));
                card.add(ResultDialog.line("你選：" + question.choices().get(choice), Font.PLAIN, 15f, Theme.MUTED));
            }
        }
        card.add(Box.createVerticalStrut(16));
        boolean last = index + 1 >= items.size();
        JButton next = Theme.button(last ? "看成績" : "下一題", Theme.RED);
        feedbackFor = index;
        next.addActionListener(e -> advanceFrom(feedbackFor));
        card.add(ShareDialog.buttonRow(next));
        getRootPane().setDefaultButton(next);
        refresh();
        int shown = index;
        timer = new Timer(FEEDBACK_MS, e -> advanceFrom(shown));
        timer.setRepeats(false);
        timer.start();
    }

    /** 看完第 shown 題的對錯，換下一題；已經換過就不再換。 */
    private void advanceFrom(int shown) {
        if (feedbackFor != shown || shown != index) {
            return;
        }
        feedbackFor = -1;
        stopTimer();
        start(index + 1);
    }

    /** 成績頁：答對幾題、平均幾秒，加上 settle 寫的過關、星與獎金。 */
    private void finish() {
        stopTimer();
        double average = seconds.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        outcome = new Outcome(correct, items.size(), average);
        String note = settle.apply(outcome);
        card.removeAll();
        card.add(ResultDialog.line(challenge.label() + "　" + challenge.title(), Font.BOLD, 22f, Theme.GOLD));
        card.add(Box.createVerticalStrut(14));
        boolean passed = correct >= challenge.drill().passCount();
        card.add(ResultDialog.line(passed ? "過關！" : "沒有過關", Font.BOLD, 28f, passed ? Theme.GOLD : Theme.TEXT));
        card.add(Box.createVerticalStrut(8));
        card.add(ResultDialog.line("答對 " + correct + " / " + items.size() + "（要答對 " + challenge.drill().passCount()
                + "）・平均 " + String.format(Locale.ROOT, "%.1f", average) + " 秒", Font.PLAIN, 17f, Theme.TEXT));
        card.add(Box.createVerticalStrut(10));
        for (String line : note.split("\n")) {
            card.add(ResultDialog.line(line, Font.PLAIN, 15f, passed ? Theme.TEXT : Theme.MUTED));
        }
        card.add(Box.createVerticalStrut(18));
        JButton done = Theme.button("回到修業檔案", Theme.RED);
        done.addActionListener(e -> dispose());
        card.add(ShareDialog.buttonRow(done));
        getRootPane().setDefaultButton(done);
        refresh();
    }

    // ---- 共用 ----

    /** 倒數 total 秒，label 例如「作答」；時間到呼叫 done。 */
    private void countDown(int total, String label, Runnable done) {
        stopTimer();
        long end = System.nanoTime() + total * 1_000_000_000L;
        Runnable show = () -> {
            long left = Math.max(0, end - System.nanoTime());
            int secondsLeft = (int) Math.ceil(left / 1e9);
            countdown.setText(label + "　剩 " + secondsLeft + " 秒");
            countdown.setForeground(secondsLeft <= 3 ? Theme.RED : Theme.GOLD);
        };
        show.run();
        timer = new Timer(TICK_MS, e -> {
            show.run();
            if (System.nanoTime() >= end) {
                stopTimer();
                done.run();
            }
        });
        timer.start();
    }

    private void stopTimer() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    private static List<Tile> sorted(List<Tile> tiles) {
        List<Tile> copy = new ArrayList<>(tiles);
        copy.sort(Comparator.comparingInt(Tile::index));
        return copy;
    }

    /** 換掉最下面的按鈕列。 */
    private void replaceButtons(JComponent buttons) {
        card.remove(card.getComponentCount() - 1);
        card.add(buttons);
        refresh();
    }

    private void refresh() {
        card.revalidate();
        card.repaint();
    }

    private void confirmQuit() {
        if (outcome != null) {
            dispose();
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "現在離開，這關修練算沒有過關。要離開嗎？", "離開修練",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            stopTimer();
            dispose();
        }
    }

    @Override
    public void dispose() {
        stopTimer();
        super.dispose();
    }
}
