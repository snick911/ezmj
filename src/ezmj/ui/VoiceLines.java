package ezmj.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

import ezmj.model.BambooCall;
import ezmj.model.Gender;
import ezmj.model.Suit;
import ezmj.model.Tile;
import ezmj.model.VoiceStyle;

/**
 * 喊牌語音：用 Windows 內建的中文語音合成，第一次需要時產生語音檔，之後直接讀檔。
 * 程式本身不帶任何語音檔。沒有中文語音或不是 Windows 時，就回報無法使用，遊戲其他部分不受影響。
 * <p>
 * 內建語音念起來很平，所以用 SSML 照 VoiceStyle 調高音調、加快、加大聲，每個字再照它的高低（0 到 9）
 * 各自提高，重點字拉高，聽起來才有起伏。每種風格的語音檔存在自己的資料夾，換回來不用重做。
 * <p>
 * 司儀是另一個聲音，不跟著語音風格：宣布流局，連莊時新局開始說「連莊，連二拉二」。用 Windows 新版語音介面的
 * 女聲 Yating（比 Hanhan 低沉、念得穩），念得稍慢，不調音高、不加大聲，存在 .ezmj\voice\announcer。
 */
public final class VoiceLines {

    /** levels 是每個字的高低，0 到 9，和 text 一樣長。流局由司儀宣布，不在這裡。 */
    public enum Line {
        CHOW("吃", "6"), PONG("碰", "6"), KONG("槓", "6"), WIN("胡", "7"), SELF_WIN("自摸", "48"),
        LISTEN("聽牌", "36");

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

    /**
     * 一句要念的話：字與每個字的高低。gentle 的句子不加快、音量最多加 GENTLE_VOLUME，音高照樣調：
     * 女聲的「條」加快又加大聲之後會糊掉，聽起來像「支」，這樣就清楚了。
     */
    public record Phrase(String text, String levels, boolean gentle) {
        public Phrase(String text, String levels) {
            this(text, levels, false);
        }
    }

    /** 排著要念的一句，clips 是喊牌的 CLIPS 或司儀的 ANNOUNCER。droppable 的是牌名，有新的一句進來就不念了。 */
    private record Queued(Map<String, Clip> clips, String file, boolean droppable) {
    }

    private static final int RATE = 44_100;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final int SILENCE = 300;
    private static final int GENERATE_TIMEOUT_SECONDS = 90;
    /** 每個風格資料夾裡記著產生時的指紋，見 signature。 */
    private static final String SIGNATURE_FILE = "signature.txt";
    /** gentle 的句子音量最多加這麼多百分比。 */
    private static final int GENTLE_VOLUME = 20;
    private static final String SSML_HEAD =
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-TW'>";

    private static final String[] DRAGON_TEXTS = {"紅中", "發財", "白板"};
    /** 喊牌都是兩個字，後面那個字（萬、筒、風、中…）拉高。 */
    private static final String TILE_LEVELS = "35";
    private static final double MALE_PITCH = .76;
    /** 比這小的語音檔只有檔頭，沒念出東西。 */
    private static final int MIN_VOICE_FILE_BYTES = 1024;

    /** 司儀的語音檔放在 .ezmj\voice 底下的這個資料夾，和喊牌的風格分開。 */
    private static final String ANNOUNCER_DIR = "announcer";
    /** 連莊念到連幾拉幾，再多就只說「連莊」。 */
    public static final int MAX_ANNOUNCED_STREAK = 20;
    /**
     * 司儀用哪個聲音，依序找：Yating 比 Hanhan 低沉、念得較慢較穩，最像司儀；都沒有就用任何台灣女聲，再沒有就任何中文女聲。
     * 改了這裡指紋就變，司儀的語音會重新產生。
     */
    public static final List<String> ANNOUNCER_VOICES = List.of("Yating", "Hanhan");
    /** 司儀念得稍慢，不調音高、不加大聲、不加驚嘆號，聽起來穩重清楚。 */
    private static final int ANNOUNCER_RATE = -5;
    /** 司儀每句都調到一樣大聲：最大振幅是滿格的這個比例。內建語音念「流局」只有「連莊」那句的一半大。 */
    public static final double ANNOUNCER_PEAK = .9;
    private static final String DRAWN_GAME_FILE = "drawn_game.wav";
    /** 連莊超過 MAX_ANNOUNCED_STREAK 時只說「連莊」。 */
    private static final String LONG_STREAK_FILE = "streak.wav";
    private static final String[] DIGITS = {"", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    /** 依檔名存每一句的 Clip。不能共用一個 Clip 換內容：還在播放時關閉要等上一兩秒，語音會跟不上牌局。 */
    private static final Map<String, Clip> CLIPS = new ConcurrentHashMap<>();
    /** 司儀每一句的 Clip，依檔名。換喊牌風格時不動。 */
    private static final Map<String, Clip> ANNOUNCER = new ConcurrentHashMap<>();
    /** 產生語音要好幾秒，和播放分開，產生時喊的牌才不會全部排隊等著一起響。 */
    private static final ExecutorService PREPARER = daemonThread("ezmj-voice-prepare");
    private static final ScheduledExecutorService PLAYER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ezmj-voice");
        thread.setDaemon(true);
        return thread;
    });
    /** 排著等前一句念完的話。只在 PLAYER 的執行緒上讀寫，下面三個也是。 */
    private static final Deque<Queued> QUEUE = new ArrayDeque<>();
    private static Clip playing;
    /** 正在念的那句念到什麼時候（System.nanoTime）。 */
    private static long busyUntil;
    /** 等著前一句念完再念下一句的排程；沒有在等是 null。 */
    private static ScheduledFuture<?> waiting;
    private static int volume = 100;
    /** CLIPS 現在是哪個風格的；還沒載入是 null。只在 PREPARER 的執行緒上讀寫。 */
    private static VoiceStyle loadedStyle;

