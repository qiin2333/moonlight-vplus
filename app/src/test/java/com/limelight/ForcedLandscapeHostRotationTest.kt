package com.limelight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForcedLandscapeHostRotationTest {
    @Test
    fun requestsOnceForConfirmedPortraitAndWaitsForConnection() {
        val state = ForcedLandscapeHostRotation()
        assertFalse(state.shouldRequest(true, false, false))
        assertTrue(state.shouldRequest(true, true, false))
        assertTrue(state.inFlight)
        assertFalse(state.shouldRequest(true, true, false))
        state.complete(state.observationVersion, success = false)
        // A failure or old portrait frames must not cause an endless retry loop.
        assertFalse(state.shouldRequest(true, true, false))
        // A confirmed change to landscape and back is a new portrait state.
        assertFalse(state.shouldRequest(true, true, true))
        assertTrue(state.shouldRequest(true, true, false))
    }

    @Test
    fun waitsForAnInflightRequestAcrossWindowTransitions() {
        val state = ForcedLandscapeHostRotation()
        assertFalse(state.shouldRequest(false, true, false))
        assertTrue(state.shouldRequest(true, true, false))
        state.leaveWindow()
        assertFalse(state.shouldRequest(true, true, false))
        state.complete(state.observationVersion, success = false)
        assertTrue(state.shouldRequest(true, true, false))
        state.reset()
        assertFalse(state.inFlight)
        assertTrue(state.shouldRequest(true, true, false))
    }

    @Test
    fun failedOldRequestRetriesOnceAfterLeavingAndReentering() {
        val state = ForcedLandscapeHostRotation()
        state.observeHostOrientation()
        assertTrue(state.shouldRequest(true, true, false))
        val firstRequestVersion = state.observationVersion
        state.leaveWindow()
        assertFalse(state.shouldRequest(true, true, false))
        assertTrue(state.complete(firstRequestVersion, success = false))
        // Completion re-evaluates the current window, enabling its one retry.
        assertTrue(state.shouldRequest(true, true, false))
        state.complete(state.observationVersion, success = false)
        assertFalse(state.shouldRequest(true, true, false))
    }

    @Test
    fun newerHostObservationWinsOverOldSuccessfulResult() {
        val state = ForcedLandscapeHostRotation()
        state.observeHostOrientation()
        assertTrue(state.shouldRequest(true, true, false))
        val requestVersion = state.observationVersion
        // A newer portrait frame is authoritative even if the old request succeeds.
        state.observeHostOrientation()
        assertFalse(state.shouldRequest(true, true, false))
        assertFalse(state.complete(requestVersion, success = true))
        assertTrue(state.shouldRequest(true, true, false))

        // Repeated newer portrait notifications cannot produce an endless loop.
        val retryVersion = state.observationVersion
        state.observeHostOrientation()
        assertFalse(state.complete(retryVersion, success = true))
        assertFalse(state.shouldRequest(true, true, false))

        // A subsequent actual landscape-to-portrait transition may request once.
        state.observeHostOrientation()
        assertFalse(state.shouldRequest(true, true, true))
        state.observeHostOrientation()
        assertTrue(state.shouldRequest(true, true, false))
    }

    @Test
    fun failedRequestDoesNotRetryNewerPortraitNotification() {
        val state = ForcedLandscapeHostRotation()
        state.observeHostOrientation()
        assertTrue(state.shouldRequest(true, true, false))
        val requestVersion = state.observationVersion
        state.observeHostOrientation()
        assertFalse(state.shouldRequest(true, true, false))
        assertFalse(state.complete(requestVersion, success = false))
        assertFalse(state.shouldRequest(true, true, false))
    }
}
