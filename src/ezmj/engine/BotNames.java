package ezmj.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import ezmj.model.Gender;

/** 電腦玩家的角色：隨機的性別，加上隨機的姓氏與符合性別的名字。 */
public final class BotNames {

    /** 一個電腦角色。 */
    public record Identity(String name, Gender gender) {
    }

    private static final String[] SURNAMES = {
        "陳", "林", "黃", "張", "李", "王", "吳", "劉", "蔡", "楊",
        "許", "鄭", "謝", "郭", "洪", "曾", "邱", "廖", "賴", "徐",
        "周", "葉", "蘇", "莊", "呂", "江", "何", "蕭", "羅", "高",
        "彭", "簡", "朱", "鍾", "游", "詹", "胡", "施", "沈", "余",
        "歐陽", "司馬", "上官", "諸葛", "司徒",
    };
    private static final String[] FEMALE_NAMES = {
        "智慧", "珍珍", "春嬌", "雅婷", "怡君", "淑芬", "美玲", "麗華", "秀英", "佳穎",
        "欣怡", "詩涵", "婉如", "玉蘭", "月娥", "小美", "靜宜", "惠美", "素娥", "淑惠",
        "家瑜", "宜蓁", "曉雯", "阿嬌",
    };
    private static final String[] MALE_NAMES = {
        "小明", "大衛", "忠義", "志明", "家豪", "俊傑", "建宏", "文雄", "明德", "國強",
        "金水", "阿土", "阿財", "來福", "旺財", "天佑", "宗翰", "冠宇", "承恩", "柏翰",
        "正雄", "榮發", "進財", "大同",
    };

    private BotNames() {
    }

    /** 挑出 count 個名字互不相同的角色，名字也不會與 taken 相同。 */
    public static List<Identity> pick(int count, String taken, Random random) {
        List<Identity> identities = new ArrayList<>();
        Set<String> names = new HashSet<>();
        while (identities.size() < count) {
            Gender gender = random.nextBoolean() ? Gender.FEMALE : Gender.MALE;
            String[] given = gender == Gender.FEMALE ? FEMALE_NAMES : MALE_NAMES;
            String name = SURNAMES[random.nextInt(SURNAMES.length)] + given[random.nextInt(given.length)];
            if (!name.equals(taken) && names.add(name)) {
                identities.add(new Identity(name, gender));
            }
        }
        return List.copyOf(identities);
    }
}
