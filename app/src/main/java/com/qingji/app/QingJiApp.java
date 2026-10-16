package com.qingji.app;

import android.app.*;
import android.content.*;
import android.os.*;
import com.qingji.app.core.LedgerCore;
import com.qingji.app.update.UpdateManager;
import static com.qingji.app.core.LedgerCore.*;
import java.util.concurrent.*;

public final class QingJiApp extends Application {
    public static final String REFRESH="com.qingji.app.REFRESH";
    public static final String CHANNEL="capture";
    private static QingJiApp instance;
    public final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService cloudWorker=Executors.newSingleThreadExecutor();
    public Config config;
    public LedgerStore store;
    public UpdateManager updater;
    public CaptureDiagnostics diagnostics;
    public EvidenceVault evidenceVault;
    public static QingJiApp app() {return instance;}
    @Override public void onCreate() {
        super.onCreate();instance=this;config=new Config(this);diagnostics=new CaptureDiagnostics(config);store=new LedgerStore(this);updater=new UpdateManager(this);evidenceVault=new EvidenceVault(this);
        config.flag("notification_connected",false);config.flag("accessibility_connected",false);config.flag("capture_running",false);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"屏幕识别运行状态",NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        RemoteDiagnostics.schedule(this);
    }
    public void refresh() {sendBroadcast(new Intent(REFRESH).setPackage(getPackageName()));}
    public void ingest(Evidence e) {ingest(e,null);}
    public void ingest(Evidence e,Runnable done) {
        ingestImage(e,null,done);
    }
    /** Takes bitmap ownership; encryption and storage run on the ledger worker. */
    public void ingestImage(Evidence e,android.graphics.Bitmap image,Runnable done) {
        worker.execute(()-> {
            try {
                if(!shouldProcess(e)){diagnostics.record(LedgerStore.sourceLabel(e.source),e.origin,"已跳过","采集开关关闭或来源未勾选");return;}
                ParseResult parsed=LedgerCore.analyze(e,config.ownTails());Candidate c=parsed.candidate;
                diagnostics.analyzed(e,parsed);
                if(c==null){diagnostics.record(LedgerStore.sourceLabel(e.source),e.origin,"未匹配",parsed.code.label);return;}
                String imageRef="",imageNote=image==null?"此来源为文字证据，没有截图":"未开启保存截图证据，本次图片已丢弃";
                if(image!=null&&config.flag("evidence_images")){
                    try{imageRef=evidenceVault.save(e.fingerprint(),image);imageNote="采集时用于 OCR 的截图；仅本机加密保存";}
                    catch(Exception|OutOfMemoryError ex){imageNote="截图保存失败或达到 100 MB 上限，可清理截图后重试";diagnostics.record(LedgerStore.sourceLabel(e.source),e.origin,"证据未保存","本地图片存储失败");}
                }
                Decision d=store.ingest(c,imageRef,imageNote);
                String stage=d.action==Action.MERGE?"已合并":d.action==Action.REVIEW?"待确认":d.action==Action.IGNORE?"已去重跳过":"已入账";
                diagnostics.record(LedgerStore.sourceLabel(e.source),e.origin,stage,d.reason);
                if(d.action!=Action.IGNORE) {
                    config.prefs.edit().remove("last_error").apply();
                    config.prefs.edit().putLong("last_capture",System.currentTimeMillis()).putString("last_source",LedgerStore.sourceLabel(e.source)).apply();
                    if(d.action==Action.MERGE) config.prefs.edit().putLong("merge_count",config.prefs.getLong("merge_count",0)+1).apply();
                    refresh();
                    if(config.flag("cloud_ai") && d.action==Action.REVIEW) cloudWorker.execute(()->{
                        if(!config.flag("cloud_ai"))return;
                        CloudClassifier.enrich(this,c);store.suggestCategory(d.target,c.category);refresh();
                    });
                }
            } catch(Exception ex) {
                // No raw financial content or credentials in logs.
                config.putText("last_error","采集未完成："+ex.getClass().getSimpleName());refresh();
                diagnostics.record(LedgerStore.sourceLabel(e.source),e.origin,"处理失败",ex.getClass().getSimpleName());
            } finally {if(image!=null&&!image.isRecycled())image.recycle();if(done!=null) new Handler(Looper.getMainLooper()).post(done);}
        });
    }
    private boolean shouldProcess(Evidence e) {
        if(!config.enabled())return false;
        switch(e.source){
            case NOTIFICATION:return config.flag("notifications")&&config.allowed(e.origin);
            case SMS:return config.flag("sms")&&e.origin.startsWith("sms:")&&config.senderAllowed(e.origin.substring(4));
            case ACCESSIBILITY:return config.flag("accessibility")&&config.allowed(e.origin);
            case ACCESSIBILITY_OCR:return config.flag("accessibility")&&config.flag("accessibility_ocr")&&config.allowed(e.origin);
            case SCREEN_CAPTURE:return config.flag("capture_running")&&config.allowed(e.origin)&&
                !getSystemService(KeyguardManager.class).isDeviceLocked()&&e.origin.equals(com.qingji.app.capture.PaymentAccessibilityService.foregroundPaymentPackage(config));
            case SCREENSHOT:return !e.origin.equals("screenshot-folder")||config.flag("screenshots");
            default:return true;
        }
    }
}
