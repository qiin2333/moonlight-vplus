package com.limelight

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import com.limelight.DynamicResolutionManager.Companion.DEBOUNCE_MS
import com.limelight.DynamicResolutionManager.StreamSize
import com.limelight.nvstream.DisplayRotator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the real OrientationManager with a fake Activity, rotate-display and scheduler. */
class OrientationManagerTest {
    private class FakeActivity : Activity() {
        val config = Configuration()
        val requestedOrientations = mutableListOf<Int>()

        @Suppress("DEPRECATION")
        private val fakeResources = object : Resources(null, null, null) {
            override fun getConfiguration(): Configuration = config
        }

        override fun getResources(): Resources = fakeResources

        override fun setRequestedOrientation(requestedOrientation: Int) {
            requestedOrientations += requestedOrientation
        }
    }

    private val scheduler = FakeScheduler()
    private val activity = FakeActivity()
    private val rotations = mutableListOf<Int>()
    private val rotator = DisplayRotator { angle, _ -> rotations += angle }
    private var follows = false
    private var predicateCalls = 0
    private val manager = OrientationManager(
        activity, 1920, 1080, true, false, scheduler = scheduler,
    ) { null }.apply {
        connection = rotator
        connected = true
        resolutionFollowsViewport = { predicateCalls++; follows }
    }

    /** The device turned (or Android re-reported the same orientation). */
    private fun rotate(landscape: Boolean) {
        activity.config.orientation =
            if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        manager.onConfigurationChanged()
    }

    /** Settles on [landscape] and forgets what that took. */
    private fun startAt(landscape: Boolean) {
        rotate(landscape)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        rotations.clear()
        predicateCalls = 0
        activity.requestedOrientations.clear()
    }

    @Test
    fun firstObservedOrientationIsSentAfterTheDebounce() {
        rotate(landscape = false)
        scheduler.advance(ROTATION_DEBOUNCE_MS - 1)
        assertEquals(emptyList<Int>(), rotations)
        scheduler.advance(1)
        assertEquals(listOf(90), rotations)
    }

    @Test
    fun rotationIsSentWithItsAngleAfterTheDebounce() {
        startAt(landscape = true)
        rotate(landscape = false)
        scheduler.advance(ROTATION_DEBOUNCE_MS - 1)
        assertEquals(emptyList<Int>(), rotations)
        scheduler.advance(1)
        rotate(landscape = true)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        assertEquals(listOf(90, 0), rotations)
    }

    @Test
    fun newerRotationReplacesThePendingOne() {
        startAt(landscape = true)
        rotate(landscape = false)
        scheduler.advance(1000)
        rotate(landscape = true)
        scheduler.advance(ROTATION_DEBOUNCE_MS * 2)
        assertEquals(listOf(0), rotations)
        assertEquals(1, predicateCalls)
    }

    @Test
    fun unchangedOrientationSchedulesNothing() {
        startAt(landscape = true)
        rotate(landscape = true)
        scheduler.advance(ROTATION_DEBOUNCE_MS * 2)
        assertEquals(emptyList<Int>(), rotations)
        assertEquals(0, predicateCalls)
    }

    @Test
    fun serverResolutionChangeCancelsThePendingRotation() {
        startAt(landscape = true)
        rotate(landscape = false)
        scheduler.advance(1000)
        manager.onServerResolutionChanged(isLandscape = true)
        scheduler.advance(ROTATION_DEBOUNCE_MS * 2)
        assertEquals(emptyList<Int>(), rotations)
        assertEquals(0, predicateCalls)
    }

    @Test
    fun repeatedServerChangeRestartsTheSettleTimer() {
        manager.onServerResolutionChanged(isLandscape = true)
        scheduler.advance(500)
        manager.onServerResolutionChanged(isLandscape = false)
        scheduler.advance(SERVER_ROTATION_SETTLE_MS - 1)
        assertTrue(manager.isServerInitiatedRotation)
        assertEquals(
            listOf(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT),
            activity.requestedOrientations,
        )
        scheduler.advance(1)
        assertFalse(manager.isServerInitiatedRotation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientations.last())
        assertEquals(3, activity.requestedOrientations.size)
    }

