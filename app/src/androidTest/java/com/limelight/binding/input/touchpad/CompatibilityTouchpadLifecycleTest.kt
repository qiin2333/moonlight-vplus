package com.limelight.binding.input.touchpad

import android.content.Context
import android.content.ContextWrapper
import android.view.PointerIcon
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.CursorServiceManager
import com.limelight.Game
import com.limelight.TouchInputHandler
import com.limelight.binding.PlatformBinding
import com.limelight.binding.input.touch.TouchContext
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class CompatibilityTouchpadLifecycleTest {
    private fun assertCursor(view: StreamView, type: Int) =
        assertEquals(PointerIcon.getSystemIcon(view.context, type), view.pointerIcon)

    private fun withCursor(test: (CursorServiceManager, StreamView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = StreamView(instrumentation.targetContext)
            val manager = CursorServiceManager(view, null,
                PreferenceConfiguration().apply { enableNativeMousePointer = true },
                arrayOfNulls<TouchContext>(TouchInputHandler.TOUCH_CONTEXT_LENGTH),
                object : CursorServiceManager.UiCallback {
                    override fun runOnUi(runnable: Runnable) = runnable.run()
                    override fun isActivityAlive() = true
                    override fun onLocalCursorFallback() = Unit
                })
            try { test(manager, view) } finally { manager.destroy() }
        }
    }

    @Test fun stoppingCompatibilityRestoresThePointerForTheNextSession() = withCursor { manager, view ->
        manager.useHostCursorForCompatibility()
        assertCursor(view, PointerIcon.TYPE_NULL)
        manager.stopService()
        assertCursor(view, PointerIcon.TYPE_ARROW)
        manager.onConnectionStarted()
        assertCursor(view, PointerIcon.TYPE_ARROW)
        manager.useHostCursorForCompatibility()
        assertCursor(view, PointerIcon.TYPE_NULL)
    }

    @Test fun startingANewConnectionClearsCompatibilityWithoutAnEarlierStop() = withCursor { manager, view ->
        manager.useHostCursorForCompatibility()
        manager.onConnectionStarted()
        assertCursor(view, PointerIcon.TYPE_ARROW)
    }

    @Test fun reinitializingTouchContextsReloadsBothSpeedsAndTheirDefaults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = object : ContextWrapper(instrumentation.targetContext) {
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("touchpad_lifecycle_$name", mode)
            }
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val game = Game()
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(game, context)
            val handler = TouchInputHandler(game)
            val view = StreamView(context)
            val config = PreferenceConfiguration()
            val connection = NvConnection(context, ComputerDetails.AddressTuple("127.0.0.1", 47989),
                47984, "regression", "Regression", StreamConfiguration.Builder().build(),
                PlatformBinding.getCryptoProvider(context), null)
            val sensitivityField = TouchInputHandler::class.java.getDeclaredField("touchpadSensitivity")
                .apply { isAccessible = true }
            fun sensitivity() = sensitivityField.get(handler) as TouchpadSensitivity
            try {
                preferences.edit().clear().putInt("touchpad_pointer_speed", 50)
                    .putInt("touchpad_scroll_speed", 200).commit()
                handler.initTouchContexts(connection, view, config)
                assertEquals(CompatibilityTouchpadGesture.Action.Move(4, 0), sensitivity().move(8f, 0f))
                assertEquals(CompatibilityTouchpadGesture.Action.Scroll(0, 16), sensitivity().scroll(0f, 8f))

                preferences.edit().putInt("touchpad_pointer_speed", 200)
                    .putInt("touchpad_scroll_speed", 50).commit()
                handler.initTouchContexts(connection, view, config)
                assertEquals(CompatibilityTouchpadGesture.Action.Move(16, 0), sensitivity().move(8f, 0f))
                assertEquals(CompatibilityTouchpadGesture.Action.Scroll(0, 4), sensitivity().scroll(0f, 8f))

                preferences.edit().clear().commit()
                handler.initTouchContexts(connection, view, config)
                assertEquals(CompatibilityTouchpadGesture.Action.Move(8, 0), sensitivity().move(8f, 0f))
                assertEquals(CompatibilityTouchpadGesture.Action.Scroll(0, 8), sensitivity().scroll(0f, 8f))
            } finally {
                handler.destroyCompatibilityTouchpad()
                preferences.edit().clear().commit()
            }
        }
    }
}
