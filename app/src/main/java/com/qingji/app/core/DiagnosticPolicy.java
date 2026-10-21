package com.qingji.app.core;

import java.net.URI;
import java.util.Set;

/** An allowlist, not regex redaction: arbitrary text has no path into an exported log. */
public final class DiagnosticPolicy {
    private DiagnosticPolicy() {}
    public static boolean endpoint(String value){
        try{URI uri=new URI(value);return value.length()<=2048&&"https".equalsIgnoreCase(uri.getScheme())&&
            uri.getHost()!=null&&!uri.getHost().isEmpty()&&uri.getRawUserInfo()==null&&uri.getRawFragment()==null&&uri.getRawQuery()==null;}
        catch(Exception ex){return false;}
    }
    private static final Set<String> STAGES=Set.of("已入账","已合并","待确认","已去重跳过","未匹配","已跳过","处理失败","采集状态","证据未保存","识别检查");
    public static String stage(String value){
        if(!STAGES.contains(value==null?"":value))return "OTHER";
        switch(value){case "已入账":return "POSTED";case "已合并":return "MERGED";case "待确认":return "REVIEW";
            case "已去重跳过":return "DEDUPLICATED";case "未匹配":return "NO_MATCH";case "已跳过":return "SKIPPED";
            case "处理失败":return "ERROR";case "证据未保存":return "IMAGE_NOT_SAVED";case "识别检查":return "PARSED";default:return "CAPTURE_STATUS";}
    }
    public static String source(String value){
        switch(value==null?"":value){case "通知":return "NOTIFICATION";case "短信":return "SMS";case "支付页面":return "ACCESSIBILITY";
            case "自动页面 OCR":return "ACCESSIBILITY_OCR";case "截图":return "SCREENSHOT";case "屏幕识别":return "SCREEN_CAPTURE";
            case "页面服务":return "PAGE_SERVICE";default:return "OTHER";}
    }
    public static String origin(String value){
        switch(value==null?"":value){case "com.tencent.mm":return "WECHAT";case "com.eg.android.AlipayGphone":return "ALIPAY";
            case "com.unionpay":return "UNIONPAY";case "银行短信":return "BANK_SMS";default:return "OTHER";}
    }
    public static String code(String value){
        String detail=value==null?"":value;
        for(LedgerCore.ParseCode c:LedgerCore.ParseCode.values())if(detail.equals(c.name())||detail.endsWith(c.label))return c.name();
        if(detail.contains("密码")||detail.contains("输入页面"))return "SENSITIVE_SCREEN";
        if(detail.contains("安全保护"))return "SECURE_WINDOW";
        if(detail.contains("截图能力")||detail.contains("重新开启系统无障碍"))return "SCREENSHOT_CAPABILITY";
        if(detail.contains("聊天、首页或未知页面"))return "MESSAGING_SCREEN";
        if(detail.contains("未取得可读窗口"))return "NO_WINDOW";
        if(detail.contains("未匹配付款窗口"))return "PAGE_OR_THROTTLE";
        if(detail.contains("本地图片存储失败"))return "IMAGE_STORAGE";
        return "STATUS_ONLY";
    }
    public static String rule(String value){return Set.of("ACTION","REFUND","RESULT","CURRENCY","YUAN").contains(value==null?"":value)?value:"NONE";}
}
