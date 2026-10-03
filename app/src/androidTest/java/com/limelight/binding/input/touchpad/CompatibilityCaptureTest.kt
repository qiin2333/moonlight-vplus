package com.limelight.binding.input.touchpad

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.binding.input.capture.AndroidNativePointerCaptureProvider
import com.limelight.binding.input.capture.InputCaptureProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class CompatibilityCaptureTest {
    private class Fallback : InputCaptureProvider() {
        var destroyed = false
        override fun destroy() { destroyed = true }
    }

    @Test fun disconnectedSelectionUsesEvdevAndHotplugRestoresItWithoutRestarting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = TouchpadCompatibilityDevices.connected().firstOrNull()
        assumeTrue("A real pointer device is needed for the connected-device lookup", device != null)
        instrumentation.runOnMainSync {
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("capture_regression_$name", mode)
            }
            val activity = object : Activity() { init { attachBaseContext(context) } }
            val view = object : View(context) { override fun hasWindowFocus() = true }
            val fallback = Fallback()
            val provider = AndroidNativePointerCaptureProvider(activity, view, fallback)
            fun select(descriptor: String) = TouchpadCompatibilityDevices.save(context,
                setOf(descriptor), mapOf(descriptor to "Regression test"))
            try {
                select("disconnected-device")
                provider.enableCapture()
                assertTrue(fallback.isCapturingActive())
                assertTrue(provider.isCapturingActive())

                select(device!!.descriptor)
                provider.onInputDeviceAdded(device.id)
                assertFalse(fallback.isCapturingEnabled())
                assertFalse(provider.isCapturingActive())
                assertTrue(provider.isPointerInputActive())
                assertFalse(fallback.destroyed)

                select("disconnected-device")
                provider.onInputDeviceRemoved(device.id)
                assertTrue(fallback.isCapturingActive())
                assertTrue(provider.isCapturingActive())
                assertFalse(fallback.destroyed)

                provider.showCursor()
                assertFalse(fallback.isCapturingEnabled())
                provider.hideCursor()
                assertTrue(fallback.isCapturingEnabled())
                provider.disableCapture()
                provider.onInputDeviceChanged(device.id)
                assertFalse(fallback.isCapturingEnabled())
            } finally {
                provider.destroy()
                context.getSharedPreferences("touchpad_compatibility", Context.MODE_PRIVATE).edit().clear().commit()
            }
            assertTrue(fallback.destroyed)
        }
    }
}
