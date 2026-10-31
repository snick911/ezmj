package ezmj.engine;

/**
 * 一局的金額結算。兩個陣列都依座位排列。
 * deltas 是輸贏，總和為 0。tais 是付款算的台數（含莊家與連莊），沒付款的座位是 0；
 * 屁胡付款也可能是 0 台，有沒有付款要看 deltas。
 */
public record Settlement(int[] deltas, int[] tais) {
}
