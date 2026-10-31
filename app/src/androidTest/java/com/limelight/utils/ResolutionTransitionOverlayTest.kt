package com.limelight.utils

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.view.SurfaceView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.limelight.HelpActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ResolutionTransitionOverlayTest {
    @get:Rule val activityRule = ActivityScenarioRule<HelpActivity>(
        Intent(ApplicationProvider.getApplicationContext(), HelpActivity::class.java)
            .setData(Uri.parse("about:blank"))
    )

    @After fun finishWithoutCrossPackageTrampoline() {
        if (activityRule.scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) {
            activityRule.scenario.onActivity { it.finish() }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test fun fallbackTransfersTheRetainedFrameWithoutRecyclingIt() {
        activityRule.scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val original = root.childCount
            val frame = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
            val overlay = ResolutionTransitionOverlay(activity, 2560, 1440)
            overlay.setFrame(frame)
            assertEquals(original + 1, root.childCount)
            overlay.reconnecting()
            assertSame(frame, overlay.takeBackdrop())
            assertNull(overlay.takeBackdrop())
            overlay.dispose()
            overlay.dispose()
            assertEquals(original, root.childCount)
            assertFalse(frame.isRecycled)
        }
    }

    @Test fun successfulRevealRemovesTheOverlayAndCompletesOnce() {
        val revealed = CountDownLatch(1)
        activityRule.scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val original = root.childCount
            val overlay = ResolutionTransitionOverlay(activity, 1280, 720)
            overlay.reveal {
                assertEquals(original, root.childCount)
                revealed.countDown()
            }
        }
        assertTrue(revealed.await(3, TimeUnit.SECONDS))
    }

    @Test fun unavailableSurfaceDoesNotDelayOrPreventTheRequest() {
        activityRule.scenario.onActivity { activity ->
            var calls = 0
            ResolutionTransitionOverlay.captureFrame(activity, SurfaceView(activity)) {
                assertNull(it)
                calls++
            }
            assertEquals(1, calls)
        }
    }
}
