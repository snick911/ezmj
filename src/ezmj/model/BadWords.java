package ezmj.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 不雅字眼。名字與聊天都不能有：練習牌局與修業檔案的名字、連線對戰的名字與房間名稱、聊天訊息。
 * Swing 版與網頁版共用這一份（網頁由 TeaVM 直接編譯這個檔案），要加減字詞改這裡就好。
 *
 * <p>比對前把全形英數換成半形、英文換成小寫。中文的字詞跳過空白、標點與符號，「幹 你 娘」「靠.北」一樣算。
 * 英文一個字一個字比，字中間的 * . - _ ' 不算斷開，@ 當 a、$ 當 s、字中間的 ! 當 i，f*ck、f.u.c.k、a$$、sh!t 一樣算；
 * 一個字母一個字母用空白隔開的（f u c k）接起來再比一次。有些英文要整個字一樣才算，ass 不會抓到 class、pass。
 * 常用詞裡剛好有的字另外列在 ALLOWED，整段都落在這些詞裡就不算，例如幹嘛、樹幹、三小時、我操心。
 *
 * <p>不用正規表示式，TeaVM 編成網頁也一樣跑。
 */
public final class BadWords {

    /** 名字有不雅字眼時的提醒。 */
    public static final String NAME_REJECTED = "名字不能有不雅的字眼，請換一個名字。";

    /** 房間名稱有不雅字眼時的提醒。 */
    public static final String ROOM_NAME_REJECTED = "房間名稱不能有不雅的字眼，請換一個名稱。";

    /** 聊天訊息有不雅字眼時的提醒，訊息不會送出。 */
    public static final String CHAT_REJECTED = "訊息裡有不雅的字眼，沒有送出。";

    /** 收到別人的聊天時，不雅字眼的每個字換成這個。 */
    public static final char MASK = '＊';

    /** 中文，也有夾著英文字母或注音的。跳過空白與標點，字串裡有就算。都是小寫、半形。 */
    private static final String[] PHRASES = {
        // 單一個字就不雅；幹在常用詞裡的寫在 ALLOWED
        "幹", "淦", "屌", "㞗", "屄", "肏", "姦", "婊", "嫖", "尻", "潲",
        // 罵人的話。幹你娘這類單一個字已經算了，列出來是為了收到的聊天整句遮起來
        "幹你娘", "幹拎娘", "幹林娘", "幹恁娘", "幹您娘", "你娘咧", "你娘的", "他娘的", "恁娘", "恁老母", "恁祖媽", "恁爸", "恁北", "林北", "林杯", "拎北", "拎杯",
        "你老母", "你老木", "他媽的", "你媽的", "去你媽", "操你", "操妳", "操他", "操她", "操機", "操蛋", "我操",
        "臥槽", "草泥馬", "靠北", "靠杯", "靠腰", "靠夭", "靠么", "靠邀",
        "機掰", "雞掰", "機歪", "雞歪", "機八", "雞八", "雞巴", "機巴", "懶叫", "卵叫", "懶趴", "卵葩", "懶葩",
        "三小", "啥小", "殺小", "蝦小", "傻逼", "傻b", "傻屄", "煞筆", "牛逼", "裝逼",
        "白癡", "白痴", "智障", "腦殘", "北七", "低能", "弱智", "去死", "死全家", "全家死光",
        "賤人", "賤貨", "騷貨", "破麻", "妓女", "王八蛋", "王八羔子", "狗娘養", "吃屎", "屁眼", "支那", "黑鬼",
        // 色情
        "做愛", "打砲", "打炮", "約砲", "口交", "肛交", "性交", "陰莖", "陰道", "陰蒂", "陰毛", "奶子", "巨乳",
        "淫蕩", "淫亂", "淫賤", "賣淫", "強奸", "輪奸", "射精", "精液", "自慰", "手淫", "打手槍", "a片",
        // 簡體
        "干你娘", "干你妈", "干林娘", "干拎娘", "干恁娘", "他妈的", "你妈的", "去你妈", "操你妈", "草你妈", "卧槽",
        "草泥马", "鸡巴", "鸡掰", "鸡歪", "懒叫", "傻屌", "煞笔", "装逼", "白痴", "脑残", "贱人", "贱货", "骚货",
        "狗娘养", "做爱", "约炮", "阴茎", "阴道", "阴蒂", "阴毛", "淫荡", "淫乱", "卖淫", "强奸", "轮奸",
        // 注音
        "ㄍㄢˋ", "ㄍㄋㄋ", "ㄎㄅ",
    };

