package ezmj.engine;

/** 打完的一局。roundWind、dealer、streak 是這局開打時的狀態，deltas 依座位排列。 */
public record RoundRecord(int number, int roundWind, int dealer, int streak, RoundResult result, int[] deltas) {
}
