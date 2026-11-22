package ezmj.online;

import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 牌桌上的語音訊息：按住說話，放開送出，由服務器轉給同桌的真人。Swing 版、網頁版與服務器共用這一份。
 *
 * 聲音壓成 8 kHz 單聲道的 IMA ADPCM，每個取樣 4 位元，一秒 4 KB。用 Java 自己寫，網頁由 TeaVM 編進去，
 * 不靠瀏覽器的錄音格式：Chrome 錄成 WebM/Opus、Safari 錄成 MP4/AAC，Swing 都播不了。
 * 最長 10 秒約 40 KB，轉成 base64 約 53 KB，放得進服務器一則訊息 64 KB 的上限。音質像電話，說話夠用。
 *
 * 格式：前兩個位元組是第一個取樣（16 位元，小端序），第三個是 ADPCM 的步階索引（0 到 88），
 * 後面每個位元組放兩個取樣，先低四位元再高四位元。所以取樣數一定是單數，偶數的最後補一個。
 */
public final class VoiceClip {

    public static final int RATE = 8_000;
    /** 按一下就放開的不送。 */
    public static final int MIN_MS = 300;
    public static final int MAX_MS = 10_000;
    /** 每人每 LIMIT_WINDOW_MS 最多 LIMIT 則，最多就是一直在說話。服務器照這個擋，客戶端按下去之前也先看。 */
    public static final int LIMIT = 3;
    public static final int LIMIT_WINDOW_MS = 30_000;

    private static final int HEADER_BYTES = 3;
    private static final int MAX_SAMPLES = MAX_MS * RATE / 1000;
    public static final int MAX_BYTES = bytesFor(MAX_SAMPLES);
    private static final int MIN_BYTES = bytesFor(MIN_MS * RATE / 1000);
    /** MAX_BYTES 轉成 base64 的長度，超過的不用解就知道太長。 */
    private static final int MAX_TEXT_LENGTH = (MAX_BYTES + 2) / 3 * 4;

    /** 降取樣前先濾掉這個頻率以上的聲音，不然會變成雜音（混疊）。8 kHz 最多只放得下 4 kHz。 */
    private static final double CUTOFF_HZ = 3_500;
    private static final double TRANSITION_HZ = 1_000;
    /** 太小聲的錄音放大到這麼大，但最多放大 MAX_GAIN 倍，免得只有雜音時把雜音放得很大。 */
    private static final double TARGET_PEAK = .8;
    private static final double MAX_GAIN = 4;
    private static final int FADE_SAMPLES = RATE / 100;

