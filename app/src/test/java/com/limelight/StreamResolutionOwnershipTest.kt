package com.limelight

import com.limelight.DynamicResolutionManager.Companion.DEBOUNCE_MS
import com.limelight.DynamicResolutionManager.Companion.IN_FLIGHT_TIMEOUT_MS
import com.limelight.DynamicResolutionManager.StreamSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-off between a menu hot switch and automatic dynamic resolution, driven the way
 * ResolutionSwitchController (begin/finish/stop/release/takeReconnectOnStart) and Game
 * (onHostReport, isApplying, isReconnecting) call it.
 */
class StreamResolutionOwnershipTest {
    private val scheduler = FakeScheduler()
    private val sent = mutableListOf<StreamSize>()
    /** Game.isChangingResolution: a reconnect replaces the Activity. */
    private var reconnecting = false
    private val ownership = StreamResolutionOwnership({ w, h ->
        sent += StreamSize(w, h)
        0
    }, { reconnecting }, scheduler)
    private val automatic get() = ownership.automatic

    private fun connect() {
        automatic.onConnected(1920, 1080, 100)
        hostReport(1920, 1080)
    }

    /** Game.onResolutionChanged; returns whether the rest of the stream may act on the report. */
    private fun hostReport(width: Int, height: Int, supported: Boolean = true): Boolean =
        ownership.onHostReport(width, height, width, height, supported).also { scheduler.advance(0) }

    private fun settle() = scheduler.advance(IN_FLIGHT_TIMEOUT_MS + DEBOUNCE_MS)

    @Test
    fun revealedSwitchSendsTheViewportHeldDuringItOnce() {
        connect()
        val attempt = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(attempt))
        automatic.request(2400, 1080)
        assertTrue(hostReport(1280, 720))
        settle()
        assertEquals(emptyList<StreamSize>(), sent)

