package com.qingji.app.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/** Pure Java, independent of Android. Every amount is integer minor units. */
public final class LedgerCore {
    private LedgerCore() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public enum Kind { EXPENSE, INCOME, TRANSFER, REFUND, UNKNOWN }
    public enum Source { NOTIFICATION, SMS, ACCESSIBILITY, SCREENSHOT, SCREEN_CAPTURE, TEXT_IMPORT, ACCESSIBILITY_OCR }
    public enum Status { CONFIRMED, REVIEW, IGNORED }
    public enum Action { INSERT, MERGE, REVIEW, IGNORE }

    public static final class Evidence {
        public final Source source;
        public final String origin, eventKey, text;
        public final long observedAt;
        public Evidence(Source source, String origin, String eventKey, String text, long observedAt) {
            this.source = source; this.origin = origin; this.eventKey = eventKey;
            this.text = text == null ? "" : text.substring(0, Math.min(text.length(), 8000));
            this.observedAt = observedAt;
        }
        public String fingerprint() { return hash(source + "|" + origin + "|" + eventKey + "|" + normalize(text)); }
    }

    public static final class Candidate {
        public long cents, occurredAt;
        public String currency = "CNY", merchant = "", category = "其他", rail = "", orderHash = "", orderScope = "";
        public String accountTail = "", targetTail = "", excerpt = "", reason = "";
        public Kind kind = Kind.UNKNOWN;
        public double confidence;
        public boolean exactTime;
        public Evidence evidence;
        public Status status() { return confidence >= 0.90 && kind != Kind.UNKNOWN ? Status.CONFIRMED : Status.REVIEW; }
    }

    public static final class Entry {
        public long id;
        public Candidate transaction;
        public Status status;
        public boolean userEdited;
        public final List<EvidenceRef> evidence = new ArrayList<>();
        public Entry(long id, Candidate c, Status status) { this.id = id; this.transaction = c; this.status = status; }
    }

    public static final class EvidenceRef {
        public final Source source;
        public final String origin, eventKey, fingerprint;
        public EvidenceRef(Evidence e) { this(e.source, e.origin, e.eventKey, e.fingerprint()); }
        public EvidenceRef(Source source, String origin, String key, String fingerprint) {
            this.source = source; this.origin = origin; this.eventKey = key; this.fingerprint = fingerprint;
        }
    }

    public static final class Decision {
        public final Action action;
        public final long target;
        public final String reason;
        public Decision(Action action, long target, String reason) { this.action = action; this.target = target; this.reason = reason; }
    }

