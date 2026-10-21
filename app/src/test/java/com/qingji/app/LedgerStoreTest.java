package com.qingji.app;

import android.app.Application;
import android.content.Context;
import com.qingji.app.core.LedgerCore;
import static com.qingji.app.core.LedgerCore.*;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Runs the production LedgerStore against SQLite, including close/reopen checks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class LedgerStoreTest {
    private static final long T = LocalDateTime.of(2026,10,21,14,0).atZone(ZONE).toInstant().toEpochMilli();
    private Context context;
    private LedgerStore store;

    @Before public void open() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("ledger.db");
        store = new LedgerStore(context);
    }
    @After public void close() { store.close(); context.deleteDatabase("ledger.db"); }
    private void reopen() { store.close(); store = new LedgerStore(context); }
    private Candidate parse(Source source,String origin,String key,String text,long at) {
        Candidate c = LedgerCore.parse(new Evidence(source,origin,key,text,at),Set.of("1234","5678"));
        assertNotNull(key,c);
        return c;
    }
    private Candidate payment(Source source,String key,String extra) {
        return parse(source,"com.unionpay",key,
            "付款成功38.00元\n商户：测试商店\n支付订单号：PAYMENT123456\n"+extra,T);
    }
    private long monthlyExpense(int year,int month) {
        List<Entry> rows = new ArrayList<>();
        for (Entry row : store.list()) {
            if (YearMonth.from(Instant.ofEpochMilli(row.transaction.occurredAt).atZone(ZONE)).equals(YearMonth.of(year,month))) rows.add(row);
        }
        return netExpense(rows);
    }
    private long income() {
        long total=0;
        for (Entry row:store.list()) if(row.status==Status.CONFIRMED&&row.transaction.kind==Kind.INCOME) total+=row.transaction.cents;
        return total;
    }

    @Test public void pendingOcrCannotRewriteConfirmedDateAfterReopeningDatabase() {
        for (Source source : List.of(Source.SCREENSHOT,Source.ACCESSIBILITY_OCR,Source.SCREEN_CAPTURE)) {
            store.clear();
            long id=store.ingest(payment(Source.NOTIFICATION,"bank-"+source,"")).target;
            reopen();
            Candidate ocr=payment(source,"ocr-"+source,"交易时间：2025-10-21 14:00:00");
            assertEquals(Status.REVIEW,ocr.status());
            Decision decision=store.ingest(ocr,"capture-"+source,"OCR input saved");
            assertEquals(Action.MERGE,decision.action);
            assertEquals(id,decision.target);
            reopen();
            Entry saved=store.get(id);
            assertEquals(source.name(),T,saved.transaction.occurredAt);
            assertEquals(Status.CONFIRMED,saved.status);
            assertEquals(Kind.EXPENSE,saved.transaction.kind);
            assertEquals(3800,saved.transaction.cents);
            assertFalse(saved.transaction.exactTime);
            assertEquals(3800,monthlyExpense(2026,10));
            assertEquals(0,monthlyExpense(2025,10));
            assertEquals(2,store.evidence(id).size());
            assertTrue(store.evidence(id).stream().anyMatch(e->e.imageRef.equals("capture-"+source)));
        }
    }

    @Test public void pendingOcrCannotReplaceConfirmedMerchantOrCategory() {
        Candidate first=parse(Source.NOTIFICATION,"com.unionpay","bank-no-merchant",
            "支付成功38元\n支付订单号：PAYMENT123456",T);
        long id=store.ingest(first).target;
        Candidate ocr=parse(Source.SCREENSHOT,"com.unionpay","ocr-merchant",
            "支付成功38元\n商户：错误咖啡店\n支付订单号：PAYMENT123456\n交易时间：2025-10-21 14:00:00",T);
        assertEquals(Action.MERGE,store.ingest(ocr).action);
        reopen();
        Entry saved=store.get(id);
        assertEquals("",saved.transaction.merchant);
        assertEquals(first.category,saved.transaction.category);
        assertEquals(T,saved.transaction.occurredAt);
        assertEquals(2,saved.evidence.size());
    }

    @Test public void conflictingOcrAmountStaysPendingAndDoesNotChangeTotals() {
        long id=store.ingest(payment(Source.NOTIFICATION,"bank","")).target;
        Candidate ocr=parse(Source.SCREENSHOT,"com.unionpay","wrong-amount",
            "付款成功99元\n商户：测试商店\n支付订单号：PAYMENT123456\n交易时间：2026-10-21 14:00:00",T);
        Decision pending=store.ingest(ocr);
        assertEquals(Action.REVIEW,pending.action);
        reopen();
        assertEquals(3800,store.get(id).transaction.cents);
        assertEquals(Status.REVIEW,store.get(pending.target).status);
        assertEquals(id,store.related(pending.target));
        assertEquals(3800,netExpense(store.list()));
    }

    @Test public void trustedEvidenceCanStillSupplyMissingTransactionTime() {
        long id=store.ingest(payment(Source.NOTIFICATION,"first","")).target;
        Candidate trusted=payment(Source.NOTIFICATION,"trusted-detail","交易时间：2026-10-20 13:00:00");
        assertEquals(Status.CONFIRMED,trusted.status());
        assertEquals(Action.MERGE,store.ingest(trusted).action);
        reopen();
        assertEquals(trusted.occurredAt,store.get(id).transaction.occurredAt);
        assertTrue(store.get(id).transaction.exactTime);
        assertEquals(Status.CONFIRMED,store.get(id).status);
    }

    @Test public void trustedRepeatDoesNotAutoConfirmAnExistingReviewEntry() {
        long id=store.ingest(payment(Source.SCREENSHOT,"first-ocr","交易时间：2026-10-21 14:00:00")).target;
        assertEquals(Action.MERGE,store.ingest(payment(Source.NOTIFICATION,"bank","")).action);
        reopen();
        assertEquals(Status.REVIEW,store.get(id).status);
        assertEquals(0,netExpense(store.list()));
    }

    @Test public void undatedTransferOcrCannotReclassifyConfirmedIncome() {
        long id=store.ingest(parse(Source.SMS,"sms:95588","income","尾号5678账户到账500元",T)).target;
        Candidate ocr=parse(Source.SCREENSHOT,"shared-image","old-transfer","转账成功500元\n转入本人银行卡尾号5678",T+60_000);
        assertEquals(Action.REVIEW,store.ingest(ocr).action);
        reopen();
        assertEquals(Kind.INCOME,store.get(id).transaction.kind);
        assertEquals(Status.CONFIRMED,store.get(id).status);
        assertEquals(50000,income());
    }

    @Test public void secondBankIncomeIsCountedAfterTransferWasAlreadyMatched() {
        store.ingest(parse(Source.NOTIFICATION,"com.unionpay","transfer","从尾号1234转入本人银行卡尾号5678，转账成功500元",T));
        assertEquals(Action.MERGE,store.ingest(parse(Source.SMS,"sms:95588","bank-transfer","尾号5678账户到账500元",T+10_000)).action);
        assertEquals(Action.INSERT,store.ingest(parse(Source.SMS,"sms:95588","new-income","尾号5678账户到账500元，付款方：李四",T+60_000)).action);
        reopen();
        assertEquals(2,store.list().size());
        assertEquals(50000,income());
    }

    @Test public void twoPartialRefundsReduceExpenseTwiceAndReplayOnlyAddsEvidence() {
        store.ingest(parse(Source.NOTIFICATION,"com.tencent.mm","purchase","支付成功100元\n商户：测试商店\n商户订单号：ORDER123456",T));
        String prefix="退款成功\n退款金额20元\n原支付金额100元\n商户：测试商店\n商户订单号：ORDER123456\n退款单号：";
        long first=store.ingest(parse(Source.NOTIFICATION,"com.tencent.mm","refund-1",prefix+"REFUND000001",T+60_000)).target;
        assertEquals(Action.INSERT,store.ingest(parse(Source.NOTIFICATION,"com.tencent.mm","refund-2",prefix+"REFUND000002",T+3_600_000)).action);
        assertEquals(Action.MERGE,store.ingest(parse(Source.NOTIFICATION,"com.tencent.mm","refund-1-repeat",prefix+"REFUND000001",T+7_200_000)).action);
        reopen();
        assertEquals(3,store.list().size());
        assertEquals(6000,netExpense(store.list()));
        assertEquals(2,store.evidence(first).size());
    }

    @Test public void payeeAccountLabelPostsExpenseRatherThanIncome() {
        store.ingest(parse(Source.ACCESSIBILITY,"com.unionpay","page",
            "付款成功38元\n商户：测试便利店\n收款账户：商户结算账户\n交易时间：2026-10-21 14:00:00",T));
        reopen();
        assertEquals(3800,netExpense(store.list()));
        assertEquals(0,income());
    }

    @Test public void editedAndIgnoredRecordsKeepUserDecisionsWhenNewEvidenceArrives() {
        long id=store.ingest(payment(Source.NOTIFICATION,"original","")).target;
        Candidate edited=store.get(id).transaction;
        edited.merchant="人工核对的商户";
        edited.exactTime=true;
        store.edit(id,edited,Status.CONFIRMED);
        assertEquals(Action.MERGE,store.ingest(payment(Source.NOTIFICATION,"trusted-change","交易时间：2025-10-21 14:00:00")).action);
        reopen();
        assertEquals("人工核对的商户",store.get(id).transaction.merchant);
        assertEquals(T,store.get(id).transaction.occurredAt);
        store.ignore(id);
        store.ingest(payment(Source.SCREENSHOT,"ocr-after-ignore","交易时间：2025-10-21 14:00:00"));
        reopen();
        assertEquals(Status.IGNORED,store.get(id).status);
        assertEquals(0,netExpense(store.list()));
    }
}
