package ezmj.ui;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/** 輸入框最多 max 個字，用 code point 算。貼上的換行等控制字元直接拿掉，服務器不收。房間名稱與聊天都用這個。 */
final class LengthLimit extends DocumentFilter {
    private final int max;

    LengthLimit(int max) {
        this.max = max;
    }

    @Override
    public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes)
            throws BadLocationException {
        replace(bypass, offset, 0, text, attributes);
    }

    @Override
    public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
            throws BadLocationException {
        String clean = text == null ? "" : text.codePoints().filter(point -> !Character.isISOControl(point))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String current = bypass.getDocument().getText(0, bypass.getDocument().getLength());
        String kept = current.substring(0, offset) + current.substring(offset + length);
        int room = Math.max(0, max - kept.codePointCount(0, kept.length()));
        if (clean.codePointCount(0, clean.length()) > room) {
            clean = clean.substring(0, clean.offsetByCodePoints(0, room));
        }
        bypass.replace(offset, length, clean, attributes);
    }
}
