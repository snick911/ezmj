package ezmj.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import ezmj.online.VoiceClip;

/**
 * 播放玩家的語音訊息：一則播完才播下一則，在背景執行緒播，不會卡住畫面。播的時候背景音樂壓低，播完再回來。
 * 自動播放時排得太多（後面還有 MAX_WAIT_MS 以上）就不播，聊天區照樣可以重播；重播會停掉正在播與排著的。
 * 和網頁版的同名類別一樣。電腦沒有音效裝置就安靜地不出聲。
 */
final class VoicePlayer {

    private static final long MAX_WAIT_MS = 20_000;
    private static final AudioFormat FORMAT = new AudioFormat(VoiceClip.RATE, 16, 1, true, false);
    /** 一次寫 0.1 秒，停掉時最多再響這麼久。 */
    private static final int CHUNK_BYTES = VoiceClip.RATE / 10 * 2;

    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ezmj-voice");
        thread.setDaemon(true);
        return thread;
    });
    /** stopAll 時加一，排著的與正在播的看到不一樣就不播了。 */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    /** 排著的全部播完的時間（System.nanoTime 的毫秒）。 */
    private static final AtomicLong QUEUED_UNTIL = new AtomicLong();
    private static final AtomicInteger PLAYING = new AtomicInteger();

    private VoicePlayer() {
    }

    /** samples 是 VoiceClip.decode 解開的。interrupt 是按重播：先停掉其他的。排太多就回傳 false。 */
    static boolean play(float[] samples, int volumePercent, boolean interrupt) {
        if (interrupt) {
            stopAll();
        }
        long now = System.nanoTime() / 1_000_000;
        long start = Math.max(now, QUEUED_UNTIL.get());
        if (start - now > MAX_WAIT_MS) {
            return false;
        }
        QUEUED_UNTIL.set(start + VoiceClip.millis(samples.length, VoiceClip.RATE));
        int generation = GENERATION.get();
        byte[] pcm = toPcm(samples, volumePercent);
        PLAYER.execute(() -> playNow(pcm, generation));
        return true;
    }

    /** 停掉正在播與排著的，例如牌桌換掉、按了重播或靜音。 */
    static void stopAll() {
        GENERATION.incrementAndGet();
        QUEUED_UNTIL.set(0);
    }

    private static void playNow(byte[] pcm, int generation) {
        if (generation != GENERATION.get()) {
            return;
        }
        if (PLAYING.getAndIncrement() == 0) {
            BackgroundMusic.duck(true);
        }
        try (SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT)) {
            line.open(FORMAT);
            line.start();
            for (int at = 0; at < pcm.length && generation == GENERATION.get(); at += CHUNK_BYTES) {
                line.write(pcm, at, Math.min(CHUNK_BYTES, pcm.length - at));
            }
            if (generation == GENERATION.get()) {
                line.drain();
            } else {
                line.flush();
            }
            line.stop();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            // 沒有音效裝置就不出聲
        } finally {
            if (PLAYING.decrementAndGet() == 0) {
                BackgroundMusic.duck(false);
            }
        }
    }

    /** 音量照百分比換算，和音效、喊牌一樣是平方，拉到一半聽起來也差不多是一半。 */
    private static byte[] toPcm(float[] samples, int volumePercent) {
        double gain = Math.pow(Math.max(0, Math.min(100, volumePercent)) / 100.0, 2);
        byte[] pcm = new byte[samples.length * 2];
        for (int n = 0; n < samples.length; n++) {
            int value = (int) Math.round(Math.max(-1, Math.min(1, samples[n] * gain)) * Short.MAX_VALUE);
            pcm[n * 2] = (byte) value;
            pcm[n * 2 + 1] = (byte) (value >> 8);
        }
        return pcm;
    }
}
