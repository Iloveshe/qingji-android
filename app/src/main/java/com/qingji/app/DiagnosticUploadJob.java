package com.qingji.app;

import android.app.job.*;

public final class DiagnosticUploadJob extends JobService {
    private volatile boolean stopped;
    @Override public boolean onStartJob(JobParameters parameters){stopped=false;RemoteDiagnostics.send(()->{if(!stopped)jobFinished(parameters,false);});return true;}
    @Override public boolean onStopJob(JobParameters parameters){stopped=true;return false;}
}
