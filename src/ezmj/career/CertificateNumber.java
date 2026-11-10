package ezmj.career;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

/**
 * 段位證書的 16 碼證號，英文與數字混合，照下面的組合規則：
 *
 * <pre>
 *   位置    內容              例（七段，2026-11-09）
 *   1–2     固定 EZ           EZ
 *   3       段位 1–9          7
 *   4       認定年份的個位數    6
 *   5       認定月份 A–M       L（一月 A … 八月 H，跳過 I，九月 J、十月 K、十一月 L、十二月 M）
 *   6–7     認定日 01–31       09
 *   8–13    六碼亂數          H4QX2M
 *   14–16   驗證碼            R8T
 * </pre>
 *
 * 畫面上每四碼空一格：EZ76 L09H 4QX2 MR8T。亂數與驗證碼只用 ALPHABET 裡的字：2–9 與不含 I、O 的大寫英文字母，
 * 不會把 0 和 O、1 和 I 看錯。驗證碼由前 13 碼與證書上的名字算出來，證號或名字改一個字就對不上，
 * 所以拿證號加名字就能驗證證書是不是 EZMJ 發的（見 verify）。算法寫在程式裡，擋的是隨手改字，擋不住看得懂原始碼的人。
 * 不依賴 Swing，也不用加密函式庫。
 */
public final class CertificateNumber {

    public static final int LENGTH = 16;
    /** 前面這麼多碼是內容，後面是驗證碼。 */
    static final int BODY_LENGTH = 13;
    public static final String PREFIX = "EZ";
    public static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String MONTHS = "ABCDEFGHJKLM";
    private static final int RANDOM_LENGTH = 6;
    private static final int CHECK_LENGTH = LENGTH - BODY_LENGTH;
    /** 驗證碼的鹽，換掉的話以前發的證號都驗不過，不要改。 */
    private static final String SALT = "EZMJ-dan-certificate-2026";
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** 驗證的結果：有效時是哪一段、哪一天認定的；year 是照證號裡的個位數推回、不晚於今年的年份。 */
    public record Check(boolean valid, Dan dan, String date) {
        static final Check INVALID = new Check(false, null, null);
    }

    private CertificateNumber() {
    }

    /** 升段時產生前 13 碼：段位、日期與六碼亂數。date 是 yyyy-MM-dd。 */
    static String body(Dan dan, String date, Random random) {
        StringBuilder body = new StringBuilder(PREFIX).append(dan.number()).append(datePart(date));
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            body.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return body.toString();
    }

    /** 以前沒有證號的證照：照段位、日期與成績算出前 13 碼，每次都一樣。 */
    static String derivedBody(Dan dan, String date, int rounds, int wins, int selfDraws) {
        long seed = hash(dan.name() + "|" + date + "|" + rounds + "|" + wins + "|" + selfDraws);
        return body(dan, date, new Random(seed));
    }

    /** 完整的 16 碼：前 13 碼加上用名字算的驗證碼。 */
    public static String full(String body, String name) {
        return body + checkCode(body, name);
    }

    /** 每四碼空一格，例如 EZ76 L09H 4QX2 MR8T。 */
    public static String grouped(String number) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(number.charAt(i));
        }
        return out.toString();
    }

    /**
     * 驗證證號與證書上的名字對不對得上。證號裡的空白與連字號不算，大小寫都可以。
     * thisYear 用來把年份的個位數推回完整的年份。
     */
    public static Check verify(String number, String name, int thisYear) {
        String cleaned = number == null ? "" : number.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        String trimmedName = name == null ? "" : name.trim();
        if (cleaned.length() != LENGTH || !cleaned.startsWith(PREFIX) || trimmedName.isEmpty()) {
            return Check.INVALID;
        }
        String body = cleaned.substring(0, BODY_LENGTH);
        if (!checkCode(body, trimmedName).equals(cleaned.substring(BODY_LENGTH))) {
            return Check.INVALID;
        }
        char danDigit = cleaned.charAt(2);
        int month = MONTHS.indexOf(cleaned.charAt(4)) + 1;
        int day;
        try {
            day = Integer.parseInt(cleaned.substring(5, 7));
        } catch (NumberFormatException e) {
            return Check.INVALID;
        }
        if (danDigit < '1' || danDigit > '9' || month == 0 || day < 1 || day > 31
                || !Character.isDigit(cleaned.charAt(3))) {
            return Check.INVALID;
        }
        int lastDigit = cleaned.charAt(3) - '0';
        int year = thisYear - Math.floorMod(thisYear - lastDigit, 10);
        Dan dan = Dan.values()[danDigit - '1'];
        return new Check(true, dan, String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day));
    }

    /** 認定日期的四碼：年份個位數、月份字母、兩碼日。看不懂的日期寫 0A00。 */
    private static String datePart(String date) {
        String[] parts = date == null ? new String[0] : date.split("-");
        try {
            if (parts.length == 3) {
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                int day = Integer.parseInt(parts[2]);
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    return String.format(Locale.ROOT, "%d%c%02d", Math.floorMod(year, 10), MONTHS.charAt(month - 1), day);
                }
            }
        } catch (NumberFormatException e) {
            // 照看不懂的寫
        }
        return "0A00";
    }

    /** 三碼驗證碼：鹽、前 13 碼與名字的雜湊，換成 ALPHABET 的字。 */
    static String checkCode(String body, String name) {
        long value = hash(SALT + "|" + body + "|" + (name == null ? "" : name.trim()));
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CHECK_LENGTH; i++) {
            code.append(ALPHABET.charAt((int) Long.remainderUnsigned(value, ALPHABET.length())));
            value = Long.divideUnsigned(value, ALPHABET.length());
        }
        return code.toString();
    }

    /**
     * FNV-1a 64 位元（UTF-8），最後再攪一次：FNV 的低位元只看得到每個位元組的低位元，
     * 中文字的 UTF-8 只差在高位元時驗證碼會一樣，攪過之後每個位元都看得到全部的字。
     */
    private static long hash(String text) {
        long value = FNV_OFFSET;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            value ^= b & 0xff;
            value *= FNV_PRIME;
        }
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
