package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import ezmj.model.BotLevel;
import ezmj.model.GameSpeed;
import ezmj.model.Gender;
import ezmj.online.Json;
import ezmj.online.OnlineGame;
import ezmj.online.RoomInfo;
import ezmj.online.RoomSettings;
import ezmj.online.VoiceClip;

/** 語音訊息：壓縮與解開、降取樣、長度限制、base64、頻率限制與收到的訊息。 */
final class VoiceClipTest {

    private VoiceClipTest() {
    }

    /** 這麼多秒的正弦波，取樣率 rate，大小 amplitude。 */
    private static float[] tone(double seconds, int rate, double frequency, double amplitude) {
        float[] samples = new float[(int) (seconds * rate)];
        for (int n = 0; n < samples.length; n++) {
            samples[n] = (float) (amplitude * Math.sin(2 * Math.PI * frequency * n / rate));
        }
        return samples;
    }

    /** 跳過頭尾淡入淡出的部分，看最大的音量。 */
    private static double peak(float[] samples) {
        double peak = 0;
        for (int n = samples.length / 10; n < samples.length * 9 / 10; n++) {
            peak = Math.max(peak, Math.abs(samples[n]));
        }
        return peak;
    }

    /** 和同一個頻率的正弦波有多像，1 是一模一樣，0 是無關。 */
    private static double likeness(float[] samples, double frequency) {
        double sin = 0;
        double cos = 0;
        double energy = 0;
        int from = samples.length / 10;
        int to = samples.length * 9 / 10;
        for (int n = from; n < to; n++) {
            double phase = 2 * Math.PI * frequency * n / VoiceClip.RATE;
            sin += samples[n] * Math.sin(phase);
            cos += samples[n] * Math.cos(phase);
            energy += samples[n] * samples[n];
        }
        return 2 * (sin * sin + cos * cos) / (to - from) / energy;
    }

