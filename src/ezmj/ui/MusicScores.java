package ezmj.ui;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ezmj.model.MusicTrack;

/**
 * 背景音樂的樂譜，全部是為這個遊戲寫的原創曲子，沒有版權問題。
 * 音符記法：音名加八度與拍數，例如 E5:1.5；R 是休止，x 是木魚。
 */
final class MusicScores {

    enum Instrument { PLUCK, KEYS, FLUTE, PAD, BASS, WOOD }

    record Voice(Instrument instrument, double gain, String notes) {
    }

    record Score(int bpm, int beatsPerBar, int bars, List<Voice> voices) {
        double totalBeats() {
            return beatsPerBar * bars;
        }
    }

    private static final Score TEAHOUSE = new Score(76, 4, 16, List.of(
            new Voice(Instrument.PLUCK, 1, "E5:1.5 G5:.5 A5:1 G5:1 | E5:1 C5:.5 D5:.5 E5:2"
                    + " | D5:1.5 E5:.5 A4:1 D5:1 | G4:.5 A4:.5 C5:.5 D5:.5 D5:2"
                    + " | E5:1.5 G5:.5 A5:1 C6:1 | A5:1 G5:.5 E5:.5 A5:2"
                    + " | G5:1 E5:.5 D5:.5 D5:1 G4:1 | C5:3 R:1"
                    + " | A4:1 C5:1 E5:1.5 D5:.5 | C5:1 E5:.5 G5:.5 G5:2"
                    + " | D5:1 G5:1 A5:.5 G5:.5 D5:1 | E5:.5 D5:.5 C5:1 A4:2"
                    + " | A4:.5 C5:.5 D5:1 E5:.5 D5:.5 A4:1 | G4:1 A4:.5 D5:.5 G5:2"
                    + " | E5:1 G5:.5 E5:.5 D5:1 E5:1 | C5:3 R:1"),
            new Voice(Instrument.PLUCK, .35, bars("C A D G C A G C A C G A D G C C", Map.of(
                    "C", "C3:.5 G3:.5 C4:.5 G3:.5 E4:.5 G3:.5 C4:.5 G3:.5",
                    "A", "A2:.5 E3:.5 A3:.5 E3:.5 C4:.5 E3:.5 A3:.5 E3:.5",
                    "D", "D3:.5 A3:.5 D4:.5 A3:.5 E4:.5 A3:.5 D4:.5 A3:.5",
                    "G", "G2:.5 D3:.5 G3:.5 D3:.5 D4:.5 D3:.5 G3:.5 D3:.5")))));

    private static final String MARKET_CHORDS = "G G E D G G A D E E G D E A D G";

    private static final Score NIGHT_MARKET = new Score(116, 4, 16, List.of(
            new Voice(Instrument.PLUCK, 1, "G5:.5 A5:.5 B5:1 D6:.5 B5:.5 A5:1 | G5:.5 E5:.5 G5:1 A5:.5 G5:.5 E5:1"
                    + " | E5:.5 G5:.5 B5:.5 G5:.5 E5:1 D5:1 | D5:.5 E5:.5 A5:1 D5:.5 A4:.5 D5:1"
                    + " | G5:.5 A5:.5 B5:1 D6:.5 E6:.5 D6:1 | B5:.5 D6:.5 B5:.5 A5:.5 G5:2"
                    + " | A5:.5 B5:.5 A5:.5 E5:.5 A5:1 E5:1 | D5:1 A4:.5 B4:.5 D5:2"
                    + " | E5:1 G5:.5 E5:.5 B4:1 E5:1 | D5:.5 E5:.5 G5:.5 A5:.5 B5:2"
                    + " | D6:.5 B5:.5 G5:1 B5:.5 A5:.5 G5:1 | A5:.5 G5:.5 E5:.5 D5:.5 A4:2"
                    + " | E5:.5 G5:.5 A5:.5 B5:.5 E6:1 D6:1 | B5:.5 A5:.5 E5:1 A5:.5 B5:.5 A5:1"
                    + " | D6:.5 B5:.5 A5:.5 E5:.5 D5:1 A5:1 | G5:2 D5:.5 E5:.5 G5:1"),
            new Voice(Instrument.PLUCK, .4, bars(MARKET_CHORDS, Map.of(
                    "G", "R:.5 D4:.5 R:.5 D4:.5 R:.5 D4:.5 R:.5 D4:.5",
                    "E", "R:.5 B3:.5 R:.5 B3:.5 R:.5 B3:.5 R:.5 B3:.5",
                    "D", "R:.5 A3:.5 R:.5 A3:.5 R:.5 A3:.5 R:.5 A3:.5",
                    "A", "R:.5 E4:.5 R:.5 E4:.5 R:.5 E4:.5 R:.5 E4:.5"))),
            new Voice(Instrument.BASS, .7, bars(MARKET_CHORDS, Map.of(
                    "G", "G2:1 D3:1 G2:1 D3:1",
                    "E", "E2:1 B2:1 E2:1 B2:1",
                    "D", "D2:1 A2:1 D2:1 A2:1",
                    "A", "A2:1 E3:1 A2:1 E3:1"))),
            new Voice(Instrument.WOOD, .3, "x:1 x:.5 x:.5 R:.5 x:.5 x:1 ".repeat(16))));

