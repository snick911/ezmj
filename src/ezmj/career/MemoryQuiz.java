package ezmj.career;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ezmj.model.Tile;

/**
 * 記牌的題目：牌局停下來的時候，照剛才打出的牌依序出題。題型有四種，從題庫輪流抽：
 * <ul>
 * <li>最後打出的幾張牌依序是什麼（照順序點牌）</li>
 * <li>某一家最後兩張打了什麼（照順序點牌）</li>
 * <li>某張牌到現在被打出了幾張（選 0～4 張）</li>
 * <li>某張牌是誰打的（選是哪一家）</li>
 * </ul>
 * 被吃、碰、槓走的牌也算打出過。座位用「東家（王小明）」這樣的名稱，由外面照這局的門風給。不依賴 Swing。
 */
public final class MemoryQuiz {

    /** 打出的一張牌：誰打的、打了什麼。 */
    public record Discard(int seat, Tile tile) {
    }

    /** 題目怎麼作答。 */
    public enum Answer {
        /** 照順序點牌，點的張數是 length。 */
        TILES,
        /** 從 choices 裡選一個。 */
        CHOICE
    }

    /**
     * 一題。TILES 的正解是 tiles（照順序）；CHOICE 的正解是 choices 的第 choice 個。
     */
    public record Question(String prompt, Answer answer, List<Tile> tiles, List<String> choices, int choice) {

        /** 要點幾張牌；選擇題是 0。 */
        public int length() {
            return answer == Answer.TILES ? tiles.size() : 0;
        }

        /** 點的牌照順序都對才算對。 */
        public boolean correctTiles(List<Tile> picked) {
            return answer == Answer.TILES && picked != null && picked.equals(tiles);
        }

        public boolean correctChoice(int picked) {
            return answer == Answer.CHOICE && picked == choice;
        }

        /** 給玩家看的正解，例如「七萬、三條、東風」或「南家（王小明）」。 */
        public String solution() {
            if (answer == Answer.CHOICE) {
                return choices.get(choice);
            }
            List<String> names = new ArrayList<>();
            for (Tile tile : tiles) {
                names.add(name(tile));
            }
            return String.join("、", names);
        }
    }

    private enum Kind {
        LAST_TILES, SEAT_LAST_TWO, COUNT, WHO
    }

    private static final int MAX_LAST = 4;
    private static final int MIN_LAST = 2;
    private static final int SEATS = 4;

    private MemoryQuiz() {
    }

    /** 牌的叫法：數牌照牌面，字牌叫「東風」「紅中」「發財」「白板」。 */
    public static String name(Tile tile) {
        switch (tile.suit()) {
            case WIND:
                return tile.label() + "風";
            case DRAGON:
                return tile.rank() == 1 ? "紅中" : tile.rank() == 2 ? "發財" : "白板";
            default:
                return tile.label();
        }
    }

    /**
     * 出 count 題。discards 是照順序打出的牌；seatLabels 是每個座位的名稱（照座位號），
     * seatOrder 是選「誰打的」時選項的順序（例如照門風東南西北）。打出的牌太少時，能出的題型會少一點，
     * 但一定出到 count 題（至少要有一張打出的牌）。
     */
    public static List<Question> questions(List<Discard> discards, String[] seatLabels, List<Integer> seatOrder,
                                           int count, Random random) {
        if (discards.isEmpty()) {
            throw new IllegalArgumentException("一張牌都還沒打出，問不了");
        }
        List<Question> questions = new ArrayList<>();
        Set<String> asked = new LinkedHashSet<>();
        List<Kind> kinds = new ArrayList<>(List.of(Kind.values()));
        Collections.shuffle(kinds, random);
        int next = 0;
        int tries = 0;
        while (questions.size() < count && tries < 400) {
            tries++;
            Kind kind = kinds.get(next % kinds.size());
            next++;
            Question question = make(kind, discards, seatLabels, seatOrder, random);
            // 一樣的題目不問兩次；實在湊不出不一樣的才允許重複
            if (question != null && (asked.add(question.prompt()) || tries > 200)) {
                questions.add(question);
            }
        }
        return questions;
    }

