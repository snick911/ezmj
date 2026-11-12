package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ezmj.model.AppSettings;
import ezmj.model.MusicTrack;
import ezmj.model.VoiceStyle;
import ezmj.ui.RecordedMusic;
import ezmj.ui.VoiceLines;

/** 錄好的背景音樂（MP3 轉 WAV、音量）與司儀的語音（連莊、流局）。實際轉檔與念出聲要在 Windows 上跑程式聽。 */
final class AudioTest {

    private AudioTest() {
    }

    private static void put(byte[] pcm, int index, int value) {
        pcm[index * 2] = (byte) value;
        pcm[index * 2 + 1] = (byte) (value >> 8);
    }

    private static int sample(byte[] pcm, int index) {
        return (short) ((pcm[index * 2] & 0xFF) | (pcm[index * 2 + 1] << 8));
    }

    /** amplitude 的正弦波，16 位元，一秒。 */
    private static byte[] sine(int amplitude) {
        int rate = 44_100;
        byte[] pcm = new byte[rate * 2];
        for (int i = 0; i < rate; i++) {
            put(pcm, i, (int) Math.round(amplitude * Math.sin(2 * Math.PI * 440 * i / rate)));
        }
        return pcm;
    }

    private static void deleteAll(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(dir);
    }

    static void run() {
        T.section("背景音樂與司儀");

        test("背景音樂：兩首錄好的曲子在選單裡，程式帶著 MP3", () -> {
            List<MusicTrack> recorded = List.of(MusicTrack.CLEAR_THE_BOARD, MusicTrack.SUNLIGHT);
            Set<String> files = new HashSet<>();
            for (MusicTrack track : MusicTrack.values()) {
                check(track.isRecorded() == recorded.contains(track), track + " 是不是錄好的");
                if (!track.isRecorded()) {
                    continue;
                }
                check(track.recording().endsWith(".mp3"), track + " 是 MP3");
                files.add(track.recording());
                try (InputStream in = RecordedMusic.open(track)) {
                    byte[] head = in.readNBytes(3);
                    boolean id3 = new String(head, StandardCharsets.ISO_8859_1).equals("ID3");
                    boolean frame = (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
                    check(id3 || frame, track + " 開頭是 ID3 標籤或 MP3 音框");
                    check(in.readAllBytes().length > 1_000_000, track + " 是整首曲子");
                }
                String signature = RecordedMusic.signature(track);
                eq(64, signature.length(), track + " 的指紋是 SHA-256");
                eq(signature, RecordedMusic.signature(track), track + " 的指紋每次算都一樣");
            }
            eq(2, files.size(), "兩首各有自己的檔案");
            check(!RecordedMusic.signature(MusicTrack.CLEAR_THE_BOARD).equals(RecordedMusic.signature(MusicTrack.SUNLIGHT)),
                    "兩首的指紋不同");
            fails(IllegalArgumentException.class, () -> {
                try {
                    RecordedMusic.open(MusicTrack.TEAHOUSE).close();
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            }, "合成的曲子沒有 MP3");
        });

        test("背景音樂：選錄好的曲子存得起來、讀得回來", () -> {
            Path file = Files.createTempDirectory("ezmj-music").resolve("settings.properties");
            for (MusicTrack track : List.of(MusicTrack.CLEAR_THE_BOARD, MusicTrack.SUNLIGHT)) {
                AppSettings settings = new AppSettings();
                settings.setMusic(track);
                settings.save(file);
                eq(track, AppSettings.load(file).music(), "讀回來");
            }
            Files.delete(file);
            Files.delete(file.getParent());
        });

        test("背景音樂：轉好的 WAV 要在，指紋也要對得上，才不用重新轉", () -> {
            Path dir = Files.createTempDirectory("ezmj-music");
            MusicTrack track = MusicTrack.SUNLIGHT;
            try {
                eq(dir.resolve("sunlight_on_the_floorboards.wav"), RecordedMusic.wavFile(track, dir), "WAV 的位置");
                check(!RecordedMusic.isCached(track, dir), "還沒轉");
                Files.write(RecordedMusic.wavFile(track, dir), new byte[4096]);
                check(!RecordedMusic.isCached(track, dir), "沒有指紋");
                Path signature = dir.resolve("sunlight_on_the_floorboards.signature");
                Files.writeString(signature, "0".repeat(64));
                check(!RecordedMusic.isCached(track, dir), "指紋對不上，曲子換過了");
                Files.writeString(signature, RecordedMusic.signature(track) + "\n");
                check(RecordedMusic.isCached(track, dir), "轉好了");
                check(!RecordedMusic.isCached(MusicTrack.CLEAR_THE_BOARD, dir), "另一首還沒轉");
                Files.write(RecordedMusic.wavFile(track, dir), new byte[10]);
                check(!RecordedMusic.isCached(track, dir), "只有檔頭的 WAV 不算");
            } finally {
                deleteAll(dir);
            }
        });

        test("背景音樂：轉檔的指令用單引號，路徑裡的單引號要寫兩次，轉成 44.1 kHz 立體聲", () -> {
            Path dir = Path.of("C:\\Users\\O'Neil\\.ezmj\\music");
            String script = RecordedMusic.script(dir.resolve("clear_the_board.mp3"), dir.resolve("clear_the_board.tmp.wav"));
            check(!script.contains("\""), "命令列放不進雙引號");
            check(script.contains("'C:\\Users\\O''Neil\\.ezmj\\music\\clear_the_board.mp3'"), "MP3 的路徑");
            check(script.contains("GetFolderFromPathAsync('C:\\Users\\O''Neil\\.ezmj\\music')"), "WAV 的資料夾");
            check(script.contains("CreateFileAsync('clear_the_board.tmp.wav'"), "WAV 的檔名");
            check(script.contains("CreatePcm(44100, 2, 16)"), "格式");
            eq(44_100f, RecordedMusic.FORMAT.getSampleRate(), "取樣率");
            eq(2, RecordedMusic.FORMAT.getChannels(), "立體聲");
        });

        test("背景音樂：錄好的曲子太大聲就壓到和合成的差不多，小聲的不放大", () -> {
            byte[] loud = sine(16_000);
            check(Math.abs(RecordedMusic.rmsDb(loud) + 9.2) < .1, "原本約 -9.2 dB，實際 " + RecordedMusic.rmsDb(loud));
            RecordedMusic.level(loud, RecordedMusic.TARGET_RMS_DB);
            check(Math.abs(RecordedMusic.rmsDb(loud) - RecordedMusic.TARGET_RMS_DB) < .1,
                    "壓到 " + RecordedMusic.TARGET_RMS_DB + "，實際 " + RecordedMusic.rmsDb(loud));
            byte[] quiet = sine(500);
            byte[] before = quiet.clone();
            RecordedMusic.level(quiet, RecordedMusic.TARGET_RMS_DB);
            check(java.util.Arrays.equals(before, quiet), "小聲的不動");
            byte[] silence = new byte[1000];
            RecordedMusic.level(silence, RecordedMusic.TARGET_RMS_DB);
            check(java.util.Arrays.equals(new byte[1000], silence), "沒有聲音的不動");
            check(RecordedMusic.TARGET_RMS_DB <= -14.5 && RecordedMusic.TARGET_RMS_DB >= -19.9,
                    "落在合成的四首之間");
        });

        test("司儀：中文數字", () -> {
            Map<Integer, String> expected = Map.of(1, "一", 2, "二", 9, "九", 10, "十", 11, "十一", 19, "十九",
                    20, "二十", 21, "二十一", 99, "九十九");
            expected.forEach((n, text) -> eq(text, VoiceLines.chineseNumber(n), String.valueOf(n)));
            fails(IllegalArgumentException.class, () -> VoiceLines.chineseNumber(0), "0");
            fails(IllegalArgumentException.class, () -> VoiceLines.chineseNumber(100), "100");
        });

        test("司儀：連莊說「連莊，連X拉X」，連太多只說「連莊」", () -> {
            eq("連莊，連一拉一", VoiceLines.streakText(1), "連一");
            eq("連莊，連二拉二", VoiceLines.streakText(2), "連二");
            eq("連莊，連十拉十", VoiceLines.streakText(10), "連十");
            eq("連莊，連十二拉十二", VoiceLines.streakText(12), "連十二");
            eq("連莊，連二十拉二十", VoiceLines.streakText(VoiceLines.MAX_ANNOUNCED_STREAK), "最多念到這裡");
            eq("連莊", VoiceLines.streakText(VoiceLines.MAX_ANNOUNCED_STREAK + 1), "再多只說連莊");
            fails(IllegalArgumentException.class, () -> VoiceLines.streakText(0), "沒有連莊不說");
        });

        test("司儀：準備流局與每種連莊的語音，喊牌不再念流局", () -> {
            Map<String, String> phrases = VoiceLines.announcerPhrases();
            check(phrases.containsValue("流局"), "流局");
            for (int streak = 1; streak <= VoiceLines.MAX_ANNOUNCED_STREAK; streak++) {
                check(phrases.containsValue(VoiceLines.streakText(streak)), "連" + streak);
            }
            check(phrases.containsValue("連莊"), "連太多");
            eq(VoiceLines.MAX_ANNOUNCED_STREAK + 2, phrases.size(), "句數");
            eq(phrases.size(), new HashSet<>(phrases.values()).size(), "每句都不同");
            check(phrases.keySet().stream().allMatch(file -> file.endsWith(".wav")), "都是 WAV");
            check(VoiceLines.phrases().values().stream().noneMatch(phrase -> phrase.text().equals("流局")),
                    "喊牌的語音沒有流局");
            for (VoiceLines.Line line : VoiceLines.Line.values()) {
                check(!line.text().equals("流局"), line + " 不是流局");
            }
        });

        test("司儀：穩重清楚，稍慢，不調音高、不加大聲、不加驚嘆號", () -> {
            eq("<prosody rate='-5%'>流局</prosody>", VoiceLines.announcerSsml("流局"), "流局");
            for (String text : VoiceLines.announcerPhrases().values()) {
                String ssml = VoiceLines.announcerSsml(text);
                check(!ssml.contains("\""), "放不進雙引號：" + ssml);
                check(!ssml.contains("pitch") && !ssml.contains("volume"), "不調音高與音量：" + ssml);
                check(!ssml.contains("！") && !ssml.contains("+"), "不加快、不加驚嘆號：" + ssml);
            }
            String script = VoiceLines.announcerScript(Path.of("C:\\Users\\O'Neil\\.ezmj\\voice\\announcer"));
            check(!script.contains("\""), "產生語音的指令放不進雙引號");
            check(script.contains("'C:\\Users\\O''Neil\\.ezmj\\voice\\announcer'"), "資料夾的單引號寫兩次");
            check(script.indexOf("'Yating'") >= 0 && script.indexOf("'Yating'") < script.indexOf("'Hanhan'"),
                    "先找 Yating，再找 Hanhan");
            check(script.contains("Female"), "只用女聲");
            for (String file : VoiceLines.announcerPhrases().keySet()) {
                check(script.contains("'" + file + "'"), "腳本裡有 " + file);
            }
        });

        test("司儀：說法或挑聲音的順序改了指紋就變，和喊牌的風格分開", () -> {
            String signature = VoiceLines.announcerSignature();
            eq(64, signature.length(), "SHA-256");
            eq(signature, VoiceLines.announcerSignature(), "每次算都一樣");
            for (VoiceStyle style : VoiceStyle.values()) {
                check(!signature.equals(VoiceLines.signature(style)), "和 " + style + " 不同");
            }
        });

        test("司儀：每句調到一樣大聲，不破音", () -> {
            byte[] quiet = sine(4_000);
            byte[] louder = VoiceLines.normalize(quiet, VoiceLines.ANNOUNCER_PEAK);
            int peak = 0;
            for (int i = 0; i < louder.length / 2; i++) {
                peak = Math.max(peak, Math.abs(sample(louder, i)));
            }
            check(Math.abs(peak - VoiceLines.ANNOUNCER_PEAK * Short.MAX_VALUE) <= 2, "放大到 " + peak);
            eq(4_000, Math.abs(sample(quiet, 44_100 / 440 / 4)), "原本的不動");
            byte[] loud = sine(Short.MAX_VALUE);
            int loudPeak = 0;
            byte[] softer = VoiceLines.normalize(loud, VoiceLines.ANNOUNCER_PEAK);
            for (int i = 0; i < softer.length / 2; i++) {
                loudPeak = Math.max(loudPeak, Math.abs(sample(softer, i)));
            }
            check(loudPeak < Short.MAX_VALUE * .91, "太大聲的壓小到 " + loudPeak);
            check(java.util.Arrays.equals(new byte[100], VoiceLines.normalize(new byte[100], .9)), "沒有聲音的照舊");
        });
    }
}
