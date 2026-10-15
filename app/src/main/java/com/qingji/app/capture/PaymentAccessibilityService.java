package com.qingji.app.capture;

import android.accessibilityservice.AccessibilityService;
import android.os.*;
import android.view.accessibility.*;
import com.qingji.app.*;
import static com.qingji.app.core.LedgerCore.*;
import java.util.*;

public final class PaymentAccessibilityService extends AccessibilityService {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private static volatile String foreground="";
    private static volatile long foregroundAt=0;
    private String lastHash="";
    private long lastAt;
    private final Runnable read=this::readPaymentWindow;
    public static String foregroundPaymentPackage(Config config) {
        return SystemClock.elapsedRealtime()-foregroundAt<=30_000 && config.allowed(foreground)?foreground:"";
    }
    @Override protected void onServiceConnected(){QingJiApp.app().config.flag("accessibility_connected",true);QingJiApp.app().refresh();}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg=event.getPackageName();if(pkg==null)return;
        foreground=pkg.toString();foregroundAt=SystemClock.elapsedRealtime();
        handler.removeCallbacks(read);
        QingJiApp app=QingJiApp.app();
        if(app.config.enabled()&&app.config.flag("accessibility")&&app.config.allowed(foreground))handler.postDelayed(read,1200);
    }
    private void readPaymentWindow() {
        QingJiApp app=QingJiApp.app();if(!app.config.enabled()||!app.config.flag("accessibility"))return;
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        try {
            String pkg=root.getPackageName()==null?"":root.getPackageName().toString();if(!app.config.allowed(pkg))return;
            LinkedHashSet<String> parts=new LinkedHashSet<>();int[] visited={0};collect(root,parts,visited,0);
            String text=String.join("\n",parts);String h=hash(text);long now=System.currentTimeMillis();
            if(h.equals(lastHash)&&now-lastAt<60_000)return;lastHash=h;lastAt=now;
            // A visible result without a date will be retained only as a review item.
            app.ingest(new Evidence(Source.ACCESSIBILITY,pkg,h,text,now));
        } finally {root.recycle();}
    }
    private void collect(AccessibilityNodeInfo node,Set<String> parts,int[] visited,int depth) {
        if(node==null||depth>30||visited[0]++>250||node.isPassword()||node.isEditable()||!node.isVisibleToUser())return;
        if(node.getText()!=null)parts.add(node.getText().toString());
        if(node.getContentDescription()!=null)parts.add(node.getContentDescription().toString());
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{collect(child,parts,visited,depth+1);}finally{child.recycle();}
        }
    }
    @Override public void onInterrupt(){handler.removeCallbacks(read);}
    @Override public void onDestroy(){handler.removeCallbacks(read);foreground="";foregroundAt=0;QingJiApp.app().config.flag("accessibility_connected",false);super.onDestroy();}
}
