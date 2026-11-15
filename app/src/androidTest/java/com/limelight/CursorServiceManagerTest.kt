package com.limelight

import android.content.Intent
import android.net.Uri
import android.view.PointerIcon
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.CursorView
import com.limelight.ui.StreamView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 24)
class CursorServiceManagerTest {
    @get:Rule
    val rule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @Test
    fun externalVideoCursorKeepsSystemPointerVisibleAfterLayoutChanges() {
        rule.scenario.onActivity { activity ->
            val root = FrameLayout(activity)
            val stream = StreamView(activity)
            val overlay = CursorView(activity)
            root.addView(stream, FrameLayout.LayoutParams(640, 360))
            root.addView(overlay, FrameLayout.LayoutParams(640, 360))
            activity.setContentView(root)
            val preferences = PreferenceConfiguration().apply {
                width = 640
                height = 360
                enableNativeMousePointer = true
                enableLocalCursorRendering = true
            }
            val manager = CursorServiceManager(
                stream, overlay, preferences, emptyArray(),
                object : CursorServiceManager.UiCallback {
                    override fun runOnUi(runnable: Runnable) = runnable.run()
                    override fun isActivityAlive(): Boolean = true
                    override fun onLocalCursorFallback() = error("External output uses video cursor directly")
                }
            )
            try {
                manager.setExternalDisplayActive(true)
                manager.onConnectionStarted()
                for ((width, height) in listOf(640 to 360, 1280 to 720)) {
                    stream.layout(0, 0, width, height)
                    val arrow = PointerIcon.getSystemIcon(activity, PointerIcon.TYPE_ARROW)
                    stream.pointerIcon = arrow
                    manager.syncCursorWithStream()
                    assertFalse(manager.isServiceRunning())
                    assertEquals(arrow, stream.pointerIcon)
                    assertEquals(View.GONE, overlay.visibility)
                }
                assertTrue(preferences.enableNativeMousePointer)
                assertTrue(preferences.enableLocalCursorRendering)
            } finally {
                manager.destroy()
            }
        }
    }
}
