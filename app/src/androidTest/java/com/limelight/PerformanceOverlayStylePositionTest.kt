package com.limelight

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.perfhud.PerfHudDensity
import com.limelight.perfhud.PerfHudView
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PerformanceOverlayStylePositionTest {
    @Test
    fun bottomCustomPositionKeepsBothStylesVisible() {
        checkBottomPosition(PreferenceConfiguration.PerfOverlayPosition.BOTTOM)
    }

    @Test
    fun bottomRightCustomPositionKeepsBothStylesVisible() {
        checkBottomPosition(PreferenceConfiguration.PerfOverlayPosition.BOTTOM_RIGHT)
    }

    private fun checkBottomPosition(position: PreferenceConfiguration.PerfOverlayPosition) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlayPrefs = context.getSharedPreferences("performance_overlay", Context.MODE_PRIVATE)
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        val savedOverlay = overlayPrefs.all
        val savedSettings = settings.all
        val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
        try {
            overlayPrefs.edit().clear().commit()
            settings.edit().putStringSet("perf_overlay_display_items", setOf(
                "resolution", "decoder", "render_fps", "packet_loss", "network_latency",
                "decode_latency", "host_latency", "one_percent_low", "clock"
            )).commit()
            ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitForLandscape(scenario)

                var manager: PerformanceOverlayManager? = null
                lateinit var container: FrameLayout
                lateinit var overlay: LinearLayout
                lateinit var hud: PerfHudView
                try {
                    scenario.onActivity { activity ->
                        container = FrameLayout(activity)
                        activity.layoutInflater.inflate(R.layout.activity_game, container, true)
                        activity.setContentView(container)
                        overlay = container.findViewById(R.id.performanceOverlay)
                        hud = container.findViewById(R.id.perfHud)
                        for (i in 0 until overlay.childCount) {
                            (overlay.getChildAt(i) as? TextView)?.text = "1"
                        }
                        val config = PreferenceConfiguration().apply {
                            enablePerfOverlay = true
                            perfOverlayStyle = PerfHudDensity.CLASSIC
                            perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL
                            perfOverlayPosition = position
                        }
                        val overlayManager = PerformanceOverlayManager(activity, config) { }
                        manager = overlayManager
                        overlayManager.initialize()
                        overlayManager.applyOverlayState()
                    }
                    waitForLayout()

                    var originalBottom = 0
                    var edgeTolerance = 0
                    var classicWidth = 0
                    scenario.onActivity {
                        assertTrue("classic overlay must be laid out", overlay.height > 0)
                        assertHorizontalAnchor(container, overlay, position)
                        originalBottom = overlay.bottom
                        classicWidth = overlay.width
                        edgeTolerance = (16 * it.resources.displayMetrics.density).toInt()
                        // This is the position saved after dragging the classic bar to the bottom.
                        overlayPrefs.edit().putBoolean("has_custom_position", true)
                            .putInt("left_margin", overlay.left).putInt("top_margin", overlay.top).commit()
                    }
                    for (style in listOf(PerfHudDensity.STANDARD, PerfHudDensity.CLASSIC)) {
                        scenario.onActivity { checkNotNull(manager).setPerfOverlayStyle(style) }
                        waitForLayout()
                        scenario.onActivity {
                            if (style == PerfHudDensity.STANDARD) {
                                assertFullyVisible(container, overlay, hud)
                                assertTrue("the styles must have different widths", overlay.width != classicWidth)
                            } else {
                                for (i in 0 until overlay.childCount) {
                                    val item = overlay.getChildAt(i)
                                    if (item is TextView && item.visibility == View.VISIBLE) {
                                        assertFullyVisible(container, overlay, item)
                                    }
                                }
                            }
                            assertHorizontalAnchor(container, overlay, position)
                            assertTrue("overlay should remain at the bottom", overlay.bottom >= originalBottom - edgeTolerance)
                        }
                    }
                } finally {
                    scenario.onActivity { manager?.cancelPendingTasks() }
                }
            }
        } finally {
            restorePreferences(overlayPrefs, savedOverlay)
            restorePreferences(settings, savedSettings)
        }
    }

    private fun assertHorizontalAnchor(
        parent: View, overlay: View, position: PreferenceConfiguration.PerfOverlayPosition
    ) {
        if (position == PreferenceConfiguration.PerfOverlayPosition.BOTTOM) {
            assertTrue("overlay should remain horizontally centered: left=${overlay.left}, right=${overlay.right}, parent=${parent.width}",
                abs(overlay.left + overlay.right - parent.width) <= 2)
        } else {
            val edgeTolerance = (16 * parent.resources.displayMetrics.density).toInt()
            assertTrue("overlay should remain at the right edge", parent.width - overlay.right <= edgeTolerance)
        }
    }

    private fun assertFullyVisible(parent: View, overlay: View, content: View) {
        assertTrue("overlay must have a size", overlay.width > 0 && overlay.height > 0)
        assertTrue("overlay must fit its parent", overlay.left >= 0 && overlay.top >= 0 &&
            overlay.right <= parent.width && overlay.bottom <= parent.height)
        assertTrue("content must have a size", content.width > 0 && content.height > 0)
        val visible = Rect()
        assertTrue("content must be visible", content.getGlobalVisibleRect(visible))
        assertEquals("content width must not be clipped", content.width, visible.width())
        assertEquals("content height must not be clipped", content.height, visible.height())
    }

    private fun waitForLayout() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForLandscape(scenario: ActivityScenario<HelpActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var landscape = false
        while (!landscape && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            if (!landscape) Thread.sleep(100)
        }
        assertTrue("activity did not reach landscape orientation", landscape)
        waitForLayout()
    }

    private fun restorePreferences(prefs: SharedPreferences, values: Map<String, *>) {
        val current = prefs.all
        val editor = prefs.edit()
        for (key in values.keys + current.keys) {
            val value = values[key]
            if (value == current[key]) continue
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }
}
