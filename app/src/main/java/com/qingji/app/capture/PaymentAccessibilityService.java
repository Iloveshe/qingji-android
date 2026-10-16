package com.qingji.app.capture;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.accessibility.*;
import android.widget.Toast;
import com.qingji.app.*;
import com.qingji.app.core.PageReadPolicy;
import com.qingji.app.core.PaymentPageSignature;
import com.qingji.app.core.ReceiptPolicy;
import static com.qingji.app.core.LedgerCore.*;
import java.util.*;

public final class PaymentAccessibilityService extends AccessibilityService {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final PageReadPolicy.Budget budget=new PageReadPolicy.Budget();
    private static volatile String foreground="";
    private static volatile long foregroundAt;
    private static volatile boolean foregroundSafe;
    private String lastHash="",activityName="";
    private int window=-1;
    private long generation;
    private boolean scheduled,ocrBusy,destroyed;
    private FloatingStatus floating;
    private final Runnable read=()->{scheduled=false;readPaymentWindow(false);};
    public static String foregroundPaymentPackage(Config config) {
        return foregroundSafe&&SystemClock.elapsedRealtime()-foregroundAt<=5000&&config.allowed(foreground)?foreground:"";
    }
    @Override protected void onServiceConnected(){
        destroyed=false;QingJiApp app=QingJiApp.app();app.config.flag("accessibility_connected",true);
        app.config.pageStatus("页面服务已连接，等待支付 App");
        app.config.flag("accessibility_screenshot_capable",Build.VERSION.SDK_INT>=30&&getServiceInfo()!=null&&
                (getServiceInfo().getCapabilities()&android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT)!=0);
        if(floating==null){floating=new FloatingStatus(this,()->{cancelRead();readPaymentWindow(true);},this::rememberCurrentPage);floating.start();}
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence name=event.getPackageName();if(name==null)return;
        foregroundSafe=false;
        String pkg=name.toString();
        if(pkg.equals(getPackageName())&&floating!=null&&floating.showing()){
            AccessibilityNodeInfo active=getRootInActiveWindow();
            if(active!=null){try{if(active.getPackageName()!=null&&!pkg.contentEquals(active.getPackageName()))return;}finally{active.recycle();}}
        }
        if(!pkg.equals(foreground)||event.getWindowId()!=window){
            if(!pkg.equals(foreground))activityName="";
            generation++;foregroundSafe=false;foreground=pkg;window=event.getWindowId();cancelRead();
        }
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getClassName()!=null){
            String component=event.getClassName().toString();if(component.startsWith(pkg+"."))activityName=component;
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
        final List<String> structure=new ArrayList<>();
        int visited;boolean sensitive,truncated;
        String text(){return String.join("\n",parts);}
    }
    private Snapshot snapshot(AccessibilityNodeInfo root){Snapshot s=new Snapshot();collect(root,s,0);return s;}
    private void collect(AccessibilityNodeInfo node,Snapshot s,int depth){
        if(node==null||!node.isVisibleToUser())return;
        if(depth>30||s.visited++>350){s.truncated=true;return;}
        if(node.isPassword()||node.isEditable()){s.sensitive=true;return;}
        s.structure.add(depth+"|"+node.getClassName()+"|"+Objects.toString(node.getViewIdResourceName(),"")+"|"+node.getChildCount());
        if(node.getText()!=null)s.parts.add(node.getText().toString());
        if(node.getContentDescription()!=null)s.parts.add(node.getContentDescription().toString());
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{collect(child,s,depth+1);}finally{child.recycle();}
        }
    }
    private void readPaymentWindow(boolean manual){
        QingJiApp app=QingJiApp.app();Config config=app.config;
        if(!config.enabled()||!config.flag("accessibility")||!unlocked()){
            if(manual)feedback("请先开启自动采集和页面识别，并解锁手机");return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){if(manual)feedback("未取得可读窗口，可重新进入付款详情页");else config.pageStatus("未取得可读窗口，可重新进入付款详情页");return;}
        try{
            String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
            if(!config.allowed(pkg)){if(manual)feedback("当前 App 未勾选，请回轻记设置选择来源");return;}
            if(!pkg.equals(foreground)){
                if(!manual)return;foreground=pkg;activityName="";generation++;
            }
            foregroundAt=SystemClock.elapsedRealtime();
            if(window!=root.getWindowId()){window=root.getWindowId();generation++;}
            Snapshot s=snapshot(root);
            if(s.sensitive){if(manual)feedback("已跳过密码或输入页面，请先进入付款详情");else config.pageStatus(config.appName(pkg)+"：已跳过密码或输入页面");return;}
            String text=s.text(),key=hash(pkg+"|"+text);
            foregroundSafe=!s.sensitive&&!s.truncated&&!ReceiptPolicy.messagingScreen(pkg,activityName);
            if(!manual&&ReceiptPolicy.messagingScreen(pkg,activityName)){
                config.pageStatus("微信：聊天、首页或未知页面已跳过；请打开单笔付款详情");return;
            }
            Evidence e=new Evidence(Source.ACCESSIBILITY,pkg,key,text,System.currentTimeMillis());
            ParseResult diagnosis=analyze(e,config.ownTails());Candidate candidate=diagnosis.candidate;
            if(candidate!=null&&!s.truncated){
                if(manual||!key.equals(lastHash)){
                    lastHash=key;app.ingest(e,()->config.pageStatus(config.appName(pkg)+"：页面已识别，请查看账单或待确认"));
                }
                if(manual)Toast.makeText(this,"已读取页面，请查看账单或待确认；诊断可查看处理结果",Toast.LENGTH_LONG).show();
                return;
            }
            config.pageStatus(config.appName(pkg)+"："+diagnosis.code.label);
            if(diagnosis.code!=ParseCode.NO_SIGNAL&&diagnosis.code!=ParseCode.NO_AMOUNT&&diagnosis.code!=ParseCode.NOT_RECEIPT&&diagnosis.code!=ParseCode.UNBOUND_AMOUNT){if(manual)Toast.makeText(this,diagnosis.code.label,Toast.LENGTH_LONG).show();return;}
            String signature=PaymentPageSignature.create(pkg,activityName,s.structure,text);
            boolean remembered=config.remembered(pkg,signature);
            if(Build.VERSION.SDK_INT>=34&&!s.truncated&&PageReadPolicy.mayCapture(Build.VERSION.SDK_INT,config.enabled(),config.flag("accessibility_ocr"),
                    config.allowed(pkg),unlocked(),s.sensitive,false,pkg,text,remembered,manual)&&!ocrBusy&&
                    budget.acquire(SystemClock.elapsedRealtime(),generation+"|"+window+"|"+key,manual)){
                if(manual)Toast.makeText(this,"正在本地识别当前支付窗口",Toast.LENGTH_SHORT).show();
                captureWindow(pkg,window,generation,text,manual);
            }else{
                String why=ocrBusy?"正在识别，请稍候":!config.flag("accessibility_ocr")?"文字未匹配，请先在设置开启支付窗口自动 OCR":s.truncated?"页面结构过于复杂，已跳过画面识别":Build.VERSION.SDK_INT<34?"当前系统不支持窗口截图":"未匹配付款窗口或触发识别频率限制，可手动识别付款详情";
                if(manual)feedback(why);else config.pageStatus(config.appName(pkg)+"："+why);
            }
        }finally{root.recycle();}
    }
    private void feedback(String message){QingJiApp.app().config.pageStatus(message);Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void rememberCurrentPage(){
        Config config=QingJiApp.app().config;
        if(!config.enabled()||!config.flag("accessibility")||!config.flag("accessibility_ocr")||!unlocked()){
            feedback("请先开启自动采集、页面识别及自动 OCR，并解锁手机");return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null){feedback("当前没有可记忆的付款窗口");return;}
        try{
            String pkg=root.getPackageName()==null?"":root.getPackageName().toString();
            if(!config.allowed(pkg)||!pkg.equals(foreground)){feedback("请先进入已勾选 App 的付款详情页");return;}
            Snapshot s=snapshot(root);if(s.sensitive||s.truncated){feedback("密码、输入或不完整页面不会被记忆");return;}
            String signature=PaymentPageSignature.create(pkg,activityName,s.structure,s.text());
            if(signature.isEmpty()){feedback("当前页面缺少稳定的付款特征，未保存。可点按悬浮窗尝试一次识别");return;}
            if(config.rememberPage(pkg,activityName,signature))feedback("已记住付款页面，今后会自动补识别；可在设置中删除");
            else feedback("页面记忆未保存，请重试");
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
    private void captureWindow(String pkg,int expectedWindow,long expectedGeneration,String nodeText,boolean manual){
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
                        Bitmap owned=software;software=null;
                        Ocr.recognize(owned,(recognized,error)->{
                            ocrBusy=false;
                            if(!stillAllowed(pkg,expectedWindow,expectedGeneration)){owned.recycle();return;}
                            Config config=QingJiApp.app().config;
                            if(error!=null){owned.recycle();config.pageStatus(config.appName(pkg)+"：本地 OCR 未完成，可重新进入页面重试");return;}
                            // Parse one image source at a time. Concatenating node text can invent amounts.
                            String content=recognized;
                            Evidence evidence=new Evidence(Source.ACCESSIBILITY_OCR,pkg,hash(content),content,System.currentTimeMillis());
                            ParseResult decoded=analyze(evidence,config.ownTails());
                            QingJiApp.app().diagnostics.analyzed(evidence,decoded);
                            if(decoded.candidate==null){owned.recycle();String message=config.appName(pkg)+"：画面已识别，"+decoded.code.label;if(manual)feedback(message);else{config.pageStatus(message);schedule(4000);}return;}
                            QingJiApp.app().ingestImage(evidence,owned,()->{config.pageStatus(config.appName(pkg)+"：OCR 候选已处理，请在待确认核对金额及截图");if(manual)Toast.makeText(PaymentAccessibilityService.this,"请在待确认中核对识别金额和采集截图",Toast.LENGTH_LONG).show();});
                        });
                    }catch(Exception|OutOfMemoryError ex){ocrBusy=false;QingJiApp.app().config.pageStatus("支付窗口画面读取失败，可重新进入页面重试");}
                    finally{if(hardware!=null)hardware.recycle();if(software!=null&&!software.isRecycled())software.recycle();}
                }
                @Override public void onFailure(int errorCode){
                    ocrBusy=false;
                    if(!stillAllowed(pkg,expectedWindow,expectedGeneration))return;
                    String reason=errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW?"该页面受系统安全保护，无法截图识别":
                        errorCode==ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS?"请在系统无障碍中关闭再开启轻记，以加载新增截图能力":"窗口截图暂不可用，可重新进入付款详情页重试";
                    if(manual)feedback(reason);else QingJiApp.app().config.pageStatus(QingJiApp.app().config.appName(pkg)+"："+reason);
                }
            });
        }catch(Exception ex){ocrBusy=false;QingJiApp.app().config.pageStatus("自动 OCR 未启动，请重新开启系统无障碍服务");}
    }
    @Override public void onInterrupt(){cancelRead();generation++;}
    @Override public void onDestroy(){
        destroyed=true;generation++;cancelRead();foreground="";foregroundAt=0;
        if(floating!=null){floating.close();floating=null;}
        QingJiApp.app().config.flag("accessibility_connected",false);QingJiApp.app().config.flag("accessibility_screenshot_capable",false);QingJiApp.app().refresh();super.onDestroy();
    }
}
