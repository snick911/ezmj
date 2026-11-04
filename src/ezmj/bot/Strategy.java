package ezmj.bot;

import java.util.List;
import java.util.Random;

import ezmj.engine.Claim;
import ezmj.engine.TurnOptions;
import ezmj.model.BotLevel;
import ezmj.model.Personality;
import ezmj.model.Tile;

/** 一個等級的思考方式。只讀 TableView、只回傳決定；能胡就胡由 ComputerBot 處理，不用問策略。 */
public interface Strategy {

    /** 手上是 3n+2 張時要打哪一張。 */
    Tile chooseDiscard(TableView view);

    /** 要不要槓；回傳要槓的牌，null 表示不槓。 */
    Tile chooseKong(TableView view, TurnOptions options);

    /** 從吃、碰、槓的選項挑一個；回傳 null 表示不宣告。 */
    Claim chooseClaim(TableView view, List<Claim> options);

    /** 剛打出牌、已經聽牌，要不要報聽。waits 是現在聽的牌。報聽多 1 台，但手牌不能再換、不能吃碰槓，別家也會知道。 */
    boolean declareListening(TableView view, List<Tile> waits);

    static Strategy of(BotLevel level, int basePay, int taiPay, Random random) {
        return of(level, Personality.BALANCED, basePay, taiPay, random);
    }

    /** 初學者憑感覺打，沒有個性之分。 */
    static Strategy of(BotLevel level, Personality personality, int basePay, int taiPay, Random random) {
        switch (level) {
            case BEGINNER: return new BeginnerStrategy(random);
            case NORMAL:   return new NormalStrategy(personality);
            case AMATEUR:  return new AmateurStrategy(personality);
            default:       return new ProStrategy(personality, basePay, taiPay);
        }
    }
}
