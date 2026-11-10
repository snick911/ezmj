package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.test;

import ezmj.model.BadWords;

/** 不雅字眼：中文、英文、夾空白與符號、全形、常用詞不算，以及收到的聊天遮起來。 */
final class BadWordsTest {

    private BadWordsTest() {
    }

    private static void bad(String text) {
        check(BadWords.contains(text), "應該算不雅：" + text);
    }

    private static void fine(String text) {
        check(!BadWords.contains(text), "不應該算不雅：" + text);
    }

    static void run() {
        T.section("不雅字眼");

        test("中文的髒話與罵人的話", () -> {
            bad("幹");
            bad("幹你娘");
            bad("靠北喔");
            bad("你是白癡嗎");
            bad("機掰人");
            bad("林北最大");
            bad("他媽的");
            bad("屌哥");
            bad("傻逼");
            bad("看A片");
        });

        test("夾著空白、標點與符號也算", () -> {
            bad("幹 你 娘");
            bad("靠.北");
            bad("白-癡");
            bad("機♥掰");
        });

        test("英文：字裡有就算、全形、大小寫、夾符號與一個字母一個字母隔開", () -> {
            bad("fuck");
            bad("FUCKING");
            bad("motherfucker");
            bad("ｆｕｃｋ");
            bad("f*ck");
            bad("f.u.c.k");
            bad("f u c k");
            bad("sh!t you");
            bad("bullshit");
            bad("a$$");
            bad("you ass");
            bad("WTF");
        });

        test("常用詞與正常的英文不算", () -> {
            fine("你在幹嘛");
            fine("幹得好");
            fine("我不幹了");
            fine("樹幹");
            fine("他很能幹");
            fine("打了三小時");
            fine("我操心太多");
            fine("我操作失誤");
            fine("低能量");
            fine("data片段");
            fine("class");
            fine("pass");
            fine("cocktail");
            fine("cumulative");
            fine("assist");
            fine("Scott");
            fine("自摸");
            fine("屁胡");
            fine("放槍");
            fine("狗屎運");
            fine("胡了 謝謝大家");
            fine("Hello world");
            fine("");
            fine(null);
        });

        test("常用詞旁邊又接著髒話照樣算", () -> {
            bad("幹嘛幹你娘");
            bad("不幹你娘");
            bad("樹幹 靠北");
        });

        test("收到的聊天：不雅字眼換成＊，其他照舊", () -> {
            eq("＊＊＊＊！", BadWords.mask("幹你娘咧！"), "中文");
            eq("你在幹嘛", BadWords.mask("你在幹嘛"), "沒有就照舊");
            eq("＊ ＊ ＊打", BadWords.mask("幹 你 娘打"), "夾空白的只遮字");
            eq("what the ＊＊＊＊", BadWords.mask("what the fuck"), "英文");
            eq("＊ ＊ ＊ ＊ off", BadWords.mask("f u c k off"), "一個字母一個字母的只遮字母");
            eq("好牌＊＊", BadWords.mask("好牌靠北"), "後面");
        });
    }
}
