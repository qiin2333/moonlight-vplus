package com.limelight.binding.input.virtual_controller

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VirtualControllerLayoutTest {
    @get:Rule
    val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @Test
    fun refreshBeforeFirstLayoutUsesMeasuredContainerBounds() {
        lateinit var controller: VirtualController
        lateinit var container: FrameLayout

        activityRule.scenario.onActivity { activity ->
            container = FrameLayout(activity)
            controller = VirtualController(null, container, activity)
            controller.refreshLayout()

            activity.setContentView(FrameLayout(activity).apply {
                addView(
                    container,
                    FrameLayout.LayoutParams(CONTAINER_WIDTH, CONTAINER_HEIGHT)
                )
            })
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity {
            assertTrue(controller.elements.isNotEmpty())
            controller.elements.forEach { element ->
                val params = element.layoutParams as FrameLayout.LayoutParams
                assertTrue(params.leftMargin >= 0)
                assertTrue(params.topMargin >= 0)
                assertTrue(params.leftMargin + params.width <= container.width)
                assertTrue(params.topMargin + params.height <= container.height)
            }
            controller.cleanup()
            (container.parent as? ViewGroup)?.removeView(container)
        }
    }

    private companion object {
        const val CONTAINER_WIDTH = 640
        const val CONTAINER_HEIGHT = 360
    }
}
