package com.qingji.app.capture;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.qingji.app.*;
import com.qingji.app.core.LedgerCore;
import static com.qingji.app.core.LedgerCore.*;
import java.io.*;
import java.util.concurrent.*;

public final class Ocr {
    private static final ExecutorService decoder=Executors.newSingleThreadExecutor();
    public interface Callback {void done(boolean success,String message);}
    public interface TextCallback {void done(String text,String error);}
    public static void uri(Context context,Uri uri,Source source,String origin,String key,Callback callback) {
        decoder.execute(()-> {
            Bitmap bitmap=null;
            try {
                if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("Content URI required");
                BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;
                try(InputStream in=context.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,opt);}
                if(opt.outWidth<=0||opt.outHeight<=0)throw new IOException("Invalid image");
                int sample=1;while(Math.max(opt.outWidth,opt.outHeight)/sample>3200 || (long)(opt.outWidth/sample)*(opt.outHeight/sample)>8_000_000L)sample*=2;
                opt.inJustDecodeBounds=false;opt.inSampleSize=sample;
                try(InputStream in=context.getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(in,null,opt);}
                if(bitmap==null)throw new IOException("Unable to decode");
                bitmap(bitmap,source,origin,key,callback);
            } catch(Exception|OutOfMemoryError ex) {
                if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();callback.done(false,"图片识别失败（"+ex.getClass().getSimpleName()+"）");
            }
        });
    }
    public static void bitmap(Bitmap bitmap,Source source,String origin,String key,Callback callback) {
        recognize(bitmap,(text,error)->{
            if(error!=null){bitmap.recycle();callback.done(false,error);return;}
            long now=System.currentTimeMillis();
            String eventKey=key.isEmpty()?LedgerCore.hash(text):key;
            Evidence e=new Evidence(source,origin,eventKey,text,now);
            ParseResult result=LedgerCore.analyze(e,QingJiApp.app().config.ownTails());
            QingJiApp.app().diagnostics.analyzed(e,result);
            if(result.candidate==null){bitmap.recycle();QingJiApp.app().diagnostics.record(LedgerStore.sourceLabel(source),origin,"未匹配",result.code.label);callback.done(true,result.code.label);return;}
            QingJiApp.app().ingestImage(e,bitmap,()->callback.done(true,"识别完成，请在待确认中对照截图核对"));
        });
    }
    /** Caller retains bitmap ownership; callback must release or hand it to ingestImage. */
    public static void recognize(Bitmap bitmap,TextCallback callback) {
        TextRecognizer recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try {
            recognizer.process(InputImage.fromBitmap(bitmap,0))
                .addOnSuccessListener(result->callback.done(result.getText(),null))
                .addOnFailureListener(ex->callback.done(null,"OCR 未完成（"+ex.getClass().getSimpleName()+"）"))
                .addOnCompleteListener(task->recognizer.close());
        }catch(Exception ex){recognizer.close();callback.done(null,"OCR 未能启动");}
    }
}
