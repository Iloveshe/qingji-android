package com.qingji.app.capture;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import com.qingji.app.*;
import static com.qingji.app.core.LedgerCore.*;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScreenCaptureService extends Service {
    public static final String STOP="com.qingji.app.STOP_CAPTURE";
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private long lastFrame;
    private final AtomicBoolean processing=new AtomicBoolean(false);
    private final MediaProjection.Callback callback=new MediaProjection.Callback(){
        @Override public void onStop(){stopSelf();}
        @Override public void onCapturedContentResize(int width,int height){if(display!=null&&handler!=null)handler.post(()->resize(width,height));}
    };
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        Intent data=intent.getParcelableExtra("data");int result=intent.getIntExtra("result",0);
        if(data==null||result!=Activity.RESULT_OK){stopSelf();return START_NOT_STICKY;}
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ScreenCaptureService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,QingJiApp.CHANNEL).setSmallIcon(com.qingji.app.R.drawable.ic_app)
            .setContentTitle("轻记正在识别支付页面").setContentText("仅处理选定的支付 App；锁屏后停止").setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,"停止识别",stop).build()).setOngoing(true).build();
        try {
            if(Build.VERSION.SDK_INT>=29)startForeground(41,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(41,n);
            thread=new HandlerThread("payment-screen");thread.start();handler=new Handler(thread.getLooper());
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(result,data);projection.registerCallback(callback,handler);
            DisplayMetrics metrics=getResources().getDisplayMetrics();
            int width=metrics.widthPixels,height=metrics.heightPixels;
            if(Build.VERSION.SDK_INT>=30){Rect bounds=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();width=bounds.width();height=bounds.height();}
            reader=makeReader(width,height);
            display=projection.createVirtualDisplay("QingJiPayment",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,handler);
            QingJiApp.app().config.flag("capture_running",true);QingJiApp.app().refresh();
        }catch(Exception ex){QingJiApp.app().config.putText("last_error","屏幕授权未启动："+ex.getClass().getSimpleName());stopSelf();}
        return START_NOT_STICKY;
    }
    private ImageReader makeReader(int width,int height) {
        ImageReader created=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);created.setOnImageAvailableListener(this::frame,handler);return created;
    }
    private void resize(int width,int height) {
        if(width<=0||height<=0||display==null||reader==null)return;
        if(reader.getWidth()==width&&reader.getHeight()==height)return;
        ImageReader old=reader;reader=makeReader(width,height);display.resize(width,height,getResources().getDisplayMetrics().densityDpi);display.setSurface(reader.getSurface());old.close();
    }
    private void frame(ImageReader source) {
        Image image=null;Bitmap padded=null,bitmap=null;
        try {
            image=source.acquireLatestImage();if(image==null)return;
            QingJiApp app=QingJiApp.app();
            if(getSystemService(KeyguardManager.class).isDeviceLocked()||!app.config.enabled()){stopSelf();return;}
            String pkg=PaymentAccessibilityService.foregroundPaymentPackage(app.config);
            if(pkg.isEmpty()||SystemClock.elapsedRealtime()-lastFrame<4000||!processing.compareAndSet(false,true))return;
            lastFrame=SystemClock.elapsedRealtime();
            Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();int stride=plane.getPixelStride();
            int padding=plane.getRowStride()-stride*image.getWidth();
            padded=Bitmap.createBitmap(image.getWidth()+padding/stride,image.getHeight(),Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(buffer);
            bitmap=Bitmap.createBitmap(padded,0,0,image.getWidth(),image.getHeight());if(bitmap!=padded)padded.recycle();padded=null;
            Ocr.bitmap(bitmap,Source.SCREEN_CAPTURE,pkg,"",(success,message)->processing.set(false));bitmap=null;
        }catch(Exception|OutOfMemoryError ex){processing.set(false);}
        finally{if(image!=null)image.close();if(padded!=null&&!padded.isRecycled())padded.recycle();if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
    }
    @Override public void onDestroy() {
        if(display!=null){display.release();display=null;}
        if(reader!=null){reader.close();reader=null;}
        if(projection!=null){projection.unregisterCallback(callback);projection.stop();projection=null;}
        if(thread!=null)thread.quitSafely();
        QingJiApp.app().config.flag("capture_running",false);QingJiApp.app().refresh();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
