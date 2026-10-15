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
    public static QingJiApp app() {return instance;}
    @Override public void onCreate() {
        super.onCreate();instance=this;config=new Config(this);store=new LedgerStore(this);updater=new UpdateManager(this);
        config.flag("notification_connected",false);config.flag("accessibility_connected",false);config.flag("capture_running",false);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"屏幕识别运行状态",NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    public void refresh() {sendBroadcast(new Intent(REFRESH).setPackage(getPackageName()));}
    public void ingest(Evidence e) {ingest(e,null);}
    public void ingest(Evidence e,Runnable done) {
        worker.execute(()-> {
            try {
                if(!shouldProcess(e)) return;
                Candidate c=LedgerCore.parse(e,config.ownTails());
                if(c==null) return;
                Decision d=store.ingest(c);
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
            } finally {if(done!=null) new Handler(Looper.getMainLooper()).post(done);}
        });
    }
    private boolean shouldProcess(Evidence e) {
        if(!config.enabled())return false;
        switch(e.source){
            case NOTIFICATION:return config.flag("notifications")&&config.allowed(e.origin);
            case SMS:return config.flag("sms")&&e.origin.startsWith("sms:")&&config.senderAllowed(e.origin.substring(4));
            case ACCESSIBILITY:return config.flag("accessibility")&&config.allowed(e.origin);
            case ACCESSIBILITY_OCR:return config.flag("accessibility")&&config.flag("accessibility_ocr")&&config.allowed(e.origin);
            case SCREEN_CAPTURE:return config.flag("capture_running")&&config.allowed(e.origin);
            case SCREENSHOT:return !e.origin.equals("screenshot-folder")||config.flag("screenshots");
            default:return true;
        }
    }
}