    /** 常用詞裡剛好有 PHRASES 的字，整段都落在這些詞裡就不算。 */
    private static final String[] ALLOWED = {
        "幹嘛", "幹麻", "幹嗎", "幹麼", "幹啥", "幹什麼", "幹甚麼", "幹掉", "幹得", "幹部", "幹事", "幹線", "幹道",
        "幹勁", "幹活", "幹練", "幹才", "幹員", "幹細胞", "幹大事", "大幹一場", "能幹", "才幹", "實幹", "苦幹", "精幹",
        "骨幹", "主幹", "樹幹", "枝幹", "軀幹", "硬幹", "蠻幹", "肯幹", "巧幹", "單幹", "不幹",
        "三小時", "三小姐", "三小孩", "三小組", "三小節",
        "低能量", "低能耗", "低能見度", "低能源",
        "做愛心",
        "我操心", "我操作", "我操控", "我操盤", "我操練",
    };

    /** 英文：字裡面有就算。 */
    private static final String[] WORD_PARTS = {
        "fuck", "fck", "fvck", "phuck", "shit", "sh1t", "bitch", "biatch", "cunt", "nigger", "nigga", "whore", "slut",
        "bastard", "asshole", "dumbass", "jackass", "dickhead", "pussy", "porn", "dildo", "wanker", "retard", "faggot",
        "penis", "kaobei", "ganlinna", "ganinna",
    };

    /** 英文：整個字一樣才算，避免抓到 class、cocktail、cumulative 這類正常的字。 */
    private static final String[] WHOLE_WORDS = {
        "ass", "arse", "dick", "dicks", "cock", "cocks", "cum", "tits", "titty", "titties", "boob", "boobs", "fuk",
        "fuq", "fag", "fags", "twat", "wtf", "stfu", "gtfo", "sb", "cnm", "nmsl", "tmd", "nmb", "mlgb", "linbei",
    };

    private BadWords() {
    }

    /** text 裡有不雅字眼。null 或空字串都沒有。 */
    public static boolean contains(String text) {
        return marks(text) != null;
    }

