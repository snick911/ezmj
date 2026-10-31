package ezmj.engine;

import java.util.List;

/** 一局的金額結算。deltas 依座位排列，總和為 0。 */
public record Settlement(int[] deltas, List<String> notes) {
}
