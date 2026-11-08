package ezmj.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
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
import ezmj.model.VoiceStyle;

/**
 * 喊牌語音：用 Windows 內建的中文語音合成，第一次需要時產生語音檔，之後直接讀檔。
 * 程式本身不帶任何語音檔。沒有中文語音或不是 Windows 時，就回報無法使用，遊戲其他部分不受影響。
 * <p>
 * 內建語音念起來很平，所以用 SSML 照 VoiceStyle 調高音調、加快、加大聲，每個字再照它的高低（0 到 9）
 * 各自提高，重點字拉高，聽起來才有起伏。每種風格的語音檔存在自己的資料夾，換回來不用重做。
 */
public final class VoiceLines {

    /** levels 是每個字的高低，0 到 9，和 text 一樣長。 */
    public enum Line {
        CHOW("吃", "6"), PONG("碰", "6"), KONG("槓", "6"), WIN("胡", "7"), SELF_WIN("自摸", "48"),
        LISTEN("聽牌", "36"), DRAWN_GAME("流局", "33");

        private final String text;
        private final String levels;

        Line(String text, String levels) {
            this.text = text;
            this.levels = levels;
        }

        public String text() {
            return text;
        }

        public String levels() {
            return levels;
        }

        private String fileName() {
            return name().toLowerCase(Locale.ROOT) + ".wav";
        }
    }

    /** 一句要念的話：字與每個字的高低。 */
    public record Phrase(String text, String levels) {
    }

    private static final int RATE = 44_100;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final int SILENCE = 300;
    private static final int GENERATE_TIMEOUT_SECONDS = 90;

    private static final String[] DRAGON_TEXTS = {"紅中", "發財", "白板"};
    /** 喊牌都是兩個字，後面那個字（萬、筒、風、中…）拉高。 */
    private static final String TILE_LEVELS = "35";
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
    /** CLIPS 現在是哪個風格的；還沒載入是 null。只在 PREPARER 的執行緒上讀寫。 */
    private static VoiceStyle loadedStyle;

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

    /** 全部要準備的語音：女聲檔名對應念的話。 */
    public static Map<String, Phrase> phrases() {
        Map<String, Phrase> phrases = new LinkedHashMap<>();
        for (Line line : Line.values()) {
            phrases.put(line.fileName(), new Phrase(line.text(), line.levels()));
        }
        for (int index = 0; index < Tile.KINDS; index++) {
            Tile tile = Tile.fromIndex(index);
            phrases.put(tileFile(tile), new Phrase(tileText(tile), TILE_LEVELS));
        }
        return phrases;
    }

