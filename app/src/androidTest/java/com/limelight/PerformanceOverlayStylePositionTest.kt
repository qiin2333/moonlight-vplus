package com.limelight

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
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
    private class OverlayFixture(
        val scenario: ActivityScenario<HelpActivity>,
        val overlayPrefs: SharedPreferences,
        val config: PreferenceConfiguration
    ) {
        lateinit var container: FrameLayout
        lateinit var overlay: LinearLayout
        lateinit var hud: PerfHudView
        var manager: PerformanceOverlayManager? = null

        fun install(activity: HelpActivity) {
            manager?.cancelPendingTasks()
            container = FrameLayout(activity)
            activity.layoutInflater.inflate(R.layout.activity_game, container, true)
            activity.setContentView(container)
            overlay = container.findViewById(R.id.performanceOverlay)
            hud = container.findViewById(R.id.perfHud)
            for (i in 0 until overlay.childCount) {
                (overlay.getChildAt(i) as? TextView)?.text = "1"
            }
            manager = PerformanceOverlayManager(activity, config).also {
                it.initialize()
                it.applyOverlayState()
            }
        }
    }

    private fun withOverlay(
        style: PerfHudDensity,
        position: PreferenceConfiguration.PerfOverlayPosition = PreferenceConfiguration.PerfOverlayPosition.TOP,
        test: OverlayFixture.() -> Unit
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlayPrefs = context.getSharedPreferences("performance_overlay", Context.MODE_PRIVATE)
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        val savedOverlay = overlayPrefs.all
        val savedSettings = settings.all
        val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
        try {
            overlayPrefs.edit().clear().commit()
            val items = if (style == PerfHudDensity.CLASSIC) setOf(
                "resolution", "decoder", "render_fps", "packet_loss", "network_latency",
                "decode_latency", "host_latency", "one_percent_low", "clock"
            ) else setOf("resolution", "decoder", "render_fps", "packet_loss", "network_latency", "decode_latency")
            settings.edit().putStringSet("perf_overlay_display_items", items).commit()
            ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
                val config = PreferenceConfiguration().apply {
                    enablePerfOverlay = true
                    perfOverlayStyle = style
                    perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL
                    perfOverlayPosition = position
                }
                val fixture = OverlayFixture(scenario, overlayPrefs, config)
                try {
                    scenario.onActivity { fixture.install(it) }
                    waitForLayout()
                    fixture.test()
                } finally {
                    scenario.onActivity { fixture.manager?.cancelPendingTasks() }
                }
            }
        } finally {
            restorePreferences(overlayPrefs, savedOverlay)
            restorePreferences(settings, savedSettings)
        }
    }

    @Test
    fun nearbyGuidesSnapAndDisappearWhenDragEndsOrLeaves() = withOverlay(PerfHudDensity.MINI) {
        var centerLeft = 0
        var freeLeft = 0
        var targetTop = 0
        var centerTop = 0
        var nearEdgeLeft = 0
        var tolerance = 0
        scenario.onActivity {
            val density = it.resources.displayMetrics.density
            val lp = overlay.layoutParams as FrameLayout.LayoutParams
            val availableWidth = container.width - container.paddingLeft - container.paddingRight -
                lp.rightMargin - overlay.width
            val availableHeight = container.height - container.paddingTop - container.paddingBottom -
                lp.bottomMargin - overlay.height
            assertTrue("overlay must have space to move", availableWidth > 0 && availableHeight > 0)
            tolerance = maxOf(2, (2 * density).toInt())
            centerLeft = (container.width - overlay.width) / 2
            freeLeft = container.paddingLeft + (availableWidth * 0.27f).toInt()
            targetTop = container.paddingTop + (availableHeight * 0.27f).toInt()
            centerTop = (container.height - overlay.height) / 2
            nearEdgeLeft = container.paddingLeft + (3 * density).toInt()
            assertGuideVisible(container, false)
        }
        val nearCenterLeft = centerLeft + tolerance
        dragOverlay(scenario, overlay, nearCenterLeft, targetTop) { move ->
            scenario.onActivity { assertGuideVisible(container, true) }
            move(freeLeft, targetTop)
            scenario.onActivity { assertGuideVisible(container, false) }
            move(nearCenterLeft, targetTop)
            scenario.onActivity { assertGuideVisible(container, true) }
        }
        waitForLayout()
        scenario.onActivity {
            assertGuideVisible(container, false)
            assertTrue("overlay should snap to the horizontal center",
                abs(overlay.left + overlay.right - container.width) <= tolerance)
        }
        dragOverlay(scenario, overlay, nearEdgeLeft, centerTop + tolerance) {
            scenario.onActivity { assertGuideVisible(container, true) }
        }
        waitForLayout()
        scenario.onActivity {
            assertGuideVisible(container, false)
            val inset = (8 * it.resources.displayMetrics.density).toInt()
            assertTrue("overlay should snap to the left edge with its HUD inset",
                abs(overlay.left - container.paddingLeft - inset) <= tolerance)
            assertTrue("overlay should snap to the vertical center",
                abs(overlay.top + overlay.bottom - container.height) <= tolerance)
        }
    }

    private fun assertGuideVisible(container: FrameLayout, visible: Boolean) {
        val guide = container.findViewWithTag<View>("performance_overlay_snap_guides")
        assertEquals("guide visibility", visible, guide != null && guide.isShown)
    }

    @Test
    fun freeDragSurvivesStyleChangeAndManagerRecreation() = withOverlay(PerfHudDensity.MINI) {
        val (relativeX, relativeY) = dragToFreePosition(this)
        scenario.onActivity { checkNotNull(manager).setPerfOverlayStyle(PerfHudDensity.DETAILED) }
        waitForLayout()
        scenario.onActivity {
            assertRelativePosition(container, overlay, relativeX, relativeY)
            install(it)
        }
        waitForLayout()
        scenario.onActivity { assertRelativePosition(container, overlay, relativeX, relativeY) }
    }

    @Test
    fun verticalDragOfFullWidthOverlayKeepsHorizontalCenterAfterShrink() = withOverlay(PerfHudDensity.CLASSIC) {
        scenario.onActivity { overlay.findViewById<TextView>(R.id.perfRes).text = "W".repeat(2000) }
        waitForLayout()
        var left = 0
        var targetTop = 0
        scenario.onActivity {
            assertEquals("classic overlay must fill the horizontal space", container.width, overlay.width)
            left = overlay.left
            targetTop = ((container.height - overlay.height) * 0.37f).toInt()
        }
        dragOverlay(scenario, overlay, left, targetTop)
        waitForLayout()
        scenario.onActivity {
            checkNotNull(manager).setPerfOverlayStyle(PerfHudDensity.MINI)
        }
        waitForLayout()
        scenario.onActivity {
            assertTrue("the new style must have horizontal space", overlay.width < container.width)
            assertTrue("vertical-only drag should keep the overlay horizontally centered",
                abs(overlay.left + overlay.right - container.width) <= 2)
        }

        val (savedX, _) = dragToFreePosition(this)
        scenario.onActivity { checkNotNull(manager).setPerfOverlayStyle(PerfHudDensity.CLASSIC) }
        waitForLayout()
        scenario.onActivity {
            assertEquals("classic overlay must fill the horizontal space", container.width, overlay.width)
            left = overlay.left
            targetTop = ((container.height - overlay.height) * 0.65f).toInt()
        }
        dragOverlay(scenario, overlay, left, targetTop)
        scenario.onActivity { checkNotNull(manager).setPerfOverlayStyle(PerfHudDensity.MINI) }
        waitForLayout()
        scenario.onActivity { assertEquals("the saved horizontal position should survive", savedX, relativeLeft(container, overlay), 0.03f) }
    }

    @Test
    fun contentGrowthDuringBottomDragRemainsFullyVisible() = withOverlay(PerfHudDensity.CLASSIC) {
        scenario.onActivity {
            config.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.VERTICAL
            checkNotNull(manager).onConfigurationChanged()
        }
        waitForLayout()
        var left = 0
        var targetTop = 0
        var originalTextHeight = 0
        scenario.onActivity {
            left = overlay.left
            targetTop = container.height - overlay.height
            originalTextHeight = overlay.findViewById<TextView>(R.id.perfRes).height
        }
        dragOverlay(scenario, overlay, left, targetTop) {
            scenario.onActivity {
                overlay.findViewById<TextView>(R.id.perfRes).apply {
                    maxLines = 4
                    text = "wide\nwide\nwide\nwide"
                }
            }
            waitForLayout()
            scenario.onActivity {
                assertTrue("the content must grow during the drag",
                    overlay.findViewById<TextView>(R.id.perfRes).height > originalTextHeight)
            }
        }
        waitForLayout()
        scenario.onActivity {
            val tolerance = (16 * it.resources.displayMetrics.density).toInt()
            assertTrue("overlay should remain near the bottom", container.height - overlay.bottom <= tolerance)
            assertClassicContentVisible(container, overlay)
        }
    }

    @Test
    fun classicTextAlignsToDraggedSide() = withOverlay(PerfHudDensity.CLASSIC) {
        scenario.onActivity {
            config.perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.VERTICAL
            checkNotNull(manager).onConfigurationChanged()
        }
        waitForLayout()
        var targetLeft = 0
        var targetTop = 0
        scenario.onActivity {
            targetLeft = ((container.width - overlay.width) * 0.9f).toInt()
            targetTop = ((container.height - overlay.height) * 0.3f).toInt()
        }
        dragOverlay(scenario, overlay, targetLeft, targetTop)
        waitForLayout()
        scenario.onActivity {
            val item = overlay.findViewById<TextView>(R.id.perfRes)
            assertEquals(Gravity.END, item.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        }
    }

    @Test
    fun cancellingDragRestoresPositionAndPreferences() = withOverlay(PerfHudDensity.MINI) {
        dragToFreePosition(this)
        var originalLeft = 0
        var originalTop = 0
        var centerLeft = 0
        var savedPosition: Map<String, *> = emptyMap<String, Any>()
        scenario.onActivity {
            originalLeft = overlay.left
            originalTop = overlay.top
            centerLeft = (container.width - overlay.width) / 2
            savedPosition = overlayPrefs.all
            assertGuideVisible(container, false)
        }
        dragOverlay(scenario, overlay, centerLeft, originalTop, MotionEvent.ACTION_CANCEL) {
            scenario.onActivity {
                assertGuideVisible(container, true)
                val lp = overlay.layoutParams as FrameLayout.LayoutParams
                assertTrue("drag must change position before cancellation", lp.leftMargin != originalLeft)
            }
        }
        waitForLayout()
        scenario.onActivity {
            assertEquals("cancel should restore the horizontal position", originalLeft, overlay.left)
            assertEquals("cancel should restore the vertical position", originalTop, overlay.top)
            assertEquals("cancel should not change saved preferences", savedPosition, overlayPrefs.all)
            assertGuideVisible(container, false)
        }
    }

    @Test
    fun freePositionSurvivesPortraitAndLandscapeRotation() = withOverlay(PerfHudDensity.MINI) {
        val (relativeX, relativeY) = dragToFreePosition(this)
        for ((requested, expected) in listOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE
        )) {
            scenario.onActivity { it.requestedOrientation = requested }
            waitForOrientation(scenario, expected)
            // HelpActivity does not own the manager; forward the configuration change as Game does.
            scenario.onActivity { checkNotNull(manager).onConfigurationChanged() }
            waitForLayout()
            scenario.onActivity { assertRelativePosition(container, overlay, relativeX, relativeY) }
        }
    }

    private fun dragToFreePosition(fixture: OverlayFixture): Pair<Float, Float> = with(fixture) {
        var targetLeft = 0
        var targetTop = 0
        scenario.onActivity {
            val lp = overlay.layoutParams as FrameLayout.LayoutParams
            val availableWidth = container.width - container.paddingLeft - container.paddingRight -
                lp.rightMargin - overlay.width
            val availableHeight = container.height - container.paddingTop - container.paddingBottom -
                lp.bottomMargin - overlay.height
            assertTrue("overlay must have space to move", availableWidth > 0 && availableHeight > 0)
            targetLeft = container.paddingLeft + (availableWidth * 0.32f).toInt()
            targetTop = container.paddingTop + (availableHeight * 0.37f).toInt()
        }
        dragOverlay(scenario, overlay, targetLeft, targetTop)
        waitForLayout()
        var relativeX = 0f
        var relativeY = 0f
        scenario.onActivity {
            relativeX = relativeLeft(container, overlay)
            relativeY = relativeTop(container, overlay)
            assertEquals("drag must stay away from horizontal guides", 0.32f, relativeX, 0.02f)
            assertEquals("drag must stay away from vertical guides", 0.37f, relativeY, 0.02f)
        }
        relativeX to relativeY
    }

    private fun dragOverlay(
        scenario: ActivityScenario<HelpActivity>, overlay: View, targetLeft: Int, targetTop: Int,
        endAction: Int = MotionEvent.ACTION_UP,
        afterMove: ((Int, Int) -> Unit) -> Unit = {}
    ) {
        val location = IntArray(2)
        var startX = 0f
        var startY = 0f
        var endX = 0f
        var endY = 0f
        var originalLeft = 0
        var originalTop = 0
        scenario.onActivity {
            overlay.getLocationOnScreen(location)
            startX = location[0] + overlay.width / 2f
            startY = location[1] + overlay.height / 2f
            originalLeft = overlay.left
            originalTop = overlay.top
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("touch event must be delivered", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, startX, startY)
        SystemClock.sleep(40)
        val move: (Int, Int) -> Unit = { left, top ->
            endX = startX + left - originalLeft
            endY = startY + top - originalTop
            send(MotionEvent.ACTION_MOVE, endX, endY)
            SystemClock.sleep(50)
        }
        try {
            move(targetLeft, targetTop)
            afterMove(move)
        } finally {
            send(endAction, endX, endY)
        }
    }

    private fun relativeLeft(parent: View, overlay: View): Float {
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        val available = parent.width - parent.paddingLeft - parent.paddingRight - lp.rightMargin - overlay.width
        assertTrue("overlay must have horizontal space to move", available > 0)
        return (overlay.left - parent.paddingLeft).toFloat() / available
    }

    private fun relativeTop(parent: View, overlay: View): Float {
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        val available = parent.height - parent.paddingTop - parent.paddingBottom - lp.bottomMargin - overlay.height
        assertTrue("overlay must have vertical space to move", available > 0)
        return (overlay.top - parent.paddingTop).toFloat() / available
    }

    private fun assertRelativePosition(parent: View, overlay: View, x: Float, y: Float) {
        assertEquals("horizontal position should be preserved", x, relativeLeft(parent, overlay), 0.03f)
        assertEquals("vertical position should be preserved", y, relativeTop(parent, overlay), 0.03f)
    }

    @Test
    fun bottomCustomPositionKeepsBothStylesVisible() {
        checkBottomPosition(PreferenceConfiguration.PerfOverlayPosition.BOTTOM)
    }

    @Test
    fun bottomRightCustomPositionKeepsBothStylesVisible() {
        checkBottomPosition(PreferenceConfiguration.PerfOverlayPosition.BOTTOM_RIGHT)
    }

    private fun checkBottomPosition(position: PreferenceConfiguration.PerfOverlayPosition) =
        withOverlay(PerfHudDensity.CLASSIC, position) {
            var originalBottom = 0
            var edgeTolerance = 0
            var classicWidth = 0
            scenario.onActivity {
                assertTrue("classic overlay must be laid out", overlay.height > 0)
                assertHorizontalAnchor(container, overlay, position)
                originalBottom = overlay.bottom
                classicWidth = overlay.width
                edgeTolerance = (16 * it.resources.displayMetrics.density).toInt()
                // Simulate the legacy position saved after dragging to the bottom.
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
                        assertClassicContentVisible(container, overlay)
                    }
                    assertHorizontalAnchor(container, overlay, position)
                    assertTrue("overlay should remain at the bottom", overlay.bottom >= originalBottom - edgeTolerance)
                }
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

    private fun assertClassicContentVisible(parent: View, overlay: LinearLayout) {
        for (i in 0 until overlay.childCount) {
            val item = overlay.getChildAt(i)
            if (item is TextView && item.visibility == View.VISIBLE) {
                assertFullyVisible(parent, overlay, item)
            }
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

    private fun waitForOrientation(scenario: ActivityScenario<HelpActivity>, orientation: Int) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var reached = false
        while (!reached && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { reached = it.resources.configuration.orientation == orientation }
            if (!reached) Thread.sleep(100)
        }
        assertTrue("activity did not reach orientation $orientation", reached)
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