    private VoiceLines() {
    }

    /** 喊牌時的講法。風牌和三元牌單念一個字聽不清楚，用牌桌上的叫法。條子喊「X條」。 */
    public static String tileText(Tile tile) {
        return tileText(tile, BambooCall.TIAO);
    }

    /** 條子照 call 喊「X條」或「X索」，其他牌不受影響。 */
    public static String tileText(Tile tile, BambooCall call) {
        switch (tile.suit()) {
            case WIND:   return tile.label() + "風";
            case DRAGON: return DRAGON_TEXTS[tile.rank() - 1];
            case TIAO:   return tile.label().substring(0, 1) + call.word();
            default:     return tile.label();
        }
    }

    /** 條子喊「X索」的另外存一個檔，其他照舊。 */
    private static String tileFile(Tile tile, BambooCall call) {
        boolean suo = tile.suit() == Suit.TIAO && call == BambooCall.SUO;
        return "tile_" + tile.index() + (suo ? "_suo" : "") + ".wav";
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
            boolean bamboo = tile.suit() == Suit.TIAO;
            phrases.put(tileFile(tile, BambooCall.TIAO), new Phrase(tileText(tile, BambooCall.TIAO), TILE_LEVELS, bamboo));
            if (bamboo) {
                phrases.put(tileFile(tile, BambooCall.SUO), new Phrase(tileText(tile, BambooCall.SUO), TILE_LEVELS));
            }
        }
        return phrases;
    }

    /**
     * 一句話在這個風格下的 SSML 內容，speak 裡面的部分：外層是語速與音量，裡面每個字包一層 prosody，
     * 音高照它的高低；逗號不調。屬性用單引號，腳本裡不能有雙引號。
     */
    public static String ssmlBody(Phrase phrase, VoiceStyle style) {
        int rate = phrase.gentle() ? 0 : style.rate();
        int loudness = phrase.gentle() ? Math.min(style.volume(), GENTLE_VOLUME) : style.volume();
        StringBuilder body = new StringBuilder("<prosody rate='+" + rate + "%' volume='+" + loudness + "%'>");
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
        return body.append("</prosody>").toString();
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
     * 做不到時在背景執行緒呼叫 onUnavailable。接著準備司儀的語音，準備過就不再做。root 是 .ezmj\voice。
     */
    public static void prepare(Path root, VoiceStyle style, Runnable onUnavailable) {
        prepareStyle(root, style, onUnavailable);
        PREPARER.execute(() -> prepareAnnouncer(root));
    }

    private static void prepareStyle(Path root, VoiceStyle style, Runnable onUnavailable) {
        PREPARER.execute(() -> {
            if (style == loadedStyle && !CLIPS.isEmpty()) {
                return;
            }
            removeOldLayout(root);
            Path dir = styleDir(root, style);
            try {
                String signature = signature(style);
                if (!allPresent(dir) || !signature.equals(storedSignature(dir))) {
                    generate(dir, style);
                    Files.writeString(dir.resolve(SIGNATURE_FILE), signature, StandardCharsets.UTF_8);
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
                PLAYER.execute(VoiceLines::applyVolume);
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

    /**
     * 這個風格要念的全部內容的指紋：每句話的字、高低與風格的調法。改了說法或風格的數字，指紋就變，
     * 下次開啟會整個風格重新產生，不會繼續用舊的語音檔。
     */
    public static String signature(VoiceStyle style) {
        StringBuilder all = new StringBuilder(SSML_HEAD);
        phrases().forEach((file, phrase) -> all.append('\n').append(file).append('=').append(ssmlBody(phrase, style)));
        return sha256(all.toString());
    }

    /** 司儀要念的全部內容與挑聲音的順序的指紋，改了就重新產生司儀的語音。 */
    public static String announcerSignature() {
        StringBuilder all = new StringBuilder(SSML_HEAD).append('\n').append(ANNOUNCER_VOICES);
        announcerPhrases().forEach((file, text) -> all.append('\n').append(file).append('=').append(announcerSsml(text)));
        return sha256(all.toString());
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("這個 Java 沒有 SHA-256", e);
        }
    }

    /** 上次產生時記下的指紋；沒有或讀不到是空字串。 */
    private static String storedSignature(Path dir) {
        try {
            return Files.readString(dir.resolve(SIGNATURE_FILE), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** 1 到 99 的中文數字：2 是「二」、10 是「十」、12 是「十二」、20 是「二十」。 */
    public static String chineseNumber(int n) {
        if (n < 1 || n > 99) {
            throw new IllegalArgumentException("只念 1 到 99：" + n);
        }
        int tens = n / 10;
        String ones = DIGITS[n % 10];
        if (tens == 0) {
            return ones;
        }
        return (tens == 1 ? "" : DIGITS[tens]) + "十" + ones;
    }

    /** 連莊的局開始時司儀說的話，例如連二是「連莊，連二拉二」；超過 MAX_ANNOUNCED_STREAK 只說「連莊」。 */
    public static String streakText(int streak) {
        if (streak < 1) {
            throw new IllegalArgumentException("沒有連莊：" + streak);
        }
        if (streak > MAX_ANNOUNCED_STREAK) {
            return "連莊";
        }
        String n = chineseNumber(streak);
        return "連莊，連" + n + "拉" + n;
    }

    private static String streakFile(int streak) {
        return streak > MAX_ANNOUNCED_STREAK ? LONG_STREAK_FILE : "streak_" + streak + ".wav";
    }

    /** 司儀要準備的全部語音：檔名對應念的話。流局、連一到連 MAX_ANNOUNCED_STREAK，以及更多時只說「連莊」。 */
    public static Map<String, String> announcerPhrases() {
        Map<String, String> phrases = new LinkedHashMap<>();
        phrases.put(DRAWN_GAME_FILE, "流局");
        for (int streak = 1; streak <= MAX_ANNOUNCED_STREAK + 1; streak++) {
            phrases.put(streakFile(streak), streakText(streak));
        }
        return phrases;
    }

    /** 司儀一句話的 SSML 內容，speak 裡面的部分：只稍微放慢，音高、音量都不動，句尾不加驚嘆號。只用單引號。 */
    public static String announcerSsml(String text) {
        return "<prosody rate='" + ANNOUNCER_RATE + "%'>" + text + "</prosody>";
    }

    /** 準備司儀的語音，準備過就不再做。做不到就不出聲，喊牌照舊。只在 PREPARER 的執行緒上跑。 */
    private static void prepareAnnouncer(Path root) {
        if (!ANNOUNCER.isEmpty()) {
            return;
        }
        Path dir = root.resolve(ANNOUNCER_DIR);
        try {
            String signature = announcerSignature();
            if (!announcerPresent(dir) || !signature.equals(storedSignature(dir))) {
                generateAnnouncer(dir);
                Files.writeString(dir.resolve(SIGNATURE_FILE), signature, StandardCharsets.UTF_8);
            }
            Map<String, Clip> loaded = new HashMap<>();
            for (String file : announcerPhrases().keySet()) {
                loaded.put(file, open(normalize(read(dir.resolve(file)), ANNOUNCER_PEAK)));
            }
            ANNOUNCER.putAll(loaded);
            PLAYER.execute(VoiceLines::applyVolume);
        } catch (IOException | UnsupportedAudioFileException | LineUnavailableException
                 | IllegalArgumentException | SecurityException e) {
            // 沒有司儀就不出聲
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean announcerPresent(Path dir) {
        return announcerPhrases().keySet().stream().allMatch(file -> Files.isRegularFile(dir.resolve(file)));
    }

    private static void generateAnnouncer(Path dir) throws IOException, InterruptedException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new IOException("只有 Windows 有內建語音");
        }
        Files.createDirectories(dir);
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", announcerScript(dir))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(GENERATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("產生司儀語音逾時");
        }
        if (!announcerPresent(dir)) {
            throw new IOException("沒有產生司儀語音檔");
        }
    }

    /**
     * 產生司儀語音的 PowerShell 腳本。先用 Windows 新版的語音介面，照 ANNOUNCER_VOICES 的順序挑女聲；
     * 新版介面不能用時改用舊介面（System.Speech）的中文女聲。一樣先寫暫存檔再改名，不能有雙引號。
     */
    public static String announcerScript(Path dir) {
        StringBuilder list = new StringBuilder();
        announcerPhrases().forEach((file, text) -> {
            if (list.length() > 0) {
                list.append(",");
            }
            list.append("@(").append(quoted(file)).append(",").append(quoted(announcerSsml(text))).append(")");
        });
        String preferred = ANNOUNCER_VOICES.stream().map(VoiceLines::quoted).collect(Collectors.joining(","));
        return "$h = " + quoted(SSML_HEAD) + ";"
                + "$e = " + quoted("</speak>") + ";"
                + "$p = @(" + list + ");"
                + "$d = " + quoted(dir.toString()) + ";"
                + "$done = $false;"
                + "try {"
                + " Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                + " $null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime];"
                + " $all = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices"
                + " | Where-Object { $_.Gender -eq 'Female' -and $_.Language -like 'zh*' };"
                + " $v = $null;"
                + " foreach ($n in @(" + preferred + ")) { if (-not $v) {"
                + " $v = $all | Where-Object { $_.DisplayName -like ('*' + $n + '*') } | Select-Object -First 1 } };"
                + " if (-not $v) { $v = $all | Where-Object { $_.Language -eq 'zh-TW' } | Select-Object -First 1 };"
                + " if (-not $v) { $v = $all | Select-Object -First 1 };"
                + " if ($v) {"
                + " $w = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer;"
                + " $w.Voice = $v;"
                + " $t = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {"
                + " $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1"
                + " -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1;"
                + " $t = $t.MakeGenericMethod([Windows.Media.SpeechSynthesis.SpeechSynthesisStream]);"
                + " foreach ($x in $p) {"
                + " $o = Join-Path $d $x[0];"
                + " $task = $t.Invoke($null, @($w.SynthesizeSsmlToStreamAsync($h + $x[1] + $e)));"
                + " $task.Wait();"
                + " $from = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($task.Result);"
                + " $to = [System.IO.File]::Create($o + '.tmp');"
                + " $from.CopyTo($to);"
                + " $to.Close();"
                + " $from.Close();"
                + " Move-Item -Force -LiteralPath ($o + '.tmp') -Destination $o };"
                + " $done = $true }"
                + "} catch { $done = $false };"
                + "if ($done) { exit 0 };"
                + "Add-Type -AssemblyName System.Speech;"
                + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                + "$voices = $s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'zh*' };"
                + "$v = $voices | Where-Object { $_.VoiceInfo.Gender -eq 'Female' -and $_.VoiceInfo.Culture.Name -eq 'zh-TW' }"
                + " | Select-Object -First 1;"
                + "if (-not $v) { $v = $voices | Where-Object { $_.VoiceInfo.Gender -eq 'Female' } | Select-Object -First 1 };"
                + "if (-not $v) { $v = $voices | Select-Object -First 1 };"
                + "if (-not $v) { exit 3 };"
                + "$s.SelectVoice($v.VoiceInfo.Name);"
                + "$f = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(" + RATE + ","
                + " [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,"
                + " [System.Speech.AudioFormat.AudioChannel]::Mono);"
                + "foreach ($x in $p) {"
                + " $o = Join-Path $d $x[0];"
                + " $s.SetOutputToWaveFile($o + '.tmp', $f);"
                + " $s.SpeakSsml($h + $x[1] + $e);"
                + " $s.SetOutputToNull();"
                + " Move-Item -Force -LiteralPath ($o + '.tmp') -Destination $o };"
                + "$s.Dispose()";
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

    /** 喊牌與司儀一起調。 */
    public static void setVolume(int percent) {
        PLAYER.execute(() -> {
            volume = percent;
            applyVolume();
        });
    }

    /** 只在 PLAYER 的執行緒上跑：照 volume 調全部的 Clip，剛載入的也一樣。 */
    private static void applyVolume() {
        for (Clip clip : CLIPS.values()) {
            SoundEffects.applyVolume(clip, volume);
        }
        for (Clip clip : ANNOUNCER.values()) {
            SoundEffects.applyVolume(clip, volume);
        }
    }

    /** 吃、碰、槓、胡這些一定會念，前一句還沒念完就等它念完。 */
    public static void play(Line line, Gender gender) {
        playFile(CLIPS, voiceFile(line.fileName(), gender), false);
    }

    /**
     * 喊出打出的這張牌，條子照 call 喊「X條」或「X索」。花牌不會被打出來，沒有語音。
     * 輪不到念之前又有新的一句，這張就不念了。
     */
    public static void play(Tile tile, Gender gender, BambooCall call) {
        playFile(CLIPS, voiceFile(tileFile(tile, call), gender), true);
    }

    /** 連莊的局開始時司儀說「連莊，連X拉X」。和喊牌排同一個隊，一定會念到。 */
    public static void announceStreak(int streak) {
        if (streak > 0) {
            playFile(ANNOUNCER, streakFile(streak), false);
        }
    }

    /** 流局時司儀宣布「流局」。 */
    public static void announceDrawnGame() {
        playFile(ANNOUNCER, DRAWN_GAME_FILE, false);
    }

    /**
     * 不打斷還沒念完的那句：新的一句排在後面，念完才念下一句，聽起來才不會斷掉。
     * 排著的牌名（droppable）只要又有新的一句進來就不念了，語音才不會越落越後面；吃碰槓胡與司儀一定會念。
     * 語音還沒準備好就不出聲。
     */
    private static void playFile(Map<String, Clip> clips, String file, boolean droppable) {
        PLAYER.execute(() -> {
            if (!clips.containsKey(file)) {
                return;
            }
            QUEUE.removeIf(Queued::droppable);
            QUEUE.add(new Queued(clips, file, droppable));
            playNext();
        });
    }

    /** 只在 PLAYER 的執行緒上跑：上一句念完了就念排在最前面的，還沒念完就等到念完再來。 */
    private static void playNext() {
        long now = System.nanoTime();
        if (now < busyUntil) {
            if (waiting == null) {
                waiting = PLAYER.schedule(() -> {
                    waiting = null;
                    playNext();
                }, busyUntil - now, TimeUnit.NANOSECONDS);
            }
            return;
        }
        Queued next = QUEUE.poll();
        while (next != null) {
            Clip clip = next.clips().get(next.file());
            if (clip != null) {
                if (playing != null) {
                    playing.stop();
                }
                clip.setFramePosition(0);
                clip.start();
                playing = clip;
                busyUntil = now + clip.getMicrosecondLength() * 1_000;
                if (!QUEUE.isEmpty()) {
                    playNext();
                }
                return;
            }
            next = QUEUE.poll();
        }
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
     * 兩邊都念 SSML：每句的內容在 $p（含語速、音量與逐字的音高），外面包上 speak。
     * 這段腳本會直接放進命令列，不能有雙引號；命令列有長度上限，每句的內容只列一次。
     */
    private static String script(Path dir, VoiceStyle style) {
        StringBuilder script = new StringBuilder("$h = " + quoted(SSML_HEAD) + ";"
                + "$e = " + quoted("</speak>") + ";"
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
        return open(read(file));
    }

    /** 讀出語音檔，換成 FORMAT 並修掉前後空白。 */
    private static byte[] read(Path file) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile());
             AudioInputStream converted = AudioSystem.getAudioInputStream(FORMAT, source)) {
            byte[] samples = trimSilence(converted.readAllBytes());
            if (samples.length == 0) {
                throw new IOException("語音檔是空的: " + file);
            }
            return samples;
        }
    }

    private static Clip open(byte[] samples) throws LineUnavailableException {
        Clip clip = AudioSystem.getClip();
        clip.open(FORMAT, samples, 0, samples.length);
        return clip;
    }

    /** 等比例放大或縮小，讓最大振幅是滿格的 peak 倍，每句才一樣大聲。傳入 16 位元單聲道，回傳新的陣列。 */
    public static byte[] normalize(byte[] pcm, double peak) {
        int loudest = 0;
        for (int i = 0; i < pcm.length / 2; i++) {
            loudest = Math.max(loudest, Math.abs(sample(pcm, i)));
        }
        byte[] scaled = new byte[pcm.length];
        if (loudest == 0) {
            return scaled;
        }
        double factor = peak * Short.MAX_VALUE / loudest;
        for (int i = 0; i < pcm.length / 2; i++) {
            int value = (int) Math.round(sample(pcm, i) * factor);
            value = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
            scaled[i * 2] = (byte) value;
            scaled[i * 2 + 1] = (byte) (value >> 8);
        }
        return scaled;
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