    private static final int[] INDEX_STEPS = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};
    private static final int[] STEPS = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
        107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
        5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
        27086, 29794, 32767,
    };

    private VoiceClip() {
    }

    private static int bytesFor(int samples) {
        return HEADER_BYTES + samples / 2;
    }

    /** 錄音的長度，rate 是錄音的取樣率。 */
    public static int millis(int samples, int rate) {
        return (int) (samples * 1000L / rate);
    }

    /** 壓好的語音有幾毫秒。 */
    public static int millis(byte[] data) {
        return millis(sampleCount(data), RATE);
    }

    private static int sampleCount(byte[] data) {
        return 1 + 2 * (data.length - HEADER_BYTES);
    }

    /**
     * 把錄到的聲音（-1 到 1，取樣率 rate）壓成語音訊息：換成 8 kHz，去掉直流、太小聲的放大、頭尾淡入淡出。
     * 超過 MAX_MS 的部分丟掉。太短的照樣壓，送不送由呼叫的人看 MIN_MS 決定。
     */
    public static byte[] encode(float[] recorded, int rate) {
        float[] samples = resample(recorded, rate);
        if (samples.length > MAX_SAMPLES) {
            float[] cut = new float[MAX_SAMPLES];
            System.arraycopy(samples, 0, cut, 0, MAX_SAMPLES);
            samples = cut;
        }
        level(samples);
        short[] pcm = new short[Math.max(1, samples.length)];
        for (int n = 0; n < samples.length; n++) {
            pcm[n] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(samples[n] * Short.MAX_VALUE)));
        }
        return adpcm(pcm);
    }

    /** 解開成 -1 到 1 的聲音，取樣率是 RATE。不合格式的丟出 IllegalArgumentException，見 check。 */
    public static float[] decode(byte[] data) {
        check(data);
        float[] samples = new float[sampleCount(data)];
        int predictor = (short) ((data[0] & 0xFF) | (data[1] & 0xFF) << 8);
        int index = data[2] & 0xFF;
        samples[0] = predictor / 32768f;
        int n = 1;
        for (int i = HEADER_BYTES; i < data.length; i++) {
            for (int half = 0; half < 2; half++) {
                int nibble = half == 0 ? data[i] & 0x0F : (data[i] >> 4) & 0x0F;
                int step = STEPS[index];
                int delta = step >> 3;
                if ((nibble & 4) != 0) {
                    delta += step;
                }
                if ((nibble & 2) != 0) {
                    delta += step >> 1;
                }
                if ((nibble & 1) != 0) {
                    delta += step >> 2;
                }
                predictor = clampSample((nibble & 8) != 0 ? predictor - delta : predictor + delta);
                index = clampIndex(index + INDEX_STEPS[nibble]);
                samples[n++] = predictor / 32768f;
            }
        }
        return samples;
    }

    /**
     * 收到或要送出的語音合不合格式，回傳有幾毫秒。服務器用它擋，客戶端解開前也看。
     * 不合格丟出 IllegalArgumentException，訊息可以直接給玩家看。
     */
    public static int check(byte[] data) {
        if (data.length > MAX_BYTES) {
            throw new IllegalArgumentException("語音最長 " + MAX_MS / 1000 + " 秒");
        }
        if (data.length < MIN_BYTES) {
            throw new IllegalArgumentException("語音太短了");
        }
        if ((data[2] & 0xFF) >= STEPS.length) {
            throw new IllegalArgumentException("語音的格式不對");
        }
        return millis(data);
    }

    public static String toBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /** 訊息裡的 data。太長、不是 base64 或格式不對都丟出 IllegalArgumentException，見 check。 */
    public static byte[] fromBase64(String text) {
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("語音最長 " + MAX_MS / 1000 + " 秒");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("語音的格式不對");
        }
        check(data);
        return data;
    }

    /** 送給服務器的語音訊息。 */
    public static Map<String, Object> message(byte[] data) {
        return Fields.object("type", "voice", "data", toBase64(data));
    }

    /** createRoom 與 joinRoom 的 supports 帶這個，服務器才把別人的語音轉過來。 */
    public static final String FEATURE = "voice";

    /** 服務器會轉語音：welcome 的 features 有 "voice"（ezmjService 0.14.0 起）。舊服務器沒有這個欄位。 */
    public static boolean serverSupports(Map<String, Object> welcome) {
        Object features = welcome.get("features");
        return features instanceof List && ((List<?>) features).contains(FEATURE);
    }

    /** 房間裡有別的真人才有人聽得到，跟電腦打的一人房間就不顯示說話的按鈕。 */
    public static boolean anyoneToHear(RoomInfo room) {
        for (RoomInfo.Seat seat : room.seats()) {
            if (seat.human() && seat.seat() != room.yourSeat()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 頻率限制：每 LIMIT_WINDOW_MS 最多 LIMIT 則。服務器每人一個；客戶端自己也記一個，
     * 按住說話之前先看，免得說完了才被服務器拒絕。時間用毫秒，只看差值，從哪裡起算都可以。
     */
    public static final class Limiter {

        private final Deque<Long> times = new ArrayDeque<>();

        /** 現在還要等幾毫秒才能再送，0 是馬上可以。 */
        public synchronized long waitMs(long nowMs) {
            forget(nowMs);
            return times.size() < LIMIT ? 0 : times.peekFirst() + LIMIT_WINDOW_MS - nowMs;
        }

        /** 可以送就記下這一則；太快就回傳 false，這一則不算。 */
        public synchronized boolean tryRecord(long nowMs) {
            if (waitMs(nowMs) > 0) {
                return false;
            }
            times.addLast(nowMs);
            return true;
        }

        private void forget(long nowMs) {
            while (!times.isEmpty() && nowMs - times.peekFirst() >= LIMIT_WINDOW_MS) {
                times.removeFirst();
            }
        }
    }

    /** 還要等 waitMs 才能再送時給玩家看的話。 */
    public static String tooFastText(long waitMs) {
        return "語音傳得太快了，" + Math.max(1, (waitMs + 999) / 1000) + " 秒後再試";
    }

    /** 畫面上的字，Swing 版與網頁版一樣。 */
    public static final String TALK_LABEL = "按住說話";
    public static final String PREPARING_LABEL = "準備麥克風…";
    public static final String HOLD_TEXT = "要按住說話，放開才送出";
    public static final String CANCELLED_TEXT = "在按鈕外面放開，取消了，沒有送出";
    public static final String OFFLINE_TEXT = "連線中斷，語音沒有送出";
    public static final String REPLAY_LABEL = "重播";
    public static final String MUTE_LABEL = "靜音";
    public static final String UNMUTE_LABEL = "取消靜音";
    /** 環境設定裡的兩項，和喊牌的「語音」分開。 */
    public static final String SETTING_LABEL = "玩家語音";
    public static final String VOLUME_LABEL = "玩家語音音量";
    public static final String SETTING_NOTE = "玩家語音是同桌的真人按住「說話」傳來的語音，關掉就不自動播放，"
            + "聊天區照樣可以按「重播」；不想聽某一位，按他那一行的「靜音」。";

    /** 錄音中按鈕上的字，ms 是錄了多久。 */
    public static String recordingLabel(int ms) {
        return "錄音中 " + (ms / 1000 + 1) + " 秒，放開送出";
    }

    /** 聊天區與泡泡上的一則語音，例如「〔語音 3.2 秒〕」。 */
    public static String label(int ms) {
        int tenths = Math.max(1, (ms + 50) / 100);
        return "〔語音 " + tenths / 10 + "." + tenths % 10 + " 秒〕";
    }

    private static byte[] adpcm(short[] pcm) {
        byte[] data = new byte[bytesFor(pcm.length + (pcm.length % 2 == 0 ? 1 : 0))];
        int predictor = pcm[0];
        int index = 0;
        data[0] = (byte) predictor;
        data[1] = (byte) (predictor >> 8);
        data[2] = (byte) index;
        for (int n = 1; n < sampleCount(data); n++) {
            int sample = n < pcm.length ? pcm[n] : pcm[pcm.length - 1];
            int diff = sample - predictor;
            int nibble = 0;
            if (diff < 0) {
                nibble = 8;
                diff = -diff;
            }
            int step = STEPS[index];
            int delta = step >> 3;
            if (diff >= step) {
                nibble |= 4;
                diff -= step;
                delta += step;
            }
            if (diff >= step >> 1) {
                nibble |= 2;
                diff -= step >> 1;
                delta += step >> 1;
            }
            if (diff >= step >> 2) {
                nibble |= 1;
                delta += step >> 2;
            }
            predictor = clampSample((nibble & 8) != 0 ? predictor - delta : predictor + delta);
            index = clampIndex(index + INDEX_STEPS[nibble]);
            int at = HEADER_BYTES + (n - 1) / 2;
            data[at] |= (byte) ((n - 1) % 2 == 0 ? nibble : nibble << 4);
        }
        return data;
    }

    private static int clampSample(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private static int clampIndex(int value) {
        return Math.max(0, Math.min(STEPS.length - 1, value));
    }

    /** 換成 RATE 的取樣率。比 RATE 高的先濾掉 CUTOFF_HZ 以上的聲音，只算內插要用到的那幾個點。 */
    static float[] resample(float[] input, int rate) {
        if (rate == RATE || input.length == 0) {
            return input.clone();
        }
        double ratio = (double) rate / RATE;
        float[] kernel = rate > RATE ? lowPass(rate) : new float[] {1};
        int length = (int) (input.length / ratio);
        float[] out = new float[length];
        for (int n = 0; n < length; n++) {
            double t = n * ratio;
            int i = (int) t;
            double fraction = t - i;
            double a = filtered(input, i, kernel);
            double b = fraction == 0 || i + 1 >= input.length ? a : filtered(input, i + 1, kernel);
            out[n] = (float) (a + (b - a) * fraction);
        }
        return out;
    }

    private static double filtered(float[] input, int at, float[] kernel) {
        int half = kernel.length / 2;
        double sum = 0;
        for (int k = 0; k < kernel.length; k++) {
            int i = at - half + k;
            if (i >= 0 && i < input.length) {
                sum += kernel[k] * input[i];
            }
        }
        return sum;
    }

    /** 低通濾波器：加 Hamming 窗的 sinc，長度照取樣率，加起來是 1。 */
    private static float[] lowPass(int rate) {
        int taps = (int) (3.3 * rate / TRANSITION_HZ) | 1;
        double cutoff = CUTOFF_HZ / rate;
        int middle = taps / 2;
        float[] kernel = new float[taps];
        double sum = 0;
        for (int k = 0; k < taps; k++) {
            double x = k - middle;
            double sinc = x == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x);
            double window = .54 - .46 * Math.cos(2 * Math.PI * k / (taps - 1));
            kernel[k] = (float) (sinc * window);
            sum += kernel[k];
        }
        for (int k = 0; k < taps; k++) {
            kernel[k] = (float) (kernel[k] / sum);
        }
        return kernel;
    }

    /** 去掉直流、太小聲的放大、頭尾淡入淡出，免得開頭結尾喀一聲。 */
    private static void level(float[] samples) {
        if (samples.length == 0) {
            return;
        }
        double mean = 0;
        for (float sample : samples) {
            mean += sample;
        }
        mean /= samples.length;
        double peak = 0;
        for (int n = 0; n < samples.length; n++) {
            samples[n] -= mean;
            peak = Math.max(peak, Math.abs(samples[n]));
        }
        double gain = peak > 0 ? Math.max(1, Math.min(MAX_GAIN, TARGET_PEAK / peak)) : 1;
        int fade = Math.min(FADE_SAMPLES, samples.length / 2);
        for (int n = 0; n < samples.length; n++) {
            double edge = Math.min(1, Math.min(n, samples.length - 1 - n) / (double) Math.max(1, fade));
            samples[n] = (float) Math.max(-1, Math.min(1, samples[n] * gain * edge));
        }
    }
}
