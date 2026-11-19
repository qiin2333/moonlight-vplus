package com.limelight

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
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
import com.limelight.perfhud.PerfHudVisibility
import com.limelight.perfhud.PerfOverlayGestures
import com.limelight.preferences.PreferenceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceOverlayGestureIntegrationTest {
    private val overlayKeys = listOf(
        "layout_orientation", "hud_health_enabled", "hud_single_tap_style_enabled",
        "hud_double_tap_layout_enabled", "hud_long_press_info_enabled"
    )

    private fun withOverlay(
        initialStyle: PerfHudDensity = PerfHudDensity.STANDARD,
        setup: (SharedPreferences, SharedPreferences) -> Unit = { _, _ -> },
        block: (ActivityScenario<HelpActivity>, PerformanceOverlayManager, PreferenceConfiguration) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val overlayPrefs = context.getSharedPreferences("performance_overlay", Context.MODE_PRIVATE)
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        val savedOverlay = overlayKeys.associateWith { overlayPrefs.all[it] }
        val savedDefaults = defaults.all.mapValues { it.value as Any? }
        var manager: PerformanceOverlayManager? = null
        try {
            overlayPrefs.edit().also { editor -> overlayKeys.forEach { editor.remove(it) } }.commit()
            defaults.edit().remove(PerfOverlayGestures.KEY).remove("perf_overlay_health_migrated").commit()
            defaults.edit().putStringSet("perf_overlay_display_items", setOf("resolution", "decoder")).commit()
            setup(overlayPrefs, defaults)
            val config = PreferenceConfiguration().apply {
                enablePerfOverlay = true
                perfOverlayStyle = initialStyle
                perfOverlayOrientation = PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL
            }
            val intent = Intent(context, HelpActivity::class.java).setData(Uri.parse("about:blank"))
            ActivityScenario.launch<HelpActivity>(intent).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        val root = FrameLayout(activity)
                        activity.layoutInflater.inflate(R.layout.activity_game, root, true)
                        activity.setContentView(root)
                        manager = PerformanceOverlayManager(activity, config).also {
                            it.initialize()
                            it.applyOverlayState()
                        }
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    block(scenario, checkNotNull(manager), config)
                } finally {
                    scenario.onActivity { manager?.cancelPendingTasks() }
                }
            }
        } finally {
            restore(overlayPrefs, savedOverlay)
            val extraDefaults = defaults.all.keys - savedDefaults.keys
            restore(defaults, savedDefaults + extraDefaults.associateWith { null })
        }
    }

    @Test
    fun gestureCancellationOnLockAndConfigurationChange() = withOverlay { scenario, manager, config ->
        scenario.onActivity { activity ->
            assertTrue(manager.perfOverlaySingleTapEnabled)
            assertTrue(manager.perfOverlayDoubleTapEnabled)
            assertTrue(manager.perfOverlayLongPressEnabled)
            assertTrue(manager.perfOverlayHealthVisible)
            val overlay = activity.findViewById<LinearLayout>(R.id.performanceOverlay)
            manager.setPerfOverlayGestureMode(PerfOverlayGestures.LONG_ONLY)
            tap(overlay)
            assertEquals(PerfHudDensity.STANDARD, config.perfOverlayStyle)
            manager.setPerfOverlayGestureMode(PerfOverlayGestures.FULL)
            tap(overlay)
            config.perfOverlayLocked = true
            manager.applyOverlayState()
        }
        SystemClock.sleep(400)
        scenario.onActivity { activity ->
            assertEquals(PerfHudDensity.STANDARD, config.perfOverlayStyle)
            config.perfOverlayLocked = false
            manager.applyOverlayState()
            tap(activity.findViewById(R.id.performanceOverlay))
            manager.onConfigurationChanged()
        }
        SystemClock.sleep(400)
        scenario.onActivity { assertEquals(PerfHudDensity.STANDARD, config.perfOverlayStyle) }
    }

    @Test
    fun cancelHideAndDestroyCancelScheduledSingleTaps() = withOverlay { scenario, manager, config ->
        scenario.onActivity { activity ->
            val overlay = activity.findViewById<LinearLayout>(R.id.performanceOverlay)
            manager.setPerfOverlayGestureMode(PerfOverlayGestures.FULL)
            tap(overlay)
            val now = SystemClock.uptimeMillis()
            // Dispatch the next gesture without waiting for another emulator frame.
            // MotionEvent times still have to be monotonic for double-tap recognition.
            val eventTime = now + 20
            val x = (overlay.width / 2f).coerceAtLeast(1f)
            val y = (overlay.height / 2f).coerceAtLeast(1f)
            val down = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val cancel = MotionEvent.obtain(eventTime, eventTime + 10, MotionEvent.ACTION_CANCEL, x, y, 0)
            try {
                overlay.dispatchTouchEvent(down)
                overlay.dispatchTouchEvent(cancel)
            } finally {
                down.recycle()
                cancel.recycle()
            }
        }
        SystemClock.sleep(400)
        scenario.onActivity { activity ->
            assertEquals(PerfHudDensity.STANDARD, config.perfOverlayStyle)
            tap(activity.findViewById(R.id.performanceOverlay))
            config.enablePerfOverlay = false
            manager.applyOverlayState()
        }
        SystemClock.sleep(400)
        scenario.onActivity { activity ->
            assertEquals(PerfHudDensity.STANDARD, config.perfOverlayStyle)
            config.enablePerfOverlay = true
            manager.applyOverlayState()
            tap(activity.findViewById(R.id.performanceOverlay))
            manager.cancelPendingTasks()
        }
        SystemClock.sleep(400)
        scenario.onActivity { assertEquals(PerfHudDensity.STANDARD, config.perfOverlayStyle) }
    }

    @Test
    fun classicEmptySelectionKeepsMoonAnchorAndHiddenChangesRefreshOnShow() =
        withOverlay(initialStyle = PerfHudDensity.CLASSIC) { scenario, manager, config ->
            scenario.onActivity { activity ->
                val overlay = activity.findViewById<LinearLayout>(R.id.performanceOverlay)
                val resolution = activity.findViewById<TextView>(R.id.perfRes)
                manager.setPerfOverlayDisplayItems(emptySet())
                assertEquals(View.VISIBLE, resolution.visibility)
                assertTrue(resolution.text.isNotEmpty())
                assertTrue(resolution.contentDescription.isNotEmpty())
                assertTrue(overlay.isClickable)
                config.enablePerfOverlay = false
                manager.applyOverlayState()
                manager.setPerfOverlayDisplayItems(setOf("decoder"))
                manager.setPerfOverlayHealthVisible(false)
                config.enablePerfOverlay = true
                manager.applyOverlayState()
                assertEquals(View.GONE, resolution.visibility)
                assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.perfDecoder).visibility)
                manager.setPerfOverlayStyle(PerfHudDensity.STANDARD)
                val hud = activity.findViewById<PerfHudView>(R.id.perfHud)
                val field = PerfHudView::class.java.getDeclaredField("visibility").apply { isAccessible = true }
                val visibility = field.get(hud) as PerfHudVisibility
                assertFalse(visibility.health)
                assertTrue(visibility.decoder)
                assertFalse(visibility.resolution)
            }
        }

    @Test
    fun legacyDirectionMigratesOnceAndDefaultPreferenceWinsLater() = withOverlay(
        setup = { overlayPrefs, defaults ->
            overlayPrefs.edit().putString("layout_orientation", "VERTICAL").commit()
            defaults.edit().putString("list_perf_overlay_orientation", "horizontal").commit()
        }
    ) { scenario, manager, config ->
        scenario.onActivity { activity ->
            val legacy = activity.getSharedPreferences("performance_overlay", Context.MODE_PRIVATE)
            val defaults = PreferenceManager.getDefaultSharedPreferences(activity)
            assertEquals(PreferenceConfiguration.PerfOverlayOrientation.VERTICAL, config.perfOverlayOrientation)
            assertEquals("vertical", defaults.getString("list_perf_overlay_orientation", null))
            assertFalse(legacy.contains("layout_orientation"))
            manager.setPerfOverlayOrientation(PreferenceConfiguration.PerfOverlayOrientation.HORIZONTAL)
            config.writePreferences(activity)
            assertEquals("horizontal", defaults.getString("list_perf_overlay_orientation", null))
            defaults.edit().putString("list_perf_overlay_orientation", "vertical").commit()
            val nextConfig = PreferenceConfiguration.readPreferences(activity)
            val next = PerformanceOverlayManager(activity, nextConfig)
            try {
                next.initialize()
                assertEquals(PreferenceConfiguration.PerfOverlayOrientation.VERTICAL, nextConfig.perfOverlayOrientation)
                assertFalse(legacy.contains("layout_orientation"))
            } finally {
                next.cancelPendingTasks()
            }
        }
    }

    private fun tap(view: View) {
        val now = SystemClock.uptimeMillis()
        val x = (view.width / 2f).coerceAtLeast(1f)
        val y = (view.height / 2f).coerceAtLeast(1f)
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, Any?>) {
        val editor = prefs.edit()
        for ((key, value) in values) {
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unexpected preference type for $key")
            }
        }
        editor.commit()
    }
}
