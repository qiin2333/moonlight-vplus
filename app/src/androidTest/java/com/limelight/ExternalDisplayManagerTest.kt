package com.limelight

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExternalDisplayManagerTest {
    @Test
    fun failedWindowAddRestoresHiddenViewAndReleasesPresentationListener() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        assumeTrue(displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isEmpty())
        val external = checkNotNull(displays.createVirtualDisplay(
            "Presentation failure", 320, 240, 160, null,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        ))
        try {
            val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
            ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val originalBrightness = 0.7f
                    activity.window.attributes = activity.window.attributes.apply {
                        screenBrightness = originalBrightness
                    }
                    val localView = StreamView(activity).apply {
                        id = R.id.surfaceView
                        visibility = View.GONE
                    }
                    activity.setContentView(localView)
                    val resolver = TargetDisplayResolver(activity)
                    val config = PreferenceConfiguration().apply { useExternalDisplay = true }
                    val manager = ExternalDisplayManager(activity, config, resolver)
                    val presentationField = ExternalDisplayManager::class.java
                        .getDeclaredField("externalPresentation").apply { isAccessible = true }
                    var disconnects = 0
                    manager.callback = object : ExternalDisplayManager.ExternalDisplayCallback {
                        override fun onExternalDisplayConnected(display: Display) = Unit

                        override fun onStreamViewReady(streamView: StreamView) {
                            // Dialog starts Presentation's listener before WindowManager.addView().
                            // Reproduce that state without adding a production injection hook.
                            val presentation = presentationField.get(manager) as Presentation
                            Presentation::class.java.getDeclaredMethod("onStart").apply {
                                isAccessible = true
                            }.invoke(presentation)
                            throw WindowManager.InvalidDisplayException("Window add rejected")
                        }

                        override fun onExternalDisplayDisconnected() {
                            assertEquals(Display.DEFAULT_DISPLAY, resolver.currentDisplay().displayId)
                            assertNull(presentationField.get(manager))
                            assertEquals(View.VISIBLE, localView.visibility)
                            assertEquals(originalBrightness, activity.window.attributes.screenBrightness, 0f)
                            disconnects++
                        }
                    }
                    try {
                        manager.initialize()
                        assertEquals(1, disconnects)
                        assertFalse(manager.isUsingExternalDisplay())
                        assertEquals(originalBrightness, activity.window.attributes.screenBrightness, 0f)
                    } finally {
                        manager.cleanup()
                    }
                }
                // A leaked Presentation listener would finish the host on this later removal.
                val externalDisplayId = external.display.displayId
                val removed = CountDownLatch(1)
                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit
                    override fun onDisplayChanged(displayId: Int) = Unit
                    override fun onDisplayRemoved(displayId: Int) {
                        if (displayId == externalDisplayId) removed.countDown()
                    }
                }
                displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
                try {
                    external.release()
                    assertTrue("Display removal was not observed", removed.await(5, TimeUnit.SECONDS))
                } finally {
                    displays.unregisterDisplayListener(listener)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        } finally {
            external.release()
        }
    }
}
