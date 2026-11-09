package ezmj.report;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小的 PDF：每頁一張 JPEG 貼滿整頁。中文字要內嵌字型才印得出來，所以頁面先畫成圖，
 * 字就不能選取，但任何閱讀器都打得開。和網頁版的 PdfWriter 寫出來的一樣，不依賴 Swing。
 */
public final class PdfWriter {

    private record Page(byte[] jpeg, int pixelWidth, int pixelHeight) {
    }

    private final int pageWidth;
    private final int pageHeight;
    private final List<Page> pages = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final List<Integer> offsets = new ArrayList<>();

    /** 頁面大小的單位是點，1/72 英吋。A4 橫放是 842 × 595。 */
    public PdfWriter(int pageWidth, int pageHeight) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
    }

    /** jpeg 是一整個 JPEG 檔，baseline 的 RGB，ImageIO 寫出來的就是。 */
    public void addPage(byte[] jpeg, int pixelWidth, int pixelHeight) {
        pages.add(new Page(jpeg, pixelWidth, pixelHeight));
    }

    public int pageCount() {
        return pages.size();
    }

    /**
     * 物件依序是目錄、頁面樹、文件資訊，接著每頁三個：頁面、內容、圖。
     * created 是 PDF 的日期格式，例如 D:20261106213000。
     */
    public byte[] toBytes(String title, String created) {
        out.reset();
        offsets.clear();
        ascii("%PDF-1.4\n%");
        // 第二行放幾個大於 127 的位元組，傳檔的程式才知道這是二進位檔
        out.write(0xE2);
        out.write(0xE3);
        out.write(0xCF);
        out.write(0xD3);
        ascii("\n");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(i == 0 ? "" : " ").append(pageObject(i)).append(" 0 R");
        }
        object("<< /Type /Catalog /Pages 2 0 R >>");
        object("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>");
        object("<< /Title " + textString(title) + " /Producer (EZMJ) /CreationDate (" + created + ") >>");
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            int number = pageObject(i);
            object("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + pageWidth + " " + pageHeight + "]"
                    + " /Resources << /XObject << /Im0 " + (number + 2) + " 0 R >> >> /Contents " + (number + 1) + " 0 R >>");
            String draw = "q\n" + pageWidth + " 0 0 " + pageHeight + " 0 0 cm\n/Im0 Do\nQ\n";
            stream("<< /Length " + draw.length() + " >>", asciiBytes(draw));
            stream("<< /Type /XObject /Subtype /Image /Width " + page.pixelWidth() + " /Height " + page.pixelHeight()
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + page.jpeg().length + " >>",
                    page.jpeg());
        }

        int xref = out.size();
        ascii("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            ascii(padded(offset) + " 00000 n \n");
        }
        ascii("trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R /Info 3 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static int pageObject(int index) {
        return 4 + index * 3;
    }

    private void object(String body) {
        begin();
        ascii(body + "\nendobj\n");
    }

    private void stream(String dictionary, byte[] data) {
        begin();
        ascii(dictionary + "\nstream\n");
        out.write(data, 0, data.length);
        ascii("\nendstream\nendobj\n");
    }

    private void begin() {
        offsets.add(out.size());
        ascii(offsets.size() + " 0 obj\n");
    }

    /** 標題有中文，用 UTF-16BE 的十六進位字串，前面加 BOM。 */
    private static String textString(String text) {
        StringBuilder hex = new StringBuilder("<FEFF");
        for (int i = 0; i < text.length(); i++) {
            String code = Integer.toHexString(text.charAt(i)).toUpperCase();
            hex.append("0000".substring(code.length())).append(code);
        }
        return hex.append('>').toString();
    }

    private static String padded(int offset) {
        String digits = String.valueOf(offset);
        return "0000000000".substring(digits.length()) + digits;
    }

    private void ascii(String text) {
        byte[] bytes = asciiBytes(text);
        out.write(bytes, 0, bytes.length);
    }

    /** 這裡的文字都是 ASCII，不必經過字元集。 */
    private static byte[] asciiBytes(String text) {
        byte[] bytes = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            bytes[i] = (byte) text.charAt(i);
        }
        return bytes;
    }
}
