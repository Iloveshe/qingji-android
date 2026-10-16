package com.qingji.app.core;

import java.util.Collection;

/** Stable page keys contain code structure and fixed receipt labels, never user content. */
public final class PaymentPageSignature {
    private PaymentPageSignature() {}
    private static final String[] LABELS={"支付成功","付款成功","交易成功","扣款成功","交易详情",
            "付款详情","消费详情","交易金额","付款金额","实付金额","商户名称","商户全称","收款方","交易时间","支付时间","订单号"};
    public static String create(String pkg,String activity,Collection<String> structure,String visible){
        if(pkg==null||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")||
                activity==null||!activity.startsWith(pkg+".")||!activity.matches("[A-Za-z0-9_.$]+")||
                structure==null||structure.size()<2||structure.size()>351)return "";
        int anchors=0;String text=visible==null?"":visible;
        for(String sensitive:new String[]{"验证码","动态密码","一次性密码","登录密码","支付密码"})if(text.contains(sensitive))return "";
        for(int i=0;i<LABELS.length;i++)if(text.contains(LABELS[i]))anchors|=1<<i;
        boolean resultHeader=(anchors&127)!=0;
        if(!resultHeader&&Integer.bitCount(anchors)<2)return "";
        StringBuilder pattern=new StringBuilder("payment-page-v1|").append(pkg).append('|').append(activity).append('|').append(anchors);
        for(String token:structure){if(token==null||token.length()>350)return "";pattern.append('|').append(token);}
        return LedgerCore.hash(pattern.toString());
    }
}
