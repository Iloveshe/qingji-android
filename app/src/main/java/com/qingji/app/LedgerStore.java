package com.qingji.app;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import com.qingji.app.core.LedgerCore;
import static com.qingji.app.core.LedgerCore.*;
import java.util.*;

/** Private sandbox SQLite, Android file encryption; no cloud backup. */
public final class LedgerStore extends SQLiteOpenHelper {
    public LedgerStore(Context c) { super(c,"ledger.db",null,2); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT,cents INTEGER NOT NULL,currency TEXT NOT NULL,kind TEXT NOT NULL,merchant TEXT NOT NULL,category TEXT NOT NULL,rail TEXT NOT NULL,occurred_at INTEGER NOT NULL,exact_time INTEGER NOT NULL,order_hash TEXT NOT NULL,order_scope TEXT NOT NULL,account_tail TEXT NOT NULL,target_tail TEXT NOT NULL,confidence REAL NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,excerpt TEXT NOT NULL,related_id INTEGER NOT NULL DEFAULT 0,user_edited INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX transaction_time ON transactions(occurred_at)");
        db.execSQL("CREATE INDEX transaction_order ON transactions(order_hash,order_scope)");
        db.execSQL("CREATE TABLE evidence (fingerprint TEXT PRIMARY KEY,transaction_id INTEGER NOT NULL,source TEXT NOT NULL,origin TEXT NOT NULL,event_key TEXT NOT NULL,observed_at INTEGER NOT NULL,excerpt TEXT NOT NULL,image_ref TEXT NOT NULL DEFAULT '',image_note TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX evidence_transaction ON evidence(transaction_id)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        if(oldVersion<2){
            db.execSQL("ALTER TABLE evidence ADD COLUMN image_ref TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE evidence ADD COLUMN image_note TEXT NOT NULL DEFAULT '旧版未保存截图，无法补回'");
            // Preserve ledger data and user decisions; quarantine only unverified OCR-only postings.
            db.execSQL("UPDATE transactions SET status='REVIEW',reason='旧版 OCR 自动记录需重新核对金额；历史截图未保存' WHERE status='CONFIRMED' AND user_edited=0 AND EXISTS (SELECT 1 FROM evidence e WHERE e.transaction_id=transactions.id AND e.source IN ('ACCESSIBILITY_OCR','SCREEN_CAPTURE','SCREENSHOT')) AND NOT EXISTS (SELECT 1 FROM evidence e WHERE e.transaction_id=transactions.id AND e.source NOT IN ('ACCESSIBILITY_OCR','SCREEN_CAPTURE','SCREENSHOT'))");
        }
    }
    private ContentValues values(Candidate c,Status status,String reason,long related) {
        ContentValues v=new ContentValues();
        v.put("cents",c.cents);v.put("currency",c.currency);v.put("kind",c.kind.name());v.put("merchant",c.merchant);v.put("category",c.category);v.put("rail",c.rail);
        v.put("occurred_at",c.occurredAt);v.put("exact_time",c.exactTime?1:0);v.put("order_hash",c.orderHash);v.put("order_scope",c.orderScope);v.put("account_tail",c.accountTail);v.put("target_tail",c.targetTail);
        v.put("confidence",c.confidence);v.put("status",status.name());v.put("reason",reason);v.put("excerpt",c.excerpt);v.put("related_id",related);return v;
    }
    private Entry read(Cursor cursor) {
        Candidate c=new Candidate();
        c.cents=cursor.getLong(cursor.getColumnIndexOrThrow("cents"));c.currency=s(cursor,"currency");c.kind=Kind.valueOf(s(cursor,"kind"));c.merchant=s(cursor,"merchant");c.category=s(cursor,"category");c.rail=s(cursor,"rail");
        c.occurredAt=cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at"));c.exactTime=cursor.getInt(cursor.getColumnIndexOrThrow("exact_time"))==1;c.orderHash=s(cursor,"order_hash");c.orderScope=s(cursor,"order_scope");c.accountTail=s(cursor,"account_tail");c.targetTail=s(cursor,"target_tail");
        c.confidence=cursor.getDouble(cursor.getColumnIndexOrThrow("confidence"));c.reason=s(cursor,"reason");c.excerpt=s(cursor,"excerpt");
        Entry row=new Entry(cursor.getLong(cursor.getColumnIndexOrThrow("id")),c,Status.valueOf(s(cursor,"status")));
        row.userEdited=cursor.getInt(cursor.getColumnIndexOrThrow("user_edited"))==1;
        try(Cursor e=getReadableDatabase().query("evidence",null,"transaction_id=?",new String[]{Long.toString(row.id)},null,null,"observed_at ASC")) {
            while(e.moveToNext()) row.evidence.add(new EvidenceRef(Source.valueOf(s(e,"source")),s(e,"origin"),s(e,"event_key"),s(e,"fingerprint")));
        }
        return row;
    }
    private static String s(Cursor c,String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
    public synchronized List<Entry> list() {
        List<Entry> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("transactions",null,null,null,null,null,"occurred_at DESC, id DESC")) { while(c.moveToNext()) rows.add(read(c)); }
        return rows;
    }
    public synchronized Entry get(long id) {
        try(Cursor c=getReadableDatabase().query("transactions",null,"id=?",new String[]{Long.toString(id)},null,null,null)) { return c.moveToFirst()?read(c):null; }
    }
    public synchronized long related(long id) {
        try(Cursor c=getReadableDatabase().query("transactions",new String[]{"related_id"},"id=?",new String[]{Long.toString(id)},null,null,null)) { return c.moveToFirst()?c.getLong(0):0; }
    }
    private List<Entry> nearby(Candidate c) {
        List<Entry> rows=new ArrayList<>();
        String where="occurred_at BETWEEN ? AND ? OR (order_hash<>'' AND order_hash=? AND order_scope=?)";
        String[] args={Long.toString(c.occurredAt-300_000),Long.toString(c.occurredAt+300_000),c.orderHash,c.orderScope};
        try(Cursor cur=getReadableDatabase().query("transactions",null,where,args,null,null,null)) { while(cur.moveToNext()) rows.add(read(cur)); }
        return rows;
    }
    public synchronized Decision ingest(Candidate c) {
        return ingest(c,"","此来源为文字证据，没有截图");
    }
    public synchronized Decision ingest(Candidate c,String imageRef,String imageNote) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor seen=db.query("evidence",new String[]{"transaction_id"},"fingerprint=?",new String[]{c.evidence.fingerprint()},null,null,null)) {
                if(seen.moveToFirst()) {
                    if(!imageRef.isEmpty()){
                        ContentValues attach=new ContentValues();attach.put("image_ref",imageRef);attach.put("image_note",imageNote);
                        db.update("evidence",attach,"fingerprint=? AND image_ref=''",new String[]{c.evidence.fingerprint()});
                    }
                    db.setTransactionSuccessful();return new Decision(Action.IGNORE,seen.getLong(0),"来源事件已处理");}
            }
            Decision d=LedgerCore.decide(c,nearby(c));long id;
            if(d.action==Action.MERGE) {
                id=d.target;Entry row=get(id);
                // Unverified OCR may add evidence, but must not rewrite a confirmed transaction.
                if(row!=null && !row.userEdited && row.status!=Status.IGNORED
                        && (row.status!=Status.CONFIRMED || c.status()==Status.CONFIRMED)) {
                    Candidate old=row.transaction;
                    boolean promoteTransfer=c.kind==Kind.TRANSFER && old.kind==Kind.INCOME;
                    boolean moreDetails=(c.exactTime&&!old.exactTime)||(!c.merchant.isEmpty()&&old.merchant.isEmpty())||(!c.orderHash.isEmpty()&&old.orderHash.isEmpty());
                    if(promoteTransfer || (old.kind==c.kind && (c.confidence>old.confidence||moreDetails))) {
                        if(c.merchant.isEmpty()) c.merchant=old.merchant;
                        if(!c.exactTime&&old.exactTime){c.exactTime=true;c.occurredAt=old.occurredAt;}
                        c.confidence=Math.max(c.confidence,old.confidence);
                        if(c.orderHash.isEmpty()) {c.orderHash=old.orderHash;c.orderScope=old.orderScope;}
                        if(c.accountTail.isEmpty()) c.accountTail=old.accountTail;
                        if(c.targetTail.isEmpty())c.targetTail=old.targetTail;
                        // Never auto-confirm an ambiguity queue entry merely because another source repeats it.
                        db.update("transactions",values(c,row.status,d.reason,related(id)),"id=?",new String[]{Long.toString(id)});
                    }
                }
            } else if(d.action==Action.IGNORE) {db.setTransactionSuccessful();return d;}
            else {
                Status state=d.action==Action.REVIEW?Status.REVIEW:Status.CONFIRMED;
                id=db.insertOrThrow("transactions",null,values(c,state,d.reason,d.target));
            }
            addEvidence(db,id,c.evidence,c.excerpt,imageRef,imageNote);db.setTransactionSuccessful();
            return new Decision(d.action,id,d.reason);
        } finally {db.endTransaction();}
    }
    private void addEvidence(SQLiteDatabase db,long id,Evidence e,String excerpt,String imageRef,String imageNote) {
        ContentValues v=new ContentValues();v.put("fingerprint",e.fingerprint());v.put("transaction_id",id);v.put("source",e.source.name());v.put("origin",e.origin);v.put("event_key",hash(e.eventKey));v.put("observed_at",e.observedAt);v.put("excerpt",excerpt);
        v.put("image_ref",imageRef);v.put("image_note",imageNote);db.insertWithOnConflict("evidence",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    public static final class StoredEvidence {
        public final String label,excerpt,imageRef,imageNote;public final long at;
        StoredEvidence(String label,String excerpt,String imageRef,String imageNote,long at){this.label=label;this.excerpt=excerpt;this.imageRef=imageRef;this.imageNote=imageNote;this.at=at;}
    }
    public synchronized List<StoredEvidence> evidence(long id){
        List<StoredEvidence> rows=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("evidence",null,"transaction_id=?",new String[]{Long.toString(id)},null,null,"observed_at ASC")){
            while(c.moveToNext())rows.add(new StoredEvidence(sourceLabel(Source.valueOf(s(c,"source")))+" · "+s(c,"origin"),s(c,"excerpt"),s(c,"image_ref"),s(c,"image_note"),c.getLong(c.getColumnIndexOrThrow("observed_at"))));
        }return rows;
    }
    public synchronized void clearImages(){ContentValues v=new ContentValues();v.put("image_ref","");v.put("image_note","截图已由用户清除，文字摘要保留");getWritableDatabase().update("evidence",v,null,null);}
    public synchronized void edit(long id,Candidate c,Status state) {
        ContentValues v=values(c,state,"用户已核对",0);v.put("user_edited",1);
        getWritableDatabase().update("transactions",v,"id=?",new String[]{Long.toString(id)});
    }
    public synchronized void ignore(long id) {
        ContentValues v=new ContentValues();v.put("status",Status.IGNORED.name());v.put("user_edited",1);
        getWritableDatabase().update("transactions",v,"id=?",new String[]{Long.toString(id)});
    }
    public synchronized void suggestCategory(long id,String category) {
        ContentValues v=new ContentValues();v.put("category",category);
        getWritableDatabase().update("transactions",v,"id=? AND status=? AND user_edited=0",new String[]{Long.toString(id),Status.REVIEW.name()});
    }
    public synchronized boolean manualMerge(long from,long to) {
        Entry a=get(from),b=get(to);
        if(a==null||b==null||from==to||a.transaction.cents!=b.transaction.cents||!a.transaction.currency.equals(b.transaction.currency)) return false;
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            ContentValues v=new ContentValues();v.put("transaction_id",to);db.update("evidence",v,"transaction_id=?",new String[]{Long.toString(from)});
            db.delete("transactions","id=?",new String[]{Long.toString(from)});db.setTransactionSuccessful();return true;
        } finally {db.endTransaction();}
    }
    public synchronized String excerpts(long id) {
        StringBuilder text=new StringBuilder();
        try(Cursor c=getReadableDatabase().query("evidence",null,"transaction_id=?",new String[]{Long.toString(id)},null,null,"observed_at ASC")) {
            while(c.moveToNext()) text.append(sourceLabel(Source.valueOf(s(c,"source")))).append(" · ").append(s(c,"origin")).append("\n").append(s(c,"excerpt")).append("\n\n");
        } return text.toString();
    }
    public synchronized void clear() {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {db.delete("evidence",null,null);db.delete("transactions",null,null);db.setTransactionSuccessful();} finally {db.endTransaction();}
    }
    public static String sourceLabel(Source s) {
        switch(s) {
            case NOTIFICATION:return "通知";case SMS:return "短信";case ACCESSIBILITY:return "支付页面";
            case SCREENSHOT:return "截图";case SCREEN_CAPTURE:return "屏幕识别";case ACCESSIBILITY_OCR:return "自动页面 OCR";default:return "文本导入";
        }
    }
    public static String kindLabel(Kind kind) {
        switch(kind) {case EXPENSE:return "支出";case INCOME:return "收入";case TRANSFER:return "内部转账";case REFUND:return "退款";default:return "待判断";}
    }
}
