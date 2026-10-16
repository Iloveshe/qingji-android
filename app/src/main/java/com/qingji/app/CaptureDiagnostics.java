package com.qingji.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.qingji.app.core.DiagnosticPolicy;
import static com.qingji.app.core.LedgerCore.*;
import static com.qingji.app.core.LedgerCore.ZONE;

/** Bounded, local stage history. Callers only pass fixed result labels, no evidence bodies. */
public final class CaptureDiagnostics {
    private final Config config;
    public CaptureDiagnostics(Config config){this.config=config;}
    private JSONArray read(){try{return new JSONArray(config.text("capture_history","[]"));}catch(Exception ex){return new JSONArray();}}
    public synchronized void record(String source,String origin,String stage,String detail){
        try{
            String safeOrigin=origin!=null&&origin.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")?origin:
                    origin!=null&&origin.startsWith("sms:")?"银行短信":"本地来源";
            long now=System.currentTimeMillis();JSONArray old=read();
            if(old.length()>0){JSONObject last=old.getJSONObject(old.length()-1);
                if(source.equals(last.optString("source"))&&safeOrigin.equals(last.optString("origin"))&&stage.equals(last.optString("stage"))&&
                        detail.equals(last.optString("detail"))&&now-last.optLong("at")<5000)return;}
            JSONArray next=new JSONArray();for(int i=Math.max(0,old.length()-31);i<old.length();i++)next.put(old.get(i));
            next.put(new JSONObject().put("at",now).put("source",source).put("origin",safeOrigin).put("stage",stage).put("detail",detail));
            config.putText("capture_history",next.toString());
        }catch(Exception ignored){}
    }
    public synchronized String report(){
        StringBuilder out=new StringBuilder("最近 32 条本地采集状态；不含金额、商户、卡号、图片或原文。\n\n");
        JSONArray rows=read();DateTimeFormatter time=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss",Locale.CHINA).withZone(ZONE);
        for(int i=rows.length()-1;i>=0;i--){try{JSONObject row=rows.getJSONObject(i);
            out.append(time.format(Instant.ofEpochMilli(row.getLong("at")))).append(" · ").append(row.getString("source"))
                .append(" · ").append(row.getString("origin")).append('\n').append(row.getString("stage"))
                .append("：").append(row.getString("detail")).append("\n\n");}catch(Exception ignored){}}
        if(rows.length()==0)out.append("暂无采集记录。请进入付款详情页或等待新的交易通知。\n");
        return out.toString();
    }
    public synchronized void analyzed(Evidence evidence,ParseResult parsed){
        record(LedgerStore.sourceLabel(evidence.source),evidence.origin,"识别检查",parsed.code.label);
        try{JSONArray rows=read();if(rows.length()==0)return;JSONObject row=rows.getJSONObject(rows.length()-1);
            row.put("code",parsed.code.name());Candidate c=parsed.candidate;
            row.put("amount_rule",DiagnosticPolicy.rule(c==null?"":c.amountRule)).put("merchant_present",c!=null&&!c.merchant.isEmpty())
                .put("exact_time",c!=null&&c.exactTime).put("review",c!=null&&c.status()==Status.REVIEW);
            config.putText("capture_history",rows.toString());
        }catch(Exception ignored){}
    }
    /** The exact same allowlisted payload is used by export, preview and HTTPS upload. */
    public synchronized JSONObject snapshot(String version,int sdk) throws Exception {
        JSONObject payload=new JSONObject().put("schema",1).put("app_version",version).put("android_api",sdk);
        JSONObject flags=new JSONObject();for(String key:new String[]{"enabled","notifications","notification_connected","accessibility","accessibility_connected","accessibility_ocr","accessibility_screenshot_capable","sms","screenshots","evidence_images"})
            flags.put(key,key.equals("enabled")?config.enabled():config.flag(key));payload.put("capture_flags",flags);
        JSONArray events=new JSONArray(),rows=read();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);
            events.put(new JSONObject().put("at",row.optLong("at")).put("source",DiagnosticPolicy.source(row.optString("source")))
                .put("origin",DiagnosticPolicy.origin(row.optString("origin"))).put("stage",DiagnosticPolicy.stage(row.optString("stage")))
                .put("code",DiagnosticPolicy.code(row.has("code")?row.optString("code"):row.optString("detail")))
                .put("amount_rule",DiagnosticPolicy.rule(row.optString("amount_rule"))).put("merchant_present",row.optBoolean("merchant_present"))
                .put("exact_time",row.optBoolean("exact_time")).put("review",row.optBoolean("review")));
        }return payload.put("events",events);
    }
    public synchronized void clear(){config.putText("capture_history","[]");}
}
