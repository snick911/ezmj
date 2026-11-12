package ezmj.online;

import java.util.Map;

/**
 * 隨時補位：服務器的 substituteWaiting（只給在等的人）與 substituted（全桌）。seat 是服務器的座位。
 * replaced 是被換掉的那個座位原本的名字；computer 表示開局時就是電腦，不然是離開的玩家留下、由電腦代打的座位。
 * 等待時還不知道自己會用什麼名字上場，name 是空的。畫面上的字都在這裡，和網頁版 ezmj_web 的 Substitution 一樣。
 */
public record Substitution(int seat, String name, String replaced, boolean computer) {

    public static final String WAITING_TITLE = "已保留座位，等一下就換你上場";

    /** substituteWaiting：被換掉的座位名字在 replacing。 */
    public static Substitution waiting(Map<String, Object> message) {
        return new Substitution(seat(message), "", Fields.string(message, "replacing"), flag(message));
    }

    public static Substitution substituted(Map<String, Object> message) {
        return new Substitution(seat(message), Fields.string(message, "name"), Fields.string(message, "replaced"),
                flag(message));
    }

    /** 被換掉的那一家：「電腦（阿土伯）」，離開的玩家留下的座位是「電腦（代打「小明」的座位）」。 */
    public String replacedLabel() {
        return computer ? "電腦（" + replaced + "）" : "電腦（代打「" + replaced + "」的座位）";
    }

    /** 牌桌上的公告，例如「玩家 小明 替換電腦（阿土伯）加入」。mine 是自己補位上場。 */
    public String announcement(boolean mine) {
        return (mine ? "你" : "玩家 " + name + " ") + "替換" + replacedLabel() + "加入";
    }

    /** 公告下面的小字：中途加入的不列入榮譽榜。 */
    public static String honorNote(boolean mine) {
        return mine ? "中途加入，這一場不列入榮譽榜" : "中途補位的玩家不列入榮譽榜";
    }

    /** 等待畫面標題下面的說明。 */
    public String waitingDetail() {
        return "牌局正在進行，等" + replacedLabel() + "打完這一手就換你上場；這一局先結束的話，下一局開始時上場。"
                + "中途加入的不列入榮譽榜。";
    }

    private static int seat(Map<String, Object> message) {
        int seat = Fields.integer(message, "seat");
        if (seat < 0 || seat > 3) {
            throw new IllegalArgumentException("補位的座位不對：" + seat);
        }
        return seat;
    }

    private static boolean flag(Map<String, Object> message) {
        return message.get("computer") != null && Fields.bool(message, "computer");
    }
}
