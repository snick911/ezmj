package ezmj.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** 環境設定：字體大小、等待時間、解析度、背景音樂、音量、音效與動畫。存成文字檔，檔案不存在或內容不正確時用預設值。 */
public final class AppSettings {

    public static final List<Integer> FONT_PERCENTS = List.of(85, 100, 125, 150);
    public static final int DEFAULT_FONT_PERCENT = 100;
    public static final List<Integer> TILE_PERCENTS = List.of(100, 115, 130, 150);
    public static final int DEFAULT_TILE_PERCENT = 100;
    public static final int NO_WAIT_LIMIT = -1;
    public static final int MAX_WAIT_SECONDS = 999;
    public static final int DEFAULT_MUSIC_VOLUME = 50;
    public static final int DEFAULT_SOUND_VOLUME = 100;
    public static final int DEFAULT_VOICE_VOLUME = 100;
    public static final MusicTrack DEFAULT_MUSIC = MusicTrack.TEAHOUSE;

    private static final String KEY_FONT = "fontPercent";
    private static final String KEY_TILE = "tilePercent";
    private static final String KEY_WAIT = "waitSeconds";
    private static final String KEY_RESOLUTION = "resolution";
    private static final String KEY_SOUND = "sound";
    private static final String KEY_ANIMATION = "animation";
    private static final String KEY_MUSIC = "music";
    private static final String KEY_MUSIC_VOLUME = "musicVolume";
    private static final String KEY_SOUND_VOLUME = "soundVolume";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_VOICE_VOLUME = "voiceVolume";
    private static final String KEY_PLAYER_GENDER = "playerGender";

    private int fontPercent = DEFAULT_FONT_PERCENT;
    private int tilePercent = DEFAULT_TILE_PERCENT;
    private int waitSeconds = NO_WAIT_LIMIT;
    private Resolution resolution = Resolution.DEFAULT;
    private boolean soundEnabled = true;
    private boolean animationEnabled = true;
    private MusicTrack music = DEFAULT_MUSIC;
    private int musicVolume = DEFAULT_MUSIC_VOLUME;
    private int soundVolume = DEFAULT_SOUND_VOLUME;
    private boolean voiceEnabled = true;
    private int voiceVolume = DEFAULT_VOICE_VOLUME;
    private Gender playerGender = Gender.FEMALE;

    public static AppSettings load(Path file) {
        AppSettings settings = new AppSettings();
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return settings;
        }
        settings.setFontPercent(intValue(values, KEY_FONT, DEFAULT_FONT_PERCENT));
        settings.setTilePercent(intValue(values, KEY_TILE, DEFAULT_TILE_PERCENT));
        settings.setWaitSeconds(intValue(values, KEY_WAIT, NO_WAIT_LIMIT));
        settings.setResolution(Resolution.fromKey(values.getProperty(KEY_RESOLUTION, "").trim()));
        settings.setSoundEnabled(!"false".equals(values.getProperty(KEY_SOUND, "").trim()));
        settings.setAnimationEnabled(!"false".equals(values.getProperty(KEY_ANIMATION, "").trim()));
        settings.setMusic(MusicTrack.fromKey(values.getProperty(KEY_MUSIC, "").trim(), DEFAULT_MUSIC));
        settings.setMusicVolume(intValue(values, KEY_MUSIC_VOLUME, DEFAULT_MUSIC_VOLUME));
        settings.setSoundVolume(intValue(values, KEY_SOUND_VOLUME, DEFAULT_SOUND_VOLUME));
        settings.setVoiceEnabled(!"false".equals(values.getProperty(KEY_VOICE, "").trim()));
        settings.setVoiceVolume(intValue(values, KEY_VOICE_VOLUME, DEFAULT_VOICE_VOLUME));
        settings.setPlayerGender(Gender.fromKey(values.getProperty(KEY_PLAYER_GENDER, "").trim()));
        return settings;
    }

    private static int intValue(Properties values, String key, int fallback) {
        try {
            return Integer.parseInt(values.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void save(Path file) throws IOException {
        Properties values = new Properties();
        values.setProperty(KEY_FONT, String.valueOf(fontPercent));
        values.setProperty(KEY_TILE, String.valueOf(tilePercent));
        values.setProperty(KEY_WAIT, String.valueOf(waitSeconds));
        values.setProperty(KEY_RESOLUTION, resolution.key());
        values.setProperty(KEY_SOUND, String.valueOf(soundEnabled));
        values.setProperty(KEY_ANIMATION, String.valueOf(animationEnabled));
        values.setProperty(KEY_MUSIC, music.name());
        values.setProperty(KEY_MUSIC_VOLUME, String.valueOf(musicVolume));
        values.setProperty(KEY_SOUND_VOLUME, String.valueOf(soundVolume));
        values.setProperty(KEY_VOICE, String.valueOf(voiceEnabled));
        values.setProperty(KEY_VOICE_VOLUME, String.valueOf(voiceVolume));
        values.setProperty(KEY_PLAYER_GENDER, playerGender.name());
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            values.store(out, "EZMJ");
        }
    }

    public int fontPercent() {
        return fontPercent;
    }

    public void setFontPercent(int percent) {
        fontPercent = FONT_PERCENTS.contains(percent) ? percent : DEFAULT_FONT_PERCENT;
    }

    public int tilePercent() {
        return tilePercent;
    }

    public void setTilePercent(int percent) {
        tilePercent = TILE_PERCENTS.contains(percent) ? percent : DEFAULT_TILE_PERCENT;
    }

    /** 等待秒數；NO_WAIT_LIMIT 表示不限時。 */
    public int waitSeconds() {
        return waitSeconds;
    }

    /** 0 以下一律視為不限時，過大的值取上限。 */
    public void setWaitSeconds(int seconds) {
        waitSeconds = seconds > 0 ? Math.min(seconds, MAX_WAIT_SECONDS) : NO_WAIT_LIMIT;
    }

    public Resolution resolution() {
        return resolution;
    }

    public void setResolution(Resolution value) {
        resolution = value;
    }

    public boolean soundEnabled() {
        return soundEnabled;
    }

    public void setSoundEnabled(boolean enabled) {
        soundEnabled = enabled;
    }

    public boolean animationEnabled() {
        return animationEnabled;
    }

    public void setAnimationEnabled(boolean enabled) {
        animationEnabled = enabled;
    }

    public MusicTrack music() {
        return music;
    }

    public void setMusic(MusicTrack track) {
        music = track;
    }

    /** 音量 0 到 100。 */
    public int musicVolume() {
        return musicVolume;
    }

    public void setMusicVolume(int percent) {
        musicVolume = Math.max(0, Math.min(100, percent));
    }

    /** 音量 0 到 100。 */
    public int soundVolume() {
        return soundVolume;
    }

    public void setSoundVolume(int percent) {
        soundVolume = Math.max(0, Math.min(100, percent));
    }

    public boolean voiceEnabled() {
        return voiceEnabled;
    }

    public void setVoiceEnabled(boolean enabled) {
        voiceEnabled = enabled;
    }

    /** 音量 0 到 100。 */
    public int voiceVolume() {
        return voiceVolume;
    }

    public void setVoiceVolume(int percent) {
        voiceVolume = Math.max(0, Math.min(100, percent));
    }

    /** 真人喊牌用的聲音。 */
    public Gender playerGender() {
        return playerGender;
    }

    public void setPlayerGender(Gender gender) {
        playerGender = gender;
    }
}