    static void run() {
        T.section("語音訊息");

        test("48 kHz 的聲音壓成 8 kHz，解開還是同一個音", () -> {
            byte[] data = VoiceClip.encode(tone(2, 48_000, 440, .5), 48_000);
            eq(2000, VoiceClip.millis(data), "長度");
            eq(2000, VoiceClip.check(data), "格式合格");
            float[] back = VoiceClip.decode(data);
            check(Math.abs(back.length - 2 * VoiceClip.RATE) <= 2, "取樣數 " + back.length);
            check(likeness(back, 440) > .97, "還是 440 Hz：" + likeness(back, 440));
        });

        test("44.1 kHz 也可以，太小聲的放大，但最多四倍", () -> {
            float[] loud = VoiceClip.decode(VoiceClip.encode(tone(1, 44_100, 300, .02), 44_100));
            check(Math.abs(peak(loud) - .08) < .01, "放大四倍：" + peak(loud));
            float[] normal = VoiceClip.decode(VoiceClip.encode(tone(1, 44_100, 300, .4), 44_100));
            check(Math.abs(peak(normal) - .8) < .03, "放大到 0.8：" + peak(normal));
            float[] already = VoiceClip.decode(VoiceClip.encode(tone(1, 44_100, 300, .95), 44_100));
            check(Math.abs(peak(already) - .95) < .03, "夠大聲的不縮小：" + peak(already));
        });

        test("降取樣先濾掉 4 kHz 以上，高音不會變成別的音", () -> {
            // 6 kHz 在 8 kHz 裡會折成 2 kHz；濾掉的話就算放大四倍也幾乎沒有聲音
            float[] high = VoiceClip.decode(VoiceClip.encode(tone(1, 48_000, 6_000, .5), 48_000));
            check(peak(high) < .05, "高音濾掉了：" + peak(high));
            float[] low = VoiceClip.decode(VoiceClip.encode(tone(1, 48_000, 1_000, .5), 48_000));
            check(likeness(low, 1_000) > .97, "1 kHz 不受影響");
        });

        test("最長 10 秒約 40 KB，轉成 base64 放得進服務器一則 64 KB", () -> {
            byte[] longest = VoiceClip.encode(tone(12, 16_000, 300, .5), 16_000);
            eq(VoiceClip.MAX_MS, VoiceClip.millis(longest), "超過的部分丟掉");
            eq(VoiceClip.MAX_BYTES, longest.length, "剛好是上限");
            String message = Json.write(Map.of("type", "voice", "data", VoiceClip.toBase64(longest)));
            check(message.length() < 60_000, "整則 " + message.length() + " 位元組");
            check(Arrays.equals(longest, VoiceClip.fromBase64(VoiceClip.toBase64(longest))), "base64 來回一樣");
        });

        test("太短、太長、不是 base64 或步階不對都不收", () -> {
            byte[] shortest = VoiceClip.encode(new float[VoiceClip.MIN_MS * VoiceClip.RATE / 1000], VoiceClip.RATE);
            eq(VoiceClip.MIN_MS, VoiceClip.check(shortest), "最短的可以");
            byte[] tooShort = VoiceClip.encode(new float[VoiceClip.MIN_MS * VoiceClip.RATE / 1000 - 4], VoiceClip.RATE);
            fails(IllegalArgumentException.class, () -> VoiceClip.check(tooShort), "太短");
            fails(IllegalArgumentException.class, () -> VoiceClip.check(new byte[VoiceClip.MAX_BYTES + 1]), "太長");
            fails(IllegalArgumentException.class, () -> VoiceClip.fromBase64("A".repeat(60_000)), "base64 太長，不用解");
            fails(IllegalArgumentException.class, () -> VoiceClip.fromBase64("不是base64"), "不是 base64");
            byte[] badIndex = shortest.clone();
            badIndex[2] = 89;
            fails(IllegalArgumentException.class, () -> VoiceClip.decode(badIndex), "步階索引最大 88");
            eq(VoiceClip.MIN_MS, VoiceClip.millis(VoiceClip.MIN_MS * 48, 48_000), "錄音的長度");
        });

        test("頻率限制：30 秒內最多 3 則，被拒絕的不算", () -> {
            VoiceClip.Limiter limiter = new VoiceClip.Limiter();
            check(limiter.tryRecord(1_000), "第一則");
            check(limiter.tryRecord(2_000), "第二則");
            check(limiter.tryRecord(3_000), "第三則");
            check(!limiter.tryRecord(4_000), "第四則太快");
            eq(27_000L, limiter.waitMs(4_000), "等到第一則滿 30 秒");
            check(limiter.tryRecord(31_000), "第一則過期了");
            eq(0L, new VoiceClip.Limiter().waitMs(0), "沒送過就不用等");
            eq("語音傳得太快了，27 秒後再試", VoiceClip.tooFastText(26_001), "進位到秒");
        });

        test("welcome 有 features 的 voice 才會用；跟電腦打沒有人聽，按鈕變淡", () -> {
            check(VoiceClip.serverSupports(Map.of("features", List.of("voice"))), "新服務器");
            check(!VoiceClip.serverSupports(Map.of("type", "welcome")), "舊服務器沒有這個欄位");
            RoomSettings settings = new RoomSettings(500, 10, 2,
                    List.of(BotLevel.PRO, BotLevel.PRO, BotLevel.PRO), 15, GameSpeed.NORMAL, "", true);
            RoomInfo.Seat me = new RoomInfo.Seat(1, "我", true, true, null);
            RoomInfo.Seat bot = new RoomInfo.Seat(0, "電腦", false, true, Gender.MALE);
            RoomInfo.Seat friend = new RoomInfo.Seat(2, "小明", true, true, null);
            RoomInfo.Seat gone = new RoomInfo.Seat(3, "小華", true, false, null);
            check(!VoiceClip.anyoneToHear(new RoomInfo("ABC234", true, 1, settings, List.of(me, bot))), "只有電腦");
            check(VoiceClip.anyoneToHear(new RoomInfo("ABC234", true, 1, settings, List.of(me, bot, friend))),
                    "有別的真人連著線");
            check(!VoiceClip.anyoneToHear(new RoomInfo("ABC234", true, 1, settings, List.of(me, bot, gone))),
                    "斷線或離開牌局的真人收不到，不算");
        });

        test("畫面上的字", () -> {
            eq("〔語音 3.2 秒〕", VoiceClip.label(3_249), "四捨五入到 0.1 秒");
            eq("〔語音 0.3 秒〕", VoiceClip.label(300), "最短");
            eq("〔語音 10.0 秒〕", VoiceClip.label(10_000), "最長");
            eq("錄音中 1 秒，放開就廣播", VoiceClip.recordingLabel(0), "剛開始");
            eq("錄音中 10 秒，放開就廣播", VoiceClip.recordingLabel(9_999), "快滿了");
        });

        test("收到的語音：座位轉成畫面座位，自己送的只有長度", () -> {
            OnlineGame game = new OnlineGame();
            byte[] data = VoiceClip.encode(tone(1.5, 8_000, 500, .5), 8_000);
            OnlineGame.Voice heard = game.parseVoice(Map.of("type", "voice", "seat", 2L, "name", "小明", "ms", 1500L,
                    "data", VoiceClip.toBase64(data)));
            eq(2, heard.seat(), "對家");
            eq("小明", heard.name(), "名字");
            eq(1500, heard.ms(), "長度照聲音算");
            check(Arrays.equals(data, heard.data()), "聲音");
            OnlineGame.Voice mine = game.parseVoice(Map.of("type", "voice", "seat", 0L, "name", "我", "ms", 800L));
            eq(0, mine.seat(), "自己");
            eq(800, mine.ms(), "長度照 ms");
            check(mine.data() == null, "服務器不回聲音");
            fails(IllegalArgumentException.class, () -> game.parseVoice(Map.of("type", "voice", "seat", 1L, "name", "x",
                    "ms", 1L, "data", "AAAA")), "格式不對的不收");
        });
    }
}
