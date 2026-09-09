package com.limelight.binding.input.virtual_controller

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.HelpActivity
import org.junit.After
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

    private var controller: VirtualController? = null
    private var container: FrameLayout? = null
    private var oscPreferences: SharedPreferences? = null
    private var originalOscValues: Map<String, String> = emptyMap()

    @After
    fun tearDown() {
        activityRule.scenario.onActivity {
            controller?.cleanup()
            container?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
            oscPreferences?.edit()?.clear()?.apply {
                originalOscValues.forEach { (key, value) -> putString(key, value) }
            }?.commit()
        }
    }

    @Test
    fun refreshBeforeFirstLayoutUsesMeasuredContainerBounds() {
        activityRule.scenario.onActivity { activity ->
            oscPreferences = activity.getSharedPreferences(
                VirtualControllerConfigurationLoader.OSC_PREFERENCE,
                0
            ).also { preferences ->
                originalOscValues = preferences.all.mapValues { it.value.toString() }
                preferences.edit().clear().commit()
            }

            container = FrameLayout(activity)
            controller = VirtualController(null, container, activity).also {
                it.refreshLayout()
            }

            activity.setContentView(FrameLayout(activity).apply {
                addView(
                    requireNotNull(container),
                    FrameLayout.LayoutParams(CONTAINER_WIDTH, CONTAINER_HEIGHT)
                )
            })
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity {
            val measuredContainer = requireNotNull(container)
            val measuredController = requireNotNull(controller)
            assertTrue(measuredController.elements.isNotEmpty())
            measuredController.elements.forEach { element ->
                val params = element.layoutParams as FrameLayout.LayoutParams
                assertTrue(params.leftMargin >= 0)
                assertTrue(params.topMargin >= 0)
                assertTrue(params.leftMargin + params.width <= measuredContainer.width)
                assertTrue(params.topMargin + params.height <= measuredContainer.height)
            }
        }
    }

    private companion object {
        const val CONTAINER_WIDTH = 640
        const val CONTAINER_HEIGHT = 360
    }
}
