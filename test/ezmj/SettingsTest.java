package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import ezmj.model.AppSettings;
import ezmj.model.MusicTrack;
import ezmj.model.Resolution;
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
    }

    static void run() {
        T.section("環境設定");

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
                }
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(nested);
                Files.delete(dir);
            }
        });

        test("內容不正確時退回預設值", () -> {
            defaults(loadText("fontPercent=abc\nwaitSeconds=\nresolution=1x1\n"));
            defaults(loadText("fontPercent=77\nwaitSeconds=-5\nresolution=\n"));
            defaults(loadText("fontPercent=\\u00zz\n"));
            defaults(loadText("music=ROCK\nmusicVolume=abc\nsoundVolume=\n"));
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

        test("背景音樂：每個聲部都對齊，長度合理，循環接得起來", () -> {
            for (MusicTrack track : MusicTrack.values()) {
                if (track == MusicTrack.OFF) {
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
