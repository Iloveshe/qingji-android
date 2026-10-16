package com.qingji.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.ImageView;

/** In-app evidence viewer; no exported URI or plaintext image cache. */
// Constructed only in code with a required bitmap; never inflated by layout tools.
@android.annotation.SuppressLint({"AppCompatCustomView","ViewConstructor"})
public final class EvidenceImageView extends ImageView {
    private final Matrix transform=new Matrix();
    private final ScaleGestureDetector zoom;
    private final Bitmap bitmap;
    private float x,y,scale=1;
    public EvidenceImageView(Context context,Bitmap bitmap){
        super(context);this.bitmap=bitmap;setImageBitmap(bitmap);setScaleType(ScaleType.MATRIX);
        zoom=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){float factor=detector.getScaleFactor();float next=scale*factor;
                if(next<0.5f||next>10f)return true;scale=next;transform.postScale(factor,factor,detector.getFocusX(),detector.getFocusY());setImageMatrix(transform);return true;}
        });setContentDescription("采集截图，可双指缩放并拖动；截图仅保存在本机");
    }
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);
        if(ow==0&&w>0&&h>0){float fit=Math.min((float)w/bitmap.getWidth(),(float)h/bitmap.getHeight());transform.setScale(fit,fit);transform.postTranslate((w-bitmap.getWidth()*fit)/2,(h-bitmap.getHeight()*fit)/2);setImageMatrix(transform);}}
    @Override public boolean onTouchEvent(MotionEvent event){
        zoom.onTouchEvent(event);float nextX=event.getX(),nextY=event.getY();
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!zoom.isInProgress()&&event.getPointerCount()==1){transform.postTranslate(nextX-x,nextY-y);setImageMatrix(transform);}
        if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();x=nextX;y=nextY;return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
