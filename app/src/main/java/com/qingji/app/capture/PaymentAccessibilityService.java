package com.qingji.app.capture;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.accessibility.*;
import com.qingji.app.*;
import com.qingji.app.core.PageReadPolicy;
import static com.qingji.app.core.LedgerCore.*;
import java.util.*;

public final class PaymentAccessibilityService extends AccessibilityService {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final PageReadPolicy.Budget budget=new PageReadPolicy.Budget();
    private static volatile String foreground="";
    private static volatile long foregroundAt;
    private String lastHash="";
    private int window=-1;
    private long generation;
    private boolean scheduled,ocrBusy,destroyed;
    private FloatingStatus floating;
    private final Runnable read=()->{scheduled=false;readPaymentWindow();};
    public static String foregroundPaymentPackage(Config config) {
        return SystemClock.elapsedRealtime()-foregroundAt<=30_000&&config.allowed(foreground)?foreground:"";
    }
    @Override protected void onServiceConnected(){
        destroyed=false;QingJiApp app=QingJiApp.app();app.config.flag("accessibility_connected",true);
        app.config.pageStatus("页面服务已连接，等待支付 App");
        if(floating==null){floating=new FloatingStatus(this);floating.start();}
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence name=event.getPackageName();if(name==null)return;
        String pkg=name.toString();
        if(pkg.equals(getPackageName())&&floating!=null&&floating.showing()){
            AccessibilityNodeInfo active=getRootInActiveWindow();
            if(active!=null){try{if(active.getPackageName()!=null&&!pkg.contentEquals(active.getPackageName()))return;}finally{active.recycle();}}
        }
        if(!pkg.equals(foreground)||event.getWindowId()!=window){
            generation++;foreground=pkg;window=event.getWindowId();cancelRead();
        }
        foregroundAt=SystemClock.elapsedRealtime();
        Config config=QingJiApp.app().config;
        if(config.enabled()&&config.flag("accessibility")&&config.allowed(pkg))schedule(650);
        else cancelRead();
    }
    private void schedule(long delay){if(!scheduled&&!destroyed){scheduled=true;handler.postDelayed(read,delay);}}
    private void cancelRead(){handler.removeCallbacks(read);scheduled=false;}
    private boolean unlocked(){return !getSystemService(KeyguardManager.class).isDeviceLocked();}
    private static final class Snapshot {
        final LinkedHashSet<String> parts=new LinkedHashSet<>();
        int visited;boolean sensitive,truncated;
        String text(){return String.join("\n",parts);}
    }
    private Snapshot snapshot(AccessibilityNodeInfo root){Snapshot s=new Snapshot();collect(root,s,0);return s;}
    private void collect(AccessibilityNodeInfo node,Snapshot s,int depth){
        if(node==null||!node.isVisibleToUser())return;
        if(depth>30||s.visited++>350){s.truncated=true;return;}
        if(node.isPassword()||node.isEditable()){s.sensitive=true;return;}
        if(node.getText()!=null)s.parts.add(node.getText().toString());
        if(node.getContentDescription()!=null)s.parts.add(node.getContentDescription().toString());
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{collect(child,s,depth+1);}finally{child.recycle();}
        }
    }
    private void readPaymentWindow(){
        QingJiApp app=QingJiApp.app();Config config=app.config;
        if(!config.enabled()||!config.flag("accessibility")||!unlocked())return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){config.pageStatus("未取得可读窗口，可重新进入付款详情页");return;}
        try{
            String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
            if(!config.allowed(pkg)||!pkg.equals(foreground))return;
            if(window!=root.getWindowId()){window=root.getWindowId();generation++;}
            Snapshot s=snapshot(root);
            if(s.sensitive){config.pageStatus(config.appName(pkg)+"：已跳过密码或输入页面");return;}
            String text=s.text(),key=hash(pkg+"|"+text);
            Evidence e=new Evidence(Source.ACCESSIBILITY,pkg,key,text,System.currentTimeMillis());
            Candidate candidate=parse(e,config.ownTails());
            if(candidate!=null){
                if(!key.equals(lastHash)){
                    lastHash=key;app.ingest(e,()->config.pageStatus(config.appName(pkg)+"：页面已识别，请查看账单或待确认"));
                }
                return;
            }
            config.pageStatus(config.appName(pkg)+"：页面文字未识别到完整交易");
            if(Build.VERSION.SDK_INT>=34&&!s.truncated&&PageReadPolicy.mayCapture(Build.VERSION.SDK_INT,config.enabled(),config.flag("accessibility_ocr"),
                    config.allowed(pkg),unlocked(),s.sensitive,false,pkg,text)&&!ocrBusy&&
                    budget.acquire(SystemClock.elapsedRealtime(),generation+"|"+window+"|"+key)){
                captureWindow(pkg,window,generation,text);
            }
        }finally{root.recycle();}
    }
    private boolean stillAllowed(String pkg,int expectedWindow,long expectedGeneration){
        Config config=QingJiApp.app().config;
        if(destroyed||generation!=expectedGeneration||!config.enabled()||!config.flag("accessibility")||
                !config.flag("accessibility_ocr")||!config.allowed(pkg)||!unlocked())return false;
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return false;
        try{
            Snapshot current=snapshot(root);
            return root.getWindowId()==expectedWindow&&pkg.contentEquals(root.getPackageName()==null?"":root.getPackageName())&&
                    !current.sensitive&&!current.truncated;
        }finally{root.recycle();}
    }
    @androidx.annotation.RequiresApi(34)
    private void captureWindow(String pkg,int expectedWindow,long expectedGeneration,String nodeText){
        ocrBusy=true;
        try{
            takeScreenshotOfWindow(expectedWindow,getMainExecutor(),new TakeScreenshotCallback(){
                @Override public void onSuccess(ScreenshotResult result){
                    Bitmap software=null,hardware=null;
                    try(HardwareBuffer buffer=result.getHardwareBuffer()){
                        if(!stillAllowed(pkg,expectedWindow,expectedGeneration)){ocrBusy=false;return;}
                        hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
                        if(hardware==null)throw new IllegalStateException("No screenshot bitmap");
                        software=hardware.copy(Bitmap.Config.ARGB_8888,false);
                        if(software==null)throw new IllegalStateException("No OCR bitmap");
                        int largest=Math.max(software.getWidth(),software.getHeight());
                        if(largest>1920){Bitmap scaled=Bitmap.createScaledBitmap(software,
                            Math.max(1,software.getWidth()*1920/largest),Math.max(1,software.getHeight()*1920/largest),true);
                            if(scaled!=software)software.recycle();software=scaled;}
                        Bitmap owned=software;software=null;
                        Ocr.recognize(owned,(recognized,error)->{
                            ocrBusy=false;
                            if(!stillAllowed(pkg,expectedWindow,expectedGeneration))return;
                            Config config=QingJiApp.app().config;
                            if(error!=null){config.pageStatus(config.appName(pkg)+"：本地 OCR 未完成，可重新进入页面重试");return;}
                            String content=nodeText+"\n"+recognized;
                            Evidence evidence=new Evidence(Source.ACCESSIBILITY_OCR,pkg,hash(content),content,System.currentTimeMillis());
                            Candidate found=parse(evidence,config.ownTails());
                            if(found==null){config.pageStatus(config.appName(pkg)+"：画面已识别，未找到完整交易");schedule(4000);return;}
                            QingJiApp.app().ingest(evidence,()->config.pageStatus(config.appName(pkg)+"：画面已识别，请查看账单或待确认"));
                        });
                    }catch(Exception|OutOfMemoryError ex){ocrBusy=false;QingJiApp.app().config.pageStatus("支付窗口画面读取失败，可重新进入页面重试");}
                    finally{if(hardware!=null)hardware.recycle();if(software!=null&&!software.isRecycled())software.recycle();}
                }
                @Override public void onFailure(int errorCode){
                    ocrBusy=false;
                    if(!stillAllowed(pkg,expectedWindow,expectedGeneration))return;
                    String reason=errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW?"该页面受系统安全保护，无法截图识别":
                        errorCode==ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS?"请在系统无障碍中关闭再开启轻记，以加载新增截图能力":"窗口截图暂不可用，可重新进入付款详情页重试";
                    QingJiApp.app().config.pageStatus(QingJiApp.app().config.appName(pkg)+"："+reason);
                }
            });
        }catch(Exception ex){ocrBusy=false;QingJiApp.app().config.pageStatus("自动 OCR 未启动，请重新开启系统无障碍服务");}
    }
    @Override public void onInterrupt(){cancelRead();generation++;}
    @Override public void onDestroy(){
        destroyed=true;generation++;cancelRead();foreground="";foregroundAt=0;
        if(floating!=null){floating.close();floating=null;}
        QingJiApp.app().config.flag("accessibility_connected",false);QingJiApp.app().refresh();super.onDestroy();
    }
}
