package com.qingji.app;

import android.app.job.*;
import android.content.*;
import android.os.Build;
import com.qingji.app.core.DiagnosticPolicy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Explicit opt-in, configured private HTTPS endpoint, no redirects, no raw evidence. */
public final class RemoteDiagnostics {
    private static final int JOB=73;
    private static final ExecutorService sender=Executors.newSingleThreadExecutor();
    private RemoteDiagnostics() {}
    public static void schedule(Context context){
        Config c=QingJiApp.app().config;JobScheduler jobs=context.getSystemService(JobScheduler.class);
        if(!c.flag("remote_diagnostics")||!DiagnosticPolicy.endpoint(c.text("diagnostic_endpoint",""))){jobs.cancel(JOB);return;}
        jobs.schedule(new JobInfo.Builder(JOB,new ComponentName(context,DiagnosticUploadJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60_000L).setPersisted(true).build());
    }
    public static void send(Runnable done){sender.execute(()->{try{upload();}finally{if(done!=null)done.run();}});}
    private static void upload(){
        QingJiApp app=QingJiApp.app();Config c=app.config;String endpoint=c.text("diagnostic_endpoint","");HttpURLConnection connection=null;
        if(!c.flag("remote_diagnostics")||!DiagnosticPolicy.endpoint(endpoint))return;
        try{
            byte[] body=app.diagnostics.snapshot(app.updater.currentVersion(),Build.VERSION.SDK_INT).toString().getBytes(StandardCharsets.UTF_8);
            if(body.length>64*1024)throw new IllegalStateException("Diagnostic size limit");
            connection=(HttpURLConnection)new URL(endpoint).openConnection();connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setFixedLengthStreamingMode(body.length);
            String token=SecretVault.loadDiagnostic(c);if(!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);
            if(!c.flag("remote_diagnostics")||!endpoint.equals(c.text("diagnostic_endpoint","")))return;
            try(java.io.OutputStream out=connection.getOutputStream()){out.write(body);}
            int status=connection.getResponseCode();
            c.putText("diagnostic_upload_status",status>=200&&status<300?"脱敏诊断发送成功":"接收端拒绝或发生重定向（HTTP "+status+"）");
            if(status>=200&&status<300)c.prefs.edit().putLong("diagnostic_uploaded_at",System.currentTimeMillis()).apply();
        }catch(Exception ex){c.putText("diagnostic_upload_status","发送失败，请检查 HTTPS 接口、网络或令牌；日志仍留在本机");}
        finally{if(connection!=null)connection.disconnect();app.refresh();}
    }
}
