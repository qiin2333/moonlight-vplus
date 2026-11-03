package com.limelight

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.limelight.binding.input.touchpad.TouchpadZoneGeometry
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.StreamView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class ScreenDs5ResizeTest {
    @get:Rule
    val rule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank")),
    )

    @Test
    fun canceledResizeRestoresTheSavedOutlineWithoutChangingTheZone() {
        rule.scenario.onActivity { activity ->
            for (savedZone in listOf("0.18,0.74,0.82,0.94", "0.25,0.50,0.75,0.85")) {
                // Exercise the production handler and overlay without starting a stream
                // or writing any of the user's preferences.
                val game = Game()
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }
                    .invoke(game, activity)
                game.prefConfig = PreferenceConfiguration().apply {
                    screenDs5Touchpad = true
                    screenDs5TouchpadZoneRect = savedZone
                }
                val container = FrameLayout(activity)
                val stream = StreamView(activity)
                game.streamView = stream
                container.addView(stream)
                game.screenDs5Touchpad.attachIfEnabled(container)
                val overlay = checkNotNull(game.screenDs5Touchpad.overlay)
                container.layout(0, 0, 1000, 1000)
                stream.layout(0, 0, 1000, 1000)
                overlay.layout(0, 0, 1000, 1000)
                val handler = TouchInputHandler(game)
                val zone = TouchpadZoneGeometry.parseZoneRect(savedZone)
                val downTime = SystemClock.uptimeMillis()

                fun send(action: Int, y: Float) {
                    val properties = arrayOf(MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    })
                    val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                        x = 500f
                        this.y = y
                        pressure = 0.25f
                        size = 0.05f
                    })
                    val event = MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(), action, 1,
                        properties, coordinates, 0, 0, 1f, 1f, -1, 0,
                        InputDevice.SOURCE_TOUCHSCREEN, 0,
                    )
                    try {
                        assertTrue(handler.handleMotionEvent(stream, event))
                    } finally {
                        event.recycle()
                    }
                }

                fun render() = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).also {
                    overlay.draw(Canvas(it))
                }

                val hidden = render()
                send(MotionEvent.ACTION_DOWN, zone.top * 1000f)
                send(MotionEvent.ACTION_MOVE, (zone.top - 0.1f) * 1000f)
                val hiddenDrag = render()
                assertTrue("Hidden edges must not start resizing", hidden.sameAs(hiddenDrag))
                send(MotionEvent.ACTION_UP, (zone.top - 0.1f) * 1000f)
                assertEquals(savedZone, game.prefConfig.screenDs5TouchpadZoneRect)
                hidden.recycle()
                hiddenDrag.recycle()

                overlay.showZoneReveal()
                val before = render()
                send(MotionEvent.ACTION_DOWN, zone.top * 1000f)
                send(MotionEvent.ACTION_MOVE, (zone.top - 0.1f) * 1000f)
                val preview = render()
                assertFalse("Dragging must change the visible outline", before.sameAs(preview))
                send(MotionEvent.ACTION_CANCEL, (zone.top - 0.1f) * 1000f)
                val canceled = render()
                assertEquals(savedZone, game.prefConfig.screenDs5TouchpadZoneRect)
                assertTrue("Cancel must restore the saved outline", before.sameAs(canceled))
                before.recycle()
                preview.recycle()
                canceled.recycle()
            }
        }
    }
}
