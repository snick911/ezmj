package ezmj.ui;

import java.nio.file.Path;
import java.util.function.Supplier;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import ezmj.model.ErrorLog;

/**
 * 接住沒有人處理的錯誤。牌局靠計時器往下走，畫面執行緒出錯時流程就停了，所以要告訴玩家並收拾；
 * 背景執行緒（音效、語音）出錯不影響牌局，只記下來。
 */
public final class ErrorReporter {

    private static final ErrorLog LOG = new ErrorLog(Path.of(System.getProperty("user.home"), ".ezmj", "error.log"));

    private static volatile Supplier<String> context = () -> "不在牌局中";
    private static volatile Runnable onUiError;
    private static boolean handling;

    private ErrorReporter() {
    }

    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler(ErrorReporter::report);
    }

    /** context 回傳出錯時要記下的局面。 */
    static void setContext(Supplier<String> supplier) {
        context = supplier;
    }

    /** 畫面執行緒出錯時，在畫面執行緒上呼叫一次。 */
    static void setUiHandler(Runnable handler) {
        onUiError = handler;
    }

    static Path logFile() {
        return LOG.file();
    }

    /** 不是程式壞掉、但值得留個紀錄的問題，例如存檔讀不懂。 */
    static void note(String what, Throwable error) {
        LOG.write(Thread.currentThread().getName(), what, error);
    }

    private static void report(Thread thread, Throwable error) {
        LOG.write(thread.getName(), describeContext(), error);
        if (SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(ErrorReporter::handleUiError);
        }
    }

    private static String describeContext() {
        try {
            return context.get();
        } catch (RuntimeException e) {
            return "局面讀不出來：" + e;
        }
    }

    /** 收拾的時候又出錯，或一次出錯好幾筆，都只跳一個視窗。 */
    private static void handleUiError() {
        if (handling) {
            return;
        }
        handling = true;
        try {
            if (onUiError != null) {
                onUiError.run();
            } else {
                JOptionPane.showMessageDialog(null, "程式發生錯誤，內容記在：\n" + LOG.file(), "錯誤",
                        JOptionPane.ERROR_MESSAGE);
            }
        } finally {
            handling = false;
        }
    }
}
