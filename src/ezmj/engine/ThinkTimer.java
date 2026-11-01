package ezmj.engine;

/**
 * 真人的平均思考時間。從摸牌或可以吃碰槓胡開始算，到這次決定做完為止；
 * 吃、碰、槓之後還要打牌，所以一路算到打出去才是一次。時間由呼叫的人傳入。
 */
public final class ThinkTimer {

    private boolean running;
    private long startedNanos;
    private long totalNanos;
    private int count;

    /** 已經在計時就繼續算，不重新開始。 */
    public void start(long nowNanos) {
        if (!running) {
            running = true;
            startedNanos = nowNanos;
        }
    }

    public void finish(long nowNanos) {
        if (running) {
            running = false;
            totalNanos += nowNanos - startedNanos;
            count++;
        }
    }

    /** 這次不算，例如交給電腦代打。 */
    public void cancel() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public int count() {
        return count;
    }

    public long totalNanos() {
        return totalNanos;
    }

    /** 讀存檔時接回之前累計的時間。 */
    public void restore(long savedNanos, int savedCount) {
        running = false;
        totalNanos = savedNanos;
        count = savedCount;
    }

    /** 還沒有紀錄時是 0。 */
    public double averageSeconds() {
        return count == 0 ? 0 : totalNanos / 1e9 / count;
    }
}
