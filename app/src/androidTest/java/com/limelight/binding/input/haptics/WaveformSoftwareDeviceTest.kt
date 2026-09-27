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
import com.limelight.nvstream.Ds5HapticsPcmFrame
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

    @Test fun encoderPreservesStereoAndPacketRateOnArt() {
        val packets = mutableListOf<ByteArray>()
        val pcm = ByteArray(480 * 4)
        for (i in 0 until 480) pcm[i * 4 + 1] = 0x40 // Left only, 0.5 full scale.
        KishiPcmEncoder().encode(Ds5HapticsPcmFrame(0, 1, 0, 0, 48000, 480, 2, 16, pcm), packets::add)
        assertEquals(3, packets.size) // 10 ms -> 40 frames at 4 kHz -> 3 complete 12-frame packets.
        assertTrue(packets.any { packet -> (0 until 12).any { packet[10 + it * 4 + 1] != 0.toByte() } })
        for (packet in packets) {
            assertEquals(64, packet.size)
            assertEquals(0x55, packet[0].toInt() and 255)
            assertEquals(0xAA, packet[1].toInt() and 255)
            assertEquals(48, packet[7].toInt())
            var checksum = 0
            for (i in 2..57) checksum = checksum xor (packet[i].toInt() and 255)
            assertEquals(checksum, packet[58].toInt() and 255)
            for (i in 0 until 12) {
                assertEquals(0, packet[12 + i * 4].toInt())
                assertEquals(0, packet[13 + i * 4].toInt())
            }
        }
    }

    @Test fun fallbackRejectsDuplicatesAndStopsOnEndOnArt() {
        val fallback = AuthoredPcmFallback()
        val frame = Ds5HapticsPcmFrame(0, 0, 1, 0, 48000, 48, 2, 16,
            ByteArray(192) { if (it % 2 == 0) 0 else 0x40 })
        assertFalse(fallback.accept(frame)!!.isZero)
        assertNull(fallback.accept(frame))
        assertEquals(ControllerRumbleState.ZERO,
            fallback.accept(Ds5HapticsPcmFrame(0, 2, 2, 0, 48000, 0, 2, 16, byteArrayOf())))
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