    private static final String NUMBER = "([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?![0-9.])";
    private static final Pattern ACTION_AMOUNT = Pattern.compile(
        "(?:实付(?:金额)?|交易金额|支付金额|付款金额|消费(?:金额|成功)?|扣款(?:金额|成功)?|扣费(?:金额|成功)?|支出|成功支付|支付(?:成功)?|成功付款|付款(?:成功)?|收款(?:到账|成功)?|收入|到账|入账|转入|转出|转账(?:成功)?|充值(?:成功)?|提现(?:成功)?|还款(?:成功)?|退款(?:成功|到账)?)[：:\\s人民币CNY￥¥]*" + NUMBER + "\\s*(?:元|人民币|CNY)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESULT_AMOUNT = Pattern.compile("(?:支付成功|付款成功|消费成功|交易成功|扣款成功)[：:\\s]*[-−]?\\s*[￥¥]?\\s*" + NUMBER + "[ \\t]*(?:元|人民币)?(?=[\\r\\n]|$)");
    private static final Pattern SYMBOL_AMOUNT = Pattern.compile("[￥¥]\\s*" + NUMBER);
    private static final Pattern YUAN_AMOUNT = Pattern.compile(NUMBER + "\\s*元");
    private static final Pattern DATE = Pattern.compile("(20[0-9]{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})[日]?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<![0-9/.-])(\\d{1,2})[月/-](\\d{1,2})[日]?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern ORDER = Pattern.compile("(商户订单号|商户单号|银联订单号|支付订单号|订单号|交易单号|交易流水号|流水号)[：:\\s]*([A-Za-z0-9_-]{6,80})");
    private static final Pattern TAIL = Pattern.compile("(?:尾号|尾数|末四位)[：:\\s(（]*([0-9]{4})(?![0-9])");
    private static final Pattern TARGET_TAIL = Pattern.compile("(?:转入|转至|转到|到账至|目标账户)[^。;；\\n]{0,24}?(?:尾号|尾数)[：:\\s(（]*([0-9]{4})(?![0-9])");
    private static final Pattern MERCHANT = Pattern.compile("(?:商户全称|商家名称|商户(?:名称)?|收款方|收款商家|交易商户|对方户名)[：:\\s]+([^\\n，,。;；]{1,40})");
    private static final Pattern AT_MERCHANT = Pattern.compile("(?:向|在)([^\\n，,。;；]{2,32}?)(?:付款|支付|消费)");
    private static boolean contains(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    public static Candidate parse(Evidence e, Set<String> ownTails) {
        String text = e.text.replace('\u00a0', ' ').replace('﹕', ':');
        String semantic = text.replace("支付宝", "").replace("微信支付", "").replace("云闪付", "");
        String direction = semantic.replaceAll("收款方|收款商家|收款人|付款方|付款人|支付方式|付款方式", "");
        // Never retain or send an OTP, even when the message also mentions an amount.
        if (contains(text, "验证码", "动态密码", "一次性密码", "登录密码", "支付密码") ||
            !contains(semantic, "支付", "消费", "付款", "收款", "到账", "入账", "转账", "转入", "转出", "退款", "充值", "提现", "还款", "收入", "支出", "交易成功", "扣款", "扣费")) return null;
        if (contains(text, "支付失败", "支付未成功", "交易失败", "付款失败", "充值失败", "扣款失败", "扣费失败", "已取消", "订单关闭", "待支付", "待付款", "支付中", "扣款中", "扣费中", "处理中", "待到账", "待入账", "退款申请", "退款中", "还款申请", "审核中", "请支付", "请付款", "预计到账")) return null;
        boolean completed = contains(semantic, "支付成功", "付款成功", "交易成功", "消费成功", "扣款成功", "扣费成功", "收款成功", "收款到账", "退款成功", "退款到账", "充值成功", "还款成功", "转账成功");
        if (!completed && contains(text, "优惠券", "立减", "领取", "消费满", "支付满", "付款满", "还款提醒", "待还款", "应还款", "预约转账")) return null;
        if (contains(text, "本月累计", "本月支出", "本日累计", "本年累计", "月度账单", "笔交易", "笔消费")) return null;
        String noBalance = text.replaceAll("(?:可用余额|账户余额|余额|可用额度|剩余额度)[：:\\s]*(?:人民币|CNY|[￥¥])?\\s*[0-9,]+(?:\\.[0-9]{1,2})?\\s*元?", "");
        LinkedHashSet<Long> amounts = amounts(ACTION_AMOUNT, noBalance);
        if (amounts.isEmpty()) amounts = amounts(RESULT_AMOUNT, noBalance);
        if (amounts.isEmpty()) amounts = amounts(SYMBOL_AMOUNT, noBalance);
        if (amounts.isEmpty()) amounts = amounts(YUAN_AMOUNT, noBalance);
        if (amounts.isEmpty()) return null;
        Candidate c = new Candidate();
        c.evidence = e; c.cents = amounts.iterator().next(); c.occurredAt = e.observedAt;
        c.rail = rail(e.origin, text);
        Matcher m = MERCHANT.matcher(text);
        if (m.find()) c.merchant = cleanMerchant(m.group(1));
        if (c.merchant.isEmpty()) {
            m = AT_MERCHANT.matcher(text);
            if (m.find()) c.merchant = cleanMerchant(m.group(1));
        }
        m = ORDER.matcher(text);
        if (m.find()) {
            c.orderHash = hash(m.group(2));
            c.orderScope = m.group(1).startsWith("商户") ? "merchant" : c.rail + ":" + m.group(1);
        }
        List<String> tails = new ArrayList<>();
        m = TAIL.matcher(text);
        while (m.find()) tails.add(m.group(1));
        if (!tails.isEmpty()) c.accountTail = tails.get(0);
        m = TARGET_TAIL.matcher(text);
        if (m.find()) c.targetTail = m.group(1);
        boolean ownPair = tails.stream().distinct().filter(ownTails::contains).count() >= 2;
        boolean explicitTransfer = contains(text, "本人账户", "本人银行卡", "账户互转", "自己账户", "转入余额", "余额充值", "充值到余额", "零钱充值", "银行卡充值", "钱包充值", "余额宝转入", "余额宝转出", "零钱通转入", "零钱通转出", "提现成功", "提现到账", "信用卡还款", "还信用卡", "还款成功") || ownPair;
        if (contains(text, "退款到账", "退款成功", "已退款", "退回原账户", "退款入账")) c.kind = Kind.REFUND;
        else if (contains(text, "退款", "退回")) c.kind = Kind.UNKNOWN;
        else if (explicitTransfer) c.kind = Kind.TRANSFER;
        else if (contains(text, "转账", "转入", "转出", "充值", "提现", "还款")) c.kind = Kind.UNKNOWN;
        else if (contains(direction, "收款", "到账", "入账", "收入", "工资")) c.kind = Kind.INCOME;
        else if (contains(direction, "消费", "支付", "付款", "支出", "扣款", "扣费")) c.kind = Kind.EXPENSE;
        parseTime(c, text);
        boolean invalidTime = c.reason.equals("交易时间格式无效");
        c.category = category(c.kind, c.merchant + " " + text);
        c.confidence = c.kind == Kind.UNKNOWN ? 0.55 : 0.95;
        c.reason = c.kind == Kind.TRANSFER ? "账户间资金移动，不计入收支" : "识别到明确交易金额与方向";
        if (c.kind == Kind.UNKNOWN) c.reason = contains(semantic,"转账","转入","转出","充值","提现","还款") ? "转账对象不明确，需要区分本人账户与对外转账" : "交易方向不明确，需要核对收支或退款性质";
        if (invalidTime) { c.confidence = 0.50; c.reason = "交易时间格式无效"; }
        boolean visual = visual(e.source);
        if (visual && (!c.exactTime || c.merchant.isEmpty())) {
            c.confidence = Math.min(c.confidence, 0.76);
            c.reason = "页面缺少交易时间或商户，避免把历史账单误记为新消费";
        }
        if (amounts.size() > 1) { c.confidence = 0.50; c.reason = "出现多个交易金额，请只提供单笔交易详情"; }
        if (contains(text, "USD", "美元", "HKD", "港币", "EUR", "欧元", "JPY", "日元")) {
            c.currency = contains(text, "USD", "美元") ? "USD" : contains(text, "HKD", "港币") ? "HKD" : contains(text, "EUR", "欧元") ? "EUR" : "JPY";
            c.confidence = Math.min(c.confidence, 0.60); c.reason = "外币交易需核对；不会与人民币合计";
        }
        if (contains(text, "已撤销", "撤销交易", "冲正", "交易撤销")) { c.confidence = 0.50; c.reason = "交易发生冲正或撤销，需要核对原账单"; }
        if (c.occurredAt > e.observedAt + 120_000L) { c.confidence = 0.50; c.reason = "交易时间晚于采集时间，需要核对"; }
        // Retain the extracted transaction fields, never a full visible chat or notification body.
        c.excerpt = "识别金额：" + amount(c.cents) + " " + c.currency + "\n类型：" + c.kind +
            "\n商户：" + redact(c.merchant) + "\n时间：" + Instant.ofEpochMilli(c.occurredAt).atZone(ZONE).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")) +
            (c.exactTime ? "" : "（采集时间）") + "\n账户尾号：" + c.accountTail + "\n判断：" + c.reason;
        return c;
    }

    private static LinkedHashSet<Long> amounts(Pattern pattern, String text) {
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            try {
                long v = new BigDecimal(m.group(1).replace(",", "")).movePointRight(2).longValueExact();
                if (v > 0 && v <= 99_999_999_999L) values.add(v);
            } catch (ArithmeticException | NumberFormatException ignored) {}
        }
        return values;
    }

    private static void parseTime(Candidate c, String text) {
        Matcher m = DATE.matcher(text);
        if (m.find()) {
            try {
                c.occurredAt = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6))).atZone(ZONE).toInstant().toEpochMilli();
                c.exactTime = true;
            } catch (DateTimeException ignored) { c.reason = "交易时间格式无效"; }
            return;
        }
        // Yearless bank SMS dates can use the nearest past year. Screenshots cannot.
        if (c.evidence.source != Source.SMS && c.evidence.source != Source.NOTIFICATION) return;
        m = SHORT_DATE.matcher(text);
        if (m.find()) {
            try {
                int year = Instant.ofEpochMilli(c.evidence.observedAt).atZone(ZONE).getYear();
                LocalDateTime d = LocalDateTime.of(year, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), m.group(5) == null ? 0 : Integer.parseInt(m.group(5)));
                int currentMonth = Instant.ofEpochMilli(c.evidence.observedAt).atZone(ZONE).getMonthValue();
                if (currentMonth <= 2 && d.getMonthValue() >= 11 && d.atZone(ZONE).toInstant().toEpochMilli() > c.evidence.observedAt + 120_000L) d = d.minusYears(1);
                c.occurredAt = d.atZone(ZONE).toInstant().toEpochMilli(); c.exactTime = true;
            } catch (DateTimeException ignored) { c.reason = "交易时间格式无效"; }
        }
    }

    /** Decisions consider persisted ignored entries too, so redelivery cannot revive them. */
    public static Decision decide(Candidate c, List<Entry> entries) {
        List<Entry> strong = new ArrayList<>(), possible = new ArrayList<>();
        String fp = c.evidence.fingerprint();
        for (Entry row : entries) {
            Candidate p = row.transaction;
            for (EvidenceRef ref : row.evidence) {
                if (fp.equals(ref.fingerprint)) return new Decision(Action.IGNORE, row.id, "来源事件已处理");
            }
            boolean orderComparable = !c.orderHash.isEmpty() && !p.orderHash.isEmpty() && c.orderScope.equals(p.orderScope);
            if (orderComparable && c.orderScope.equals("merchant") && !c.merchant.isEmpty() && !p.merchant.isEmpty() && !normalize(c.merchant).equals(normalize(p.merchant))) continue;
            if (orderComparable && !c.orderHash.equals(p.orderHash)) continue;
            boolean sameOrder = orderComparable && c.orderHash.equals(p.orderHash);
            if (sameOrder && c.kind == p.kind && (c.cents != p.cents || !c.currency.equals(p.currency))) {
                return new Decision(Action.REVIEW, row.id, "同一订单出现不同金额或币种，需要核对");
            }
            if (c.cents != p.cents || !c.currency.equals(p.currency)) continue;
            long delta = Math.abs(c.occurredAt - p.occurredAt);
            // Pair a transfer with its incoming bank evidence only with a known target account.
            boolean transferPair = delta <= 300_000L && (
                (p.kind == Kind.TRANSFER && c.kind == Kind.INCOME && !p.targetTail.isEmpty() && p.targetTail.equals(c.accountTail)) ||
                (c.kind == Kind.TRANSFER && p.kind == Kind.INCOME && !c.targetTail.isEmpty() && c.targetTail.equals(p.accountTail)));
            if (transferPair) { strong.add(row); continue; }
            if (c.kind != p.kind) continue;
            if (sameOrder) { strong.add(row); continue; }
            if (delta > 120_000L) continue;
            // Different wallets can represent two genuine purchases at the same shop.
            if (wallet(c.rail) && wallet(p.rail) && !c.rail.equals(p.rail)) continue;
            boolean differentChannel = row.evidence.stream().noneMatch(r -> r.source == c.evidence.source && r.origin.equals(c.evidence.origin));
            boolean sameMerchant = !c.merchant.isEmpty() && normalize(c.merchant).equals(normalize(p.merchant));
            boolean undatedVisual = (visual(c.evidence.source) && !c.exactTime) ||
                row.evidence.stream().anyMatch(r -> visual(r.source) && !p.exactTime);
            boolean visualRepeat = c.exactTime && p.exactTime && delta == 0 && sameMerchant &&
                (c.evidence.source == Source.SCREENSHOT || c.evidence.source == Source.SCREEN_CAPTURE || c.evidence.source == Source.ACCESSIBILITY || c.evidence.source == Source.ACCESSIBILITY_OCR);
            if (sameMerchant && !undatedVisual && (differentChannel || visualRepeat)) { strong.add(row); continue; }
            // Equal amounts are never sufficient to merge. Ambiguous cross-source candidates wait.
            if (differentChannel && (c.merchant.isEmpty() || p.merchant.isEmpty() || undatedVisual)) possible.add(row);
        }
        if (strong.size() == 1) return new Decision(Action.MERGE, strong.get(0).id, "订单号或商户、金额、时间及来源一致");
        if (strong.size() > 1) return new Decision(Action.REVIEW, 0, "有多笔可能匹配的交易，不能自动选择");
        if (!possible.isEmpty()) return new Decision(Action.REVIEW, possible.size() == 1 ? possible.get(0).id : 0, "可能与另一来源重复，但缺少商户或订单信息");
        return new Decision(c.status() == Status.CONFIRMED ? Action.INSERT : Action.REVIEW, 0, c.reason);
    }

    private static boolean wallet(String rail) { return rail.equals("微信") || rail.equals("支付宝") || rail.equals("云闪付"); }
    private static boolean visual(Source s) { return s == Source.SCREENSHOT || s == Source.SCREEN_CAPTURE || s == Source.ACCESSIBILITY || s == Source.TEXT_IMPORT || s == Source.ACCESSIBILITY_OCR; }

    public static String rail(String origin, String text) {
        if (origin.equals("com.tencent.mm") || text.contains("微信支付")) return "微信";
        if (origin.equals("com.eg.android.AlipayGphone") || text.contains("支付宝")) return "支付宝";
        if (origin.equals("com.unionpay") || text.contains("云闪付")) return "云闪付";
        if (origin.startsWith("sms:")) return "银行:" + origin.substring(4);
        return origin;
    }
    public static String category(Kind kind, String text) {
        if (kind == Kind.TRANSFER) return "内部转账";
        if (kind == Kind.REFUND) return "退款";
        if (kind == Kind.INCOME) return text.contains("工资") ? "工资" : "收入";
        if (contains(text, "餐", "咖啡", "奶茶", "外卖", "美团", "饿了么", "星巴克", "瑞幸")) return "餐饮";
        if (contains(text, "地铁", "公交", "滴滴", "打车", "铁路", "加油", "停车")) return "交通";
        if (contains(text, "超市", "便利店", "购物", "淘宝", "京东", "拼多多")) return "购物";
        if (contains(text, "医院", "药", "诊所")) return "医疗";
        if (contains(text, "话费", "电费", "水费", "燃气", "房租", "宽带")) return "生活缴费";
        if (contains(text, "电影", "游戏", "视频", "音乐")) return "娱乐";
        return "其他";
    }
    private static String cleanMerchant(String s) {
        return s.trim().replaceAll("(?:交易时间|支付时间|订单号|支付方式|交易金额|付款金额).*$", "").trim();
    }
    public static String redact(String text) {
        return text.replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[手机号已隐藏]")
            .replaceAll("(?<!\\d)\\d{12,}(?!\\d)", "[长号码已隐藏]")
            .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[邮箱已隐藏]");
    }
    public static String normalize(String s) { return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
    public static String hash(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte v : b) out.append(String.format(Locale.ROOT, "%02x", v & 255));
            return out.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String amount(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
    public static long netExpense(List<Entry> rows) {
        long sum = 0;
        for (Entry e : rows) {
            if (e.status != Status.CONFIRMED || !e.transaction.currency.equals("CNY")) continue;
            if (e.transaction.kind == Kind.EXPENSE) sum = Math.addExact(sum, e.transaction.cents);
            if (e.transaction.kind == Kind.REFUND) sum = Math.subtractExact(sum, e.transaction.cents);
        }
        return sum;
    }
}
