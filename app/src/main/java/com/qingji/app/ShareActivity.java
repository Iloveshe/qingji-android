package com.qingji.app;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import com.qingji.app.capture.Ocr;
import static com.qingji.app.core.LedgerCore.*;

public final class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);TextView text=new TextView(this);text.setText("正在本地识别交易…");text.setPadding(32,64,32,32);setContentView(text);
        if(!QingJiApp.app().config.enabled()){finishWith("自动采集已暂停，请先在轻记中开启");return;}
        Intent intent=getIntent();
        if(!Intent.ACTION_SEND.equals(intent.getAction())){finish();return;}
        if(intent.getType()!=null&&intent.getType().startsWith("image/")) {
            Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(uri==null&&intent.getClipData()!=null&&intent.getClipData().getItemCount()>0)uri=intent.getClipData().getItemAt(0).getUri();
            Ocr.uri(this,uri,Source.SCREENSHOT,"shared-image",uri==null?"invalid":uri.toString(),(success,message)->runOnUiThread(()->finishWith(message)));
        }else {
            String value=intent.getStringExtra(Intent.EXTRA_TEXT);
            QingJiApp.app().ingest(new Evidence(Source.TEXT_IMPORT,"shared-text",hash(value==null?"":value),value,System.currentTimeMillis()),()->finishWith("文本已识别，请查看账单和待确认"));
        }
    }
    private void finishWith(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}
}