    private static Question make(Kind kind, List<Discard> discards, String[] seatLabels, List<Integer> seatOrder,
                                 Random random) {
        switch (kind) {
            case LAST_TILES:
                return lastTiles(discards);
            case SEAT_LAST_TWO:
                return seatLastTwo(discards, seatLabels, random);
            case COUNT:
                return count(discards, random);
            default:
                return who(discards, seatLabels, seatOrder, random);
        }
    }

    /** 最後打出的 2～4 張依序是什麼。 */
    private static Question lastTiles(List<Discard> discards) {
        int length = Math.min(MAX_LAST, discards.size());
        if (length < MIN_LAST) {
            return null;
        }
        List<Tile> tiles = new ArrayList<>();
        for (Discard discard : discards.subList(discards.size() - length, discards.size())) {
            tiles.add(discard.tile());
        }
        return new Question("最後打出的 " + length + " 張牌，依序是什麼？（先打的先點）", Answer.TILES, tiles, List.of(), -1);
    }

    /** 某一家最後兩張打了什麼；打不到兩張的家不問。 */
    private static Question seatLastTwo(List<Discard> discards, String[] seatLabels, Random random) {
        List<Integer> seats = new ArrayList<>();
        for (int seat = 0; seat < SEATS; seat++) {
            if (bySeat(discards, seat).size() >= 2) {
                seats.add(seat);
            }
        }
        if (seats.isEmpty()) {
            return null;
        }
        int seat = seats.get(random.nextInt(seats.size()));
        List<Tile> own = bySeat(discards, seat);
        return new Question(seatLabels[seat] + "最後兩張打了什麼？（先打的先點）", Answer.TILES,
                List.copyOf(own.subList(own.size() - 2, own.size())), List.of(), -1);
    }

    /** 某張牌被打出了幾張：大約六成問打過的牌，四成問沒打過的。 */
    private static Question count(List<Discard> discards, Random random) {
        int[] counts = new int[Tile.KINDS];
        for (Discard discard : discards) {
            counts[discard.tile().index()]++;
        }
        List<Tile> seen = new ArrayList<>();
        List<Tile> unseen = new ArrayList<>();
        for (int index = 0; index < Tile.KINDS; index++) {
            (counts[index] > 0 ? seen : unseen).add(Tile.fromIndex(index));
        }
        List<Tile> pool = !seen.isEmpty() && (unseen.isEmpty() || random.nextDouble() < .6) ? seen : unseen;
        Tile tile = pool.get(random.nextInt(pool.size()));
        List<String> choices = List.of("0 張", "1 張", "2 張", "3 張", "4 張");
        return new Question("到現在，" + name(tile) + "一共被打出了幾張？", Answer.CHOICE, List.of(), choices,
                Math.min(4, counts[tile.index()]));
    }

    /** 某張牌是誰打的：只問只有一家打過的牌，答案才不會有兩個。 */
    private static Question who(List<Discard> discards, String[] seatLabels, List<Integer> seatOrder, Random random) {
        List<Tile> single = new ArrayList<>();
        for (int index = 0; index < Tile.KINDS; index++) {
            Tile tile = Tile.fromIndex(index);
            Set<Integer> seats = new LinkedHashSet<>();
            for (Discard discard : discards) {
                if (discard.tile().equals(tile)) {
                    seats.add(discard.seat());
                }
            }
            if (seats.size() == 1) {
                single.add(tile);
            }
        }
        if (single.isEmpty()) {
            return null;
        }
        Tile tile = single.get(random.nextInt(single.size()));
        int seat = -1;
        for (Discard discard : discards) {
            if (discard.tile().equals(tile)) {
                seat = discard.seat();
            }
        }
        List<String> choices = new ArrayList<>();
        for (int each : seatOrder) {
            choices.add(seatLabels[each]);
        }
        return new Question(name(tile) + "是誰打的？", Answer.CHOICE, List.of(), List.copyOf(choices),
                seatOrder.indexOf(seat));
    }

    private static List<Tile> bySeat(List<Discard> discards, int seat) {
        List<Tile> tiles = new ArrayList<>();
        for (Discard discard : discards) {
            if (discard.seat() == seat) {
                tiles.add(discard.tile());
            }
        }
        return tiles;
    }
}
