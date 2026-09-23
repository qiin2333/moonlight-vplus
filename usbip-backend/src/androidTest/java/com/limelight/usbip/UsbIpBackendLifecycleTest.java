package com.limelight.usbip;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** What a failed release could not do, the fallback exporter stop does, so
     *  closing must not report it: the owner keeps devices reserved on failure
     *  and refuses to open the panel again.
     *
     * Reaching that state needs an export, which needs a physical device. The
     * state is therefore assembled directly, around an exporter handle no native
     * instance can have: its unbind fails, and its stop succeeds as a no-op. */
    @Test public void closeReportsSuccessWhenTheFallbackReleasesAFailedUnbind() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UsbIpBackend backend = new UsbIpBackend(context);
        try {
            NativeUsbIp.load();
            AtomicBoolean connectionClosed = new AtomicBoolean();
            Object active = newActive(newExport(-1L, "usb/test", "9-9:0", 1),
                    () -> connectionClosed.set(true));
            field(backend, "exporter").setLong(backend, -1L);
            exports(backend).put("usb/test", active);

            // The unbind of the injected export fails, the fallback stop closes
            // the exporter, and nothing is left for the caller to answer for.
            backend.closeAsync().get(5, TimeUnit.SECONDS);
            assertTrue("The connection a failed release kept stayed open", connectionClosed.get());
        } finally {
            backend.closeAsync().get(2, TimeUnit.SECONDS);
        }
    }

    /** A release whose exporter stop failed is retried by the owner. The export
     *  is already gone by then, so the retry has to stop the idle exporter the
     *  first attempt could not, or it would keep listening until closing. */
    @Test public void retryingAReleaseStopsTheExporterLeftBehind() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UsbIpBackend backend = new UsbIpBackend(context);
        try {
            NativeUsbIp.load();
            UsbIpBackend.Export stale = (UsbIpBackend.Export) newExport(-1L, "usb/test", "9-9:0", 1);
            field(backend, "exporter").setLong(backend, -1L);

            backend.release(stale).get(5, TimeUnit.SECONDS);
            assertEquals("The idle exporter survived its release", 0L,
                    field(backend, "exporter").getLong(backend));
        } finally {
            backend.closeAsync().get(2, TimeUnit.SECONDS);
        }
    }

    private static Field field(UsbIpBackend backend, String name) throws Exception {
        Field field = UsbIpBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exports(UsbIpBackend backend) throws Exception {
        return (Map<String, Object>) field(backend, "exports").get(backend);
    }

    private static Object newExport(long handle, String deviceName, String busId, int port) throws Exception {
        Constructor<?> constructor = Class.forName("com.limelight.usbip.UsbIpBackend$Export")
                .getDeclaredConstructor(long.class, String.class, String.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(handle, deviceName, busId, port);
    }

    private static Object newActive(Object export, UsbIpBackend.Connection connection) throws Exception {
        Constructor<?> constructor = Class.forName("com.limelight.usbip.UsbIpBackend$Active")
                .getDeclaredConstructor(Class.forName("com.limelight.usbip.UsbIpBackend$Export"),
                        UsbIpBackend.Connection.class);
        constructor.setAccessible(true);
        return constructor.newInstance(export, connection);
    }
}
