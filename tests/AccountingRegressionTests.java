import com.qingji.app.core.LedgerCore;
import com.qingji.app.core.DiagnosticPolicy;
import static com.qingji.app.core.LedgerCore.*;
import java.time.*;
import java.util.*;

public final class AccountingRegressionTests {
    private static final long T=LocalDateTime.of(2026,10,21,14,0).atZone(ZONE).toInstant().toEpochMilli();
    private static int passed;
    private static Candidate parse(Source source,String origin,String key,String text,long at) {
        Candidate c=LedgerCore.parse(new Evidence(source,origin,key,text,at),Set.of("1234","5678"));
        if(c==null)throw new AssertionError("Rejected fixture: "+key);
        return c;
    }
    private static Entry row(long id,Candidate c) {
        Entry e=new Entry(id,c,c.status());e.evidence.add(new EvidenceRef(c.evidence));return e;
    }
    private static void check(String name,boolean ok) {
        if(!ok)throw new AssertionError(name);
        passed++;
    }
    private static Candidate refund(String key,String amount,String id,long at) {
        return parse(Source.NOTIFICATION,"com.tencent.mm",key,
            "退款成功"+amount+"元\n商户：测试商店\n商户订单号：ORDER123456"+
            (id.isEmpty()?"":"\n退款单号："+id),at);
    }
    public static void main(String[] args) {
        Candidate partial=parse(Source.NOTIFICATION,"com.tencent.mm","partial",
            "退款成功\n退款金额：20.00元\n原支付金额：100.00元\n商户：测试商店",T);
        check("Use refund amount rather than original payment",partial.cents==2000&&partial.kind==Kind.REFUND&&partial.status()==Status.CONFIRMED);
        Candidate reversed=parse(Source.NOTIFICATION,"com.tencent.mm","reversed",
            "原支付金额：100元\n退款成功\n退款金额：20元",T);
        check("Refund amount does not depend on field order",reversed.cents==2000);
        Candidate ambiguous=parse(Source.NOTIFICATION,"com.tencent.mm","ambiguous",
            "退款成功\n退款金额20元\n实际退款金额30元\n原支付金额100元",T);
        check("Conflicting refund amounts require review",ambiguous.status()==Status.REVIEW);
        Candidate noRefundAmount=parse(Source.NOTIFICATION,"com.tencent.mm","reference-only",
            "退款成功\n原支付金额100元",T);
        check("Original payment alone cannot confirm a refund amount",noRefundAmount.status()==Status.REVIEW);
        Candidate ocrRefund=parse(Source.ACCESSIBILITY_OCR,"com.unionpay","refund-ocr",
            "退款成功\n退款金额：20\n原支付金额：100元\n商户：测试商店\n交易时间：2026-10-21 14:00:00",T);
        check("Explicit integer refund amount is bound to its label",ocrRefund.cents==2000&&ocrRefund.status()==Status.REVIEW);
        check("Refund amount rule remains diagnostic-safe",DiagnosticPolicy.rule(partial.amountRule).equals("REFUND"));

        for(String label:List.of("收款账户","收款账号","收款帐号","收款银行账号","收款方","收款人","收款商家")) {
            Candidate c=parse(Source.ACCESSIBILITY,"com.unionpay",label,
                "付款成功38元\n商户：测试商店\n"+label+"：商户结算账户\n交易时间：2026-10-21 14:00:00",T);
            check("Payee field cannot reverse expense: "+label,c.kind==Kind.EXPENSE&&c.status()==Status.CONFIRMED);
        }
        Candidate income=parse(Source.NOTIFICATION,"com.tencent.mm","income-label",
            "收款到账38元\n付款账户：张三",T);
        check("Payer account label cannot reverse income",income.kind==Kind.INCOME&&income.status()==Status.CONFIRMED);
        Candidate bothDirections=parse(Source.NOTIFICATION,"com.tencent.mm","both",
            "支付成功38元\n收款到账38元",T);
        check("Conflicting transaction directions require review",bothDirections.status()==Status.REVIEW);

        Candidate transfer=parse(Source.NOTIFICATION,"com.unionpay","transfer",
            "从尾号1234转入本人银行卡尾号5678，转账成功500元",T);
        Candidate firstIncome=parse(Source.SMS,"sms:95588","bank-transfer","尾号5678账户到账500元",T+10_000);
        Entry transferRow=row(1,transfer);
        check("Trusted transfer links its first bank evidence",decide(firstIncome,List.of(transferRow)).action==Action.MERGE);
        check("Trusted transfer can arrive after bank evidence",decide(transfer,List.of(row(2,firstIncome))).action==Action.MERGE);
        transferRow.evidence.add(new EvidenceRef(firstIncome.evidence));
        Candidate laterIncome=parse(Source.SMS,"sms:95588","different-income","尾号5678账户到账500元，付款方：李四",T+60_000);
        check("Independent bank event is not swallowed by matched transfer",decide(laterIncome,List.of(transferRow)).action==Action.INSERT);
        Candidate oldImage=parse(Source.SCREENSHOT,"shared-image","old",
            "转账成功500元\n转入本人银行卡尾号5678",T+60_000);
        check("Undated transfer image cannot consume a current income",decide(oldImage,List.of(row(3,laterIncome))).action==Action.REVIEW);
        check("Income cannot silently join a pending undated transfer",decide(laterIncome,List.of(row(4,oldImage))).action==Action.REVIEW);
        Candidate datedOcr=parse(Source.ACCESSIBILITY_OCR,"com.unionpay","dated-ocr",
            "转账成功500元\n转入本人银行卡尾号5678\n商户：账户互转\n交易时间：2026-10-21 14:00:00",T);
        check("Even dated unverified OCR cannot reclassify income",decide(datedOcr,List.of(row(5,firstIncome))).action==Action.REVIEW);

        Candidate r1=refund("r1","20","REFUND000001",T);
        Candidate r2=refund("r2","20","REFUND000002",T+3_600_000);
        check("Different refund IDs for one purchase stay separate",decide(r2,List.of(row(6,r1))).action==Action.INSERT);
        Candidate replay=refund("repost","20","REFUND000001",T+7_200_000);
        check("Same refund ID still merges after the time window",decide(replay,List.of(row(6,r1))).action==Action.MERGE);
        Candidate conflict=refund("conflict","30","REFUND000001",T+1000);
        check("Same refund ID with conflicting amount requires review",decide(conflict,List.of(row(6,r1))).action==Action.REVIEW);
        Candidate noId1=refund("no-id-1","20","",T),noId2=refund("no-id-2","20","",T+1000);
        check("Original order alone cannot identify a refund",decide(noId2,List.of(row(7,noId1))).action==Action.INSERT);
        Candidate noIdOtherAmount=refund("no-id-3","30","",T+2000);
        check("Different partial amounts on original order are valid refunds",decide(noIdOtherAmount,List.of(row(7,noId1))).action==Action.INSERT);
        Candidate otherSource=parse(Source.SMS,"sms:95588","refund-sms",
            "微信支付退款成功20元\n商户：测试商店\n商户订单号：ORDER123456",T+1000);
        check("Refund without event ID across channels requires review",decide(otherSource,List.of(row(7,noId1))).action==Action.REVIEW);
        Candidate otherSourceWithId=parse(Source.SMS,"sms:95588","refund-id-sms",
            "微信支付退款成功20元\n商户：测试商店\n退款单号：REFUND000001",T+1000);
        check("Same refund ID across channels merges",decide(otherSourceWithId,List.of(row(6,r1))).action==Action.MERGE);
        Candidate purchase=parse(Source.NOTIFICATION,"com.tencent.mm","purchase",
            "支付成功100元\n商户：测试商店\n商户订单号：ORDER123456",T);
        check("Purchase and refund stay separate",decide(r1,List.of(row(8,purchase))).action==Action.INSERT);
        check("Two partial refunds both reduce net expense",netExpense(List.of(row(8,purchase),row(6,r1),row(9,r2)))==6000);
        System.out.println("Accounting regression tests passed: "+passed);
    }
}
