package com.qingji.app.core;

import java.util.regex.Pattern;

/** A visible number is not a receipt. These checks do not claim OCR accuracy. */
public final class ReceiptPolicy {
    private ReceiptPolicy() {}
    private static final Pattern RESULT = Pattern.compile("(?m)^[ \\t]*(?:微信支付[ \\t]*)?(?:支付成功|付款成功|消费成功|交易成功|扣款成功|收款成功|退款成功|退款到账|转账成功|充值成功|还款成功)(?=[ \\t:：￥¥\\-−0-9]|$)");
    private static final Pattern DETAILS = Pattern.compile("(?m)^[ \\t]*(?:交易详情|账单详情|消费详情|付款详情|转账详情|退款详情)[ \\t]*$");
    private static final Pattern FIELD = Pattern.compile("(?:交易时间|付款时间|支付时间|商户全称|商户名称|商家名称|商户订单号|交易单号|收款方|支付方式|付款方式|交易金额|实付金额)[：:\\s]");
    private static final Pattern LABEL_AMOUNT = Pattern.compile("(?m)(?:交易金额|支付金额|付款金额|实付金额|实付|消费金额)[：: \\t]*(?:\\r?\\n)?[ \\t]*(?:人民币|CNY|[￥¥])?[ \\t]*[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?(?:[ \\t]*元|[ \\t]*$)");
    public static boolean receipt(String text) {
        String value=text==null?"":text;
        return RESULT.matcher(value).find() || (DETAILS.matcher(value).find() && FIELD.matcher(value).find()) ||
            (LABEL_AMOUNT.matcher(value).find() && fields(value)>=2);
    }
    private static int fields(String text){java.util.regex.Matcher matcher=FIELD.matcher(text);int count=0;while(matcher.find()&&count<2)count++;return count;}
    public static boolean resultHint(String text) {
        return RESULT.matcher(text==null?"":text).find() || DETAILS.matcher(text==null?"":text).find();
    }
    public static boolean messagingScreen(String pkg,String activity) {
        if(!"com.tencent.mm".equals(pkg))return false;
        String name=activity==null?"":activity.toLowerCase(java.util.Locale.ROOT);
        return name.isEmpty() || name.endsWith(".launcherui") || name.contains("chatting") ||
            name.contains("sns") || name.contains("fts") || name.contains("search");
    }
}
