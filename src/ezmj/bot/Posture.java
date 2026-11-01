package ezmj.bot;

import ezmj.bot.TableView.Opponent;
import ezmj.model.Personality;

/**
 * 職業等級這一手的心態，由個性與籌碼局勢決定。穩健又沒有特別局勢時每個值都是中性，等於原本的算法。
 * attack 放大胡牌的期望收入、caution 放大放槍的期望賠款、ambition 放大大牌的台數估計；
 * nearlyAsFast 是願意為了別的考量少多少進張；foldBelow 是胡牌機會低於多少就考慮棄胡。
 */
record Posture(double attack, double caution, double ambition, double nearlyAsFast, double foldBelow) {

    private static final int LAST_ROUND_WIND = 4;
    /** 領先或落後超過這麼多筆一般付款，才算有差距。 */
    private static final double CLEAR_GAP_PAYMENTS = 10;
    /** 籌碼只夠付這麼多筆一般付款時，放一槍可能就輸光。 */
    private static final double SHORT_STACK_PAYMENTS = 8;
    private static final int TYPICAL_TAI = 3;

    static Posture of(Personality personality, TableView view, int basePay, int taiPay) {
        return base(personality).inSituation(view, basePay + TYPICAL_TAI * taiPay);
    }

    private static Posture base(Personality personality) {
        switch (personality) {
            case BOLD:     return new Posture(1.2, 0.7, 1, 0.95, 0.04);
            case CAUTIOUS: return new Posture(0.9, 1.5, 1, 0.8, 0.18);
            case GREEDY:   return new Posture(1, 1, 3, 0.6, 0.1);
            default:       return new Posture(1, 1, 1, 0.9, 0.1);
        }
    }

    /** 籌碼快沒了就保命；最後一圈領先就守住，落後就拚大牌。其他時候只看牌。 */
    private Posture inSituation(TableView view, double payment) {
        int highest = Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        for (Opponent opponent : view.opponents()) {
            highest = Math.max(highest, opponent.chips());
            lowest = Math.min(lowest, opponent.chips());
        }
        Posture posture = this;
        if (view.roundWind() >= LAST_ROUND_WIND) {
            if (view.chips() - highest >= CLEAR_GAP_PAYMENTS * payment) {
                posture = posture.scaled(0.9, 1.3, 1);
            } else if (lowest - view.chips() >= CLEAR_GAP_PAYMENTS * payment) {
                posture = posture.scaled(1.2, 0.8, 1.3);
            }
        }
        if (view.chips() <= SHORT_STACK_PAYMENTS * payment) {
            posture = posture.scaled(1, 1.6, 1);
        }
        return posture;
    }

    private Posture scaled(double attackBy, double cautionBy, double ambitionBy) {
        return new Posture(attack * attackBy, caution * cautionBy, ambition * ambitionBy, nearlyAsFast, foldBelow);
    }
}
