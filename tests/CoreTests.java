import com.qingji.app.core.LedgerCore;
import static com.qingji.app.core.LedgerCore.*;
import java.time.*;
import java.util.*;

public class CoreTests {
    static final long T = LocalDateTime.of(2026, 10, 14, 14, 0).atZone(ZONE).toInstant().toEpochMilli();
    static int passed;
    static Candidate parse(Source s, String origin, String key, String text, long t) {
        return LedgerCore.parse(new Evidence(s, origin, key, text, t), Set.of("1234", "5678"));
    }
    static Candidate payment(Source s, String origin, String key, String merchant, String order, long t) {
        return parse(s, origin, key, "支付成功 38.00元\n商户：" + merchant + (order.isEmpty() ? "" : "\n商户订单号：" + order), t);
    }
    static Entry row(long id, Candidate c) { Entry e = new Entry(id,c,c.status()); e.evidence.add(new EvidenceRef(c.evidence)); return e; }
    static void check(String name, boolean result) {
        if (!result) throw new AssertionError(name);
        passed++; System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        Candidate transfer = parse(Source.NOTIFICATION,"com.unionpay","t1","从尾号1234转入本人银行卡尾号5678，转账成功500.00元", T);
        Candidate consume = payment(Source.NOTIFICATION,"com.tencent.mm","p1","瑞幸咖啡","ORDER123456", T+60_000);
        check("self transfer excluded; expense counted once", transfer.kind==Kind.TRANSFER && netExpense(List.of(row(1,transfer),row(2,consume)))==3800);
        Candidate sms = parse(Source.SMS,"sms:95588","s1","尾号5678消费38.00元，商户：瑞幸咖啡，余额800.00元",T+61_000);
        check("balance is not transaction amount",sms.cents==3800);
        check("bank SMS and wallet notification merge",decide(sms,List.of(row(2,consume))).action==Action.MERGE);
        check("same raw event replay ignored",decide(consume,List.of(row(2,consume))).action==Action.IGNORE);
        Candidate otherMerchant = payment(Source.NOTIFICATION,"com.eg.android.AlipayGphone","p2","地铁","",T+30_000);
        check("equal amount, different merchants stay separate",decide(otherMerchant,List.of(row(2,consume))).action==Action.INSERT);
        Candidate secondSame = payment(Source.NOTIFICATION,"com.tencent.mm","p2","瑞幸咖啡","ORDER999999",T+30_000);
        check("distinct order IDs never merge",decide(secondSame,List.of(row(2,consume))).action==Action.INSERT);
        Candidate firstNoOrder=payment(Source.NOTIFICATION,"com.tencent.mm","p1","瑞幸咖啡","",T);
        Candidate secondNoOrder=payment(Source.NOTIFICATION,"com.tencent.mm","p2","瑞幸咖啡","",T+10_000);
        check("two same-source payments remain separate",decide(secondNoOrder,List.of(row(4,firstNoOrder))).action==Action.INSERT);
        Candidate vague = parse(Source.SMS,"sms:95588","s2","尾号5678消费38.00元，余额500元",T+40_000);
        check("amount-only cross-source matching waits for review",decide(vague,List.of(row(2,consume))).action==Action.REVIEW);
        Candidate screenshot= parse(Source.SCREENSHOT,"shared-image","img1","微信支付 支付成功 ￥38.00\n商户：瑞幸咖啡\n交易时间：2026-10-14 14:01:00\n商户订单号：ORDER123456",T+70_000);
        check("screenshot order evidence merges",decide(screenshot,List.of(row(2,consume))).action==Action.MERGE);
        Candidate old= parse(Source.SCREENSHOT,"shared-image","old","支付成功 ￥38.00\n商户：瑞幸咖啡\n交易时间：2025-10-14 14:01:00",T);
        check("historical screenshot uses historical date", Instant.ofEpochMilli(old.occurredAt).atZone(ZONE).getYear()==2025);
        Candidate missingDate=parse(Source.SCREENSHOT,"shared-image","no-date","支付成功 38元 商户：瑞幸咖啡",T);
        check("undated screenshot needs review",missingDate.status()==Status.REVIEW);
        check("undated historical image never auto merges by recent amount",decide(missingDate,List.of(row(2,consume))).action==Action.REVIEW);
        Candidate otherWallet=payment(Source.NOTIFICATION,"com.eg.android.AlipayGphone","w2","瑞幸咖啡","",T+10_000);
        check("two different wallets same merchant and amount stay separate",decide(otherWallet,List.of(row(2,consume))).action==Action.INSERT);
        Candidate invalidDate=parse(Source.NOTIFICATION,"com.tencent.mm","bad-date","支付成功 38元 商户：咖啡\n交易时间：2026-99-32 14:01:00",T);
        check("invalid transaction date cannot auto post",invalidDate.status()==Status.REVIEW);
        Candidate reusedOrder=payment(Source.NOTIFICATION,"com.eg.android.AlipayGphone","reused","地铁","ORDER123456",T);
        check("merchant-scoped order number not global across merchants",decide(reusedOrder,List.of(row(2,consume))).action==Action.INSERT);
        Candidate income=parse(Source.SMS,"sms:95588","income","尾号5678账户到账500.00元",T+10_000);
        check("known transfer target links incoming bank evidence",decide(income,List.of(row(1,transfer))).action==Action.MERGE);
        Candidate notTarget=parse(Source.SMS,"sms:95588","other","尾号9999账户到账500.00元",T+10_000);
        check("unrelated income is not suppressed",decide(notTarget,List.of(row(1,transfer))).action==Action.INSERT);
        Candidate personal=parse(Source.NOTIFICATION,"com.tencent.mm","personal","向张三转账成功38元",T);
        check("personal transfer does not become self transfer",personal.kind==Kind.UNKNOWN && personal.status()==Status.REVIEW);
        Candidate refund=parse(Source.NOTIFICATION,"com.tencent.mm","refund","退款成功38.00元 商户订单号：ORDER123456",T+80_000);
        check("refund offsets spending without merging original charge",decide(refund,List.of(row(2,consume))).action==Action.INSERT && netExpense(List.of(row(2,consume),row(3,refund)))==0);
        check("failed payment ignored",parse(Source.NOTIFICATION,"com.tencent.mm","failed","支付失败 38元",T)==null);
        check("OTP ignored",parse(Source.SMS,"sms:95588","otp","支付验证码123456，交易38元",T)==null);
        check("balance-only message ignored",parse(Source.SMS,"sms:95588","balance","账户余额800元",T)==null);
        check("summary bill ignored",parse(Source.NOTIFICATION,"com.tencent.mm","summary","本月支出500元共12笔交易",T)==null);
        check("Alipay brand is not a payment verb",parse(Source.NOTIFICATION,"com.eg.android.AlipayGphone","ad1","支付宝送你38元红包",T)==null);
        check("WeChat Pay promotion is not an expense",parse(Source.NOTIFICATION,"com.tencent.mm","ad2","微信支付\n领取38元优惠券",T)==null);
        check("unpaid bill reminder ignored",parse(Source.NOTIFICATION,"com.eg.android.AlipayGphone","unpaid","支付宝待付款38元",T)==null);
        check("repayment reminder ignored",parse(Source.SMS,"sms:95588","repay-due","还款提醒：应还款500元",T)==null);
        Candidate unsureRefund=parse(Source.NOTIFICATION,"com.tencent.mm","refund-unknown","微信支付 退款38元",T);
        check("unconfirmed refund never becomes expense",unsureRefund!=null&&unsureRefund.kind==Kind.UNKNOWN&&unsureRefund.status()==Status.REVIEW);
        Candidate cents=parse(Source.NOTIFICATION,"com.tencent.mm","cents","支付成功 0.01元",T);
        check("exact integer cents",cents.cents==1);
        Candidate comma=parse(Source.SMS,"sms:95588","comma","消费人民币1,234.56元，余额5,000.00元",T);
        check("comma grouping and RMB parsing",comma.cents==123456);
        Candidate multi=parse(Source.SCREENSHOT,"image","multi","消费38元\n消费46元",T);
        check("multi-transaction screenshot cannot auto post",multi.status()==Status.REVIEW);
        Candidate conflict=payment(Source.SMS,"sms:95588","conflict","瑞幸咖啡","ORDER123456",T);
        conflict.cents=3900;
        check("same order conflicting amount waits",decide(conflict,List.of(row(2,consume))).action==Action.REVIEW);
        Candidate sameOrderOtherKind=payment(Source.SMS,"sms:95588","same","瑞幸咖啡","ORDER123456",T);
        check("ignored transaction is not revived",decide(sameOrderOtherKind,List.of(new Entry(2,consume,Status.IGNORED))).action==Action.MERGE);
        Candidate ambiguous=payment(Source.SMS,"sms:95588","a","瑞幸咖啡","",T+30_000);
        check("multiple plausible matches wait",decide(ambiguous,List.of(row(1,firstNoOrder),row(2,secondNoOrder))).action==Action.REVIEW);
        check("redacts long card number and phone", !redact("卡号6222021234567890 手机13800138000").contains("6222021234567890") && !redact("13800138000").contains("13800138000"));
        Candidate fx=payment(Source.NOTIFICATION,"com.tencent.mm","fx","商店","",T);fx.currency="USD";
        check("foreign currency never mixed into RMB totals",netExpense(List.of(row(1,fx)))==0);
        check("distinct currencies do not merge",decide(fx,List.of(row(2,consume))).action==Action.INSERT);
        System.out.println("\n"+passed+" tests passed");
    }
}
