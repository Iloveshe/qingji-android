package com.qingji.app;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.qingji.app.update.UpdatePolicy;
import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AppIdentityTest {
    @Test public void newInstallHasSeparateStorageProviderAndWorkingLauncher() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageManager pm = context.getPackageManager();
        assertEquals(UpdatePolicy.APP_ID, context.getPackageName());
        assertNotEquals("com.qingji.app", context.getPackageName());
        assertEquals("轻记新版", pm.getApplicationLabel(context.getApplicationInfo()));
        PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_PROVIDERS);
        assertTrue(Arrays.stream(info.providers).anyMatch(p ->
            (context.getPackageName()+".updates").equals(p.authority)));
        assertFalse(Arrays.stream(info.providers).anyMatch(p -> "com.qingji.app.updates".equals(p.authority)));
        assertEquals(MainActivity.class.getName(), pm.getLaunchIntentForPackage(context.getPackageName()).getComponent().getClassName());
        assertNotEquals("update.json", UpdatePolicy.MANIFEST_NAME);
    }
}
