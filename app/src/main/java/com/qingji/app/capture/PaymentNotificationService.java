package com.qingji.app.capture;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.*;
import com.qingji.app.*;
import static com.qingji.app.core.LedgerCore.*;
import java.util.LinkedHashSet;

public final class PaymentNotificationService extends NotificationListenerService {
    @Override public void onListenerConnected() {
        QingJiApp.app().config.flag("notification_connected",true);QingJiApp.app().refresh();
        ScreenshotScanJob.schedule(this);
        // No historical notification replay on connect: old posts are not new transactions.
    }
    @Override public void onListenerDisconnected() {
        QingJiApp.app().config.flag("notification_connected",false);QingJiApp.app().refresh();
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        QingJiApp app=QingJiApp.app();
        if(!app.config.enabled()||!app.config.flag("notifications")||!app.config.allowed(sbn.getPackageName()))return;
        Notification n=sbn.getNotification();
        if((n.flags&Notification.FLAG_GROUP_SUMMARY)!=0)return;
        Bundle b=n.extras;LinkedHashSet<String> parts=new LinkedHashSet<>();
        for(String key:new String[]{Notification.EXTRA_TITLE,Notification.EXTRA_TEXT,Notification.EXTRA_BIG_TEXT,Notification.EXTRA_SUB_TEXT}) {
            CharSequence t=b.getCharSequence(key);if(t!=null&&!t.toString().trim().isEmpty())parts.add(t.toString().trim());
        }
        // Notification.EXTRA_TEXT_LINES is an inbox/summary and can contain older unrelated entries.
        String text=String.join("\n",parts);
        app.ingest(new Evidence(Source.NOTIFICATION,sbn.getPackageName(),sbn.getKey()+"|"+sbn.getPostTime(),text,sbn.getPostTime()));
    }
}
