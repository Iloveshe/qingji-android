package com.qingji.app;

import android.content.*;
import java.util.*;

public final class Config {
    public static final String WECHAT = "com.tencent.mm", ALIPAY = "com.eg.android.AlipayGphone", UNIONPAY = "com.unionpay";
    public static final Map<String,String> DEFAULT_APPS = new LinkedHashMap<>();
    static {
        DEFAULT_APPS.put(WECHAT,"微信"); DEFAULT_APPS.put(ALIPAY,"支付宝"); DEFAULT_APPS.put(UNIONPAY,"云闪付");
        DEFAULT_APPS.put("com.icbc","工商银行"); DEFAULT_APPS.put("com.chinamworld.main","建设银行");
        DEFAULT_APPS.put("com.chinamworld.bocmbci","中国银行"); DEFAULT_APPS.put("com.android.bankabc","农业银行");
        DEFAULT_APPS.put("com.bankcomm.Bankcomm","交通银行"); DEFAULT_APPS.put("com.cmbchina.ccd.pluto","招商银行");
        DEFAULT_APPS.put("com.cib.cibmb","兴业银行"); DEFAULT_APPS.put("com.psbc.mbank","邮储银行");
        DEFAULT_APPS.put("com.pingan.pabank.activity","平安银行"); DEFAULT_APPS.put("com.spdb.mobilebank.per","浦发银行");
        DEFAULT_APPS.put("com.cebbank.mobile.cemb","光大银行"); DEFAULT_APPS.put("cn.com.cmbc.newmbank","民生银行");
    }
    public final SharedPreferences prefs;
    public Config(Context context) { prefs = context.getSharedPreferences("qingji_config",Context.MODE_PRIVATE); }
    public boolean enabled() { return prefs.getBoolean("enabled",true); }
    public boolean flag(String key) { return prefs.getBoolean(key,key.equals("notifications")); }
    public void flag(String key, boolean value) { prefs.edit().putBoolean(key,value).apply(); }
    public String text(String key, String fallback) { return prefs.getString(key,fallback); }
    public void putText(String key,String value) { prefs.edit().putString(key,value.trim()).apply(); }
    public void pageStatus(String message) {
        long now=System.currentTimeMillis();
        if(message.equals(text("page_status",""))&&now-prefs.getLong("page_status_at",0)<10_000)return;
        prefs.edit().putString("page_status",message).putLong("page_status_at",now).apply();
        QingJiApp.app().refresh();
    }
    public Set<String> apps() {
        Set<String> apps=new HashSet<>();
        for(String pkg:DEFAULT_APPS.keySet()) if(prefs.getBoolean("app_"+pkg,true)) apps.add(pkg);
        for(String pkg:text("custom_apps","").split("[,，\\s]+")) if(pkg.contains(".")) apps.add(pkg.trim());
        return apps;
    }
    public boolean allowed(String pkg) { return apps().contains(pkg); }
    public Set<String> ownTails() {
        Set<String> tails=new HashSet<>();
        for(String t:text("own_tails","").split("[,，\\s]+")) if(t.matches("[0-9]{4}")) tails.add(t);
        return tails;
    }
    public boolean senderAllowed(String sender) {
        if(sender==null) return false;
        String n=sender.replaceAll("\\s+","").replaceFirst("^\\+86","");
        for(String prefix:text("sms_senders","955,953,106").split("[,，\\s]+")) if(!prefix.isEmpty() && n.startsWith(prefix)) return true;
        return false;
    }
    public String appName(String pkg) { return pkg.startsWith("sms:") ? "短信 · "+pkg.substring(4) : DEFAULT_APPS.getOrDefault(pkg,pkg); }
}