    @Test
    fun settledServerRotationCancelsItsSafetyTimer() {
        manager.onServerResolutionChanged(isLandscape = true)
        manager.onConfigurationChanged()
        scheduler.advance(SERVER_ROTATION_SETTLE_MS * 2)
        assertEquals(
            listOf(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
            activity.requestedOrientations,
        )
    }

    @Test
    fun cleanupCancelsTheSettleTimer() {
        manager.onServerResolutionChanged(isLandscape = true)
        manager.cleanup()
        scheduler.advance(SERVER_ROTATION_SETTLE_MS * 2)
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE), activity.requestedOrientations)
    }

    @Test
    fun ownershipIsReadWhenRotateDisplayIsDue() {
        startAt(landscape = true)
        follows = false
        rotate(landscape = false)
        assertEquals(0, predicateCalls)
        scheduler.advance(1000)
        follows = true
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        assertEquals(emptyList<Int>(), rotations)
        assertEquals(1, predicateCalls)

        rotate(landscape = true)
        scheduler.advance(1000)
        follows = false
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        assertEquals(listOf(0), rotations)
        assertEquals(2, predicateCalls)
    }

    @Test
    fun skippedRotationIsHandedBackOnceWithItsAngle() {
        startAt(landscape = true)
        follows = true
        rotate(landscape = false)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        assertEquals(emptyList<Int>(), rotations)
        // Android may report a locked orientation meanwhile; the skipped angle still wins.
        activity.config.orientation = Configuration.ORIENTATION_LANDSCAPE
        manager.onRotationReturned()
        assertEquals(listOf(90), rotations)
        manager.onRotationReturned()
        assertEquals(listOf(90), rotations)
    }

    @Test
    fun nothingIsHandedBackWithoutASkippedRotation() {
        startAt(landscape = true)
        follows = false
        rotate(landscape = false)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        assertEquals(listOf(90), rotations)
        manager.onRotationReturned()
        assertEquals(listOf(90), rotations)
    }

    @Test
    fun newerRotationDropsTheSkippedAngle() {
        startAt(landscape = true)
        follows = true
        rotate(landscape = false)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        rotate(landscape = true)
        manager.onRotationReturned()
        assertEquals(emptyList<Int>(), rotations)
    }

    @Test
    fun resetDropsTheSkippedAngle() {
        startAt(landscape = true)
        follows = true
        rotate(landscape = false)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        manager.reset()
        manager.onRotationReturned()
        assertEquals(emptyList<Int>(), rotations)
    }

    @Test
    fun cleanupDropsTheSkippedAngle() {
        startAt(landscape = true)
        follows = true
        rotate(landscape = false)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        manager.cleanup()
        manager.onRotationReturned()
        assertEquals(emptyList<Int>(), rotations)
    }

    @Test
    fun disconnectedStreamIsNotRotatedByHandBack() {
        startAt(landscape = true)
        follows = true
        rotate(landscape = false)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        manager.connected = false
        manager.onRotationReturned()
        manager.connected = true
        manager.onRotationReturned()
        assertEquals(emptyList<Int>(), rotations)
    }

    // Wired like Game: the real ownership decides at the debounced rotate-display and hands a
    // rotation back if the size carrying it fails to send.

    private val ownershipScheduler = FakeScheduler()
    private val sent = mutableListOf<StreamSize>()
    private var sendResult = 0
    private val ownership = StreamResolutionOwnership({ w, h ->
        sent += StreamSize(w, h)
        sendResult
    }, { false }, ownershipScheduler)

    private fun wireOwnershipLikeGame() {
        manager.resolutionFollowsViewport = { ownership.followsRotation(true) }
        ownership.onRotationReturned = manager::onRotationReturned
        ownership.automatic.onConnected(1920, 1080, 100)
        ownership.onHostReport(1920, 1080, 1920, 1080, hostSupportsAutomatic = true)
        ownershipScheduler.advance(0)
        startAt(landscape = true)
    }

    /** A rotates the stream and stays unanswered; the rotated window size B waits behind it. */
    private fun rotateWhileARequestIsUnanswered() {
        ownership.automatic.request(1600, 1200)
        ownershipScheduler.advance(DEBOUNCE_MS)
        rotate(landscape = false)
        ownership.automatic.request(1080, 2400)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        assertEquals(emptyList<Int>(), rotations)
        ownership.onHostReport(1600, 1200, 1600, 1200, hostSupportsAutomatic = true)
        ownershipScheduler.advance(0)
        ownershipScheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1600, 1200), StreamSize(1080, 2400)), sent)
    }

    @Test
    fun failedSendOfTheRotatedSizeRotatesTheHostOnce() {
        wireOwnershipLikeGame()
        sendResult = 0
        ownership.automatic.request(1600, 1200)
        ownershipScheduler.advance(DEBOUNCE_MS)
        sendResult = -4
        rotate(landscape = false)
        ownership.automatic.request(1080, 2400)
        scheduler.advance(ROTATION_DEBOUNCE_MS)
        assertEquals(emptyList<Int>(), rotations)
        ownership.onHostReport(1600, 1200, 1600, 1200, hostSupportsAutomatic = true)
        ownershipScheduler.advance(0)
        ownershipScheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(1600, 1200), StreamSize(1080, 2400)), sent)
        assertEquals(listOf(90), rotations)

        ownership.automatic.request(2400, 1080)
        ownershipScheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(90), rotations)
    }

    @Test
    fun sentRotatedSizeLeavesTheHostToAutomaticResolution() {
        wireOwnershipLikeGame()
        rotateWhileARequestIsUnanswered()
        assertEquals(emptyList<Int>(), rotations)
    }

    private companion object {
        const val ROTATION_DEBOUNCE_MS = 3000L
        const val SERVER_ROTATION_SETTLE_MS = 1000L
    }
}
