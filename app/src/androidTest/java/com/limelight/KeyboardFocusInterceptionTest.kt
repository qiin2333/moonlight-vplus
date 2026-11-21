package com.limelight

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.limelight.services.KeyboardAccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class KeyboardFocusInterceptionTest {
    @Test
    fun interceptionFollowsMainAndExternalStreamWindowFocus() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is Game && stage == Stage.CREATED) {
                activity.streamView.holder.removeCallback(activity)
                activity.activeStreamView?.holder?.removeCallback(activity)
                activity.progressOverlay?.dismiss()
                activity.progressOverlay = null
            }
        }
        monitor.addLifecycleCallback(callback)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val intent = Intent(context, Game::class.java)
                .putExtra(Game.EXTRA_APP_ID, 1).putExtra(Game.EXTRA_APP_NAME, "app")
                .putExtra(Game.EXTRA_HOST, InetAddress.getLoopbackAddress().hostAddress)
                .putExtra(Game.EXTRA_PORT, 1)
            ActivityScenario.launch<Game>(intent).use { scenario ->
                scenario.onActivity { game ->
                    val service = KeyboardAccessibilityService()
                    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                        isAccessible = true
                    }.invoke(service, context)
                    service.keyEventCallback = game
                    val onKeyEvent = KeyboardAccessibilityService::class.java
                        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
                    fun consumed(action: Int): Boolean = onKeyEvent.invoke(service, KeyEvent(action, KeyEvent.KEYCODE_A)) as Boolean

                    game.onWindowFocusChanged(true)
                    assertTrue(consumed(KeyEvent.ACTION_DOWN))
                    assertTrue(consumed(KeyEvent.ACTION_UP))
                    game.onWindowFocusChanged(false)
                    assertFalse(consumed(KeyEvent.ACTION_DOWN))
                    assertFalse(consumed(KeyEvent.ACTION_UP))

                    val external = Game::class.java.getDeclaredMethod("createExternalDisplayCallback").apply {
                        isAccessible = true
                    }.invoke(game) as ExternalDisplayManager.ExternalDisplayCallback
                    external.onStreamWindowFocusChanged(true)
                    assertTrue(consumed(KeyEvent.ACTION_DOWN))
                    assertTrue(consumed(KeyEvent.ACTION_UP))
                    external.onStreamWindowFocusChanged(false)
                    assertFalse(consumed(KeyEvent.ACTION_DOWN))
                    assertFalse(consumed(KeyEvent.ACTION_UP))
                    game.onWindowFocusChanged(true)
                    assertTrue(consumed(KeyEvent.ACTION_DOWN))
                    assertTrue(consumed(KeyEvent.ACTION_UP))
                    service.keyEventCallback = null
                }
            }
        } finally {
            monitor.removeLifecycleCallback(callback)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { KeyboardAccessibilityService.setIntercepting(false) }
        }
    }
}
