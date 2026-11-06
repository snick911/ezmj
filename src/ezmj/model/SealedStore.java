package ezmj.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Properties;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 玩家的存檔與紀錄：Properties 的內容用 AES-GCM 加密，打開來是看不懂的位元組，改了任何一個位元組都驗不過。
 * 檔名也算在驗證裡，統計檔改名成存檔也讀不出來。
 * <p>
 * 檔案的開頭是 MAGIC 與封存的版本，後面是 12 位元組的 nonce 與密文（含 16 位元組的驗證碼）。
 * 換加密方式時把 VERSION 加一、在 read 裡加一個新的版本，舊版本照樣讀得出來，寫一律用最新的。
 * <p>
 * 金鑰在程式裡，看得懂原始碼的人還是做得出能讀的檔案；這是單機遊戲，擋的是用記事本改籌碼與成就。
 * 把整個檔案換成以前的備份也擋不住。
 */
public final class SealedStore implements PropertyStore {

    public static final SealedStore INSTANCE = new SealedStore();

    private static final byte[] MAGIC = "EZMJSEAL".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final SecretKeySpec KEY_V1 = key("EZMJ 台灣十六張麻將 存檔封印 第一版");

    private final SecureRandom random = new SecureRandom();

    private SealedStore() {
    }

    private static SecretKeySpec key(String phrase) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(phrase.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("這個 Java 沒有 SHA-256", e);
        }
    }

    /** 這個檔案是不是封存過的，只看開頭，不驗證內容。 */
    public static boolean isSealed(Path file) throws IOException {
        byte[] head = new byte[MAGIC.length];
        try (var in = Files.newInputStream(file)) {
            return in.readNBytes(head, 0, head.length) == head.length && Arrays.equals(head, MAGIC);
        }
    }

    @Override
    public Properties read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < MAGIC.length + 1 || !Arrays.equals(Arrays.copyOf(bytes, MAGIC.length), MAGIC)) {
            throw new IOException("不是封存的存檔：" + file.getFileName());
        }
        int version = bytes[MAGIC.length];
        int start = MAGIC.length + 1;
        byte[] plain;
        switch (version) {
            case 1:
                plain = open(KEY_V1, bytes, start, file);
                break;
            default:
                throw new IOException("存檔的封存版本 " + version + " 看不懂，可能是新版程式寫的：" + file.getFileName());
        }
        Properties values = new Properties();
        try {
            values.load(new ByteArrayInputStream(plain));
        } catch (IllegalArgumentException e) {
            throw new IOException("存檔內容不正確：" + file.getFileName(), e);
        }
        return values;
    }

    @Override
    public void write(Properties values, Path file) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        values.store(text, null);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] sealed;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, KEY_V1, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(label(file));
            sealed = cipher.doFinal(text.toByteArray());
        } catch (GeneralSecurityException e) {
            throw new IOException("存檔無法加密", e);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(VERSION);
        out.write(nonce);
        out.write(sealed);
        PropertyFiles.writeAtomically(out.toByteArray(), file);
    }

    private static byte[] open(SecretKeySpec key, byte[] bytes, int start, Path file) throws IOException {
        if (bytes.length < start + NONCE_BYTES + TAG_BITS / 8) {
            throw new IOException("存檔不完整：" + file.getFileName());
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, start, NONCE_BYTES));
            cipher.updateAAD(label(file));
            return cipher.doFinal(bytes, start + NONCE_BYTES, bytes.length - start - NONCE_BYTES);
        } catch (AEADBadTagException e) {
            throw new IOException("存檔被改過或壞掉了：" + file.getFileName(), e);
        } catch (GeneralSecurityException e) {
            throw new IOException("存檔無法解開：" + file.getFileName(), e);
        }
    }

    /** 驗證時一起算進去的檔名，只用檔名，整個資料夾搬到別台電腦還是讀得出來。 */
    private static byte[] label(Path file) {
        return file.getFileName().toString().getBytes(StandardCharsets.UTF_8);
    }
}
