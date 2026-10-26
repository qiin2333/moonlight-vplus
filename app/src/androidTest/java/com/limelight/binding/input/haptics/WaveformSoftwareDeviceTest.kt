package com.limelight.binding.input.haptics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.binding.input.driver.UsbDriverService
import com.limelight.binding.input.driver.UsbWaveformBackends
import com.limelight.nvstream.jni.MoonBridge
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Software checks on Android/ART. Passing these does not validate a physical haptic output. */
@RunWith(AndroidJUnit4::class)
class WaveformSoftwareDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val usb get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Test fun nativeBridgeLoadsAndRejectsInputWithoutStreaming() {
        MoonBridge.getHostFeatureFlags() // Resolves the installed ABI's JNI library and symbol.
        assertEquals(-2, MoonBridge.sendControllerArrivalEvent(0, 1, MoonBridge.LI_CTYPE_PS, 0, 0))
    }

    @Test fun realUsbEnumerationDoesNotInventAWaveformDevice() {
        val devices = usb.deviceList.values
        Log.i("WaveformDeviceTest", "USB host devices=${devices.size}")
        for (device in devices) {
            val candidates = HapticBackendRegistry().discover(UsbWaveformBackends.identity(device))
            Log.i("WaveformDeviceTest", "VID=${device.vendorId} PID=${device.productId} candidates=${candidates.size}")
            assertTrue(candidates.none { it.capability.availability == HapticAvailability.READY })
        }
        if (devices.isEmpty()) assertFalse(UsbWaveformBackends.hasEligibleController(usb, true))
    }

    @Test fun usbServiceCanStartAndStopRepeatedlyWithoutHardware() {
        assumeTrue("This test must not claim physical USB devices", usb.deviceList.isEmpty())
        val connected = CountDownLatch(1)
        val binder = AtomicReference<UsbDriverService.UsbDriverBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder.set(service as UsbDriverService.UsbDriverBinder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, UsbDriverService::class.java), connection, Context.BIND_AUTO_CREATE))
        var token: Long? = null
        try {
            assertTrue("Service did not bind", connected.await(5, TimeUnit.SECONDS))
            repeat(3) {
                val started = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    token = binder.get().attachSession(null, object : UsbDriverService.UsbDriverStateListener {
                        override fun onUsbPermissionPromptStarting() = Unit
                        override fun onUsbPermissionPromptCompleted() = Unit
                        override fun onUsbDriverStartCompleted() { started.countDown() }
                    })
                }
                assertTrue("USB start callback timed out", started.await(5, TimeUnit.SECONDS))
                val stopped = CountDownLatch(1)
                instrumentation.runOnMainSync { binder.get().releaseSession(token!!) { stopped.countDown() } }
                assertTrue("USB stop callback timed out", stopped.await(5, TimeUnit.SECONDS))
                token = null
            }
        } finally {
            token?.let { active -> instrumentation.runOnMainSync { binder.get().releaseSession(active) {} } }
            context.unbindService(connection)
        }
    }
}
