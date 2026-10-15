package com.qingji.app.capture;

import android.Manifest;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.qingji.app.*;
import static com.qingji.app.core.LedgerCore.*;
import java.util.*;
import java.util.concurrent.*;

/** System content-triggered jobs, no continuous gallery polling or wake lock. */
public final class ScreenshotScanJob extends JobService {
    public static final int JOB_ID=7312;
    private final ExecutorService scanner=Executors.newSingleThreadExecutor();
    private volatile boolean stopped;
    public static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    public static void schedule(Context c) {
        schedule(c,false);
    }
    private static void schedule(Context c,boolean rearm) {
        Config config=QingJiApp.app().config;JobScheduler jobs=c.getSystemService(JobScheduler.class);
        if(!config.enabled()||!config.flag("screenshots")||!hasPermission(c)){jobs.cancel(JOB_ID);return;}
        if(!rearm&&jobs.getPendingJob(JOB_ID)!=null)return;
        JobInfo info=new JobInfo.Builder(JOB_ID,new ComponentName(c,ScreenshotScanJob.class))
            .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(3000).setTriggerContentMaxDelay(15000).build();
        jobs.schedule(info);
    }
    @Override public boolean onStartJob(JobParameters params) {
        stopped=false;scanner.execute(()-> {
            try{scan();}catch(Exception ex){QingJiApp.app().config.putText("last_error","截图扫描未完成："+ex.getClass().getSimpleName());}
            finally{if(!stopped){jobFinished(params,false);schedule(this,true);}}
        });return true;
    }
    private void scan() {
        Config config=QingJiApp.app().config;if(!config.enabled()||!config.flag("screenshots")||!hasPermission(this))return;
        long enabledAt=config.prefs.getLong("screenshots_since",System.currentTimeMillis())/1000;
        long last=config.prefs.getLong("screenshots_last",enabledAt);
        long since=Math.max(enabledAt,last-300);
        Set<String> processed=new LinkedHashSet<>(config.prefs.getStringSet("images_processed",Collections.emptySet()));
        String pathColumn=Build.VERSION.SDK_INT>=29?MediaStore.Images.Media.RELATIVE_PATH:MediaStore.Images.Media.DATA;
        String selection="date_added >= ? AND ("+pathColumn+" LIKE ? OR "+pathColumn+" LIKE ? OR _display_name LIKE ?)"+(Build.VERSION.SDK_INT>=29?" AND is_pending=0":"");
        String[] args={Long.toString(since),"%Screenshots%","%截屏%","Screenshot%"};
        try(Cursor c=getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id","date_added","date_modified"},selection,args,"date_added ASC")) {
            if(c==null)return;
            while(!stopped&&c.moveToNext()) {
                long id=c.getLong(0),date=c.getLong(1),modified=c.getLong(2);String key=id+":"+modified;
                if(processed.contains(key))continue;
                Uri uri=ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id);
                CountDownLatch done=new CountDownLatch(1);boolean[] ok={false};
                Ocr.uri(this,uri,Source.SCREENSHOT,"screenshot-folder",key,(success,message)->{ok[0]=success;done.countDown();});
                try{if(!done.await(20,TimeUnit.SECONDS))break;}catch(InterruptedException ex){Thread.currentThread().interrupt();break;}
                if(!ok[0])break;
                processed.add(key);while(processed.size()>256)processed.remove(processed.iterator().next());
                last=Math.max(last,date);
                config.prefs.edit().putStringSet("images_processed",new LinkedHashSet<>(processed)).putLong("screenshots_last",last).apply();
            }
        }
    }
    @Override public boolean onStopJob(JobParameters params){stopped=true;return true;}
    @Override public void onDestroy(){stopped=true;scanner.shutdownNow();super.onDestroy();}
}
