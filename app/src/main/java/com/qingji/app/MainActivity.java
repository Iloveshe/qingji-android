package com.qingji.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.qingji.app.capture.*;
import com.qingji.app.core.LedgerCore;
import com.qingji.app.update.*;
import static com.qingji.app.core.LedgerCore.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Native Android UI; no WebView, server or account needed. */
public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(244,247,245), INK=Color.rgb(26,47,43), MUTED=Color.rgb(107,124,116);
    private static final int TEAL=Color.rgb(8,126,118), LIGHT=Color.rgb(226,241,233), ORANGE=Color.rgb(166,99,29);
    private static final int SMS_PERMISSION=100, IMAGES_PERMISSION=101, IMAGE_PICK=200, PROJECTION=201, EXPORT=202;
    private final Handler main=new Handler(Looper.getMainLooper());
    private QingJiApp app;
    private LinearLayout root,body;
    private ScrollView scroll;
    private int tab,filter;
    private List<Entry> rows=new ArrayList<>();
    private boolean registered;
    private final UpdateManager.Listener updateUi=new UpdateManager.Listener(){
        public void onUpdateState(){if(tab==3&&!isFinishing()&&!isDestroyed())render();}
        public void onUpdateAvailable(UpdatePolicy.Offer offer){showUpdate(offer);}
        public void onCheckFinished(boolean manual,String error,boolean found){
            if(!manual)return;
            if(error!=null)toast(error);else if(found)showUpdate(app.updater.offer());else info("检查更新","当前已是此更新渠道的最新版本。");
        }
        public void onDownloadReady(){new AlertDialog.Builder(MainActivity.this).setTitle("更新已下载").setMessage("安装包已通过哈希与签名校验。覆盖安装会保留本地账本，请在系统页面确认安装。").setNegativeButton("稍后",null).setPositiveButton("安装",(d,w)->installUpdate()).show();}
        public void onInstallVerified(File apk){
            try{Uri uri=FileProvider.getUriForFile(MainActivity.this,getPackageName()+".updates",apk);
                Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                install.setClipData(ClipData.newRawUri("轻记更新",uri));startActivity(install);
            }catch(Exception ex){toast("无法打开系统安装器，请到发布页下载后安装");}
        }
        public void onUpdateError(String message){toast(message);}
    };
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){load();}};
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm",Locale.CHINA);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);app=QingJiApp.app();if(state!=null){tab=state.getInt("tab");filter=state.getInt("filter");}
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});}
        setContentView(root);load();
    }
    @Override protected void onResume(){super.onResume();
        if(!registered){ContextCompat.registerReceiver(this,updates,new IntentFilter(QingJiApp.REFRESH),ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;}
        ScreenshotScanJob.schedule(this);load();app.refresh();app.updater.attach(updateUi);
        if(app.config.flag("update_waiting_permission")){app.config.flag("update_waiting_permission",false);if(getPackageManager().canRequestPackageInstalls())app.updater.verifyForInstall();else toast("尚未允许安装更新，可在设置页重试");}
        app.updater.check(false);
    }
    @Override protected void onPause(){app.updater.detach(updateUi);if(registered){unregisterReceiver(updates);registered=false;}super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("tab",tab);out.putInt("filter",filter);super.onSaveInstanceState(out);}
    private void load(){app.worker.execute(()->{List<Entry> latest=app.store.list();main.post(()->{if(isFinishing()||isDestroyed())return;rows=latest;render();});});}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(dp(3),1);return t;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void gap(LinearLayout parent,int height){Space s=new Space(this);parent.addView(s,new LinearLayout.LayoutParams(1,dp(height)));}
    private void add(LinearLayout parent,View view){parent.addView(view,new LinearLayout.LayoutParams(-1,-2));}
    private void section(String value){gap(body,22);add(body,text(value,17,INK,true));gap(body,10);}
    private LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(shape(Color.WHITE,20));add(parent,c);return c;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(TEAL);b.setMinHeight(dp(48));b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LIGHT));b.setOnClickListener(v->action.run());return b;}
    private void render(){
        int oldScroll=scroll==null?0:scroll.getScrollY();root.removeAllViews();
        LinearLayout header=horizontal();header.setPadding(dp(22),dp(15),dp(22),dp(10));
        LinearLayout brand=column();brand.addView(text("轻记",26,INK,true));brand.addView(text("本地识别 · 自动归账",12,MUTED,false));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView privacy=text(app.config.flag("cloud_ai")?"云端分类已开":"数据留在手机",12,TEAL,true);privacy.setPadding(dp(10),dp(8),dp(10),dp(8));privacy.setBackground(shape(LIGHT,20));privacy.setOnClickListener(v->info("数据与隐私",privacyText()));header.addView(privacy);add(root,header);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        body=column();body.setPadding(dp(20),dp(8),dp(20),dp(24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(tab==0)home();else if(tab==1)ledger();else if(tab==2)review();else settings();
        LinearLayout nav=horizontal();nav.setBackgroundColor(Color.WHITE);nav.setPadding(dp(8),dp(8),dp(8),dp(8));
        String[] labels={"总览","账单","待确认","设置"};
        for(int i=0;i<labels.length;i++){final int target=i;TextView item=text(labels[i],14,tab==i?TEAL:MUTED,tab==i);item.setGravity(Gravity.CENTER);item.setMinHeight(dp(48));if(tab==i)item.setBackground(shape(LIGHT,14));item.setOnClickListener(v->{tab=target;scroll=null;render();});nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));}
        add(root,nav);scroll.post(()->scroll.scrollTo(0,Math.min(oldScroll,body.getHeight())));
    }
    private boolean currentMonth(Entry e){return YearMonth.from(Instant.ofEpochMilli(e.transaction.occurredAt).atZone(ZONE)).equals(YearMonth.now(ZONE));}
    private void home(){
        List<Entry> month=new ArrayList<>();long income=0,transfer=0;int pending=0;
        for(Entry e:rows){if(e.status==Status.REVIEW)pending++;if(!currentMonth(e))continue;month.add(e);if(e.status==Status.CONFIRMED&&e.transaction.currency.equals("CNY")){if(e.transaction.kind==Kind.INCOME)income+=e.transaction.cents;if(e.transaction.kind==Kind.TRANSFER)transfer+=e.transaction.cents;}}
        LinearLayout hero=column();hero.setBackground(shape(TEAL,24));hero.setPadding(dp(22),dp(22),dp(22),dp(22));add(body,hero);
        add(hero,text(YearMonth.now(ZONE).getMonthValue()+" 月净支出",14,Color.rgb(204,237,224),false));gap(hero,7);add(hero,text("¥ "+amount(netExpense(month)),36,Color.WHITE,true));gap(hero,8);add(hero,text("已扣除退款，内部转账不计入收支",12,Color.rgb(207,237,224),false));
        gap(hero,22);LinearLayout stats=horizontal();LinearLayout a=column(),b=column();a.addView(text("收入",12,Color.rgb(207,237,224),false));a.addView(text("¥ "+amount(income),18,Color.WHITE,true));b.addView(text("内部转账",12,Color.rgb(207,237,224),false));b.addView(text("¥ "+amount(transfer),18,Color.WHITE,true));stats.addView(a,new LinearLayout.LayoutParams(0,-2,1));stats.addView(b,new LinearLayout.LayoutParams(0,-2,1));add(hero,stats);
        gap(body,16);LinearLayout status=card(body);add(status,text(app.config.enabled()?"自动采集已开启":"自动采集已暂停",17,INK,true));gap(status,8);
        String permission=notificationAccess()?"通知权限已授予" : "尚未开启通知读取";
        String connected=app.config.flag("notification_connected")?" · 服务已连接":" · 等待系统连接";
        add(status,text(permission+(notificationAccess()?connected:"")+"\n"+(app.config.flag("sms")?"交易短信已开启":"交易短信未开启")+" · "+(app.config.flag("accessibility")&&accessibilityAccess()?"页面识别已开启":"页面识别未开启"),13,MUTED,false));
        long last=app.config.prefs.getLong("last_capture",0);gap(status,8);add(status,text(last==0?"还没有采集到交易。授权后，新交易会自动出现在这里。":"最近采集："+formatTime(last)+" · "+app.config.text("last_source",""),12,MUTED,false));
        gap(status,10);add(status,button("检查采集与后台设置",()->{tab=3;scroll=null;render();}));
        if(pending>0){gap(body,14);LinearLayout p=card(body);add(p,text(pending+" 笔交易等待核对",17,ORANGE,true));gap(p,6);add(p,text("信息不完整或可能重复，暂时不计入统计。",13,MUTED,false));add(p,button("查看待确认",()->{tab=2;scroll=null;render();}));}
        section("最近账单");int count=0;for(Entry e:rows){if(e.status!=Status.CONFIRMED)continue;entryCard(e);if(++count==5)break;}
        if(count==0)empty("让账单自己出现","开启通知或短信采集后，无需手动输入金额。你也可以分享支付截图到轻记。",()->{tab=3;scroll=null;render();},"开启采集");
        section("转账与消费怎么区分");LinearLayout explain=card(body);add(explain,text("转入本人银行卡 500 元 → 内部转账\n买一杯咖啡 38 元 → 支出 38 元",14,INK,true));gap(explain,10);add(explain,text("账户移动和实际消费分别保留，支出统计只增加 38 元。微信、短信、截图可以成为同一条账单的证据。",13,MUTED,false));gap(explain,10);add(explain,button("查看独立演示",this::demo));
    }
    private void ledger(){
        LinearLayout row=horizontal();row.addView(text("账单",24,INK,true),new LinearLayout.LayoutParams(0,-2,1));row.addView(button("导出 CSV",this::export));add(body,row);gap(body,12);
        Spinner spinner=new Spinner(this);String[] filters={"全部类型","支出","收入","内部转账","退款"};spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,filters));spinner.setSelection(filter);add(body,spinner);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(filter!=position){filter=position;scroll=null;render();}}});gap(body,14);
        Kind[] kinds={Kind.UNKNOWN,Kind.EXPENSE,Kind.INCOME,Kind.TRANSFER,Kind.REFUND};int count=0;
        for(Entry e:rows){if(e.status!=Status.CONFIRMED||(filter>0&&e.transaction.kind!=kinds[filter]))continue;entryCard(e);count++;}
        if(count==0)empty("这里还没有账单","已确认的交易会显示在这里。信息不足的交易放在待确认中。",this::pickImage,"识别支付截图");
        gap(body,12);add(body,button("识别一张支付截图",this::pickImage));
    }
    private void review(){
        add(body,text("待确认",24,INK,true));gap(body,8);add(body,text("这些交易暂不计入收支。你可以补充类型，或将重复来源合并到已有账单。",13,MUTED,false));gap(body,16);
        int count=0;for(Entry e:rows){if(e.status==Status.REVIEW){entryCard(e);count++;}}
        if(count==0)empty("没有待确认交易","明确的交易自动入账；无法确定的转账、缺少日期的截图会在这里等待核对。",this::pickImage,"识别截图");
    }
    private void entryCard(Entry e){
        Candidate c=e.transaction;LinearLayout card=card(body);LinearLayout row=horizontal();LinearLayout left=column();
        left.addView(text(c.merchant.isEmpty()?LedgerStore.kindLabel(c.kind):c.merchant,16,INK,true));left.addView(text(c.category+" · "+formatTime(c.occurredAt),12,MUTED,false));row.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        String sign=c.kind==Kind.EXPENSE?"− ":c.kind==Kind.INCOME||c.kind==Kind.REFUND?"+ ":"";
        row.addView(text(sign+(c.currency.equals("CNY")?"¥ ":c.currency+" ")+amount(c.cents),18,c.kind==Kind.TRANSFER?MUTED:INK,true));add(card,row);gap(card,8);
        LinkedHashSet<String> sources=new LinkedHashSet<>();for(EvidenceRef ref:e.evidence)sources.add(LedgerStore.sourceLabel(ref.source));
        add(card,text((e.status==Status.REVIEW?"待确认 · ":"")+String.join(" + ",sources)+" · "+e.evidence.size()+" 个证据",12,e.status==Status.REVIEW?ORANGE:TEAL,false));
        if(e.status==Status.REVIEW){gap(card,6);add(card,text(c.reason,12,MUTED,false));}
        card.setOnClickListener(v->details(e));card.setFocusable(true);card.setContentDescription((c.merchant.isEmpty()?LedgerStore.kindLabel(c.kind):c.merchant)+"，"+LedgerStore.kindLabel(c.kind)+amount(c.cents)+c.currency+"，点击查看详情");gap(body,10);
    }
    private void empty(String title,String subtitle,Runnable action,String label){LinearLayout c=card(body);add(c,text(title,19,INK,true));gap(c,10);add(c,text(subtitle,14,MUTED,false));gap(c,16);add(c,button(label,action));}
    private void settings(){
        add(body,text("采集与设置",24,INK,true));gap(body,12);
        LinearLayout general=card(body);toggle(general,"自动采集","暂停后，通知、短信与页面均不入账",app.config.enabled(),on->{app.config.flag("enabled",on);if(!on)stopService(new Intent(this,ScreenCaptureService.class));ScreenshotScanJob.schedule(this);load();});
        section("自动采集来源");LinearLayout sources=card(body);
        toggle(sources,"支付通知","读取勾选 App 的新通知，合并交易证据",app.config.flag("notifications"),on->{app.config.flag("notifications",on);if(on&&!notificationAccess())openNotificationSettings();});
        add(sources,button(notificationAccess()?"通知读取已授权 · 查看系统设置":"开启通知读取权限",this::openNotificationSettings));
        gap(sources,12);toggle(sources,"新到达的交易短信","仅处理允许号码发来的交易信息，忽略验证码",app.config.flag("sms"),this::toggleSms);
        gap(sources,12);toggle(sources,"支付页面文字","只读取勾选 App 的可见交易文字，无点击操作",app.config.flag("accessibility"),on->{app.config.flag("accessibility",on);if(on&&!accessibilityAccess())confirmAccessibility();});
        add(sources,button(accessibilityAccess()?"无障碍已授权 · 查看系统设置":"开启支付页面识别权限",this::confirmAccessibility));
        gap(sources,12);toggle(sources,"支付窗口自动 OCR（无通知）","Android 14+：文字读不到时自动识别支付窗口，不必每次授权录屏",app.config.flag("accessibility_ocr"),this::toggleAutoOcr);
        gap(sources,8);add(sources,text("云闪付可对当前选定窗口补识别；其他选定 App 仅在检测到交易结果或详情提示时补识别。图片不保存、不上传。缺少日期或方向会进待确认；安全页面可能无法读取。",12,MUTED,false));
        gap(sources,12);toggle(sources,"悬浮采集状态","需要“显示在其他应用上层”和页面无障碍服务；可拖动，点按返回轻记",app.config.flag("floating_status"),this::toggleFloating);
        add(sources,button(Settings.canDrawOverlays(this)?"悬浮窗已授权 · 查看设置":"允许显示在其他应用上层",this::overlaySettings));
        gap(sources,8);add(sources,text(app.config.text("page_status","页面服务尚未连接"),12,MUTED,false));
        gap(sources,12);toggle(sources,"新截图自动识别","授权后监测截屏目录；不扫描普通相册和旧截图",app.config.flag("screenshots"),this::toggleImages);
        add(sources,button("选择或分享一张截图识别",this::pickImage));gap(sources,12);
        add(sources,text("临时屏幕识别",15,INK,true));gap(sources,4);add(sources,text("需要系统屏幕授权及无障碍来识别当前 App；仅处理勾选 App。每 4 秒最多识别一帧，锁屏后停止，可能增加耗电。",12,MUTED,false));gap(sources,8);
        add(sources,button(app.config.flag("capture_running")?"停止本次屏幕识别":"开始本次屏幕识别",this::screenCapture));
        section("识别哪些 App");LinearLayout apps=card(body);
        for(Map.Entry<String,String> appInfo:Config.DEFAULT_APPS.entrySet()) {
            CheckBox check=new CheckBox(this);check.setText(appInfo.getValue());check.setTextColor(INK);check.setMinHeight(dp(48));check.setChecked(app.config.allowed(appInfo.getKey()));check.setOnCheckedChangeListener((b,on)->app.config.flag("app_"+appInfo.getKey(),on));add(apps,check);
        }
        add(apps,text("银行版本的包名可能不同，可在下方补充具体包名。",12,MUTED,false));gap(apps,8);add(apps,button("补充其他银行 / App 包名",()->editConfig("其他 App 包名","custom_apps","多个完整包名用逗号或换行分隔。只添加需要识别交易的 App。","")));
        section("本人账户与短信号码");LinearLayout accounts=card(body);add(accounts,text("填写本人银行卡尾号，用于辅助识别账户互转。不会将所有“转账”都当作内部转账。",13,MUTED,false));gap(accounts,8);
        add(accounts,button("设置本人银行卡尾号",()->editConfig("本人银行卡尾号","own_tails","每个尾号 4 位，多个尾号用逗号分隔。","")));
        gap(accounts,8);add(accounts,button("允许的短信号码前缀",()->editConfig("短信来源号码","sms_senders","默认 955、953、106。可以替换为你实际收到银行通知的号码或前缀。","955,953,106")));
        section("Redmi · HyperOS 3 后台设置");LinearLayout hyper=card(body);add(hyper,text("1. 应用信息 → 电量与性能 → 无限制\n2. 系统设置 → 应用设置 → 授权管理 → 自启动，开启轻记\n3. 最近任务中长按轻记，锁定后台任务\n4. 确保微信、支付宝和银行 App 的通知能显示交易内容\n5. 安装来源可能触发“允许受限设置”，需在应用信息里自行开启",13,INK,false));gap(hyper,12);
        add(hyper,button("打开轻记应用信息",()->open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));
        gap(hyper,8);add(hyper,button("打开后台自启动设置",this::autoStartSettings));gap(hyper,8);add(hyper,button("重新连接通知采集",()->{NotificationListenerService.requestRebind(new ComponentName(this,PaymentNotificationService.class));toast("已请求系统重新连接");}));
        gap(hyper,10);add(hyper,text("系统版本的菜单名称可能变化。强制停止后需要重新打开轻记；无法保证系统永远不终止后台服务。",12,MUTED,false));
        section("可选云端分类");LinearLayout cloud=card(body);add(cloud,text("默认关闭。本地规则与中文 OCR 不依赖网络。配置后，云端只建议待确认交易的分类，不改变金额、时间或自动确认。",13,MUTED,false));gap(cloud,10);add(cloud,button("配置模型接口与密钥",this::cloudSettings));gap(cloud,8);add(cloud,text(app.config.text("cloud_status","尚未调用云端模型"),12,MUTED,false));
        updateSettings();
        section("数据与诊断");LinearLayout data=card(body);add(data,text("自动合并证据："+app.config.prefs.getLong("merge_count",0)+" 次\n"+app.config.text("last_error","暂无采集错误"),13,MUTED,false));gap(data,8);
        add(data,button("导出全部账单 CSV",this::export));gap(data,8);add(data,button("数据与能力说明",()->info("数据与能力",privacyText()+"\n\n通知内容缺失、银行安全页面、未被识别的格式都可能造成漏记。App 不读取支付账户后台，也不能绕过安全截屏限制。当前版本需在你的实机验证后台与权限兼容性。")));gap(data,8);
        add(data,button("清空手机内全部账单",()->new AlertDialog.Builder(this).setTitle("清空本地账单？").setMessage("这会删除已确认、待确认和所有交易证据。请先导出需要保留的数据。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->app.worker.execute(()->{app.store.clear();app.config.prefs.edit().putLong("merge_count",0).remove("last_capture").apply();main.post(()->{toast("本地账单已清空");load();});})).show()));
        gap(body,18);add(body,text("轻记 "+app.updater.currentVersion()+" · Android 8+\n按 Android 16 / HyperOS 3 设计；实机兼容性待验证",12,MUTED,false));
    }
    private void updateSettings(){
        section("应用更新");LinearLayout update=card(body);
        add(update,text("当前版本 "+app.updater.currentVersion(),16,INK,true));gap(update,8);
        toggle(update,"启动时自动检查","每 12 小时最多检查一次；发现新版后提示",app.updater.autoCheck(),on->{app.config.flag("update_auto",on);if(on)app.updater.check(false);});gap(update,8);
        toggle(update,"接收测试版","包含 0.1.x 等 GitHub 预发布版本",app.updater.previews(),on->{app.config.flag("update_previews",on);app.updater.changeChannel();});gap(update,10);
        add(update,text(app.updater.status(),13,MUTED,false));
        long last=app.updater.lastCheck();if(last>0){gap(update,4);add(update,text("上次成功检查："+formatTime(last),12,MUTED,false));}gap(update,10);
        Button check=button(app.updater.busy()?"正在处理更新…":"立即检查更新",()->app.updater.check(true));check.setEnabled(!app.updater.busy());add(update,check);
        UpdatePolicy.Offer offer=app.updater.offer();
        if(offer!=null){gap(update,8);Button download=button("下载新版 "+offer.version,()->showUpdate(offer));download.setEnabled(!app.updater.busy());add(update,download);}
        if(app.updater.hasDownload()){gap(update,8);Button install=button("安装已下载的更新",this::installUpdate);install.setEnabled(!app.updater.busy());add(update,install);}
        gap(update,8);add(update,button("打开 GitHub 发布页",()->open(new Intent(Intent.ACTION_VIEW,Uri.parse(UpdatePolicy.RELEASES)))));
        gap(update,10);add(update,text("版本检查只连接 GitHub，不发送账单、短信或截图。下载需手动确认，可能使用移动流量；安装仍需系统确认。",12,MUTED,false));
    }
    private void showUpdate(UpdatePolicy.Offer offer){
        if(offer==null||isFinishing()||isDestroyed())return;
        String size=String.format(Locale.CHINA,"%.1f MB",offer.bytes/1048576d);
        String notes=offer.notes.isEmpty()?"请查看 GitHub 发布说明。":offer.notes;
        new AlertDialog.Builder(this).setTitle("发现新版本 "+offer.version+(offer.preview?"（测试版）":""))
            .setMessage("下载大小："+size+"，可能使用移动流量。\n覆盖安装保留本地账本。\n\n"+notes)
            .setNegativeButton("稍后",null).setNeutralButton("发布说明",(d,w)->open(new Intent(Intent.ACTION_VIEW,Uri.parse(offer.page()))))
            .setPositiveButton("下载更新",(d,w)->app.updater.download()).show();
    }
    private void installUpdate(){
        if(!app.updater.hasDownload()){toast("安装包不存在，请重新下载");return;}
        if(!getPackageManager().canRequestPackageInstalls()){
            infoAction("允许安装轻记更新","请在下一页允许轻记安装应用。返回后会复核安装包并打开系统安装确认。",()->{
                app.config.flag("update_waiting_permission",true);
                open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));
            });
        }else app.updater.verifyForInstall();
    }
    private void toggle(LinearLayout parent,String title,String subtitle,boolean checked,java.util.function.Consumer<Boolean> callback){
        LinearLayout row=horizontal();LinearLayout labels=column();labels.addView(text(title,15,INK,true));labels.addView(text(subtitle,12,MUTED,false));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));Switch s=new Switch(this);s.setContentDescription(title);s.setChecked(checked);s.setMinWidth(dp(56));s.setMinHeight(dp(48));s.setOnCheckedChangeListener((b,on)->callback.accept(on));row.addView(s);add(parent,row);
    }
    private void toggleAutoOcr(boolean on){
        if(!on){app.config.flag("accessibility_ocr",false);app.refresh();return;}
        if(Build.VERSION.SDK_INT<34){toast("自动窗口 OCR 需要 Android 14 或更新系统，可使用临时屏幕识别");load();return;}
        new AlertDialog.Builder(this).setTitle("自动识别支付窗口")
            .setMessage("开启后，轻记可在后台对当前可见、已勾选的云闪付窗口，以及其他已勾选 App 的交易结果窗口截图并本地识别。跳过可识别的密码或输入页面，锁屏或切换窗口后丢弃未完成结果。图片不保存、不上传，也不会点击或操作付款。\n\n需要系统无障碍授权。升级后若画面读取不可用，请在系统无障碍中关闭再开启轻记。")
            .setNegativeButton("取消",(d,w)->load()).setPositiveButton("开启",(d,w)->{app.config.flag("accessibility_ocr",true);app.config.flag("accessibility",true);app.refresh();if(!accessibilityAccess())confirmAccessibility();load();}).show();
    }
    private void toggleFloating(boolean on){
        app.config.flag("floating_status",on);
        if(on){app.config.flag("accessibility",true);if(!Settings.canDrawOverlays(this))overlaySettings();else if(!accessibilityAccess())confirmAccessibility();}
        app.refresh();load();
    }
    private void overlaySettings(){infoAction("显示在其他应用上层","这项权限用于悬浮采集状态，不用于读取其他应用。请在系统页允许轻记显示在其他应用上层，然后返回；页面无障碍服务也需要开启。",()->open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));}
    private boolean settingContains(String setting,Class<?> service){String values=Settings.Secure.getString(getContentResolver(),setting);if(values==null)return false;ComponentName wanted=new ComponentName(this,service);for(String entry:values.split(":")){ComponentName actual=ComponentName.unflattenFromString(entry);if(wanted.equals(actual))return true;}return false;}
    private boolean notificationAccess(){return settingContains("enabled_notification_listeners",PaymentNotificationService.class);}
    private boolean accessibilityAccess(){return settingContains(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,PaymentAccessibilityService.class);}
    private void openNotificationSettings(){infoAction("支付通知读取","授权后，轻记只处理勾选 App 的新交易通知。验证码和非交易内容不会保存。隐藏金额的通知无法自动识别。",()->open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));}
    private void confirmAccessibility(){infoAction("支付页面识别","轻记读取你勾选 App 中可见的交易文字；另行开启自动 OCR 后，还可截图识别当前支付窗口，图片不保存、不上传。跳过可识别的密码或输入页面，不点击、不操作支付。系统会提示无障碍具有较大的访问范围，你可以随时关闭。",()->open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));}
    private void toggleSms(boolean on){if(!on){app.config.flag("sms",false);return;}if(checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED){app.config.flag("sms",true);return;}app.config.flag("sms",false);infoAction("交易短信识别","只处理授权后新到达、来自允许号码前缀的交易短信。不会读取历史短信，不处理验证码。",()->requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS},SMS_PERMISSION));}
    private void enableImages(){app.config.prefs.edit().putBoolean("screenshots",true).putLong("screenshots_since",System.currentTimeMillis()).apply();ScreenshotScanJob.schedule(this);load();}
    private void toggleImages(boolean on){if(!on){app.config.flag("screenshots",false);ScreenshotScanJob.schedule(this);return;}if(ScreenshotScanJob.hasPermission(this)){enableImages();return;}app.config.flag("screenshots",false);infoAction("自动识别新截图","安卓的图片权限范围可能覆盖相册；轻记只查询授权后新增的截屏文件，不识别普通照片。Android 14+ 需要允许所有图片才能监测未来截图；也可以继续使用单张截图分享。",()->requestPermissions(Build.VERSION.SDK_INT>=34?new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}:new String[]{Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_EXTERNAL_STORAGE},IMAGES_PERMISSION));}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] granted){super.onRequestPermissionsResult(code,permissions,granted);boolean ok=granted.length>0&&granted[0]==PackageManager.PERMISSION_GRANTED;if(code==SMS_PERMISSION)app.config.flag("sms",ok);if(code==IMAGES_PERMISSION&&ok)enableImages();if(code==103){if(ok)launchProjection();else toast("通知权限未授予，屏幕识别未开始");}else if(!ok){if(code==IMAGES_PERMISSION&&Build.VERSION.SDK_INT>=34&&checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED)toast("目前仅授权部分图片，无法监测未来截图；可用单张截图分享或重新授权全部图片");else toast("权限未授予，可在系统设置中开启");}load();}
    private void autoStartSettings(){Intent i=new Intent().setComponent(new ComponentName("com.miui.securitycenter","com.miui.permcenter.autostart.AutoStartManagementActivity"));try{startActivity(i);}catch(Exception ex){open(new Intent(Settings.ACTION_SETTINGS));toast("请在系统设置中搜索“自启动”");}}
    private void pickImage(){if(!app.config.enabled()){toast("请先开启自动采集");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMAGE_PICK);}
    private void screenCapture(){if(app.config.flag("capture_running")){stopService(new Intent(this,ScreenCaptureService.class));load();return;}if(!app.config.enabled()){toast("请先开启自动采集");return;}if(!accessibilityAccess()){confirmAccessibility();return;}
        infoAction("开始本次屏幕识别","随后会出现安卓系统的屏幕共享授权。仅识别勾选的支付 App，屏幕图像只在内存处理，不保存录屏。锁屏或点击常驻通知的停止按钮后结束。银行禁止截屏的页面仍无法读取。",()->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},103);return;}launchProjection();});}
    private void launchProjection(){startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),PROJECTION);}
    private void export(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"轻记账单-"+LocalDate.now(ZONE)+".csv");startActivityForResult(i,EXPORT);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==IMAGE_PICK&&data.getData()!=null){toast("正在本地识别截图");Uri uri=data.getData();Ocr.uri(this,uri,Source.SCREENSHOT,"picked-image",uri.toString(),(success,message)->main.post(()->{toast(message);load();}));}
        if(request==PROJECTION){Intent i=new Intent(this,ScreenCaptureService.class).putExtra("result",result).putExtra("data",data);try{startForegroundService(i);}catch(Exception ex){toast("屏幕识别未启动，请重新授权");}}
        if(request==EXPORT&&data.getData()!=null){Uri target=data.getData();app.worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(target,"wt")){if(out==null)throw new IOException("No output");StringBuilder csv=new StringBuilder("\ufeff时间,金额,币种,类型,商户,分类,状态,证据数量\r\n");for(Entry e:app.store.list()){Candidate c=e.transaction;csv.append(csvCell(formatTime(c.occurredAt))).append(',').append(amount(c.cents)).append(',').append(csvCell(c.currency)).append(',').append(csvCell(LedgerStore.kindLabel(c.kind))).append(',').append(csvCell(c.merchant)).append(',').append(csvCell(c.category)).append(',').append(csvCell(e.status==Status.CONFIRMED?"已确认":e.status==Status.REVIEW?"待确认":"已忽略")).append(',').append(e.evidence.size()).append("\r\n");}out.write(csv.toString().getBytes(StandardCharsets.UTF_8));main.post(()->toast("账单已导出"));}catch(Exception ex){main.post(()->toast("导出失败，请重新选择保存位置"));}});}
    }
    private static String csvCell(String v){if(v.startsWith("=")||v.startsWith("+")||v.startsWith("-")||v.startsWith("@")||v.startsWith("\t")||v.startsWith("\r"))v="'"+v;return "\""+v.replace("\"","\"\"")+"\"";}
    private void details(Entry e){Candidate c=e.transaction;LinearLayout content=column();content.setPadding(dp(22),dp(12),dp(22),dp(16));ScrollView wrap=new ScrollView(this);wrap.addView(content);
        add(content,text((c.currency.equals("CNY")?"¥ ":c.currency+" ")+amount(c.cents),30,INK,true));gap(content,10);add(content,text("类型："+LedgerStore.kindLabel(c.kind)+"\n时间："+formatTime(c.occurredAt)+(c.exactTime?"":"（以采集时间暂存）")+"\n商户："+(c.merchant.isEmpty()?"未识别":c.merchant)+"\n来源："+c.rail+"\n判断："+c.reason,14,MUTED,false));gap(content,16);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(e.status==Status.REVIEW?"核对交易":"账单详情").setView(wrap).setNegativeButton("关闭",null).create();
        add(content,button(e.status==Status.REVIEW?"核对并入账":"修改账单",()->{dialog.dismiss();editEntry(e);}));gap(content,8);
        add(content,button("查看交易证据",()->app.worker.execute(()->{String excerpts=app.store.excerpts(e.id);main.post(()->info("本地交易证据",excerpts.isEmpty()?"没有可显示的证据":excerpts));})));
        long related=app.store.related(e.id);if(e.status==Status.REVIEW&&related>0){gap(content,8);add(content,button("核对与可能重复的账单",()->{dialog.dismiss();showMerge(e.id,related);}));}
        gap(content,8);add(content,button("忽略这笔交易",()->{dialog.dismiss();new AlertDialog.Builder(this).setTitle("忽略这笔交易？").setMessage("忽略后不计入收支，同一来源重复推送也不会恢复。").setNegativeButton("取消",null).setPositiveButton("忽略",(d,w)->app.worker.execute(()->{app.store.ignore(e.id);main.post(this::load);})).show();}));dialog.show();
    }
    private void showMerge(long from,long to){Entry a=app.store.get(from),b=app.store.get(to);if(a==null||b==null){toast("候选账单已变动");return;}Candidate target=b.transaction;
        new AlertDialog.Builder(this).setTitle("这是同一笔交易吗？").setMessage("已有账单："+(target.merchant.isEmpty()?LedgerStore.kindLabel(target.kind):target.merchant)+"\n"+target.currency+" "+amount(target.cents)+" · "+formatTime(target.occurredAt)+"\n类型："+LedgerStore.kindLabel(target.kind)+"\n\n合并后只保留已有账单的金额和类型，将新来源附加为证据。").setNegativeButton("保留两笔",null).setPositiveButton("合并到已有账单",(d,w)->app.worker.execute(()->{boolean ok=app.store.manualMerge(from,to);main.post(()->{toast(ok?"证据已合并":"金额或币种不同，无法合并");load();});})).show();
    }
    private EditText field(LinearLayout form,String label,String value,int inputType){add(form,text(label,13,MUTED,false));EditText e=new EditText(this);e.setText(value);e.setTextSize(16);e.setSingleLine();e.setInputType(inputType);add(form,e);gap(form,8);return e;}
    private void editEntry(Entry row){Candidate c=row.transaction;LinearLayout form=column();form.setPadding(dp(20),dp(12),dp(20),dp(8));ScrollView wrap=new ScrollView(this);wrap.addView(form);
        EditText money=field(form,"金额（"+c.currency+"）",amount(c.cents),InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText merchant=field(form,"商户 / 说明",c.merchant,InputType.TYPE_CLASS_TEXT);
        EditText time=field(form,"交易时间（北京时间）",formatTime(c.occurredAt),InputType.TYPE_CLASS_TEXT);
        EditText category=field(form,"分类",c.category,InputType.TYPE_CLASS_TEXT);
        add(form,text("交易类型",13,MUTED,false));Spinner type=new Spinner(this);String[] labels={"请选择类型","支出","收入","内部转账","退款"};Kind[] kinds={Kind.UNKNOWN,Kind.EXPENSE,Kind.INCOME,Kind.TRANSFER,Kind.REFUND};type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));for(int i=0;i<kinds.length;i++)if(c.kind==kinds[i])type.setSelection(i);add(form,type);gap(form,12);add(form,text("内部转账不计入收支。退款会冲减净支出。",12,MUTED,false));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("核对账单").setView(wrap).setNegativeButton("取消",null).setPositiveButton("确认入账",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{long value=new BigDecimal(money.getText().toString()).movePointRight(2).longValueExact();if(value<=0||value>99_999_999_999L)throw new IllegalArgumentException();int selected=type.getSelectedItemPosition();if(selected==0){toast("请选择交易类型");return;}long when=LocalDateTime.parse(time.getText().toString().trim(),TIME.withResolverStyle(ResolverStyle.STRICT)).atZone(ZONE).toInstant().toEpochMilli();c.cents=value;c.kind=kinds[selected];c.merchant=merchant.getText().toString().trim();c.category=category.getText().toString().trim();c.occurredAt=when;c.exactTime=true;c.confidence=1;dialog.dismiss();app.worker.execute(()->{app.store.edit(row.id,c,Status.CONFIRMED);main.post(this::load);});}
            catch(Exception ex){toast("请核对金额与时间，格式如 2026-10-14 14:00");}
        }));dialog.show();
    }
    private void editConfig(String title,String key,String hint,String fallback){EditText input=new EditText(this);input.setText(app.config.text(key,fallback));input.setHint(hint);input.setPadding(dp(20),dp(12),dp(20),dp(12));input.setMinLines(2);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{String value=input.getText().toString().trim();if(key.equals("own_tails")){for(String t:value.split("[,，\\s]+"))if(!t.isEmpty()&&!t.matches("[0-9]{4}")){toast("银行卡尾号必须为 4 位数字");return;}}app.config.putText(key,value);load();}).show();
    }
    private void cloudSettings(){LinearLayout form=column();form.setPadding(dp(20),dp(12),dp(20),dp(12));ScrollView wrap=new ScrollView(this);wrap.addView(form);
        add(form,text("开启后，将待确认交易的金额、商户和本地分类发送到你填写的 HTTPS 模型接口。不会发送截图、短信全文或完整卡号。可能产生接口费用。",13,MUTED,false));gap(form,12);
        EditText url=field(form,"Chat Completions 完整接口 URL",app.config.text("api_url","https://api.openai.com/v1/chat/completions"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText model=field(form,"模型",app.config.text("api_model","gpt-4.1-mini"),InputType.TYPE_CLASS_TEXT);
        EditText key=field(form,"API Key（留空保留现有密钥）","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox enabled=new CheckBox(this);enabled.setText("允许发送上述交易字段，开启云端分类");enabled.setChecked(app.config.flag("cloud_ai"));add(form,enabled);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("可选云端分类").setView(wrap).setNegativeButton("取消",null).setNeutralButton("清除密钥",(d,w)->{try{SecretVault.save(app.config,"");app.config.flag("cloud_ai",false);toast("密钥已清除，云端分类已关闭");load();}catch(Exception ex){toast("密钥清除失败");}}).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{Uri address=Uri.parse(url.getText().toString().trim());if(!"https".equals(address.getScheme())||address.getHost()==null||address.getUserInfo()!=null){toast("接口必须为完整 HTTPS 地址");return;}String newKey=key.getText().toString().trim();if(enabled.isChecked()&&newKey.isEmpty()&&SecretVault.load(app.config).isEmpty()){toast("开启云端分类前请填写 API Key");return;}if(model.getText().toString().trim().isEmpty()){toast("请填写模型名称");return;}if(!newKey.isEmpty())SecretVault.save(app.config,newKey);app.config.putText("api_url",address.toString());app.config.putText("api_model",model.getText().toString());app.config.flag("cloud_ai",enabled.isChecked());dialog.dismiss();load();}catch(Exception ex){toast("配置保存失败，请重试");}}));dialog.show();
    }
    private void demo(){long now=System.currentTimeMillis();Candidate transfer=LedgerCore.parse(new Evidence(Source.NOTIFICATION,Config.UNIONPAY,"demo1","转入本人银行卡，转账成功500元",now),Set.of());Candidate expense=LedgerCore.parse(new Evidence(Source.NOTIFICATION,Config.WECHAT,"demo2","支付成功38元 商户：瑞幸咖啡",now+10_000),Set.of());Candidate sms=LedgerCore.parse(new Evidence(Source.SMS,"sms:95588","demo3","消费38元 商户：瑞幸咖啡，余额800元",now+11_000),Set.of());Entry first=new Entry(1,transfer,Status.CONFIRMED),second=new Entry(2,expense,Status.CONFIRMED);second.evidence.add(new EvidenceRef(expense.evidence));Decision decision=LedgerCore.decide(sms,List.of(second));info("独立演示 · 不写入账本","本人银行卡转入 500 元 → "+LedgerStore.kindLabel(transfer.kind)+"\n瑞幸咖啡支付 38 元 → "+LedgerStore.kindLabel(expense.kind)+"\n银行短信消费 38 元 → "+(decision.action==Action.MERGE?"合并为同笔消费的证据":"待核对")+"\n短信余额 800 元 → 忽略余额\n\n净支出：¥ "+amount(netExpense(List.of(first,second))));}
    private String privacyText(){return "交易存储在手机应用私有目录，系统备份已关闭。中文 OCR 使用随 APK 打包的本地模型，图片只在内存处理，不保存录屏文件。长卡号和手机号会被隐藏，验证码不保存。\n\n自动窗口 OCR 需单独开启，在 Android 14+ 的可见选定支付窗口本地识别，锁屏或切换窗口后丢弃未完成结果。悬浮窗需另行授权，仅显示采集状态。\n\n默认不调用网络模型。开启可选云端分类后，只发送金额、商户、交易类型及本地分类；API 密钥由 Android Keystore 加密保存。\n\n启动时自动检查更新默认开启，每 12 小时最多一次，可在设置关闭。版本检查只连接 GitHub 获取公开版本信息，不发送交易数据；下载和安装均需你确认。\n\n当前自动入账使用本地规则。金额、商户、时间或转账对象存在歧义时会进入待确认；同金额不能作为唯一去重依据。";}
    private void info(String title,String message){TextView content=text(message,14,INK,false);content.setTextIsSelectable(true);content.setPadding(dp(22),dp(16),dp(22),dp(16));ScrollView wrap=new ScrollView(this);wrap.addView(content);new AlertDialog.Builder(this).setTitle(title).setView(wrap).setPositiveButton("知道了",null).show();}
    private void infoAction(String title,String message,Runnable action){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("暂不开启",(d,w)->load()).setPositiveButton("继续授权",(d,w)->action.run()).show();}
    private void open(Intent intent){try{startActivity(intent);}catch(Exception ex){toast("当前系统没有此设置入口，请到系统设置中手动开启");}}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private static String formatTime(long when){return Instant.ofEpochMilli(when).atZone(ZONE).format(TIME);}
}
