import com.qingji.app.core.PaymentPageSignature;
import com.qingji.app.core.PageReadPolicy;
import java.util.List;

public final class PageSignatureTests {
    static int passed;
    static void check(String label,boolean result){if(!result)throw new AssertionError(label);passed++;}
    static final List<String> SHAPE=List.of("0|FrameLayout||3","1|TextView|com.unionpay:id/title|0","1|TextView|com.unionpay:id/amount|0");
    static String sig(String pkg,String activity,String text){return PaymentPageSignature.create(pkg,activity,SHAPE,text);}
    public static void main(String[] args){
        String first=sig("com.unionpay","com.unionpay.PaymentDetail","交易详情\n交易金额38元\n商户名称：咖啡店\n交易时间2026-10-15 12:00:00");
        check("Receipt page can be remembered",first.matches("[a-f0-9]{64}"));
        check("Changing merchant, amount and date keeps page identity",first.equals(sig("com.unionpay","com.unionpay.PaymentDetail","交易详情\n交易金额138元\n商户名称：测试便利店\n交易时间2026-10-16 13:50:00")));
        check("No merchant or amount in stored key",!first.contains("咖啡店")&&!first.contains("38元"));
        check("Activity scopes page identity",!first.equals(sig("com.unionpay","com.unionpay.OtherDetail","交易详情\n交易金额38元\n商户名称：咖啡店\n交易时间2026-10-15 12:00:00")));
        check("App scopes page identity",!first.equals(sig("com.example.bank","com.example.bank.PaymentDetail","交易详情\n交易金额38元\n商户名称：咖啡店\n交易时间2026-10-15 12:00:00")));
        check("Structural changes need new memory",!first.equals(PaymentPageSignature.create("com.unionpay","com.unionpay.PaymentDetail",List.of("0|WebView||1","1|Button|new-id|0"),"交易详情\n交易金额38元\n商户名称：咖啡店\n交易时间2026-10-15 12:00:00")));
        check("Chat without payment anchors cannot be remembered",sig("com.tencent.mm","com.tencent.mm.ChatActivity","今天吃饭花了38元").isEmpty());
        check("Missing Activity cannot become app-wide memory",sig("com.unionpay","","交易详情").isEmpty());
        check("Framework view class cannot become Activity wildcard",sig("com.unionpay","android.widget.FrameLayout","交易详情").isEmpty());
        check("Too little structure is rejected",PaymentPageSignature.create("com.unionpay","com.unionpay.PaymentDetail",List.of("one"),"交易详情").isEmpty());
        check("A single weak label cannot remember a generic page",sig("com.unionpay","com.unionpay.MainActivity","收款方").isEmpty());
        check("OTP page memory rejected",sig("com.unionpay","com.unionpay.PaymentDetail","付款成功38元 验证码123456").isEmpty());
        check("Password prompt memory rejected",sig("com.unionpay","com.unionpay.PaymentDetail","交易详情 输入支付密码").isEmpty());
        check("Remembered bank page permits OCR without hardcoded hints",PageReadPolicy.mayCapture(36,true,true,true,true,false,false,"com.example.bank","",true,false));
        check("Remembered page still obeys selection",!PageReadPolicy.mayCapture(36,true,true,false,true,false,false,"com.example.bank","",true,false));
        check("Manual request allows one selected generic payment window",PageReadPolicy.mayCapture(36,true,true,true,true,false,false,"com.example.bank","",false,true));
        check("Manual request cannot bypass lock",!PageReadPolicy.mayCapture(36,true,true,true,false,false,false,"com.example.bank","",false,true));
        check("Manual request cannot bypass OCR opt-in",!PageReadPolicy.mayCapture(36,true,false,true,true,false,false,"com.example.bank","",false,true));
        check("Manual request cannot capture sensitive input",!PageReadPolicy.mayCapture(36,true,true,true,true,true,false,"com.example.bank","",false,true));
        PageReadPolicy.Budget budget=new PageReadPolicy.Budget();budget.acquire(0,"one");budget.acquire(4000,"one");budget.acquire(8000,"one");
        check("Automatic retries remain bounded",!budget.acquire(12000,"one"));
        check("User can retry after automatic attempts exhausted",budget.acquire(12000,"one",true));
        check("User retry still throttled",!budget.acquire(13000,"one",true));
        System.out.println("Page memory and manual capture tests passed: "+passed);
    }
}
