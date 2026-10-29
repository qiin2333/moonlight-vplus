package com.limelight.nvstream.jni;

import com.limelight.nvstream.NvConnectionListener;
import org.junit.After;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MoonBridgeListenerTest {
    private NvConnectionListener listener(AtomicInteger calls) {
        return (NvConnectionListener) Proxy.newProxyInstance(
                NvConnectionListener.class.getClassLoader(),
                new Class<?>[]{NvConnectionListener.class}, (proxy, method, args) -> {
                    if (method.getName().equals("connectionStarted")) calls.incrementAndGet();
                    return null;
                });
    }

    @After
    public void cleanup() {
        MoonBridge.cleanupBridge();
    }

    @Test
    public void cancelledStartupCannotRegisterAfterDetachment() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        NvConnectionListener listener = listener(calls);
        assertTrue(MoonBridge.setupBridge(null, null, listener, cancelled::get));

        cancelled.set(true);
        MoonBridge.detachConnectionListener(listener);
        assertFalse(MoonBridge.setupBridge(null, null, listener, cancelled::get));
        MoonBridge.bridgeClConnectionStarted();
        assertEquals(0, calls.get());
    }

    @Test
    public void detachmentAvoidsNativeMonitorAndPreservesReplacement() throws Exception {
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        NvConnectionListener oldListener = listener(oldCalls);
        assertTrue(MoonBridge.setupBridge(null, null, oldListener, () -> false));
        assertTrue(MoonBridge.setupBridge(null, null, listener(newCalls), () -> false));

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            synchronized (MoonBridge.class) {
                worker.submit(() -> MoonBridge.detachConnectionListener(oldListener))
                        .get(2, TimeUnit.SECONDS);
            }
            MoonBridge.bridgeClConnectionStarted();
            assertEquals(0, oldCalls.get());
            assertEquals(1, newCalls.get());
        } finally {
            worker.shutdownNow();
        }
    }
}
