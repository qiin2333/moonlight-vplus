package com.limelight

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.view.Display
import android.view.KeyEvent
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.binding.input.capture.NativeKeyboardCapture
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalKeyboardCaptureTest {
    @Test
    fun captureFollowsChangesAndNewPresentationWindows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        assumeTrue(displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isEmpty())
        val external = checkNotNull(displays.createVirtualDisplay(
            "Keyboard capture test", 640, 480, 160, null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        ))
        try {
            val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
            ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
                var manager: ExternalDisplayManager? = null
                try {
                    scenario.onActivity { activity ->
                        activity.setContentView(StreamView(activity).apply { id = R.id.surfaceView })
                        val config = PreferenceConfiguration().apply { useExternalDisplay = true }
                        val displayManager = ExternalDisplayManager(activity, config, TargetDisplayResolver(activity))
                        manager = displayManager
                        with(displayManager) {
                            // Capture can be enabled before a window exists (late display attachment).
                            setKeyboardCaptureEnabled(true)
                            initialize()
                            val first = presentation(this)
                            val focusEvents = mutableListOf<Boolean>()
                            callback = object : ExternalDisplayManager.ExternalDisplayCallback {
                                override fun onExternalDisplayConnected(display: Display) = Unit
                                override fun onExternalDisplayDisconnected() = Unit
                                override fun onStreamViewReady(streamView: StreamView) = Unit
                                override fun onStreamWindowFocusChanged(hasFocus: Boolean) { focusEvents.add(hasFocus) }
                            }
                            first.onWindowFocusChanged(true)
                            first.onWindowFocusChanged(false)
                            assertEquals(listOf(true, false), focusEvents)
                            assertCapture(first.window!!, true)
                            setKeyboardCaptureEnabled(false)
                            assertCapture(first.window!!, false)
                            setKeyboardCaptureEnabled(true)
                            assertCapture(first.window!!, true)

                            // A recreated window inherits the desired state, including release.
                            cleanup()
                            setKeyboardCaptureEnabled(false)
                            initialize()
                            val second = presentation(this)
                            assertNotSame(first, second)
                            assertCapture(second.window!!, false)
                            setKeyboardCaptureEnabled(true)
                            assertCapture(second.window!!, true)
                        }
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        val currentManager = checkNotNull(manager)
                        val second = presentation(currentManager)
                        // Captured keys use the existing focused StreamView route exactly once.
                        val stream = currentManager.getStreamView()!!
                        val events = mutableListOf<Int>()
                        stream.setOnKeyListener { _, _, event -> events.add(event.action); true }
                        assertTrue(stream.requestFocus())
                        assertTrue(second.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
                        assertTrue(second.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
                        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events)
                    }
                } finally {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { manager?.cleanup() }
                }
            }
        } finally {
            external.release()
        }
    }

    @Test
    fun nativeApiIsSkippedOnLegacyAndroid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<HelpActivity>(Intent(context, HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))).use { scenario ->
            scenario.onActivity { activity ->
                val originalFlags = activity.window.attributes.flags
                val supported = Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= 3600001
                assertEquals(supported, NativeKeyboardCapture.apply(activity.window, true))
                assertEquals(originalFlags, activity.window.attributes.flags)
                assertCapture(activity.window, true)
                assertEquals(supported, NativeKeyboardCapture.apply(activity.window, false))
                assertCapture(activity.window, false)
            }
        }
    }

    private fun presentation(manager: ExternalDisplayManager): Presentation =
        ExternalDisplayManager::class.java.getDeclaredField("externalPresentation").apply {
            isAccessible = true
        }.get(manager) as Presentation

    private fun assertCapture(window: Window, expected: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
        ) {
            assertEquals(expected, window.attributes.hasKeyboardCapture())
        }
    }
}
