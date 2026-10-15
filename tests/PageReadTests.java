import com.qingji.app.core.PageReadPolicy;

public final class PageReadTests {
    static int passed;
    static void check(String name,boolean result){if(!result)throw new AssertionError(name);passed++;}
    static boolean allow(int sdk,boolean enabled,boolean consent,boolean selected,boolean unlocked,boolean sensitive,boolean parsed,String pkg,String text){
        return PageReadPolicy.mayCapture(sdk,enabled,consent,selected,unlocked,sensitive,parsed,pkg,text);
    }
    public static void main(String[] args){
        check("QuickPass canvas fallback",allow(36,true,true,true,true,false,false,"com.unionpay",""));
        check("No global screenshots on older Android",!allow(33,true,true,true,true,false,false,"com.unionpay",""));
        check("Paused capture",!allow(36,false,true,true,true,false,false,"com.unionpay",""));
        check("OCR opt-in required",!allow(36,true,false,true,true,false,false,"com.unionpay",""));
        check("Unchecked apps never captured",!allow(36,true,true,false,true,false,false,"com.unionpay",""));
        check("Lock screen suppresses capture",!allow(36,true,true,true,false,false,false,"com.unionpay",""));
        check("Sensitive input suppresses whole screenshot",!allow(36,true,true,true,true,true,false,"com.unionpay","付款成功"));
        check("Parsed nodes do not trigger redundant OCR",!allow(36,true,true,true,true,false,true,"com.unionpay","付款成功"));
        check("Messaging window without payment hint skipped",!allow(36,true,true,true,true,false,false,"com.tencent.mm","今天去吃饭"));
        check("Messaging payment result eligible",allow(36,true,true,true,true,false,false,"com.tencent.mm","支付成功"));
        check("Selected bank transaction detail eligible",allow(36,true,true,true,true,false,false,"com.example.bank","交易详情"));
        PageReadPolicy.Budget budget=new PageReadPolicy.Budget();
        check("First capture",budget.acquire(1000,"page-a"));
        check("Animation events throttled",!budget.acquire(2000,"page-a"));
        check("Switching pages does not bypass global throttle",!budget.acquire(2500,"page-b"));
        check("Second retry allowed",budget.acquire(5000,"page-a"));
        check("Third retry allowed",budget.acquire(9000,"page-a"));
        check("Unchanged page stops after three attempts",!budget.acquire(13000,"page-a"));
        check("Changed payment page can start new attempts",budget.acquire(13000,"page-b"));
        System.out.println("Page capture policy tests passed: "+passed);
    }
}
