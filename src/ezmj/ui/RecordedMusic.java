package ezmj.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import ezmj.model.MusicTrack;

/**
 * 錄好的背景音樂：程式帶著 MP3（放在 ezmj/ui/music），可是 Java Sound 解不了 MP3。
 * 第一次播放時用 Windows 內建的 Media Foundation 轉成 WAV，存在 .ezmj\music，之後直接讀檔。
 * 旁邊記著 MP3 的指紋，換了曲子就重新轉。不是 Windows 或轉不了時就不出聲，遊戲其他部分不受影響。
 */
public final class RecordedMusic {

    /** 轉出來的格式，和 MP3 一樣：44.1 kHz、16 位元、立體聲。 */
    public static final AudioFormat FORMAT = new AudioFormat(44_100, 16, 2, true, false);
    /**
     * 錄好的曲子混音得比較大聲，比這個大聲就壓下來，和合成的曲子差不多大聲，同一個「音樂音量」聽起來才一致。
     * 合成的四首 RMS 在 -14.5 到 -19.9 dBFS 之間，這兩首 MP3 是 -14.3 dBFS。
     */
    public static final double TARGET_RMS_DB = -17;
    private static final int TRANSCODE_TIMEOUT_SECONDS = 120;
    /** 比這小的 WAV 只有檔頭，沒轉出東西。 */
    private static final int MIN_WAV_BYTES = 1024;

    private RecordedMusic() {
    }

    /** 曲子在 .ezmj\music 裡用的名字，例如 clear_the_board。 */
    private static String baseName(MusicTrack track) {
        String file = track.recording();
        return file.substring(0, file.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    }

    public static Path wavFile(MusicTrack track, Path dir) {
        return dir.resolve(baseName(track) + ".wav");
    }

    private static Path signatureFile(MusicTrack track, Path dir) {
        return dir.resolve(baseName(track) + ".signature");
    }

    /** 程式帶著的 MP3；找不到就丟 IOException。 */
    public static InputStream open(MusicTrack track) throws IOException {
        if (!track.isRecorded()) {
            throw new IllegalArgumentException("不是錄好的曲子: " + track);
        }
        InputStream in = RecordedMusic.class.getResourceAsStream("music/" + track.recording());
        if (in == null) {
            throw new IOException("找不到音樂檔: " + track.recording());
        }
        return in;
    }

    /** MP3 的 SHA-256，記在轉好的 WAV 旁邊；換了曲子指紋就對不上，會重新轉。 */
    public static String signature(MusicTrack track) throws IOException {
        try (InputStream in = open(track)) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("這個 Java 沒有 SHA-256", e);
        }
    }

    /** 已經轉好了，而且是這一版的曲子。 */
    public static boolean isCached(MusicTrack track, Path dir) throws IOException {
        Path wav = wavFile(track, dir);
        Path stored = signatureFile(track, dir);
        return Files.isRegularFile(wav) && Files.size(wav) >= MIN_WAV_BYTES && Files.isRegularFile(stored)
                && Files.readString(stored, StandardCharsets.UTF_8).trim().equals(signature(track));
    }

