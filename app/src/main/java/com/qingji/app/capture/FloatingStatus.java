package com.qingji.app.capture;

import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;
import com.qingji.app.*;

/** Optional, draggable status indicator; contains no amounts or transaction text. */
final class FloatingStatus implements AutoCloseable {
    private final Context context;
    private final WindowManager manager;
    private View bubble;
    private TextView labelView;
    private final Runnable recognize,remember;
    private Runnable cancelTouch;
    private WindowManager.LayoutParams params;
    private boolean registered;
    // The app uses framework Activity/widgets, without an AppCompat inflater or tint support.
    @android.annotation.SuppressLint("AppCompatCustomView")
    private static final class StatusBubble extends TextView {
        StatusBubble(Context context){super(context);}
        @Override public boolean performClick(){return super.performClick();}
    }
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){refresh();}
    };
    FloatingStatus(Context context,Runnable recognize,Runnable remember){this.context=context;this.recognize=recognize;this.remember=remember;manager=context.getSystemService(WindowManager.class);}
    void start(){
        IntentFilter filter=new IntentFilter(QingJiApp.REFRESH);filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;refresh();
    }
    boolean showing(){return bubble!=null;}
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private void refresh(){
        Config config=QingJiApp.app().config;
        if(!config.flag("floating_status")||!Settings.canDrawOverlays(context)||
                !config.flag("accessibility")||context.getSystemService(KeyguardManager.class).isDeviceLocked()){remove();return;}
        if(bubble==null){
            StatusBubble view=new StatusBubble(context);view.setTextColor(Color.WHITE);view.setTextSize(12);view.setGravity(Gravity.CENTER);
            view.setPadding(dp(12),dp(10),dp(12),dp(10));view.setMinHeight(dp(48));
            LinearLayout frame=new LinearLayout(context);frame.setOrientation(LinearLayout.HORIZONTAL);frame.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable background=new GradientDrawable();background.setColor(Color.rgb(8,126,118));background.setCornerRadius(dp(24));frame.setBackground(background);
            frame.addView(view,new LinearLayout.LayoutParams(0,dp(52),1));
            TextView ledger=new TextView(context);ledger.setText("账本");ledger.setTextSize(12);ledger.setTextColor(Color.WHITE);ledger.setGravity(Gravity.CENTER);ledger.setContentDescription("打开轻记账本");
            frame.addView(ledger,new LinearLayout.LayoutParams(dp(50),dp(52)));
            params=new WindowManager.LayoutParams(dp(182),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.START;
            params.x=config.prefs.getInt("floating_x",dp(12));params.y=config.prefs.getInt("floating_y",dp(180));clamp();
            ledger.setOnClickListener(v->{try{context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception ignored){}});
            view.setOnClickListener(v->recognize.run());view.setOnLongClickListener(v->{remember.run();return true;});
            view.setOnTouchListener(new View.OnTouchListener(){
                float downX,downY;int startX,startY;boolean moved,longPressed;
                final Runnable longPress=()->{if(!moved){longPressed=true;view.performLongClick();}};
                {cancelTouch=()->view.removeCallbacks(longPress);}
                @Override public boolean onTouch(View v,android.view.MotionEvent e){
                    switch(e.getActionMasked()){
                        case MotionEvent.ACTION_DOWN:downX=e.getRawX();downY=e.getRawY();startX=params.x;startY=params.y;moved=false;longPressed=false;v.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                            if(Math.abs(dx)+Math.abs(dy)>dp(8)){moved=true;v.removeCallbacks(longPress);}
                            if(moved){boolean rtl=context.getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;params.x=startX+(int)(rtl?-dx:dx);params.y=startY+(int)dy;clamp();try{manager.updateViewLayout(frame,params);}catch(Exception ignored){}}return true;
                        case MotionEvent.ACTION_UP:
                            v.removeCallbacks(longPress);if(!moved&&!longPressed)v.performClick();else if(moved)config.prefs.edit().putInt("floating_x",params.x).putInt("floating_y",params.y).apply();return true;
                        case MotionEvent.ACTION_CANCEL:v.removeCallbacks(longPress);return true;
                        default:return false;
                    }
                }
            });
            try{manager.addView(frame,params);bubble=frame;labelView=view;}catch(Exception ex){config.pageStatus("悬浮窗未显示，请检查系统悬浮窗授权");return;}
        }
        String label=config.enabled()?"轻记 · 点按识别":"轻记 · 已暂停";
        if(!label.contentEquals(labelView.getText())){labelView.setText(label);labelView.setContentDescription(label+"；长按记住付款页面，拖动可移动悬浮窗");}
    }
    private void clamp(){
        android.util.DisplayMetrics metrics=context.getResources().getDisplayMetrics();
        params.x=Math.max(0,Math.min(params.x,metrics.widthPixels-dp(182)));
        params.y=Math.max(dp(28),Math.min(params.y,metrics.heightPixels-dp(100)));
    }
    private void remove(){if(cancelTouch!=null){cancelTouch.run();cancelTouch=null;}if(bubble!=null){try{manager.removeView(bubble);}catch(Exception ignored){}bubble=null;labelView=null;}}
    @Override public void close(){if(registered){context.unregisterReceiver(receiver);registered=false;}remove();}
}
