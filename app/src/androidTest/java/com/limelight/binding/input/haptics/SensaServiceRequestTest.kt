package com.limelight.binding.input.haptics

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.UsbDriverServiceManager
import com.limelight.binding.input.driver.UsbDriverService
import org.junit.Assert.*
import org.junit.Test

/** Exercises requests before asynchronous service attachment without relying on stored preferences. */
class SensaServiceRequestTest {
    @Test fun latestRequestSurvivesAttachmentAndNewSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun manager() = UsbDriverServiceManager(instrumentation.targetContext,
            object : UsbDriverService.UsbDriverStateListener {
                override fun onUsbPermissionPromptStarting() = Unit
                override fun onUsbPermissionPromptCompleted() = Unit
            })
        fun awaitApplied(manager: UsbDriverServiceManager, value: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            var applied: Boolean? = null
            do {
                instrumentation.runOnMainSync { applied = manager.appliedSensaHaptics() }
                if (applied == value) return
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals(value, applied)
        }
        val first = manager()
        val second = manager()
        try {
            instrumentation.runOnMainSync {
                first.updateSensaHaptics(true)
                first.updateSensaHaptics(false)
                assertNull(first.appliedSensaHaptics())
                first.bind()
            }
            awaitApplied(first, false)
            instrumentation.runOnMainSync {
                first.updateSensaHaptics(false)
                first.updateSensaHaptics(true)
            }
            awaitApplied(first, true)
            instrumentation.runOnMainSync {
                first.stopAndUnbind()
                second.updateSensaHaptics(false)
                second.bind()
            }
            awaitApplied(second, false)
            instrumentation.runOnMainSync { assertNull(first.appliedSensaHaptics()) }
        } finally {
            instrumentation.runOnMainSync { first.stopAndUnbind(); second.stopAndUnbind() }
        }
    }
}
