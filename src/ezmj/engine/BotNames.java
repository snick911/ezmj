package ezmj.engine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** 電腦玩家的名字：隨機的姓氏加名字。 */
public final class BotNames {

    private static final String[] SURNAMES = {
        "陳", "林", "黃", "張", "李", "王", "吳", "劉", "蔡", "楊",
        "許", "鄭", "謝", "郭", "洪", "曾", "邱", "廖", "賴", "徐",
        "周", "葉", "蘇", "莊", "呂", "江", "何", "蕭", "羅", "高",
        "彭", "簡", "朱", "鍾", "游", "詹", "胡", "施", "沈", "余",
        "歐陽", "司馬", "上官", "諸葛", "司徒",
    };
    private static final String[] GIVEN_NAMES = {
        "小明", "大衛", "智慧", "珍珍", "忠義", "志明", "春嬌", "家豪", "俊傑", "建宏",
        "雅婷", "怡君", "淑芬", "美玲", "麗華", "秀英", "文雄", "明德", "國強", "金水",
        "阿土", "阿財", "來福", "旺財", "天佑", "宗翰", "冠宇", "承恩", "柏翰", "佳穎",
        "欣怡", "詩涵", "婉如", "玉蘭", "月娥", "正雄", "榮發", "進財", "大同", "小美",
    };

    private BotNames() {
    }

    /** 挑出 count 個互不相同的名字，也不會與 taken 相同。 */
    public static List<String> pick(int count, String taken, Random random) {
        Set<String> names = new LinkedHashSet<>();
        while (names.size() < count) {
            String name = SURNAMES[random.nextInt(SURNAMES.length)]
                    + GIVEN_NAMES[random.nextInt(GIVEN_NAMES.length)];
            if (!name.equals(taken)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }
}