        assertTrue(attempt.complete())
        assertTrue(ownership.finish(attempt))
        assertFalse(ownership.isApplying)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(2400, 1080)), sent)

        assertFalse(ownership.finish(attempt))
        settle()
        assertEquals(listOf(StreamSize(2400, 1080)), sent)
    }

    @Test
    fun revealedSwitchWithoutViewportChangeKeepsMenuChoice() {
        connect()
        val attempt = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(attempt))
        assertTrue(hostReport(1280, 720))
        assertTrue(attempt.complete())
        assertTrue(ownership.finish(attempt))
        settle()
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun startingSwitchDropsUnsentAutomaticSize() {
        connect()
        automatic.request(2400, 1080)
        scheduler.advance(DEBOUNCE_MS / 2)
        val attempt = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(attempt))
        settle()
        assertTrue(hostReport(1280, 720))
        assertTrue(attempt.complete())
        assertTrue(ownership.finish(attempt))
        settle()
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun hostReportAfterConfirmedFrameIsTrackedWhileStreamIgnoresIt() {
        connect()
        val attempt = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(attempt))
        automatic.request(1280, 720)
        assertTrue(attempt.complete())
        // The control-stream report lands during the reveal: the stream ignores it, but
        // automatic resolution must learn the new size so the held one isn't sent again.
        assertFalse(hostReport(1280, 720))
        assertTrue(ownership.finish(attempt))
        settle()
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun cancelDuringRevealReleasesOnceAndLateRevealCannotReleaseReplacement() {
        connect()
        val first = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(first))
        automatic.request(2400, 1080)
        assertTrue(hostReport(1280, 720))
        settle()
        assertEquals(emptyList<StreamSize>(), sent)
        assertTrue(first.complete())
        // onStop during the reveal: the switch already presented, so nothing reconnects.
        ownership.stop(isFinishing = false)
        assertTrue(ownership.release())
        assertFalse(ownership.isApplying)
        scheduler.advance(DEBOUNCE_MS)
        assertEquals(listOf(StreamSize(2400, 1080)), sent)
        assertFalse(ownership.release())

        val second = ResolutionSwitchSession(1600, 900)
        assertTrue(ownership.begin(second))
        automatic.request(1000, 800)
        assertFalse(ownership.finish(first))
        assertTrue(ownership.isApplying)
        settle()
        assertEquals(listOf(StreamSize(2400, 1080)), sent)
    }

    @Test
    fun switchStoppedWhileWaitingStaysHeldThroughItsReconnect() {
        connect()
        val attempt = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(attempt))
        automatic.request(2400, 1080)
        ownership.stop(isFinishing = false)
        assertTrue(ownership.release())
        assertTrue(ownership.isApplying)
        assertFalse(hostReport(1280, 720))
        settle()
        assertEquals(emptyList<StreamSize>(), sent)

        assertTrue(ownership.takeReconnectOnStart())
        reconnecting = true
        assertTrue(ownership.isApplying)
        settle()
        automatic.onDisconnected()
        settle()
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun finishingActivityDoesNotReconnectAStoppedSwitch() {
        connect()
        assertTrue(ownership.begin(ResolutionSwitchSession(1280, 720)))
        ownership.stop(isFinishing = true)
        assertTrue(ownership.release())
        assertFalse(ownership.takeReconnectOnStart())
        assertFalse(ownership.isApplying)
    }

    @Test
    fun fallbackReconnectKeepsAutomaticResolutionHeld() {
        connect()
        val attempt = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(attempt))
        automatic.request(2400, 1080)
        assertTrue(attempt.fallback())
        reconnecting = true
        // onStop(isReconnecting = true) leaves the attempt; onDestroy cancels it.
        assertTrue(ownership.release())
        assertTrue(ownership.isApplying)
        assertFalse(hostReport(1280, 720))
        assertFalse(ownership.begin(ResolutionSwitchSession(1600, 900)))
        settle()
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun onlyOneSwitchOwnsTheStream() {
        assertTrue(ownership.begin(ResolutionSwitchSession(1280, 720)))
        assertFalse(ownership.begin(ResolutionSwitchSession(1600, 900)))
    }

    @Test
    fun switchIgnoresReportsForOtherSizesUntilItEnds() {
        connect()
        val attempt = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(attempt))
        assertFalse(hostReport(2560, 1440))
        assertTrue(hostReport(1280, 720))
        assertTrue(attempt.complete())
        assertTrue(ownership.finish(attempt))
        assertTrue(hostReport(2560, 1440))
    }

    @Test
    fun streamActsOnReportsOnlyWhenNoSwitchOrReconnectClaimsThem() {
        assertTrue(hostReport(1280, 720))

        val waiting = ResolutionSwitchSession(1280, 720)
        assertTrue(ownership.begin(waiting))
        assertTrue(hostReport(1280, 720))
        assertFalse(hostReport(1600, 900))
        assertTrue(waiting.complete())
        assertFalse(hostReport(1280, 720))
        assertTrue(ownership.finish(waiting))

        assertTrue(ownership.begin(ResolutionSwitchSession(1280, 720)))
        ownership.stop(isFinishing = false)
        assertTrue(ownership.release())
        assertFalse(hostReport(1280, 720))
        assertTrue(ownership.takeReconnectOnStart())
        assertTrue(hostReport(1280, 720))

        reconnecting = true
        assertFalse(hostReport(1280, 720))
    }

    @Test
    fun automaticTracksEncodedSizeWhileSwitchMatchesBaseSize() {
        connect()
        val attempt = ResolutionSwitchSession(2560, 1440)
        assertTrue(ownership.begin(attempt))
        assertTrue(ownership.onHostReport(1280, 720, 2560, 1440, hostSupportsAutomatic = true))
        scheduler.advance(0)
        assertTrue(attempt.complete())
        assertTrue(ownership.finish(attempt))
        automatic.request(1280, 720)
        settle()
        assertEquals(emptyList<StreamSize>(), sent)
    }

    @Test
    fun reportsAreNotTrackedWithoutHostSupport() {
        automatic.onConnected(1920, 1080, 100)
        assertTrue(hostReport(1920, 1080, supported = false))
        // Without a stream-start report the host never counts as 1:1, so nothing is sent.
        automatic.request(2400, 1080)
        settle()
        assertEquals(emptyList<StreamSize>(), sent)
    }
}
