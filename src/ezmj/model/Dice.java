package ezmj.model;

import java.util.Random;

/**
 * 開局時莊家擲的三顆骰子，點數決定從哪一家的牌牆、哪一墩開門，見 engine.WallRing。
 * 牌牆是洗好的，從哪裡開門拿到什麼牌的機率都一樣，所以只影響畫面，不影響牌局。
 */
public record Dice(int first, int second, int third) {

    private static final int FACES = 6;

    public Dice {
        for (int face : new int[] {first, second, third}) {
            if (face < 1 || face > FACES) {
                throw new IllegalArgumentException("骰子是 1 到 6 點：" + face);
            }
        }
    }

    /** 三顆加起來，3 到 18 點。 */
    public int total() {
        return first + second + third;
    }

    public static Dice roll(Random random) {
        return new Dice(random.nextInt(FACES) + 1, random.nextInt(FACES) + 1, random.nextInt(FACES) + 1);
    }

    /**
     * 同樣的 key 一定擲出同樣的點數。連線時服務器沒有送骰子，全桌用同樣的資料算，每家看到的才一樣。
     * 只用 String.hashCode 與 int 運算，網頁版編成 JavaScript 算出來也一樣。
     */
    public static Dice of(String key) {
        int hash = key.hashCode();
        int a = mix(hash);
        int b = mix(a ^ 0x632BE5AB);
        int c = mix(b ^ 0x2545F491);
        return new Dice(face(a), face(b), face(c));
    }

    private static int face(int value) {
        return (value >>> 1) % FACES + 1;
    }

    /** 把相近的 hash 打散，房號只差一個字的點數也不會差不多。 */
    private static int mix(int value) {
        int x = value;
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }

    /** 紀錄上的寫法，例如「4、5、2，共 11 點」。 */
    public String describe() {
        return first + "、" + second + "、" + third + "，共 " + total() + " 點";
    }
}
