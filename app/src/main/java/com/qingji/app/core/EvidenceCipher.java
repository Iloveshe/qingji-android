package com.qingji.app.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned authenticated image envelope, bound to the evidence fingerprint. */
public final class EvidenceCipher {
    private EvidenceCipher() {}
    public static byte[] encrypt(byte[] plain,SecretKey key,String id) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);byte[] iv=cipher.getIV();
        if(iv.length!=12)throw new IllegalArgumentException("Unsupported IV size");
        cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));byte[] encrypted=cipher.doFinal(plain);
        byte[] out=new byte[13+encrypted.length];out[0]=1;System.arraycopy(iv,0,out,1,12);System.arraycopy(encrypted,0,out,13,encrypted.length);return out;
    }
    public static byte[] decrypt(byte[] envelope,SecretKey key,String id) throws Exception {
        if(envelope.length<29||envelope[0]!=1)throw new IllegalArgumentException("Invalid evidence format");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(envelope,1,13)));
        cipher.updateAAD(id.getBytes(StandardCharsets.US_ASCII));return cipher.doFinal(envelope,13,envelope.length-13);
    }
}
