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
    fun verticalSeekBarNormalizesInitialValueAndSupportsControllerAdjustment() {
        var previewOpacity = 92
        var persistedOpacity: Int? = null
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario?.onActivity { activity ->
            dialog = GameMenuOpacityEditor.show(
                context = activity,
                anchor = GameMenuOpacityAnchor(centerX = 200, bottomY = 80),
                initialOpacity = 92,
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
        waitFor { previewOpacity == 93 }
        scenario?.onActivity {
            assertEquals(53, seekBar.progress)
        }

        scenario?.onActivity {
            seekBar.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            seekBar.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }
        waitFor { previewOpacity == 90 && persistedOpacity == 90 }

        scenario?.onActivity {
            assertEquals(50, seekBar.progress)
            assertTrue(dialog?.window?.attributes?.gravity?.and(Gravity.TOP) != 0)
        }
    }

    @Test
    fun unchangedEditorPersistsNormalizedInitialValueOnDismiss() {
        var pendingOpacity = 92
        var persistedOpacity = 92
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario?.onActivity { activity ->
            dialog = GameMenuOpacityEditor.show(
                context = activity,
                anchor = GameMenuOpacityAnchor(centerX = 200, bottomY = 80),
                initialOpacity = 92,
                onOpacityChange = { pendingOpacity = it },
                onOpacityChangeFinished = { persistedOpacity = it }
            ).also { opacityDialog ->
                opacityDialog.setOnDismissListener {
                    if (pendingOpacity != persistedOpacity) {
                        persistedOpacity = pendingOpacity
                    }
                }
            }
        }

        waitFor { dialog?.isShowing == true && pendingOpacity == 93 }
        scenario?.onActivity { dialog?.dismiss() }
        waitFor { persistedOpacity == 93 }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(condition())
    }
}
