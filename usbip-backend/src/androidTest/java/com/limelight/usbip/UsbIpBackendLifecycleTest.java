package com.limelight.usbip;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UsbIpBackendLifecycleTest {
    /** Backends no longer contend for a process-wide native slot: each export owns
     * its own exporter, so closing one must neither block nor disturb another. */
    @Test public void backendsCloseIndependentlyAndIdempotently() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UsbIpBackend first = new UsbIpBackend(context);
        UsbIpBackend second = new UsbIpBackend(context);
        try {
            first.closeAsync().get(2, TimeUnit.SECONDS);
            // Repeated close returns the completion of the first one.
            first.closeAsync().get(2, TimeUnit.SECONDS);
            assertTrue(first.closeAsync().isDone());
            second.closeAsync().get(2, TimeUnit.SECONDS);
        } finally {
            first.closeAsync().get(2, TimeUnit.SECONDS);
            second.closeAsync().get(2, TimeUnit.SECONDS);
        }
    }
}
