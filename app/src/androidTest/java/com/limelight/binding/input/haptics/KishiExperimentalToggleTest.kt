package com.limelight.binding.input.haptics

import android.content.*
import android.os.IBinder
import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.binding.input.driver.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.*

/** Opt-in hardware lifecycle check. Initialization sends only silence. */
class KishiExperimentalToggleTest {
    @Test fun disablingReleasesAndEnablingReopensCompanion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kishiToggleTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = prefs.getBoolean("checkbox_experimental_haptic_protocols", false)
        val sensaPrevious = com.limelight.preferences.SensaStrengthPreferences.enabled(context)
        prefs.edit().putBoolean("checkbox_experimental_haptic_protocols", false)
            .putBoolean("checkbox_sensa_haptics", true).commit()
        val connected = CountDownLatch(1)
        lateinit var binder: UsbDriverService.UsbDriverBinder
        val sinks = LinkedBlockingQueue<WaveformHapticsSink>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder = service as UsbDriverService.UsbDriverBinder
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val listener = object : UsbDriverListener {
            override fun onSystemWaveformSinkAvailable(route: HapticRouteSnapshot, sink: WaveformHapticsSink,
                                                       onAssociationLost: () -> Unit) {
                if (route.capability.backendId == KishiSensaHapticProfile.id) sinks.offer(sink)
            }
            override fun reportControllerState(controllerId: Int, buttonFlags: Int, leftStickX: Float,
                leftStickY: Float, rightStickX: Float, rightStickY: Float, leftTrigger: Float, rightTrigger: Float) = Unit
            override fun deviceRemoved(controller: AbstractController) = Unit
            override fun deviceAdded(controller: AbstractController) = Unit
            override fun reportControllerMotion(controllerId: Int, motionType: Byte, x: Float, y: Float, z: Float) = Unit
        }
        val bound = context.bindService(Intent(context, UsbDriverService::class.java), connection, Context.BIND_AUTO_CREATE)
        var token: Long? = null
        try {
            assertTrue(bound)
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            token = binder.attachSession(listener, object : UsbDriverService.UsbDriverStateListener {
                override fun onUsbPermissionPromptStarting() = Unit
                override fun onUsbPermissionPromptCompleted() = Unit
            })
            val first = checkNotNull(sinks.poll(5, TimeUnit.SECONDS)) { "No Sensa companion" }
            assertTrue(first.start())
            assertEquals(true, binder.appliedSensaHaptics(token))
            binder.updateSensaHaptics(token, false)
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (first.isOperational && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
            assertFalse("Sensa output must stop", first.isOperational)
            assertEquals(false, binder.appliedSensaHaptics(token))
            assertNull("Disabled backend must not reopen", sinks.poll(300, TimeUnit.MILLISECONDS))
            binder.updateSensaHaptics(token, true)
            val second = checkNotNull(sinks.poll(5, TimeUnit.SECONDS)) { "Sensa did not reopen" }
            assertNotSame(first, second)
            assertTrue(second.start())
            assertEquals(true, binder.appliedSensaHaptics(token))
            binder.updateSensaHaptics(token + 1, false)
            SystemClock.sleep(100)
            assertTrue("Foreign session changed output", second.isOperational)
            assertNull(first.releaseFailure)
        } finally {
            token?.let {
                val released = CountDownLatch(1)
                binder.releaseSession(it) { released.countDown() }
                released.await(3, TimeUnit.SECONDS)
            }
            if (bound) context.unbindService(connection)
            prefs.edit().putBoolean("checkbox_experimental_haptic_protocols", previous)
                .putBoolean("checkbox_sensa_haptics", sensaPrevious).commit()
        }
    }
}