    /** 不雅字眼的每個字換成 MASK，其他照舊。收到別人的聊天時用，舊版的程式沒有擋。 */
    public static String mask(String text) {
        boolean[] marked = marks(text);
        if (marked == null) {
            return text;
        }
        StringBuilder masked = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            masked.append(marked[i] ? MASK : text.charAt(i));
        }
        return masked.toString();
    }

    /** 要遮住的字，和 text 一樣長；沒有不雅字眼就是 null。 */
    private static boolean[] marks(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        boolean[] marked = new boolean[text.length()];
        boolean found = markPhrases(text, marked);
        found |= markWords(text, marked);
        return found ? marked : null;
    }

    /** 全形英數換成半形，英文換成小寫。 */
    private static char fold(char c) {
        char folded = c >= '！' && c <= '～' ? (char) (c - 0xFEE0) : c;
        return folded >= 'A' && folded <= 'Z' ? (char) (folded + ('a' - 'A')) : folded;
    }

    private static boolean isLatin(char folded) {
        return folded >= 'a' && folded <= 'z';
    }

    private static boolean isWordChar(char folded) {
        return isLatin(folded) || (folded >= '0' && folded <= '9') || folded == '@' || folded == '$';
    }

    /** 英文字中間可以夾著，不算斷開。 */
    private static boolean isJoiner(char folded) {
        return folded == '*' || folded == '.' || folded == '-' || folded == '_' || folded == '\'';
    }

    /** 中文的字詞：只留字母與數字比對，再對回原本的位置。 */
    private static boolean markPhrases(String text, boolean[] marked) {
        StringBuilder kept = new StringBuilder(text.length());
        int[] origin = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = fold(text.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                origin[kept.length()] = i;
                kept.append(c);
            }
        }
        String compact = kept.toString();
        boolean[] allowed = new boolean[compact.length()];
        for (String word : ALLOWED) {
            for (int at = compact.indexOf(word); at >= 0; at = compact.indexOf(word, at + 1)) {
                for (int k = at; k < at + word.length(); k++) {
                    allowed[k] = true;
                }
            }
        }
        boolean found = false;
        for (String word : PHRASES) {
            for (int at = compact.indexOf(word); at >= 0; at = compact.indexOf(word, at + 1)) {
                int end = at + word.length();
                if (allCovered(allowed, at, end) || insideLatinWord(compact, word, at, end)) {
                    continue;
                }
                found = true;
                for (int k = at; k < end; k++) {
                    marked[origin[k]] = true;
                }
            }
        }
        return found;
    }

    private static boolean allCovered(boolean[] allowed, int from, int to) {
        for (int k = from; k < to; k++) {
            if (!allowed[k]) {
                return false;
            }
        }
        return true;
    }

    /** 開頭或結尾是英文字母的字詞，旁邊還接著英文字母就是別的字的一部分，例如 data片段 不是 a片。 */
    private static boolean insideLatinWord(String compact, String word, int at, int end) {
        return (isLatin(word.charAt(0)) && at > 0 && isLatin(compact.charAt(at - 1)))
                || (isLatin(word.charAt(word.length() - 1)) && end < compact.length() && isLatin(compact.charAt(end)));
    }

    /** 一個英文字：原本的位置與比對用的字母。 */
    private record Word(int start, int end, String letters) {
    }

    /** 英文：一個字一個字比，一個字母一個字母隔開的再接起來比一次。 */
    private static boolean markWords(String text, boolean[] marked) {
        List<Word> words = words(text);
        boolean found = false;
        for (Word word : words) {
            if (isBadWord(word.letters())) {
                mark(marked, word.start(), word.end());
                found = true;
            }
        }
        for (int first = 0; first < words.size(); ) {
            int last = first;
            if (words.get(first).letters().length() == 1) {
                StringBuilder joined = new StringBuilder(words.get(first).letters());
                while (last + 1 < words.size() && words.get(last + 1).letters().length() == 1
                        && onlySeparators(text, words.get(last).end(), words.get(last + 1).start())) {
                    last++;
                    joined.append(words.get(last).letters());
                }
                if (last > first && isBadWord(joined.toString())) {
                    // 只遮字母，中間的空白照舊
                    for (int k = first; k <= last; k++) {
                        mark(marked, words.get(k).start(), words.get(k).end());
                    }
                    found = true;
                }
            }
            first = last + 1;
        }
        return found;
    }

    private static List<Word> words(String text) {
        List<Word> words = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (!isWordChar(fold(text.charAt(i)))) {
                i++;
                continue;
            }
            int start = i;
            StringBuilder letters = new StringBuilder();
            while (i < text.length()) {
                char c = fold(text.charAt(i));
                boolean between = i + 1 < text.length() && isWordChar(fold(text.charAt(i + 1)));
                if (isWordChar(c)) {
                    letters.append(c == '@' ? 'a' : c == '$' ? 's' : c);
                    i++;
                } else if (c == '!' && between) {
                    // sh!t
                    letters.append('i');
                    i++;
                } else if (isJoiner(c) && between) {
                    i++;
                } else {
                    break;
                }
            }
            words.add(new Word(start, i, letters.toString()));
        }
        return words;
    }

    /** 兩個字之間只有空白或標點，沒有中文字。 */
    private static boolean onlySeparators(String text, int from, int to) {
        for (int k = from; k < to; k++) {
            if (Character.isLetterOrDigit(text.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBadWord(String letters) {
        for (String whole : WHOLE_WORDS) {
            if (whole.equals(letters)) {
                return true;
            }
        }
        for (String part : WORD_PARTS) {
            if (letters.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static void mark(boolean[] marked, int from, int to) {
        for (int k = from; k < to; k++) {
            marked[k] = true;
        }
    }
}
