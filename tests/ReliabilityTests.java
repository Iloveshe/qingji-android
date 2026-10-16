import com.qingji.app.core.*;
import static com.qingji.app.core.LedgerCore.*;
import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class ReliabilityTests {
    static int passed;
    static void check(String name,boolean value){if(!value)throw new AssertionError(name);passed++;}
    static Candidate ocr(String text){return LedgerCore.parse(new Evidence(Source.ACCESSIBILITY_OCR,"com.tencent.mm","test",text,1780000000000L),Set.of());}
    static void rejects(String name,Checked action)throws Exception{boolean rejected=false;try{action.run();}catch(Exception expected){rejected=true;}check(name,rejected);}
    interface Checked{void run()throws Exception;}
    public static void main(String[] args)throws Exception{
        check("WeChat home unknown activity cannot trigger automatic OCR",ReceiptPolicy.messagingScreen("com.tencent.mm",""));
        check("WeChat home skipped",ReceiptPolicy.messagingScreen("com.tencent.mm","com.tencent.mm.ui.LauncherUI"));
        check("Chat skipped despite a payment phrase",ReceiptPolicy.messagingScreen("com.tencent.mm","com.tencent.mm.ui.chatting.ChattingUI"));
        check("Wallet receipt can be considered",!ReceiptPolicy.messagingScreen("com.tencent.mm","com.tencent.mm.plugin.wallet.ui.WalletOrderInfoUI"));
        check("Product price 5999 is not a receipt",ocr("微信支付\n商城\n手机限时价￥5999.00\n支付方式\n微信") == null);
        check("Chat prose mentioning payment does not create 5999",ocr("张三：支付成功5999元\n昨天买的手机") == null);
        check("Bare 5999 under result is unbound",ocr("付款成功\n5999\n商户全称：商店\n交易时间2026-05-28 12:00:00") == null);
        Candidate mixed=ocr("付款成功\n5999\n实际付款￥5.99\n商户全称：商店\n交易时间2026-05-28 12:00:00");
        check("An unrelated marked amount cannot validate a bare 5999",mixed!=null&&mixed.cents==599);
        check("A timestamp under payment verb is not an amount",ocr("付款成功\n2026-05-28 12:00:00\n商户全称：商店") == null);
        check("Card tail is not an amount",ocr("交易详情\n支付方式：银行卡尾号5999\n交易时间2026-05-28 12:00:00") == null);
        Candidate genuine=ocr("付款成功\n￥5999.00\n商户全称：测试商店\n交易时间2026-05-28 12:00:00");
        check("Legitimate 5999 is not blacklisted but waits for visual confirmation",genuine!=null&&genuine.cents==599900&&genuine.status()==Status.REVIEW);
        check("Unconfirmed OCR adds nothing to spending",netExpense(List.of(new Entry(1,genuine,genuine.status())))==0);
        Candidate small=ocr("付款成功\n￥59.99\n商户全称：测试商店\n交易时间2026-05-28 12:00:00");
        check("Decimal amount remains 59.99",small!=null&&small.cents==5999&&small.status()==Status.REVIEW);
        Candidate bank=LedgerCore.parse(new Evidence(Source.SMS,"sms:95588","bank","消费59.99元 商户：测试商店",1780000000000L),Set.of());
        check("Trusted explicit bank SMS can still post",bank.status()==Status.CONFIRMED&&bank.cents==5999);
        check("Inline casual phrase cannot trigger automatic screen capture",!PageReadPolicy.mayCapture(36,true,true,true,true,false,false,"com.tencent.mm","他昨天付款成功，花了5999元"));
        check("HTTPS endpoint accepted",DiagnosticPolicy.endpoint("https://logs.example.com/qingji"));
        check("Cleartext endpoint rejected",!DiagnosticPolicy.endpoint("http://logs.example.com/"));
        check("URL credentials rejected",!DiagnosticPolicy.endpoint("https://user:password@logs.example.com/"));
        check("URL query secrets rejected",!DiagnosticPolicy.endpoint("https://logs.example.com/?token=secret"));
        check("URL fragment rejected",!DiagnosticPolicy.endpoint("https://logs.example.com/#secret"));
        String privateText="5999.00 商户敏感名称 卡号6222021234567890 验证码123456";
        check("Unknown log source never passes through",DiagnosticPolicy.source(privateText).equals("OTHER"));
        check("Unknown log stage never passes through",DiagnosticPolicy.stage(privateText).equals("OTHER"));
        check("Unknown log detail never passes through",DiagnosticPolicy.code(privateText).equals("STATUS_ONLY"));
        check("Arbitrary origin never passes through",DiagnosticPolicy.origin(privateText).equals("OTHER"));
        check("Amount rule is an allowlist",DiagnosticPolicy.rule(privateText).equals("NONE"));
        check("Fixed error code survives without message",DiagnosticPolicy.code(ParseCode.NOT_RECEIPT.label).equals("NOT_RECEIPT"));
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey key=generator.generateKey();
        byte[] plain="actual test screenshot bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);String id="a".repeat(64);
        byte[] encrypted=EvidenceCipher.encrypt(plain,key,id);
        check("Stored evidence decrypts",Arrays.equals(plain,EvidenceCipher.decrypt(encrypted,key,id)));
        check("Encryption uses independent random IVs",!Arrays.equals(encrypted,EvidenceCipher.encrypt(plain,key,id)));
        check("Plain image is not stored",!new String(encrypted,java.nio.charset.StandardCharsets.ISO_8859_1).contains("actual test screenshot"));
        rejects("Image cannot be rebound to different transaction",()->EvidenceCipher.decrypt(encrypted,key,"b".repeat(64)));
        byte[] changed=encrypted.clone();changed[changed.length-1]^=1;
        rejects("Tampered image fails authentication",()->EvidenceCipher.decrypt(changed,key,id));
        rejects("Wrong keystore key fails authentication",()->EvidenceCipher.decrypt(encrypted,generator.generateKey(),id));
        rejects("Truncated image rejected",()->EvidenceCipher.decrypt(Arrays.copyOf(encrypted,12),key,id));
        System.out.println("OCR reliability, diagnostic privacy and evidence encryption tests passed: "+passed);
    }
}
