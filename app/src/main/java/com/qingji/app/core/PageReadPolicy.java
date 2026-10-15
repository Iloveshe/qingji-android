package com.qingji.app.core;

/** Pure policy for opt-in screenshots of an identified payment app window. */
public final class PageReadPolicy {
    private PageReadPolicy() {}
    public static boolean mayCapture(int sdk, boolean enabled, boolean ocrEnabled,
            boolean selected, boolean unlocked, boolean sensitiveInput, boolean nodeHasTransaction,
            String pkg, String text) {
        if (sdk < 34 || !enabled || !ocrEnabled || !selected || !unlocked || sensitiveInput || nodeHasTransaction)
            return false;
        // QuickPass sometimes renders the entire result in a canvas with no useful nodes.
        // The explicitly selected QuickPass window can be recognized even with empty nodes.
        if ("com.unionpay".equals(pkg)) return true;
        String visible = text == null ? "" : text;
        for (String hint : new String[]{"支付成功", "付款成功", "交易成功", "消费成功", "交易详情",
                "消费详情", "付款详情", "交易金额", "实付金额", "扣款成功", "商户全称"})
            if (visible.contains(hint)) return true;
        return false;
    }

    public static final class Budget {
        private String page = "";
        private int attempts;
        private long last = -1;
        public boolean acquire(long now, String pageKey) {
            if (last >= 0 && now >= last && now - last < 4000) return false;
            if (!page.equals(pageKey)) { page = pageKey; attempts = 0; }
            if (attempts >= 3) return false;
            last = now; attempts++;
            return true;
        }
    }
}
