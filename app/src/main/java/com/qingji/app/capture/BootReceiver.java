package com.qingji.app.capture;

import android.content.*;
import android.service.notification.NotificationListenerService;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))return;
        NotificationListenerService.requestRebind(new ComponentName(c,PaymentNotificationService.class));
        ScreenshotScanJob.schedule(c);
        // MediaProjection is never restarted on boot; it needs a new system consent.
    }
}
