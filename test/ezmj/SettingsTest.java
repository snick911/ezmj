package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ezmj.model.AppSettings;
import ezmj.model.BambooCall;
import ezmj.model.GameConfig;
import ezmj.model.GameSpeed;
import ezmj.model.Gender;
import ezmj.model.MusicTrack;
import ezmj.model.Resolution;
import ezmj.model.Tile;
import ezmj.model.VoiceStyle;
import ezmj.ui.BackgroundMusic;
import ezmj.ui.SoundEffects;
import ezmj.ui.VoiceLines;

/** 環境設定：預設值、存檔讀檔與不正確的內容；以及合成出來的音效與音樂。 */
final class SettingsTest {

    private SettingsTest() {
    }

    private static AppSettings loadText(String text) throws Exception {
        Path file = Files.createTempFile("ezmj-settings", ".properties");
        try {
            Files.writeString(file, text);
            return AppSettings.load(file);
        } finally {
            Files.delete(file);
        }
    }

    private static int sample(byte[] pcm, int index) {
        return (short) ((pcm[index] & 0xFF) | (pcm[index + 1] << 8));
    }

    /** from 到 to 這段樣本穿過零點的次數，用來估音調高低。 */
    private static int crossings(byte[] pcm, int from, int to) {
        int count = 0;
        for (int i = from + 1; i < to; i++) {
            if (sample(pcm, (i - 1) * 2) < 0 != sample(pcm, i * 2) < 0) {
                count++;
            }
        }
        return count;
    }

    private static void defaults(AppSettings settings) {
        eq(100, settings.fontPercent(), "字體");
        eq(100, settings.tilePercent(), "麻將大小");
        eq(-1, settings.waitSeconds(), "等待時間");
        eq(Resolution.DEFAULT, settings.resolution(), "解析度");
        check(settings.soundEnabled(), "音效預設開啟");
        check(settings.animationEnabled(), "動畫預設開啟");
        eq(MusicTrack.TEAHOUSE, settings.music(), "背景音樂");
        eq(50, settings.musicVolume(), "音樂音量");
        eq(100, settings.soundVolume(), "音效音量");
        check(settings.voiceEnabled(), "語音預設開啟");
        eq(100, settings.voiceVolume(), "語音音量");
        eq(Gender.FEMALE, settings.playerGender(), "我的語音");
        eq(VoiceStyle.HYPE, settings.voiceStyle(), "語音風格");
        eq(GameSpeed.NORMAL, settings.gameSpeed(), "遊戲速度");
    }

