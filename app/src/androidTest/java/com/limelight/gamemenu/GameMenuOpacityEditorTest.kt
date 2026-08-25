package com.limelight.gamemenu

import android.app.Dialog
import android.view.Gravity
import android.view.KeyEvent
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameMenuOpacityEditorTest {
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var dialog: Dialog? = null

    @After
    fun tearDown() {
        scenario?.onActivity { dialog?.dismiss() }
        scenario?.close()
    }

    @Test
    fun verticalSeekBarReceivesFocusAndSupportsControllerAdjustment() {
        var previewOpacity = 90
        var persistedOpacity: Int? = null
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario?.onActivity { activity ->
            dialog = GameMenuOpacityEditor.show(
                context = activity,
                anchor = GameMenuOpacityAnchor(centerX = 200, bottomY = 80),
                initialOpacity = 90,
                onOpacityChange = { previewOpacity = it },
                onOpacityChangeFinished = { persistedOpacity = it }
            )
        }

        waitFor { dialog?.isShowing == true }
        lateinit var seekBar: SeekBar
        scenario?.onActivity {
            seekBar = requireNotNull(dialog?.findViewById(R.id.game_menu_opacity_seekbar))
        }
        waitFor { seekBar.hasFocus() }

        scenario?.onActivity {
            seekBar.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            seekBar.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }
        waitFor { previewOpacity == 85 && persistedOpacity == 85 }

        scenario?.onActivity {
            assertEquals(65, seekBar.progress)
            assertTrue(dialog?.window?.attributes?.gravity?.and(Gravity.TOP) != 0)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(condition())
    }
}
