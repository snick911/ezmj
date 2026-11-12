package ezmj.model;

import java.util.List;

/** 視窗大小的選項。MAXIMIZED 表示視窗最大化，不用固定寬高。 */
public record Resolution(int width, int height) {

    public static final Resolution MAXIMIZED = new Resolution(0, 0);
    public static final Resolution DEFAULT = new Resolution(1360, 900);
    public static final List<Resolution> OPTIONS = List.of(
            new Resolution(1280, 720),
            new Resolution(1366, 768),
            DEFAULT,
            new Resolution(1600, 900),
            new Resolution(1920, 1080),
            MAXIMIZED);

    public boolean isMaximized() {
        return equals(MAXIMIZED);
    }

    public String label() {
        return isMaximized() ? "最大化視窗" : width + " × " + height;
    }

    /** 設定檔裡的寫法。 */
    public String key() {
        return isMaximized() ? "maximized" : width + "x" + height;
    }

    /**
     * 視窗（width × height）比預設解析度大多少，畫面就放大多少：寬高取比較小的那個比例，以 5% 為一級往下取，
     * 放大後還放得進視窗。比預設小時不縮小，回傳 1，放不下的照舊用捲軸。
     */
    public static float zoomFor(int width, int height) {
        double ratio = Math.min((double) width / DEFAULT.width, (double) height / DEFAULT.height);
        return (float) Math.max(1, Math.floor(ratio * 20 + 1e-9) / 20);
    }

    /** 找不到對應的選項時回傳 DEFAULT。 */
    public static Resolution fromKey(String key) {
        for (Resolution option : OPTIONS) {
            if (option.key().equals(key)) {
                return option;
            }
        }
        return DEFAULT;
    }
}