    static void run() {
        T.section("環境設定");

        test("專家模式每次最多等 10 秒，不限時也當作 10 秒", () -> {
            eq(10, GameConfig.EXPERT_MAX_WAIT_SECONDS, "上限");
            eq(10, GameConfig.expertWaitSeconds(AppSettings.NO_WAIT_LIMIT), "不限時");
            eq(10, GameConfig.expertWaitSeconds(0), "0 也是不限時");
            eq(10, GameConfig.expertWaitSeconds(30), "比較長的");
            eq(10, GameConfig.expertWaitSeconds(10), "剛好 10 秒");
            eq(7, GameConfig.expertWaitSeconds(7), "比較短的照舊");
        });

        test("檔案不存在時用預設值，等待時間預設 -1", () -> {
            Path dir = Files.createTempDirectory("ezmj-settings");
            try {
                defaults(AppSettings.load(dir.resolve("settings.properties")));
            } finally {
                Files.delete(dir);
            }
        });

        test("存檔後可以讀回來，資料夾不存在時會建立", () -> {
            Path dir = Files.createTempDirectory("ezmj-settings");
            Path nested = dir.resolve("ezmj");
            Path file = nested.resolve("settings.properties");
            try {
                for (Resolution resolution : Resolution.OPTIONS) {
                    AppSettings settings = new AppSettings();
                    settings.setFontPercent(150);
                    settings.setTilePercent(130);
                    settings.setWaitSeconds(15);
                    settings.setResolution(resolution);
                    settings.setSoundEnabled(false);
                    settings.setAnimationEnabled(false);
                    settings.setMusic(MusicTrack.MOONLIGHT);
                    settings.setMusicVolume(30);
                    settings.setSoundVolume(70);
                    settings.setVoiceEnabled(false);
                    settings.setVoiceVolume(40);
                    settings.setPlayerGender(Gender.MALE);
                    settings.save(file);

                    AppSettings loaded = AppSettings.load(file);
                    eq(150, loaded.fontPercent(), "字體");
                    eq(130, loaded.tilePercent(), "麻將大小");
                    eq(15, loaded.waitSeconds(), "等待時間");
                    eq(resolution, loaded.resolution(), "解析度");
                    check(!loaded.soundEnabled(), "音效");
                    check(!loaded.animationEnabled(), "動畫");
                    eq(MusicTrack.MOONLIGHT, loaded.music(), "背景音樂");
                    eq(30, loaded.musicVolume(), "音樂音量");
                    eq(70, loaded.soundVolume(), "音效音量");
                    check(!loaded.voiceEnabled(), "語音");
                    eq(40, loaded.voiceVolume(), "語音音量");
                    eq(Gender.MALE, loaded.playerGender(), "我的語音");
                }
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(nested);
                Files.delete(dir);
            }
        });

        test("解析度：視窗比預設大時畫面等比例放大，以 5% 為一級往下取，比預設小時不縮小", () -> {
            eq(1f, Resolution.zoomFor(1360, 900), "預設");
            eq(1.2f, Resolution.zoomFor(1920, 1080), "1920 × 1080 照高度放大 20%");
            eq(1.15f, Resolution.zoomFor(1920, 1040), "螢幕放不下 1080 高時往下取");
            eq(1f, Resolution.zoomFor(1600, 900), "高度一樣就不放大");
            eq(1.5f, Resolution.zoomFor(2560, 1350), "寬高取比較小的比例");
            eq(1f, Resolution.zoomFor(1280, 720), "比預設小不縮小");
            eq(1f, Resolution.zoomFor(1366, 768), "比預設矮不縮小");
            for (Resolution resolution : Resolution.OPTIONS) {
                float zoom = Resolution.zoomFor(resolution.width(), resolution.height());
                if (!resolution.isMaximized() && zoom > 1) {
                    check(Resolution.DEFAULT.width() * zoom <= resolution.width()
                            && Resolution.DEFAULT.height() * zoom <= resolution.height(), resolution.label() + " 放大後放得進視窗");
                }
            }
        });

        test("內容不正確時退回預設值", () -> {
            defaults(loadText("fontPercent=abc\nwaitSeconds=\nresolution=1x1\n"));
            defaults(loadText("fontPercent=77\nwaitSeconds=-5\nresolution=\n"));
            defaults(loadText("fontPercent=\\u00zz\n"));
            defaults(loadText("music=ROCK\nmusicVolume=abc\nsoundVolume=\n"));
            defaults(loadText("playerGender=ROBOT\n"));
            defaults(loadText("tilePercent=999\n"));
            defaults(loadText("tilePercent=abc\n"));
        });

        test("音量限制在 0 到 100，換算成分貝", () -> {
            AppSettings settings = new AppSettings();
            settings.setMusicVolume(-5);
            eq(0, settings.musicVolume(), "太小");
            settings.setSoundVolume(500);
            eq(100, settings.soundVolume(), "太大");
            eq(0.0, SoundEffects.gainDb(100), "100%");
            check(Math.abs(SoundEffects.gainDb(50) + 12.04) < .01, "50% 約 -12 dB");
            eq(-80.0, SoundEffects.gainDb(0), "0%");
        });

        test("語音：修掉前後空白，留一點頭尾，結尾淡出", () -> {
            int rate = 44_100;
            byte[] pcm = new byte[rate * 2];
            for (int i = rate / 4; i < rate / 2; i++) {
                int value = 10_000;
                pcm[i * 2] = (byte) value;
                pcm[i * 2 + 1] = (byte) (value >> 8);
            }
            byte[] trimmed = VoiceLines.trimSilence(pcm);
            int expected = rate / 4 + rate / 100 + rate / 25;
            check(Math.abs(trimmed.length / 2 - expected) <= 1, "長度 " + trimmed.length / 2 + "，預期約 " + expected);
            eq(0, sample(trimmed, 0), "開頭留了一點空白");
            eq(0, sample(trimmed, trimmed.length - 2), "結尾歸零");
            eq(0, VoiceLines.trimSilence(new byte[1000]).length, "全部是空白");
        });

        test("語音：每種牌的講法都不同，字牌用牌桌上的叫法", () -> {
            Set<String> texts = new HashSet<>();
            for (int index = 0; index < Tile.KINDS; index++) {
                texts.add(VoiceLines.tileText(Tile.fromIndex(index)));
            }
            eq(Tile.KINDS, texts.size(), "牌種");
            eq("三萬", VoiceLines.tileText(Tile.parseAll("3w").get(0)), "數牌");
            eq("東風", VoiceLines.tileText(Tile.parseAll("1z").get(0)), "風牌");
            eq("紅中", VoiceLines.tileText(Tile.parseAll("5z").get(0)), "三元牌");
            Tile bamboo = Tile.parseAll("3s").get(0);
            eq("三條", VoiceLines.tileText(bamboo, BambooCall.TIAO), "條子喊條");
            eq("三索", VoiceLines.tileText(bamboo, BambooCall.SUO), "條子喊索");
            eq("三萬", VoiceLines.tileText(Tile.parseAll("3w").get(0), BambooCall.SUO), "別的牌不受影響");
            eq("三條", bamboo.label(), "畫面上照舊寫條");
            long suoFiles = VoiceLines.phrases().values().stream().filter(p -> p.text().endsWith("索")).count();
            eq(9L, suoFiles, "一條到九條都另外有喊索的語音");
            check(VoiceLines.phrases().values().stream().filter(p -> p.text().endsWith("條")).allMatch(VoiceLines.Phrase::gentle),
                    "喊條的不加快、不太大聲，女聲才聽得清楚");
        });

        test("條子的喊法：自己一律喊條、不能設定，別人照名字，同一個名字每次都一樣", () -> {
            Path file = Files.createTempDirectory("ezmj-settings").resolve("settings.properties");
            Files.writeString(file, "bambooCall=SUO\nwaitSeconds=7\n");
            AppSettings settings = AppSettings.load(file);
            eq(7, settings.waitSeconds(), "以前存的喊索讀得進來，其他設定照舊");
            settings.save(file);
            check(!Files.readString(file).contains("bambooCall"), "存檔不再寫條子的喊法");
            eq(BambooCall.forName("阿土"), BambooCall.forName("阿土"), "同一個名字");
            Set<BambooCall> calls = new HashSet<>();
            for (String name : List.of("陳小明", "林雅婷", "黃阿土", "張淑芬", "李國強", "王美玲", "吳志明", "劉佳穎")) {
                calls.add(BambooCall.forName(name));
            }
            eq(2, calls.size(), "有人喊條、有人喊索");
        });

        test("語音：說法或風格改了指紋就變，舊的語音檔會重新產生", () -> {
            Set<String> signatures = new HashSet<>();
            for (VoiceStyle style : VoiceStyle.values()) {
                String signature = VoiceLines.signature(style);
                eq(64, signature.length(), style + " 是 SHA-256");
                eq(signature, VoiceLines.signature(style), style + " 每次算都一樣");
                signatures.add(signature);
            }
            eq(VoiceStyle.values().length, signatures.size(), "每種風格的指紋都不同");
        });

        test("語音風格：預設熱血，存了讀得回來，不認得就用預設", () -> {
            eq(VoiceStyle.HYPE, new AppSettings().voiceStyle(), "預設");
            eq(VoiceStyle.HYPE, loadText("voiceStyle=ROBOT\n").voiceStyle(), "不認得");
            Path file = Files.createTempDirectory("ezmj-settings").resolve("settings.properties");
            for (VoiceStyle style : VoiceStyle.values()) {
                AppSettings settings = new AppSettings();
                settings.setVoiceStyle(style);
                settings.save(file);
                eq(style, AppSettings.load(file).voiceStyle(), "讀回來");
            }
        });

        test("語音：每句話每個字都有高低，SSML 照風格提高、只用單引號", () -> {
            for (Map.Entry<String, VoiceLines.Phrase> entry : VoiceLines.phrases().entrySet()) {
                VoiceLines.Phrase phrase = entry.getValue();
                eq(phrase.text().length(), phrase.levels().length(), entry.getKey() + " 的高低和字一樣多");
                check(phrase.levels().chars().allMatch(Character::isDigit), entry.getKey() + " 的高低是 0 到 9");
                for (VoiceStyle style : VoiceStyle.values()) {
                    String body = VoiceLines.ssmlBody(phrase, style);
                    check(!body.contains("\""), "產生語音的指令放不進雙引號：" + body);
                    eq(phrase.text().length() + 1, body.split("<prosody ").length - 1, entry.getKey() + " 外面一層，每個字一層");
                }
            }
            VoiceLines.Phrase zimo = new VoiceLines.Phrase("自摸", "48");
            eq("<prosody rate='+25%' volume='+40%'><prosody pitch='+65%'>自</prosody><prosody pitch='+105%'>摸</prosody>！</prosody>",
                    VoiceLines.ssmlBody(zimo, VoiceStyle.HYPE), "熱血：加快加大聲、拉高、重點字更高、加驚嘆號");
            eq("<prosody rate='+20%' volume='+30%'><prosody pitch='+15%'>自</prosody><prosody pitch='+15%'>摸</prosody></prosody>",
                    VoiceLines.ssmlBody(zimo, VoiceStyle.BRISK), "輕快：整句一樣高");
            eq("<prosody rate='+15%' volume='+30%'><prosody pitch='+34%'>自</prosody><prosody pitch='+58%'>摸</prosody></prosody>",
                    VoiceLines.ssmlBody(zimo, VoiceStyle.LIVELY), "抑揚：逐字有高低");
            eq("<prosody rate='+0%' volume='+20%'><prosody pitch='+55%'>三</prosody><prosody pitch='+75%'>條</prosody>！</prosody>",
                    VoiceLines.ssmlBody(new VoiceLines.Phrase("三條", "35", true), VoiceStyle.HYPE),
                    "條：音高照調，但不加快、音量最多加 20%");
            check(VoiceLines.ssmlBody(new VoiceLines.Phrase("吃，碰", "200"), VoiceStyle.HYPE)
                    .contains("<prosody pitch='+45%'>吃</prosody>，<prosody pitch='+25%'>碰</prosody>"), "逗號不調");
        });

        test("語音：沒有男聲時，女聲壓低後音調更低、更長", () -> {
            int rate = 44_100;
            byte[] pcm = new byte[rate * 2];
            for (int i = 0; i < rate; i++) {
                int value = (int) (5_000 * Math.sin(2 * Math.PI * 200 * i / rate));
                pcm[i * 2] = (byte) value;
                pcm[i * 2 + 1] = (byte) (value >> 8);
            }
            byte[] lowered = VoiceLines.lowerPitch(pcm);
            check(lowered.length > pcm.length * 1.2, "長度 " + lowered.length);
            double before = crossings(pcm, 0, rate) / 1.0 / rate;
            double after = crossings(lowered, 0, lowered.length / 2) / 1.0 / (lowered.length / 2);
            check(after < before * .8, "音調 " + before + " → " + after);
            eq(0, VoiceLines.lowerPitch(new byte[0]).length, "沒有聲音");
        });

        test("背景音樂：每個聲部都對齊，長度合理，循環接得起來", () -> {
            for (MusicTrack track : MusicTrack.values()) {
                // 錄好的曲子沒有樂譜，見 AudioTest
                if (track == MusicTrack.OFF || track.isRecorded()) {
                    continue;
                }
                for (double beats : BackgroundMusic.voiceBeats(track)) {
                    eq(BackgroundMusic.totalBeats(track), beats, track + " 聲部拍數");
                }
                byte[] pcm = BackgroundMusic.render(track);
                double seconds = pcm.length / 2 / 44_100.0;
                check(seconds >= 30 && seconds <= 90, track + " 長度 " + seconds);
                int peak = 0;
                long energy = 0;
                for (int i = 0; i < pcm.length; i += 2) {
                    peak = Math.max(peak, Math.abs(sample(pcm, i)));
                    energy += Math.abs(sample(pcm, i));
                }
                check(peak > 15_000 && peak <= 21_000, track + " 音量 " + peak);
                check(energy / (pcm.length / 2) > 500, track + " 太安靜");
                check(Math.abs(sample(pcm, 0) - sample(pcm, pcm.length - 2)) < 2_000, track + " 接縫跳太大");
            }
        });

        test("遊戲速度：存了讀得回來，不認得就用中速，越快等越短", () -> {
            Path file = Files.createTempDirectory("ezmj-speed").resolve("settings.properties");
            AppSettings settings = new AppSettings();
            settings.setGameSpeed(GameSpeed.FAST);
            settings.save(file);
            eq(GameSpeed.FAST, AppSettings.load(file).gameSpeed(), "讀回來");
            defaults(loadText("gameSpeed=WARP\n"));
            check(GameSpeed.FAST.turnDelayMs() < GameSpeed.NORMAL.turnDelayMs(), "快比中短");
            check(GameSpeed.NORMAL.turnDelayMs() < GameSpeed.SLOW.turnDelayMs(), "中比慢短");
            eq(550, GameSpeed.NORMAL.turnDelayMs(), "中速和原本一樣");
            eq(200, GameSpeed.NORMAL.claimDelayMs(), "中速和原本一樣");
        });

        test("存設定時先寫暫存檔：寫到一半失敗也不會弄壞原本的檔案", () -> {
            Path dir = Files.createTempDirectory("ezmj-settings");
            Path file = dir.resolve("settings.properties");
            AppSettings settings = new AppSettings();
            settings.setFontPercent(125);
            settings.save(file);
            check(!Files.exists(dir.resolve("settings.properties.tmp")), "暫存檔不會留著");

            // 暫存檔的位置被資料夾占住，寫的時候一定失敗
            Files.createDirectory(dir.resolve("settings.properties.tmp"));
            settings.setFontPercent(150);
            boolean failed = false;
            try {
                settings.save(file);
            } catch (java.io.IOException e) {
                failed = true;
            }
            check(failed, "應該存不了");
            eq(125, AppSettings.load(file).fontPercent(), "原本的設定還在");
        });

        test("等待時間：0 以下視為不限時，過大的值取上限", () -> {
            AppSettings settings = new AppSettings();
            for (int seconds : List.of(0, -1, -30)) {
                settings.setWaitSeconds(seconds);
                eq(-1, settings.waitSeconds(), "設成 " + seconds);
            }
            settings.setWaitSeconds(1);
            eq(1, settings.waitSeconds(), "設成 1");
            settings.setWaitSeconds(100_000);
            eq(AppSettings.MAX_WAIT_SECONDS, settings.waitSeconds(), "設成很大");
        });

        test("音效：長度合理、不破音、尾端歸零", () -> {
            for (SoundEffects.Cue cue : SoundEffects.Cue.values()) {
                for (int variant = 0; variant < cue.variants(); variant++) {
                    byte[] pcm = SoundEffects.render(cue, variant);
                    double seconds = pcm.length / 2 / 44_100.0;
                    check(seconds >= .1 && seconds <= 1.5, cue + " 長度 " + seconds);
                    int peak = 0;
                    for (int i = 0; i < pcm.length; i += 2) {
                        peak = Math.max(peak, Math.abs(sample(pcm, i)));
                    }
                    check(peak > 10_000 && peak <= 28_000, cue + " 音量 " + peak);
                    eq(0, sample(pcm, pcm.length - 2), cue + " 尾端");
                }
            }
        });
    }
}
