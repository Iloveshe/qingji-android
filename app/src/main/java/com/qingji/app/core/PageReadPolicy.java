package com.qingji.app.core;

/** Pure policy for opt-in screenshots of an identified payment app window. */
public final class PageReadPolicy {
    private PageReadPolicy() {}
    public static boolean mayCapture(int sdk, boolean enabled, boolean ocrEnabled,
            boolean selected, boolean unlocked, boolean sensitiveInput, boolean nodeHasTransaction,
            String pkg, String text) {
        return mayCapture(sdk,enabled,ocrEnabled,selected,unlocked,sensitiveInput,nodeHasTransaction,pkg,text,false,false);
    }
    public static boolean mayCapture(int sdk,boolean enabled,boolean ocrEnabled,boolean selected,
            boolean unlocked,boolean sensitiveInput,boolean nodeHasTransaction,String pkg,String text,
            boolean rememberedPage,boolean manual){
        if (sdk < 34 || !enabled || !ocrEnabled || !selected || !unlocked || sensitiveInput || nodeHasTransaction)
            return false;
        if(rememberedPage||manual)return true;
        // QuickPass sometimes renders the entire result in a canvas with no useful nodes.
        // The explicitly selected QuickPass window can be recognized even with empty nodes.
        if ("com.unionpay".equals(pkg)) return true;
        return ReceiptPolicy.resultHint(text)||ReceiptPolicy.receipt(text);
    }

    public static final class Budget {
        private String page = "";
        private int attempts;
        private long last = -1;
        public boolean acquire(long now, String pageKey) {
            return acquire(now,pageKey,false);
        }
        public boolean acquire(long now,String pageKey,boolean manual){
            if (last >= 0 && now >= last && now - last < 4000) return false;
            if (!page.equals(pageKey)) { page = pageKey; attempts = 0; }
            if (!manual && attempts >= 3) return false;
            last = now; attempts++;
            return true;
        }
    }
}
