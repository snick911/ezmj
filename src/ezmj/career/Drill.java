package ezmj.career;

/**
 * 修練關卡的設定。修練不是完整的牌局：記牌是打到一半停下來問答，看聽是看一手牌、點出它聽哪幾種。
 * 每題都限時，逾時算錯；答對 passCount 題過關，全部答對加一顆星，平均作答時間在 fastSeconds 以內再加一顆。
 *
 * @param minRounds   記牌：最早停在第幾輪（一輪是四家各打一張）
 * @param maxRounds   記牌：最晚停在第幾輪
 * @param minWaits    看聽：聽幾種牌，最少
 * @param maxWaits    看聽：聽幾種牌，最多
 * @param oneSuit     看聽：多出一色牌（清一色的手牌，聽的種類多又容易看錯）
 * @param viewSeconds 看聽：先亮牌幾秒，時間到牌就蓋起來
 */
public record Drill(Kind kind, int minRounds, int maxRounds, int minWaits, int maxWaits, boolean oneSuit,
                    int viewSeconds, int questions, int answerSeconds, int passCount, int fastSeconds) {

    /** 修練的種類。 */
    public enum Kind {
        /** 記牌：照常打牌，牌河只看得到每家最後一張，打到隨機的一輪停下來問打過什麼。 */
        MEMORY,
        /** 看聽：看一手聽牌的 16 張，時間到牌蓋起來，點出它聽哪幾種。 */
        WAITS
    }

    public Drill {
        if (questions <= 0 || passCount <= 0 || passCount > questions || answerSeconds <= 0 || fastSeconds <= 0) {
            throw new IllegalArgumentException("修練的題數或秒數不對");
        }
        if (kind == Kind.MEMORY && (minRounds < 1 || maxRounds < minRounds)) {
            throw new IllegalArgumentException("記牌停在第幾輪不對");
        }
        if (kind == Kind.WAITS && (minWaits < 1 || maxWaits < minWaits || viewSeconds <= 0)) {
            throw new IllegalArgumentException("看聽的設定不對");
        }
    }

    /** 記牌：停在第 minRounds～maxRounds 輪，問 questions 題，每題 seconds 秒，答對 pass 題過關。 */
    static Drill memory(int minRounds, int maxRounds, int questions, int seconds, int pass, int fastSeconds) {
        return new Drill(Kind.MEMORY, minRounds, maxRounds, 0, 0, false, 0, questions, seconds, pass, fastSeconds);
    }

    /** 看聽：聽 minWaits～maxWaits 種，先看 viewSeconds 秒、作答 answerSeconds 秒，hands 手對 pass 手過關。 */
    static Drill waits(int minWaits, int maxWaits, boolean oneSuit, int viewSeconds, int answerSeconds, int hands,
                       int pass, int fastSeconds) {
        return new Drill(Kind.WAITS, 0, 0, minWaits, maxWaits, oneSuit, viewSeconds, hands, answerSeconds, pass,
                fastSeconds);
    }

    public boolean isMemory() {
        return kind == Kind.MEMORY;
    }

    /**
     * 這次修練拿到哪幾顆星，和 Challenge.Check 的 stars 一樣：第一顆是過關，第二顆是全部答對，
     * 第三顆是平均作答時間在 fastSeconds 以內。沒過關是 0。
     */
    public int stars(int correct, double averageSeconds) {
        if (correct < passCount) {
            return 0;
        }
        int stars = 1;
        if (correct >= questions) {
            stars |= 1 << 1;
        }
        if (averageSeconds <= fastSeconds) {
            stars |= 1 << 2;
        }
        return stars;
    }

    /** 第二、第三顆星的說明。 */
    String allCorrectText() {
        return "全部答對";
    }

    String fastText() {
        return "平均 " + fastSeconds + " 秒內作答";
    }

    /** 例如「三家一般・第 2～3 輪停下來問・3 題・每題 15 秒・答對 2 題過關」，level 是記牌的對手。 */
    String setup(String level) {
        if (isMemory()) {
            return "三家" + level + "・第 " + minRounds + "～" + maxRounds + " 輪停下來問・" + questions + " 題・每題 "
                    + answerSeconds + " 秒・答對 " + passCount + " 題過關";
        }
        return "聽 " + minWaits + "～" + maxWaits + " 種" + (oneSuit ? "（多為一色牌）" : "") + "・看牌 " + viewSeconds
                + " 秒・作答 " + answerSeconds + " 秒・" + questions + " 手對 " + passCount + " 手過關";
    }
}
