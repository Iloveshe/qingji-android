package com.qingji.app;

import android.content.Context;
import android.graphics.*;
import android.security.keystore.*;
import android.util.AtomicFile;
import com.qingji.app.core.EvidenceCipher;
import java.io.*;
import java.security.KeyStore;
import javax.crypto.*;

/** Private, no-backup, encrypted copies of actual OCR input. Never a network attachment. */
public final class EvidenceVault {
    private static final String ALIAS="qingji-evidence-images-v1";
    private static final long LIMIT=100L*1024*1024, IMAGE_LIMIT=12L*1024*1024;
    private final File directory;
    public EvidenceVault(Context context){directory=new File(context.getNoBackupFilesDir(),"evidence-images");}
    private static SecretKey key() throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        if(!keys.containsAlias(ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }
        return (SecretKey)keys.getKey(ALIAS,null);
    }
    private File file(String id){if(id==null||!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid evidence id");return new File(directory,id+".qei");}
    public synchronized String save(String id,Bitmap bitmap) throws Exception {
        File target=file(id);if(target.isFile())return id;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Storage unavailable");
        ByteArrayOutputStream png=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,png))throw new IOException("Image encoding failed");
        if(png.size()>IMAGE_LIMIT)throw new IOException("Image size limit");
        byte[] encrypted=EvidenceCipher.encrypt(png.toByteArray(),key(),id);
        long used=0;File[] files=directory.listFiles();if(files!=null)for(File f:files)used+=f.length();
        if(used+encrypted.length>LIMIT)throw new IOException("Evidence quota full");
        AtomicFile atomic=new AtomicFile(target);FileOutputStream out=null;
        try{out=atomic.startWrite();out.write(encrypted);atomic.finishWrite(out);}catch(Exception ex){if(out!=null)atomic.failWrite(out);throw ex;}
        return id;
    }
    public synchronized Bitmap load(String id) throws Exception {
        File target=file(id);if(!target.isFile()||target.length()>IMAGE_LIMIT+64)throw new IOException("Evidence unavailable");
        byte[] data=new AtomicFile(target).readFully();byte[] png=EvidenceCipher.decrypt(data,key(),id);
        Bitmap image=BitmapFactory.decodeByteArray(png,0,png.length);if(image==null)throw new IOException("Image decoding failed");return image;
    }
    public synchronized void clear(){File[] files=directory.listFiles();if(files!=null)for(File f:files)f.delete();}
    public synchronized void remove(String id){new AtomicFile(file(id)).delete();}
}
