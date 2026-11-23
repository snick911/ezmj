package ezmj.ui;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import ezmj.online.VoiceClip;

/**
 * 按住說話：按下開始錄音，放開就送出去廣播；在按鈕外面放開就取消，錄滿 10 秒自動送出。和網頁版的同名類別一樣，
 * 不到 0.3 秒的不送，按下去之前先看自己的頻率限制。按鈕在 TablePanel，這裡只管錄音與送出，都在 EDT 上呼叫。
 *
 * 錄音在背景執行緒用 Java Sound 的 TargetDataLine（見 Microphone），每次錄完就關掉麥克風。
 * Windows 的隱私權設定不准桌面程式用麥克風時，錄到的是一片安靜，這裡分不出來。
 */
final class VoiceTalk {

    /** 按鈕與提示，由 TablePanel 提供，都在 EDT 上呼叫。 */
    interface View {
        /** 錄音中按鈕旁邊的字；recording 時按鈕變紅。 */
        void talkState(String label, boolean recording);

        /** 送不出去的原因，和聊天的提示放在同一個地方。 */
        void talkNotice(String text);
    }

    private static final int TICK_MS = 200;

    /** 錄音的來源，16 位元、小端序、單聲道。平常是麥克風；實機試打（OnlineTrial）換成固定的音，才不會錄到旁邊的聲音。 */
    interface Microphone extends AutoCloseable {
        /** 打開並開始收音，回傳取樣率；打不開回傳 0。 */
        int open();

        /** 讀滿 buffer 才回來，回傳讀到幾個位元組。 */
        int read(byte[] buffer);

        @Override
        void close();
    }

    /** 每次按下說話就拿一個新的。 */
    static Supplier<Microphone> microphones = SoundMicrophone::new;

    /** Java Sound 的麥克風：先試 8 kHz，不支援就用比較高的取樣率，由 VoiceClip 降下來。 */
    private static final class SoundMicrophone implements Microphone {
        private static final int[] RATES = {VoiceClip.RATE, 16_000, 44_100, 48_000};
        private TargetDataLine line;

