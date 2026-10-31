package ezmj.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * 喊牌語音：用 Windows 內建的中文語音合成，第一次需要時產生語音檔，之後直接讀檔。
 * 程式本身不帶任何語音檔。沒有中文語音或不是 Windows 時，就回報無法使用，遊戲其他部分不受影響。
 */
public final class VoiceLines {

    public enum Line {
        CHOW("吃"), PONG("碰"), KONG("槓"), WIN("胡"), SELF_WIN("自摸"), LISTEN("聽牌"), DRAWN_GAME("流局");

        private final String text;

        Line(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        private String fileName() {
            return name().toLowerCase(Locale.ROOT) + ".wav";
        }
    }

    private static final int RATE = 44_100;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final int SILENCE = 300;
    private static final int GENERATE_TIMEOUT_SECONDS = 60;

    private static final Map<Line, Clip> CLIPS = new ConcurrentHashMap<>();
    /** 產生語音要好幾秒，和播放分開，產生時喊的牌才不會全部排隊等著一起響。 */
    private static final ExecutorService PREPARER = daemonThread("ezmj-voice-prepare");
    private static final ExecutorService PLAYER = daemonThread("ezmj-voice");
    private static Clip playing;
    private static int volume = 100;

    private VoiceLines() {
    }

    private static ExecutorService daemonThread(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 準備好全部語音，已經準備好就不再重做。做不到時在背景執行緒呼叫 onUnavailable。 */
    public static void prepare(Path dir, Runnable onUnavailable) {
        PREPARER.execute(() -> {
            // 全部載入成功才會放進 CLIPS，所以有內容就是都好了；重新載入會多佔音訊資源
            if (!CLIPS.isEmpty()) {
                return;
            }
            try {
                if (!allPresent(dir)) {
                    generate(dir);
                }
                Map<Line, Clip> loaded = new EnumMap<>(Line.class);
                for (Line line : Line.values()) {
                    loaded.put(line, load(dir.resolve(line.fileName())));
                }
                CLIPS.putAll(loaded);
                setVolume(volume);
            } catch (IOException | UnsupportedAudioFileException | LineUnavailableException
                     | IllegalArgumentException | SecurityException e) {
                onUnavailable.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onUnavailable.run();
            }
        });
    }

    public static void setVolume(int percent) {
        PLAYER.execute(() -> {
            volume = percent;
            for (Clip clip : CLIPS.values()) {
                SoundEffects.applyVolume(clip, percent);
            }
        });
    }

    /** 新的一句會打斷還沒喊完的上一句。語音還沒準備好就不出聲。 */
    public static void play(Line line) {
        PLAYER.execute(() -> {
            Clip clip = CLIPS.get(line);
            if (clip == null) {
                return;
            }
            if (playing != null) {
                playing.stop();
            }
            clip.setFramePosition(0);
            clip.start();
            playing = clip;
        });
    }

    private static boolean allPresent(Path dir) {
        return Arrays.stream(Line.values()).allMatch(line -> Files.isRegularFile(dir.resolve(line.fileName())));
    }

    private static void generate(Path dir) throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new IOException("只有 Windows 有內建語音");
        }
        Files.createDirectories(dir);
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", script(dir))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(GENERATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("產生語音逾時");
        }
        if (!allPresent(dir)) {
            throw new IOException("沒有產生語音檔");
        }
    }

    /** 優先用台灣語音，其次任何中文語音。先寫暫存檔再改名，中斷時不會留下不完整的語音檔。 */
    private static String script(Path dir) {
        StringBuilder script = new StringBuilder("Add-Type -AssemblyName System.Speech;"
                + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                + "$voices = $s.GetInstalledVoices() | Where-Object { $_.Enabled };"
                + "$v = $voices | Where-Object { $_.VoiceInfo.Culture.Name -eq 'zh-TW' } | Select-Object -First 1;"
                + "if (-not $v) { $v = $voices | Where-Object { $_.VoiceInfo.Culture.Name -like 'zh*' } | Select-Object -First 1 };"
                + "if (-not $v) { exit 3 };"
                + "$s.SelectVoice($v.VoiceInfo.Name);"
                + "$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(" + RATE + ","
                + " [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,"
                + " [System.Speech.AudioFormat.AudioChannel]::Mono);");
        for (Line line : Line.values()) {
            String file = dir.resolve(line.fileName()).toString().replace("'", "''");
            script.append("$s.SetOutputToWaveFile('").append(file).append(".tmp', $f);")
                    .append("$s.Speak('").append(line.text()).append("');")
                    .append("$s.SetOutputToNull();")
                    .append("Move-Item -Force -LiteralPath '").append(file).append(".tmp' -Destination '")
                    .append(file).append("';");
        }
        return script.append("$s.Dispose()").toString();
    }

    private static Clip load(Path file) throws IOException, UnsupportedAudioFileException, LineUnavailableException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile());
             AudioInputStream converted = AudioSystem.getAudioInputStream(FORMAT, source)) {
            byte[] samples = trimSilence(converted.readAllBytes());
            if (samples.length == 0) {
                throw new IOException("語音檔是空的: " + file);
            }
            Clip clip = AudioSystem.getClip();
            clip.open(FORMAT, samples, 0, samples.length);
            return clip;
        }
    }

    /** 語音前後常有一段空白，修掉喊牌才跟得上畫面。留一點頭尾，結尾再淡出避免爆音。傳入 16 位元單聲道。 */
    public static byte[] trimSilence(byte[] pcm) {
        int samples = pcm.length / 2;
        int first = 0;
        while (first < samples && Math.abs(sample(pcm, first)) < SILENCE) {
            first++;
        }
        if (first == samples) {
            return new byte[0];
        }
        int last = samples - 1;
        while (Math.abs(sample(pcm, last)) < SILENCE) {
            last--;
        }
        int start = Math.max(0, first - RATE / 100);
        int end = Math.min(samples, last + RATE / 25);
        byte[] trimmed = Arrays.copyOfRange(pcm, start * 2, end * 2);
        int fade = Math.min(end - start, RATE / 200);
        int length = end - start;
        for (int i = 0; i < fade; i++) {
            int index = length - 1 - i;
            int value = sample(trimmed, index) * i / fade;
            trimmed[index * 2] = (byte) value;
            trimmed[index * 2 + 1] = (byte) (value >> 8);
        }
        return trimmed;
    }

    private static int sample(byte[] pcm, int index) {
        return (short) ((pcm[index * 2] & 0xFF) | (pcm[index * 2 + 1] << 8));
    }
}