    private static final String BAMBOO_CHORDS = "A A C G A D G A C C G A D C G A";

    private static final Score BAMBOO = new Score(58, 4, 16, List.of(
            new Voice(Instrument.FLUTE, .9, "A5:3 G5:.5 E5:.5 | E5:3 R:1 | G5:1.5 A5:.5 G5:1 E5:1 | D5:3 R:1"
                    + " | C5:1 D5:1 E5:2 | A5:1.5 G5:.5 E5:1 D5:1 | D5:1 E5:.5 G5:.5 G5:2 | A5:3 R:1"
                    + " | C6:2 A5:1 G5:1 | E5:1.5 G5:.5 C5:2 | D5:1 G5:1 A5:1 G5:1 | E5:3 R:1"
                    + " | D5:1 E5:1 G5:1 A5:1 | C6:1.5 A5:.5 G5:2 | E5:1 D5:1 G4:2 | A4:3 R:1"),
            new Voice(Instrument.PAD, .35, bars(BAMBOO_CHORDS, Map.of(
                    "A", "A2:4", "C", "C3:4", "G", "G2:4", "D", "D3:4"))),
            new Voice(Instrument.PAD, .3, bars(BAMBOO_CHORDS, Map.of(
                    "A", "E3:4", "C", "G3:4", "G", "D3:4", "D", "A3:4"))),
            new Voice(Instrument.PLUCK, .3, bars(BAMBOO_CHORDS, Map.of(
                    "A", "A3:2 E4:2", "C", "C4:2 G4:2", "G", "G3:2 D4:2", "D", "D4:2 A4:2")))));

    private static final Score MOONLIGHT = new Score(92, 3, 16, List.of(
            new Voice(Instrument.KEYS, 1, "E5:1.5 D5:.5 C5:1 | A4:1 C5:1 E5:1 | F5:1.5 E5:.5 C5:1 | D5:2 G4:1"
                    + " | E5:1.5 G5:.5 E5:1 | A5:1.5 G5:.5 E5:1 | F5:1 A5:1 G5:.5 F5:.5 | D5:2 R:1"
                    + " | C5:1 E5:1 A5:1 | G5:1.5 E5:.5 B4:1 | C5:1 F5:1 A5:1 | G5:1.5 E5:.5 C5:1"
                    + " | A4:1 C5:1 F5:1 | B4:1 D5:1 G5:1 | E5:1 D5:1 G4:1 | C5:2 R:1"),
            new Voice(Instrument.KEYS, .45, bars("C Am F G C Am F G Am Em F C F G C C", Map.of(
                    "C", "C3:1 G3:1 E4:1",
                    "Am", "A2:1 E3:1 C4:1",
                    "F", "F2:1 C3:1 A3:1",
                    "G", "G2:1 D3:1 B3:1",
                    "Em", "E2:1 B2:1 G3:1")))));

    private MusicScores() {
    }

    static Score score(MusicTrack track) {
        switch (track) {
            case TEAHOUSE:
                return TEAHOUSE;
            case NIGHT_MARKET:
                return NIGHT_MARKET;
            case BAMBOO:
                return BAMBOO;
            case MOONLIGHT:
                return MOONLIGHT;
            default:
                throw new IllegalArgumentException("沒有樂譜: " + track);
        }
    }

    /** 依和弦順序把每小節的伴奏接起來。 */
    private static String bars(String chords, Map<String, String> patterns) {
        return Arrays.stream(chords.split(" ")).map(patterns::get).collect(Collectors.joining(" "));
    }
}