    /**
     * 回傳轉好的 WAV，還沒轉或曲子換過就先轉，要一兩秒。先寫暫存檔再改名，中斷時不會留下不完整的檔案。
     * 會卡住呼叫的執行緒，不要在畫面執行緒呼叫。
     */
    public static Path prepare(MusicTrack track, Path dir) throws IOException, InterruptedException {
        Path wav = wavFile(track, dir);
        if (isCached(track, dir)) {
            return wav;
        }
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new IOException("只有 Windows 能把 MP3 轉成 WAV");
        }
        Files.createDirectories(dir);
        Path mp3 = dir.resolve(baseName(track) + ".mp3");
        Path temp = dir.resolve(baseName(track) + ".tmp.wav");
        try {
            try (InputStream in = open(track)) {
                Files.copy(in, mp3, StandardCopyOption.REPLACE_EXISTING);
            }
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-Command", script(mp3, temp))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(TRANSCODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("轉換音樂逾時");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(temp) || Files.size(temp) < MIN_WAV_BYTES) {
                throw new IOException("沒有轉出 WAV，代碼 " + process.exitValue());
            }
            Files.move(temp, wav, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(signatureFile(track, dir), signature(track), StandardCharsets.UTF_8);
            return wav;
        } finally {
            Files.deleteIfExists(mp3);
            Files.deleteIfExists(temp);
        }
    }

    /** 放進 PowerShell 單引號字串裡，單引號要寫兩次。 */
    private static String quoted(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /**
     * 用 Windows 的 MediaTranscoder 把 mp3 轉成 44.1 kHz、16 位元、立體聲的 wav。WinRT 的非同步呼叫用 AsTask 等它做完。
     * 這段腳本會直接放進命令列，不能有雙引號。
     */
    public static String script(Path mp3, Path wav) {
        return "$ErrorActionPreference = 'Stop';"
                + "Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
                + "$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime];"
                + "$null = [Windows.Media.Transcoding.MediaTranscoder, Windows.Media.Transcoding, ContentType = WindowsRuntime];"
                + "$null = [Windows.Media.MediaProperties.MediaEncodingProfile, Windows.Media.MediaProperties, ContentType = WindowsRuntime];"
                + "$m = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {"
                + " $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 };"
                + "$op = $m | Where-Object { $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1;"
                + "$pr = $m | Where-Object { $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncActionWithProgress`1' } | Select-Object -First 1;"
                + "function Await($o, $t) { $k = $op.MakeGenericMethod($t).Invoke($null, @($o)); $k.Wait(); $k.Result };"
                + "$in = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync(" + quoted(mp3.toString()) + "))"
                + " ([Windows.Storage.StorageFile]);"
                + "$d = Await ([Windows.Storage.StorageFolder]::GetFolderFromPathAsync("
                + quoted(wav.toAbsolutePath().getParent().toString()) + ")) ([Windows.Storage.StorageFolder]);"
                + "$out = Await ($d.CreateFileAsync(" + quoted(wav.getFileName().toString())
                + ", [Windows.Storage.CreationCollisionOption]::ReplaceExisting)) ([Windows.Storage.StorageFile]);"
                + "$p = [Windows.Media.MediaProperties.MediaEncodingProfile]::CreateWav("
                + "[Windows.Media.MediaProperties.AudioEncodingQuality]::High);"
                + "$p.Audio = [Windows.Media.MediaProperties.AudioEncodingProperties]::CreatePcm("
                + (int) FORMAT.getSampleRate() + ", " + FORMAT.getChannels() + ", " + FORMAT.getSampleSizeInBits() + ");"
                + "$r = Await ((New-Object Windows.Media.Transcoding.MediaTranscoder).PrepareFileTranscodeAsync($in, $out, $p))"
                + " ([Windows.Media.Transcoding.PrepareTranscodeResult]);"
                + "if (-not $r.CanTranscode) { exit 4 };"
                + "$k = $pr.MakeGenericMethod([double]).Invoke($null, @($r.TranscodeAsync()));"
                + "$k.Wait()";
    }

    /** 讀出轉好的 WAV，換成 FORMAT，太大聲就壓到 TARGET_RMS_DB。 */
    public static byte[] load(Path wav) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(wav.toFile());
             AudioInputStream converted = AudioSystem.getAudioInputStream(FORMAT, source)) {
            byte[] pcm = converted.readAllBytes();
            level(pcm, TARGET_RMS_DB);
            return pcm;
        }
    }

    /** 16 位元 PCM 的 RMS，以滿格為 0 dB；沒有聲音是負無限大。 */
    public static double rmsDb(byte[] pcm) {
        int samples = pcm.length / 2;
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            double value = sample(pcm, i) / 32768.0;
            sum += value * value;
        }
        return samples == 0 ? Double.NEGATIVE_INFINITY : 10 * Math.log10(sum / samples);
    }

    /** 比 targetDb 大聲就整首等比例壓小，直接改在 pcm 上；比較小聲的不放大，免得破音。傳入 16 位元。 */
    public static void level(byte[] pcm, double targetDb) {
        double rms = rmsDb(pcm);
        if (!(rms > targetDb)) {
            return;
        }
        double factor = Math.pow(10, (targetDb - rms) / 20);
        for (int i = 0; i < pcm.length / 2; i++) {
            int value = (int) Math.round(sample(pcm, i) * factor);
            pcm[i * 2] = (byte) value;
            pcm[i * 2 + 1] = (byte) (value >> 8);
        }
    }

    private static int sample(byte[] pcm, int index) {
        return (short) ((pcm[index * 2] & 0xFF) | (pcm[index * 2 + 1] << 8));
    }
}
