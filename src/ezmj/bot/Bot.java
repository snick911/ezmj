package ezmj.bot;

import java.util.List;

import ezmj.engine.Claim;
import ezmj.engine.Round;
import ezmj.engine.TurnOptions;

/** 電腦玩家的決策介面。之後要加 AI 時實作這個介面即可，引擎與畫面都不用改。 */
public interface Bot {

    /** 輪到自己時的動作：自摸、槓或打牌。 */
    void playTurn(Round round, int seat, TurnOptions options);

    /** 對別人打出的牌要宣告什麼；回傳 null 表示不宣告。options 不為空才會被呼叫。 */
    Claim chooseClaim(Round round, int seat, List<Claim> options);
}
