package com.limelight.binding.input.driver.wireless.dualsense

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in ROM/API validation; no physical controller output is sent. */
class DualSenseDeviceIntegrationTest {
    @Test fun targetRomCanOpenHidHostProfile() {
        assumeTrue("pass -e romHidHost true on a Bluetooth-enabled physical device",
            InstrumentationRegistry.getArguments().getString("romHidHost") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val ready = CountDownLatch(1)
        val transport = AndroidBluetoothHidHostTransport(
            InstrumentationRegistry.getInstrumentation().targetContext) { ready.countDown() }
        try {
            transport.start()
            assertTrue("HID Host permission/profile access failed on this ROM", ready.await(8, TimeUnit.SECONDS))
        } finally { transport.close() }
    }
}
