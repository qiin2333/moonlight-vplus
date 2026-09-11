package com.limelight.usbip;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UsbIpBackendLifecycleTest {
    @Test public void closeCompletionReleasesNativeOwnerBeforeReplacement() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UsbIpBackend oldBackend = new UsbIpBackend(context);
        UsbIpBackend replacement = new UsbIpBackend(context);
        Method acquireNative = UsbIpBackend.class.getDeclaredMethod("acquireNative");
        acquireNative.setAccessible(true);
        acquireNative.invoke(oldBackend);
        try {
            try {
                acquireNative.invoke(replacement);
                fail("Replacement acquired native ownership before cleanup");
            } catch (InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
            oldBackend.closeAsync().get(2, TimeUnit.SECONDS);
            acquireNative.invoke(replacement);
        } finally {
            oldBackend.closeAsync().get(2, TimeUnit.SECONDS);
            replacement.closeAsync().get(2, TimeUnit.SECONDS);
        }
    }
}
