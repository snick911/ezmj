package ezmj.online;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

/**
 * 連線上一則訊息的樣子：JSON 先用 ChaCha20 加密，再轉成 base64。瀏覽器的開發者工具與封包紀錄裡只看得到一串亂碼，
 * 看不出打了哪張牌、誰在聽牌、聊了什麼。Swing 版、網頁版與服務器共用這一份，網頁版由 TeaVM 編成 JS，
 * 所以只用 int 運算、java.util.Base64 與 java.util.Random。
 *
 * 金鑰寫在程式裡，網頁的 JS 與 Swing 的 jar 都拿得到，原始碼也公開，所以這只是不讓人一看就懂，擋不住有心拆程式的人。
 * 別人的手牌與牌牆本來就不送，不靠這個；網路上的竊聽由 wss 的 TLS 擋。
 *
 * 一則訊息是 base64(nonce ‖ 加密(UTF-8 的 JSON ‖ 檢查碼))。nonce 12 個位元組，每則重新亂數，同樣的 ping 每次看起來也不一樣；
 * 檢查碼是 JSON 的 FNV-1a，4 個位元組，金鑰不對或內容被改過就解不開。base64 沒有換行，TCP 照樣一行一則。
 *
 * 舊的客戶端與服務器送的是沒加密的 JSON，一定以 { 開頭，base64 不會，所以兩種分得出來，見 isPlain。
 */
public final class Wire {

    private static final int NONCE_BYTES = 12;
    private static final int CHECK_BYTES = 4;
    /** ChaCha20 的常數 "expand 32-byte k"。 */
    private static final int[] SIGMA = {0x61707865, 0x3320646e, 0x79622d32, 0x6b206574};
    /** 256 位元的金鑰。三邊要一樣，改了舊版就連不上。 */
    private static final int[] KEY = {
        0xb919eaf3, 0xa2be2937, 0xd4b87a84, 0x1212b017, 0x0702b43d, 0xc0407e53, 0x77f6b1a2, 0x715a0575,
    };

    private Wire() {
    }

    /** 加密一則 JSON。random 給 nonce 用，不必是 SecureRandom，但同一個 nonce 不要用兩次。 */
    public static String encode(String json, Random random) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[NONCE_BYTES + text.length + CHECK_BYTES];
        for (int i = 0; i < NONCE_BYTES; i += 4) {
            putInt(data, i, random.nextInt());
        }
        System.arraycopy(text, 0, data, NONCE_BYTES, text.length);
        putInt(data, NONCE_BYTES + text.length, fnv(data, NONCE_BYTES, text.length));
        crypt(KEY, data, NONCE_BYTES);
        return Base64.getEncoder().encodeToString(data);
    }

    /** 解開 encode 的結果。不是 base64、太短、金鑰不對或被改過都丟 IllegalArgumentException。 */
    public static String decode(String line) {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(line.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("訊息不是加密過的格式");
        }
        int length = data.length - NONCE_BYTES - CHECK_BYTES;
        if (length < 0) {
            throw new IllegalArgumentException("訊息太短");
        }
        crypt(KEY, data, NONCE_BYTES);
        if (fnv(data, NONCE_BYTES, length) != getInt(data, NONCE_BYTES + length)) {
            throw new IllegalArgumentException("訊息解不開");
        }
        return new String(data, NONCE_BYTES, length, StandardCharsets.UTF_8);
    }

    /** 沒加密的 JSON 物件，舊版送的就是這樣。前面的空白不算。 */
    public static boolean isPlain(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return c == '{';
            }
        }
        return false;
    }

    /** 收到的一則：沒加密的照原樣，加密的解開。解不開丟 IllegalArgumentException。 */
    public static String read(String line) {
        return isPlain(line) ? line : decode(line);
    }

    /**
     * ChaCha20（RFC 8439）：data 從 from 開始和金鑰流 XOR，nonce 是 data 的前 12 個位元組，區塊計數從 0 開始。
     * 加密與解密是同一件事。測試拿 JDK 的 ChaCha20 比對。
     */
    static void crypt(int[] key, byte[] data, int from) {
        int[] state = new int[16];
        System.arraycopy(SIGMA, 0, state, 0, 4);
        System.arraycopy(key, 0, state, 4, 8);
        state[13] = getInt(data, 0);
        state[14] = getInt(data, 4);
        state[15] = getInt(data, 8);
        int[] block = new int[16];
        int at = from;
        for (int counter = 0; at < data.length; counter++) {
            state[12] = counter;
            System.arraycopy(state, 0, block, 0, 16);
            for (int round = 0; round < 10; round++) {
                quarter(block, 0, 4, 8, 12);
                quarter(block, 1, 5, 9, 13);
                quarter(block, 2, 6, 10, 14);
                quarter(block, 3, 7, 11, 15);
                quarter(block, 0, 5, 10, 15);
                quarter(block, 1, 6, 11, 12);
                quarter(block, 2, 7, 8, 13);
                quarter(block, 3, 4, 9, 14);
            }
            for (int i = 0; i < 16 && at < data.length; i++) {
                int word = block[i] + state[i];
                for (int shift = 0; shift < 32 && at < data.length; shift += 8) {
                    data[at++] ^= (byte) (word >>> shift);
                }
            }
        }
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = rotate(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = rotate(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = rotate(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = rotate(x[b] ^ x[c], 7);
    }

    private static int rotate(int value, int bits) {
        return (value << bits) | (value >>> (32 - bits));
    }

    /** FNV-1a，32 位元。 */
    private static int fnv(byte[] data, int from, int length) {
        int hash = 0x811c9dc5;
        for (int i = from; i < from + length; i++) {
            hash ^= data[i] & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }

    /** 小端序，和 ChaCha20 一樣。 */
    private static void putInt(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >>> 8);
        data[at + 2] = (byte) (value >>> 16);
        data[at + 3] = (byte) (value >>> 24);
    }

    private static int getInt(byte[] data, int at) {
        return (data[at] & 0xff) | (data[at + 1] & 0xff) << 8 | (data[at + 2] & 0xff) << 16 | (data[at + 3] & 0xff) << 24;
    }
}
