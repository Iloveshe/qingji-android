package com.qingji.app.capture;

import android.content.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import com.qingji.app.*;
import static com.qingji.app.core.LedgerCore.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction()))return;
        QingJiApp app=QingJiApp.app();if(!app.config.enabled()||!app.config.flag("sms"))return;
        Map<String,StringBuilder> bodies=new LinkedHashMap<>();Map<String,Long> dates=new HashMap<>();
        for(SmsMessage msg:Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
            String sender=msg.getOriginatingAddress();if(!app.config.senderAllowed(sender))continue;
            bodies.computeIfAbsent(sender,k->new StringBuilder()).append(msg.getMessageBody());dates.putIfAbsent(sender,msg.getTimestampMillis());
        }
        if(bodies.isEmpty())return;
        PendingResult result=goAsync();AtomicInteger remaining=new AtomicInteger(bodies.size());
        bodies.forEach((sender,text)->app.ingest(new Evidence(Source.SMS,"sms:"+sender,dates.get(sender)+"|"+hash(text.toString()),text.toString(),dates.get(sender)),()->{if(remaining.decrementAndGet()==0)result.finish();}));
    }
}