        @Override
        public int open() {
            for (int rate : RATES) {
                AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
                try {
                    TargetDataLine opened = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, format));
                    opened.open(format);
                    opened.start();
                    line = opened;
                    return rate;
                } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                    // 換下一個取樣率
                }
            }
            return 0;
        }

        @Override
        public int read(byte[] buffer) {
            return line.read(buffer, 0, buffer.length);
        }

        @Override
        public void close() {
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    /** 一次錄音。錄音的執行緒與 EDT 都會碰，欄位用 volatile。 */
    private static final class Recording {
        volatile boolean stopped;
        volatile boolean ready;
        volatile long readyAt;
        volatile int rate;
        /** 停下來時要不要送；錄滿自動停下來的是 true。 */
        volatile boolean send;
    }

    private final Consumer<byte[]> sender;
    private final VoiceClip.Limiter limiter = new VoiceClip.Limiter();
    private final Timer ticker = new Timer(TICK_MS, e -> tick());
    private View view;
    private Recording recording;
    /** 房間裡有別的真人連著線。 */
    private boolean audience = true;

    /** sender 收到壓好的語音，負責送出去，在 EDT 上呼叫。 */
    VoiceTalk(Consumer<byte[]> sender) {
        this.sender = sender;
    }

    void attach(View view) {
        this.view = view;
    }

    boolean isRecording() {
        return recording != null;
    }

    /** 有人補位上場、斷線或離開時，TablePanel 照房間的人更新。還在錄的照樣錄完。 */
    void setAudience(boolean anyoneToHear) {
        audience = anyoneToHear;
    }

    /** 按下說話的按鈕。桌上沒有別的真人時只說明沒有人聽，不開麥克風。 */
    void press() {
        if (recording != null || view == null) {
            return;
        }
        if (!audience) {
            view.talkNotice(VoiceClip.NOBODY_TEXT);
            return;
        }
        long waitMs = limiter.waitMs(nowMs());
        if (waitMs > 0) {
            view.talkNotice(VoiceClip.tooFastText(waitMs));
            return;
        }
        Recording mine = new Recording();
        recording = mine;
        view.talkState(VoiceClip.PREPARING_LABEL, true);
        Thread thread = new Thread(() -> record(mine), "ezmj-mic");
        thread.setDaemon(true);
        thread.start();
    }

    /** 放開。send 是在按鈕上放開，false 是移到外面放開，錄到的不要。 */
    void release(boolean send) {
        Recording done = recording;
        if (done == null) {
            return;
        }
        recording = null;
        ticker.stop();
        view.talkState(VoiceClip.TALK_LABEL, false);
        done.send = send;
        done.stopped = true;
        if (!done.ready && send) {
            // 麥克風還沒開好就放開了，多半是按一下而已
            view.talkNotice(VoiceClip.HOLD_TEXT);
        } else if (done.ready && !send) {
            view.talkNotice(VoiceClip.CANCELLED_TEXT);
        }
    }

    /** 牌桌換掉或連線斷了：還在錄的丟掉，麥克風關掉。 */
    void stop() {
        if (recording != null) {
            recording.send = false;
            recording.stopped = true;
            recording = null;
        }
        ticker.stop();
    }

    private void tick() {
        Recording current = recording;
        if (current == null || !current.ready) {
            return;
        }
        int ms = (int) (nowMs() - current.readyAt);
        if (ms >= VoiceClip.MAX_MS) {
            release(true);
            return;
        }
        view.talkState(VoiceClip.recordingLabel(ms), true);
    }

    /** 錄音的執行緒：打開麥克風，錄到放開或錄滿，再回 EDT 送出。 */
    private void record(Recording mine) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try (Microphone mic = microphones.get()) {
            int rate = mic.open();
            if (rate <= 0) {
                SwingUtilities.invokeLater(() -> failed(mine));
                return;
            }
            int maxBytes = rate * VoiceClip.MAX_MS / 1000 * 2;
            byte[] buffer = new byte[rate / 20 * 2];
            mine.rate = rate;
            mine.readyAt = nowMs();
            mine.ready = true;
            SwingUtilities.invokeLater(() -> started(mine));
            while (!mine.stopped && pcm.size() < maxBytes) {
                int read = mic.read(buffer);
                pcm.write(buffer, 0, Math.max(0, Math.min(read, maxBytes - pcm.size())));
            }
        }
        float[] samples = toSamples(pcm.toByteArray());
        SwingUtilities.invokeLater(() -> finished(mine, samples));
    }

    private void failed(Recording mine) {
        if (recording == mine) {
            recording = null;
            ticker.stop();
            view.talkState(VoiceClip.TALK_LABEL, false);
        }
        view.talkNotice(NO_MIC_TEXT);
    }

    static final String NO_MIC_TEXT = "找不到可以用的麥克風";

    private void started(Recording mine) {
        if (recording == mine) {
            ticker.start();
            tick();
        }
    }

    /** 錄好了。放開時才知道要不要送；錄滿 10 秒時還按著，就當作放開送出。 */
    private void finished(Recording mine, float[] samples) {
        if (recording == mine) {
            release(true);
        }
        if (!mine.send) {
            return;
        }
        if (VoiceClip.millis(samples.length, mine.rate) < VoiceClip.MIN_MS) {
            view.talkNotice(VoiceClip.HOLD_TEXT);
            return;
        }
        long now = nowMs();
        if (!limiter.tryRecord(now)) {
            view.talkNotice(VoiceClip.tooFastText(limiter.waitMs(now)));
            return;
        }
        sender.accept(VoiceClip.encode(samples, mine.rate));
    }

    private static float[] toSamples(byte[] pcm) {
        float[] samples = new float[pcm.length / 2];
        for (int n = 0; n < samples.length; n++) {
            samples[n] = (short) ((pcm[n * 2] & 0xFF) | pcm[n * 2 + 1] << 8) / 32768f;
        }
        return samples;
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }
}