    /**
     * 一句話在這個風格下的 SSML 內容：每個字包一層 prosody，音高照它的高低；逗號不調。
     * 外面的 speak 與語速、音量由產生語音的腳本包上去。屬性用單引號，腳本裡不能有雙引號。
     */
    public static String ssmlBody(Phrase phrase, VoiceStyle style) {
        StringBuilder body = new StringBuilder();
        String text = phrase.text();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '，') {
                body.append(c);
                continue;
            }
            int level = Character.digit(phrase.levels().charAt(i), 10);
            body.append("<prosody pitch='+").append(style.pitch(level)).append("%'>").append(c).append("</prosody>");
        }
        if (style.exclaim()) {
            body.append('！');
        }
        return body.toString();
    }

    private static ExecutorService daemonThread(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 這個風格的語音檔放在哪裡，例如 .ezmj\voice\hype。 */
    private static Path styleDir(Path root, VoiceStyle style) {
        return root.resolve(style.name().toLowerCase(Locale.ROOT));
    }

    /**
     * 準備好這個風格的全部語音，換風格時換掉；已經準備好就不再重做。缺任何一個語音檔就整個風格重新產生。
     * 做不到時在背景執行緒呼叫 onUnavailable。root 是 .ezmj\voice。
     */
    public static void prepare(Path root, VoiceStyle style, Runnable onUnavailable) {
        PREPARER.execute(() -> {
            if (style == loadedStyle && !CLIPS.isEmpty()) {
                return;
            }
            removeOldLayout(root);
            Path dir = styleDir(root, style);
            try {
                if (!allPresent(dir)) {
                    generate(dir, style);
                }
                Map<String, Clip> loaded = new HashMap<>();
                for (Gender gender : Gender.values()) {
                    for (String file : phrases().keySet()) {
                        String voice = voiceFile(file, gender);
                        loaded.put(voice, load(dir.resolve(voice)));
                    }
                }
                Map<String, Clip> old = new HashMap<>(CLIPS);
                CLIPS.clear();
                CLIPS.putAll(loaded);
                loadedStyle = style;
                setVolume(volume);
                // 舊的 Clip 還在播放的話要等一下才關得掉，這裡是背景執行緒，不影響牌局
                old.values().forEach(Clip::close);
            } catch (IOException | UnsupportedAudioFileException | LineUnavailableException
                     | IllegalArgumentException | SecurityException e) {
                onUnavailable.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onUnavailable.run();
            }
        });
    }

    /** 換好風格之後喊一聲試聽。排在準備語音的後面，所以要重新產生時會等產生完才響。 */
    public static void previewWhenReady(Line line, Gender gender) {
        PREPARER.execute(() -> play(line, gender));
    }

    /** 0.11 以前沒有風格，語音檔直接放在 .ezmj\voice。現在用不到，刪掉免得佔地方。 */
    private static void removeOldLayout(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.{wav,tmp}")) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            // 刪不掉就留著，不影響語音
        }
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
        return phrases().keySet().stream().allMatch(file -> Files.isRegularFile(dir.resolve(voiceFile(file, gender))));
    }

    private static void generate(Path dir, VoiceStyle style) throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new IOException("只有 Windows 有內建語音");
        }
        Files.createDirectories(dir);
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", script(dir, style))
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
        for (String file : phrases().keySet()) {
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

    /** 放進 PowerShell 單引號字串裡，單引號要寫兩次。 */
    private static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * 優先用台灣語音，其次任何中文語音。先寫暫存檔再改名，中斷時不會留下不完整的語音檔。
     * 舊介面通常只有女聲，男聲要走 Windows 新版的語音介面；這部分失敗就略過，之後由女聲壓低音調代替。
     * 兩邊都念 SSML：每句的內容在 $p，外面包上 speak 與這個風格的語速、音量。
     * 這段腳本會直接放進命令列，不能有雙引號；命令列有長度上限，每句的內容只列一次。
     */
    private static String script(Path dir, VoiceStyle style) {
        String head = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-TW'>"
                + "<prosody rate='+" + style.rate() + "%' volume='+" + style.volume() + "%'>";
        StringBuilder script = new StringBuilder("$h = " + quoted(head) + ";"
                + "$e = " + quoted("</prosody></speak>") + ";"
                + "$p = @(");
        StringBuilder list = new StringBuilder();
        phrases().forEach((file, phrase) -> {
            if (list.length() > 0) {
                list.append(",");
            }
            list.append("@(").append(quoted(file)).append(",").append(quoted(ssmlBody(phrase, style))).append(")");
        });
        script.append(list).append(");")
                .append("Add-Type -AssemblyName System.Speech;")
                .append("$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;")
                .append("$voices = $s.GetInstalledVoices() | Where-Object { $_.Enabled };")
                .append("$v = $voices | Where-Object { $_.VoiceInfo.Culture.Name -eq 'zh-TW' } | Select-Object -First 1;")
                .append("if (-not $v) { $v = $voices | Where-Object { $_.VoiceInfo.Culture.Name -like 'zh*' } | Select-Object -First 1 };")
                .append("if (-not $v) { exit 3 };")
                .append("$s.SelectVoice($v.VoiceInfo.Name);")
                .append("$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(").append(RATE).append(",")
                .append(" [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,")
                .append(" [System.Speech.AudioFormat.AudioChannel]::Mono);")
                .append("$d = ").append(quoted(dir.toString())).append(";")
                .append("foreach ($x in $p) {")
                .append(" $o = Join-Path $d $x[0];")
                .append(" $s.SetOutputToWaveFile($o + '.tmp', $f);")
                .append(" $s.SpeakSsml($h + $x[1] + $e);")
                .append(" $s.SetOutputToNull();")
                .append(" Move-Item -Force -LiteralPath ($o + '.tmp') -Destination $o };")
                .append("$s.Dispose();")
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
                .append(" foreach ($x in $p) {")
                .append(" $o = Join-Path $d ('male_' + $x[0]);")
                .append(" $task = $t.Invoke($null, @($w.SynthesizeSsmlToStreamAsync($h + $x[1] + $e)));")
                .append(" $task.Wait();")
                .append(" $from = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($task.Result);")
                .append(" $to = [System.IO.File]::Create($o + '.tmp');")
                .append(" $from.CopyTo($to);")
                .append(" $to.Close();")
                .append(" $from.Close();")
                .append(" Move-Item -Force -LiteralPath ($o + '.tmp') -Destination $o }");
        return script.append("} catch { exit 0 }").toString();
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

    /** 把女聲壓低成男聲的音域，沒有男聲語音時用。念得也會跟著變慢。傳入 16 位元單聲道。 */
    public static byte[] lowerPitch(byte[] pcm) {
        return shiftPitch(pcm, MALE_PITCH);
    }

    /** 重新取樣來改音調。比例大於 1 音調變高、長度變短。 */
    private static byte[] shiftPitch(byte[] pcm, double ratio) {
        int samples = pcm.length / 2;
        byte[] shifted = new byte[((int) (samples / ratio) + 1) * 2];
        int length = 0;
        for (double position = 0; position < samples - 1 && length * 2 < shifted.length; length++) {
            int index = (int) position;
            double fraction = position - index;
            int value = (int) Math.round(sample(pcm, index) * (1 - fraction) + sample(pcm, index + 1) * fraction);
            shifted[length * 2] = (byte) value;
            shifted[length * 2 + 1] = (byte) (value >> 8);
            position += ratio;
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
