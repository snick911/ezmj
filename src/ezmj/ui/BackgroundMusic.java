package ezmj.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;

import ezmj.model.MusicTrack;
import ezmj.ui.MusicScores.Score;
import ezmj.ui.MusicScores.Voice;

/**
 * 背景音樂：依 MusicScores 的樂譜即時合成，不需要音樂檔。
 * 合成與播放都在背景執行緒，不會卡住畫面；電腦沒有音效裝置時就安靜地不出聲。
 */
public final class BackgroundMusic {

    private static final int RATE = 44_100;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final double PEAK = .6;

    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ezmj-music");
        thread.setDaemon(true);
        return thread;
    });
    private static Clip clip;
    private static MusicTrack current = MusicTrack.OFF;
    private static int volume = 100;

    private BackgroundMusic() {
    }

    public static void select(MusicTrack track) {
        PLAYER.execute(() -> switchTo(track));
    }

    public static void setVolume(int percent) {
        PLAYER.execute(() -> {
            volume = percent;
            if (clip != null) {
                SoundEffects.applyVolume(clip, percent);
            }
        });
    }

    private static void switchTo(MusicTrack track) {
        if (track == current) {
            return;
        }
        current = track;
        if (clip != null) {
            clip.close();
            clip = null;
        }
        if (track == MusicTrack.OFF) {
            return;
        }
        try {
            byte[] samples = render(track);
            Clip next = AudioSystem.getClip();
            next.open(FORMAT, samples, 0, samples.length);
            SoundEffects.applyVolume(next, volume);
            next.loop(Clip.LOOP_CONTINUOUSLY);
            clip = next;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            clip = null;
        }
    }

    /** 每個聲部的總拍數。樂譜寫錯時各聲部會對不齊，測試靠這個檢查。 */
    public static List<Double> voiceBeats(MusicTrack track) {
        List<Double> beats = new ArrayList<>();
        for (Voice voice : MusicScores.score(track).voices()) {
            double total = 0;
            for (String note : notes(voice)) {
                total += Double.parseDouble(note.substring(note.indexOf(':') + 1));
            }
            beats.add(total);
        }
        return beats;
    }

    public static double totalBeats(MusicTrack track) {
        return MusicScores.score(track).totalBeats();
    }

    /**
     * 合成一整首：44.1 kHz、16 位元、單聲道。
     * 響到曲尾之後的餘音會繞回曲頭，循環播放時才接得起來。
     */
    public static byte[] render(MusicTrack track) {
        Score score = MusicScores.score(track);
        double secondsPerBeat = 60.0 / score.bpm();
        int length = (int) (score.totalBeats() * secondsPerBeat * RATE);
        double[] mix = new double[length];
        Random noise = new Random(track.ordinal());
        for (Voice voice : score.voices()) {
            double beat = 0;
            for (String note : notes(voice)) {
                int colon = note.indexOf(':');
                String pitch = note.substring(0, colon);
                double beats = Double.parseDouble(note.substring(colon + 1));
                if (!pitch.equals("R")) {
                    int start = (int) (beat * secondsPerBeat * RATE);
                    addNote(mix, voice, start, beats * secondsPerBeat, pitch, noise);
                }
                beat += beats;
            }
        }
        return toPcm(withEcho(mix));
    }

    private static List<String> notes(Voice voice) {
        List<String> notes = new ArrayList<>();
        for (String token : voice.notes().trim().split("\\s+")) {
            if (!token.equals("|")) {
                notes.add(token);
            }
        }
        return notes;
    }

    private static double frequency(String pitch) {
        int semitone = "C D EF G A B".indexOf(pitch.charAt(0));
        int octave = pitch.charAt(pitch.length() - 1) - '0';
        return 440 * Math.pow(2, (semitone + (octave + 1) * 12 - 69) / 12.0);
    }

    private static void addNote(double[] mix, Voice voice, int start, double seconds, String pitch, Random noise) {
        double gain = voice.gain();
        switch (voice.instrument()) {
            case PLUCK:
                struck(mix, start, frequency(pitch), gain, new double[] {1, .6, .35, .2, .12, .06}, 2.8, .35, 1.6, .001);
                break;
            case KEYS:
                struck(mix, start, frequency(pitch), gain, new double[] {1, .35, .12, .05}, 1.4, .5, 2.2, .003);
                break;
            case BASS:
                struck(mix, start, frequency(pitch), gain, new double[] {1, .4, .1}, 3, .3, 1, .003);
                break;
            case PAD:
                pad(mix, start, seconds, frequency(pitch), gain);
                break;
            case FLUTE:
                flute(mix, start, seconds, frequency(pitch), gain, noise);
                break;
            default:
                partial(mix, start, .12, 980, gain, 60, .001);
                partial(mix, start, .12, 1430, gain * .5, 90, .001);
                break;
        }
    }

    /** 撥弦、敲擊類：幾個泛音各自衰減，越高的泛音消失得越快。 */
    private static void struck(double[] mix, int start, double frequency, double gain, double[] harmonics,
                               double decay, double decayGrowth, double seconds, double attack) {
        for (int h = 0; h < harmonics.length; h++) {
            partial(mix, start, seconds, frequency * (h + 1), gain * harmonics[h],
                    decay * (1 + decayGrowth * h), attack);
        }
    }

    /** 一個會衰減的正弦波。用遞迴公式算，比每個取樣都呼叫 Math.sin 快很多。 */
    private static void partial(double[] mix, int start, double seconds, double frequency, double amplitude,
                                double decayPerSecond, double attackSeconds) {
        int samples = (int) (seconds * RATE);
        int attack = Math.max(1, (int) (attackSeconds * RATE));
        int release = 256;
        double w = 2 * Math.PI * frequency / RATE;
        double c = 2 * Math.cos(w);
        double y1 = Math.sin(-w);
        double y2 = Math.sin(-2 * w);
        double envelope = amplitude;
        double fall = Math.exp(-decayPerSecond / RATE);
        for (int n = 0; n < samples; n++) {
            double y = c * y1 - y2;
            y2 = y1;
            y1 = y;
            double shape = Math.min(1, Math.min((double) n / attack, (double) (samples - n) / release));
            mix[(start + n) % mix.length] += envelope * shape * y;
            envelope *= fall;
        }
    }

    /** 長音襯底：三個略微走音的正弦波疊在一起，緩緩進出。 */
    private static void pad(double[] mix, int start, double seconds, double frequency, double gain) {
        double release = 1;
        int samples = (int) ((seconds + release) * RATE);
        for (double detune : new double[] {1, 1.003, .997}) {
            double w = 2 * Math.PI * frequency * detune / RATE;
            for (int n = 0; n < samples; n++) {
                double t = (double) n / RATE;
                double shape = Math.min(1, Math.min(t / .8, (seconds + release - t) / release));
                mix[(start + n) % mix.length] += gain * .33 * shape
                        * (Math.sin(w * n) + .1 * Math.sin(2 * w * n));
            }
        }
    }

    /** 笛聲：吹氣聲加上慢慢出現的顫音。 */
    private static void flute(double[] mix, int start, double seconds, double frequency, double gain, Random noise) {
        double release = .12;
        int samples = (int) ((seconds + release) * RATE);
        double phase = 0;
        for (int n = 0; n < samples; n++) {
            double t = (double) n / RATE;
            double vibrato = 1 + .004 * Math.min(1, t / .5) * Math.sin(2 * Math.PI * 5 * t);
            phase += 2 * Math.PI * frequency * vibrato / RATE;
            double shape = Math.min(1, Math.min(t / .06, (seconds + release - t) / release));
            double breath = .02 * (noise.nextDouble() * 2 - 1);
            mix[(start + n) % mix.length] += gain * shape
                    * (Math.sin(phase) + .12 * Math.sin(2 * phase) + .04 * Math.sin(3 * phase) + breath);
        }
    }

    /** 加上兩道回音，讓聲音不那麼乾。 */
    private static double[] withEcho(double[] mix) {
        int first = (int) (.29 * RATE);
        int second = (int) (.47 * RATE);
        double[] out = new double[mix.length];
        for (int n = 0; n < mix.length; n++) {
            out[n] = mix[n]
                    + .22 * mix[Math.floorMod(n - first, mix.length)]
                    + .13 * mix[Math.floorMod(n - second, mix.length)];
        }
        return out;
    }

    private static byte[] toPcm(double[] mix) {
        double peak = 1e-9;
        for (double sample : mix) {
            peak = Math.max(peak, Math.abs(sample));
        }
        byte[] pcm = new byte[mix.length * 2];
        for (int n = 0; n < mix.length; n++) {
            int value = (int) (mix[n] / peak * PEAK * Short.MAX_VALUE);
            pcm[n * 2] = (byte) value;
            pcm[n * 2 + 1] = (byte) (value >> 8);
        }
        return pcm;
    }
}
