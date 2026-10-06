package com.github.lechandonga.openbankingconsentgatewayjava.gateway;

/**
 * 固定窗口计数器。窗口切换在客户锁内原子完成，杜绝跨窗口多扣或超发。
 */
public class QuotaWindow {

    private final long windowStart;
    private final int used;

    public QuotaWindow(long windowStart, int used) {
        this.windowStart = windowStart;
        this.used = used;
    }

    public long getWindowStart() {
        return windowStart;
    }

    public int getUsed() {
        return used;
    }
}
