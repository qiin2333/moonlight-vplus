package com.limelight.binding.audio

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MicrophoneButtonPositionControllerTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @Test
    fun restoresCustomPositionWhenInitiallyGoneButtonBecomesVisible() {
        var controller: MicrophoneButtonPositionController? = null
        lateinit var container: FrameLayout
        lateinit var button: ImageButton

        activityRule.scenario.onActivity { activity ->
            val store = MicrophoneButtonPositionStore(activity)
            store.clearCustomPosition()
            store.saveCustom(savedPosition)

            container = FrameLayout(activity)
            button = ImageButton(activity).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(BUTTON_SIZE_PX, BUTTON_SIZE_PX)
            }
            container.addView(button)
            activity.setContentView(container)
            controller = MicrophoneButtonPositionController.attach(activity, button)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { button.visibility = View.VISIBLE }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val viewport = MicrophoneButtonViewport(
                containerWidth = container.width,
                containerHeight = container.height,
                buttonWidth = button.width,
                buttonHeight = button.height,
                edgeInset = (10f * activity.resources.displayMetrics.density).roundToInt()
            )
            val expected = MicrophoneButtonPlacement.resolve(
                position = MicrophoneButtonPlacement.POSITION_CUSTOM,
                customPosition = savedPosition,
                viewport = viewport
            )

            assertEquals(expected.x.toFloat(), button.x, 0.01f)
            assertEquals(expected.y.toFloat(), button.y, 0.01f)

            controller?.dispose()
            MicrophoneButtonPositionStore(activity).clearCustomPosition()
        }
    }

    private companion object {
        const val BUTTON_SIZE_PX = 40
        val savedPosition = MicrophoneButtonNormalizedPosition(0.25f, 0.75f)
    }
}
