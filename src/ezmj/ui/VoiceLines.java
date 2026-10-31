package ezmj.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

import ezmj.model.Gender;
import ezmj.model.Tile;

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

    private static final String[] DRAGON_TEXTS = {"紅中", "發財", "白板"};
    private static final double EXCITED_PITCH_START = 1.25;
    private static final double EXCITED_PITCH_END = 1.5;
    private static final double EXCITED_DRIVE = 1.6;
    private static final double MALE_PITCH = .76;
    /** 比這小的語音檔只有檔頭，沒念出東西。 */
    private static final int MIN_VOICE_FILE_BYTES = 1024;

    /** 依檔名存每一句的 Clip。不能共用一個 Clip 換內容：還在播放時關閉要等上一兩秒，語音會跟不上牌局。 */
    private static final Map<String, Clip> CLIPS = new ConcurrentHashMap<>();
    /** 產生語音要好幾秒，和播放分開，產生時喊的牌才不會全部排隊等著一起響。 */
    private static final ExecutorService PREPARER = daemonThread("ezmj-voice-prepare");
    private static final ExecutorService PLAYER = daemonThread("ezmj-voice");
    private static Clip playing;
    private static int volume = 100;

    private VoiceLines() {
    }

    /** 喊牌時的講法。風牌和三元牌單念一個字聽不清楚，用牌桌上的叫法。 */
    public static String tileText(Tile tile) {
        switch (tile.suit()) {
            case WIND:   return tile.label() + "風";
            case DRAGON: return DRAGON_TEXTS[tile.rank() - 1];
            default:     return tile.label();
        }
    }

    private static String tileFile(Tile tile) {
        return "tile_" + tile.index() + ".wav";
    }

    /** 女聲直接用基本檔名，男聲多一個前綴。 */
    private static String voiceFile(String file, Gender gender) {
        return gender == Gender.MALE ? "male_" + file : file;
    }

    /** 全部要準備的語音：女聲檔名對應念的內容。 */
    private static Map<String, String> texts() {
        Map<String, String> texts = new LinkedHashMap<>();
        for (Line line : Line.values()) {
            texts.put(line.fileName(), line.text());
        }
        for (int index = 0; index < Tile.KINDS; index++) {
            Tile tile = Tile.fromIndex(index);
            texts.put(tileFile(tile), tileText(tile));
        }
        return texts;
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
                Map<String, Clip> loaded = new HashMap<>();
                for (Gender gender : Gender.values()) {
                    for (String file : texts().keySet()) {
                        String voice = voiceFile(file, gender);
                        loaded.put(voice, load(dir.resolve(voice), file.equals(Line.SELF_WIN.fileName())));
                    }
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

    public static void play(Line line, Gender gender) {
        playFile(voiceFile(line.fileName(), gender));
    }

    /** 喊出打出的這張牌。花牌不會被打出來，沒有語音。 */
    public static void play(Tile tile, Gender gender) {
        playFile(voiceFile(tileFile(tile), gender));
    }

    /** 新的一句會打斷還沒喊完的上一句。語音還沒準備好就不出聲。 */
    private static void playFile(String file) {
        PLAYER.execute(() -> {
            Clip clip = CLIPS.get(file);
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
        return allPresent(dir, Gender.FEMALE) && allPresent(dir, Gender.MALE);
    }

    private static boolean allPresent(Path dir, Gender gender) {
        return texts().keySet().stream().allMatch(file -> Files.isRegularFile(dir.resolve(voiceFile(file, gender))));
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
        if (!allPresent(dir, Gender.FEMALE)) {
            throw new IOException("沒有產生語音檔");
        }
        deriveMissingMale(dir);
    }

    /** 男聲語音不是每台電腦都有，也可能個別句子念不出來。缺的就用女聲壓低音調代替。 */
    private static void deriveMissingMale(Path dir) throws IOException {
        for (String file : texts().keySet()) {
            Path male = dir.resolve(voiceFile(file, Gender.MALE));
            if (Files.isRegularFile(male) && Files.size(male) >= MIN_VOICE_FILE_BYTES) {
                continue;
            }
            byte[] lowered;
            try (AudioInputStream source = AudioSystem.getAudioInputStream(dir.resolve(file).toFile());
                 AudioInputStream converted = AudioSystem.getAudioInputStream(FORMAT, source)) {
                lowered = lowerPitch(converted.readAllBytes());
            } catch (UnsupportedAudioFileException e) {
                throw new IOException(e);
            }
            Path temp = dir.resolve(male.getFileName() + ".tmp");
            try (AudioInputStream stream = new AudioInputStream(
                    new ByteArrayInputStream(lowered), FORMAT, lowered.length / 2)) {
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, temp.toFile());
            }
            Files.move(temp, male, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 優先用台灣語音，其次任何中文語音。先寫暫存檔再改名，中斷時不會留下不完整的語音檔。
     * 舊介面通常只有女聲，男聲要走 Windows 新版的語音介面；這部分失敗就略過，之後由女聲壓低音調代替。
     * 這段腳本會直接放進命令列，不能有雙引號。
     */
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
                + " [System.Speech.AudioFormat.AudioChannel]::Mono);"
                + "$d = '" + dir.toString().replace("'", "''") + "';"
                + "function say($n, $t) {"
                + " $p = Join-Path $d $n;"
                + " $s.SetOutputToWaveFile($p + '.tmp', $f);"
                + " $s.Speak($t);"
                + " $s.SetOutputToNull();"
                + " Move-Item -Force -LiteralPath ($p + '.tmp') -Destination $p };");
        texts().forEach((file, text) ->
                script.append("say '").append(file).append("' '").append(text).append("';"));
        script.append("$s.Dispose();")
                .append("try {")
                .append(" Add-Type -AssemblyName System.Runtime.WindowsRuntime;")
                .append(" $null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime];")
                .append(" $all = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | Where-Object { $_.Gender -eq 'Male' };")
                .append(" $m = $all | Where-Object { $_.Language -eq 'zh-TW' } | Select-Object -First 1;")
                .append(" if (-not $m) { $m = $all | Where-Object { $_.Language -like 'zh*' } | Select-Object -First 1 };")
                .append(" if (-not $m) { exit 0 };")
                .append(" $w = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer;")
                .append(" $w.Voice = $m;")
                .append(" $t = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {")
                .append(" $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1")
                .append(" -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1;")
                .append(" $t = $t.MakeGenericMethod([Windows.Media.SpeechSynthesis.SpeechSynthesisStream]);")
                .append(" function male($n, $x) {")
                .append(" $p = Join-Path $d $n;")
                .append(" $task = $t.Invoke($null, @($w.SynthesizeTextToStreamAsync($x)));")
                .append(" $task.Wait();")
                .append(" $from = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($task.Result);")
                .append(" $to = [System.IO.File]::Create($p + '.tmp');")
                .append(" $from.CopyTo($to);")
                .append(" $to.Close();")
                .append(" $from.Close();")
                .append(" Move-Item -Force -LiteralPath ($p + '.tmp') -Destination $p };");
        texts().forEach((file, text) -> script.append("male '").append(voiceFile(file, Gender.MALE))
                .append("' '").append(text).append("';"));
        return script.append("} catch { exit 0 }").toString();
    }

    private static Clip load(Path file, boolean excited)
            throws IOException, UnsupportedAudioFileException, LineUnavailableException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile());
             AudioInputStream converted = AudioSystem.getAudioInputStream(FORMAT, source)) {
            byte[] samples = trimSilence(converted.readAllBytes());
            if (samples.length == 0) {
                throw new IOException("語音檔是空的: " + file);
            }
            if (excited) {
                samples = excite(samples);
            }
            Clip clip = AudioSystem.getClip();
            clip.open(FORMAT, samples, 0, samples.length);
            return clip;
        }
    }

    /**
     * 把語音變得高亢興奮：音調拉高並一路上揚（念得也跟著變快），再把音量推到接近滿格。
     * Windows 內建語音不理會語氣設定，所以在這裡加工聲音，換哪個中文語音都有效。傳入 16 位元單聲道。
     */
    public static byte[] excite(byte[] pcm) {
        byte[] raised = shiftPitch(pcm, EXCITED_PITCH_START, EXCITED_PITCH_END);
        int length = raised.length / 2;
        double peak = 1;
        for (int i = 0; i < length; i++) {
            peak = Math.max(peak, Math.abs(sample(raised, i)));
        }
        byte[] excited = new byte[raised.length];
        for (int i = 0; i < length; i++) {
            double driven = Math.tanh(sample(raised, i) / peak * EXCITED_DRIVE) / Math.tanh(EXCITED_DRIVE);
            int value = (int) Math.round(driven * Short.MAX_VALUE * .95);
            excited[i * 2] = (byte) value;
            excited[i * 2 + 1] = (byte) (value >> 8);
        }
        return excited;
    }

    /** 把女聲壓低成男聲的音域，沒有男聲語音時用。念得也會跟著變慢。傳入 16 位元單聲道。 */
    public static byte[] lowerPitch(byte[] pcm) {
        return shiftPitch(pcm, MALE_PITCH, MALE_PITCH);
    }

    /** 重新取樣來改音調，音調比例從 start 線性變到 end。比例大於 1 音調變高、長度變短。 */
    private static byte[] shiftPitch(byte[] pcm, double start, double end) {
        int samples = pcm.length / 2;
        byte[] shifted = new byte[((int) (samples / Math.min(start, end)) + 1) * 2];
        int length = 0;
        for (double position = 0; position < samples - 1 && length * 2 < shifted.length; length++) {
            int index = (int) position;
            double fraction = position - index;
            int value = (int) Math.round(sample(pcm, index) * (1 - fraction) + sample(pcm, index + 1) * fraction);
            shifted[length * 2] = (byte) value;
            shifted[length * 2 + 1] = (byte) (value >> 8);
            position += start + (end - start) * position / samples;
        }
        return Arrays.copyOf(shifted, length * 2);
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
