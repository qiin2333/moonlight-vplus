package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSessionCoordinatorTest {
    @Test
    fun connectionMustBeAttemptedBeforeItCanBecomeConnected() {
        val session = StreamSessionCoordinator()

        assertFalse(session.markConnectionEstablished())
        assertTrue(session.markConnectionAttempted())
        assertFalse(session.markConnectionAttempted())
        assertTrue(session.markConnectionEstablished())
        assertFalse(session.markConnectionEstablished())
        assertEquals(StreamSessionState.CONNECTED, session.state)
    }

    @Test
    fun backgroundResumeAllowsExactlyOneReconnect() {
        val session = connectedSession()

        assertTrue(stopForBackground(session))
        assertEquals(StreamSessionState.BACKGROUND_RESUME_PENDING, session.state)
        assertFalse(session.markConnectionEstablished())
        assertFalse(session.markConnectionAttempted())

        assertTrue(session.prepareForReconnect())
        assertFalse(session.prepareForReconnect())
        assertTrue(session.markConnectionAttempted())
        assertTrue(session.markConnectionEstablished())
    }

    @Test
    fun surfaceLossBeforeOnStopPreservesResumeAcrossBothStops() {
        val session = connectedSession()

        // Surface loss after onPause uses the background stop reason. onStop follows.
        assertTrue(stopForBackground(session))
        assertFalse(session.markConnectionAttempted())
        assertTrue(stopForBackground(session))
        assertTrue(session.prepareForReconnect())
    }

    @Test
    fun onStopBeforeSurfaceLossDoesNotLeaveAnActiveConnectionToStop() {
        val session = connectedSession()

        assertTrue(stopForBackground(session))
        assertFalse(session.hasAttemptedConnection)
        assertTrue(session.shouldResumeSession)
        assertFalse(session.canStartConnection)
    }

    @Test
    fun startupCanBeStoppedForBackgroundResume() {
        val session = StreamSessionCoordinator()
        session.markConnectionAttempted()

        assertTrue(stopForBackground(session))
        assertFalse(session.markConnectionEstablished())
        assertTrue(session.prepareForReconnect())
    }

    @Test
    fun backgroundBeforeFirstSurfaceCanResumePreparedSession() {
        val session = StreamSessionCoordinator()

        assertTrue(stopForBackground(session))
        assertTrue(session.prepareForReconnect())
        assertTrue(session.markConnectionAttempted())
    }

    @Test
    fun extremeResumeKeepsActiveConnectionWithoutSchedulingReconnect() {
        val session = connectedSession()

        assertTrue(session.shouldKeepConnectionInBackground(true, false, true))
        assertFalse(session.shouldResumeSession)
        assertFalse(session.prepareForReconnect())
    }

    @Test
    fun extremeResumeDoesNotKeepAnUnestablishedConnection() {
        val session = StreamSessionCoordinator()
        session.markConnectionAttempted()

        assertFalse(session.shouldKeepConnectionInBackground(true, false, true))
    }

    @Test
    fun terminalReasonsCannotBeUndoneByBackgroundOrSurfaceCallbacks() {
        for (reason in StreamStopReason.entries.filter { it != StreamStopReason.BACKGROUND_RESUME }) {
            val session = connectedSession()
            session.deferKeepAlivePreparation()

            assertFalse(session.beginStop(reason, false, true).keepAliveService)
            assertFalse(session.shouldKeepAliveForBackgroundStop(false, true))
            assertFalse(session.shouldKeepConnectionInBackground(true, false, true))
            assertFalse(stopForBackground(session))
            assertFalse(session.markConnectionAttempted())
            assertFalse(session.markConnectionEstablished())
            assertFalse(session.prepareForReconnect())
            assertFalse(session.consumePendingKeepAlivePreparation())
            assertEquals(StreamSessionState.TERMINAL, session.state)
        }
    }

    @Test
    fun finishOrDisabledResumeEndsSession() {
        val finishing = connectedSession()
        assertFalse(finishing.beginStop(StreamStopReason.BACKGROUND_RESUME, true, true).keepAliveService)
        assertFalse(finishing.shouldResumeSession)

        val disabled = connectedSession()
        assertFalse(disabled.beginStop(StreamStopReason.BACKGROUND_RESUME, false, false).keepAliveService)
        assertFalse(disabled.shouldResumeSession)
    }

    @Test
    fun activityDestructionCancelsPendingResume() {
        val session = connectedSession()
        stopForBackground(session)

        assertFalse(session.beginStop(StreamStopReason.ACTIVITY_DESTROYED, false, true).keepAliveService)
        assertFalse(session.prepareForReconnect())
    }

    @Test
    fun pausedConnectionPreparationIsConsumedOnceAfterResume() {
        val session = connectedSession()
        session.deferKeepAlivePreparation()
        session.deferKeepAlivePreparation()

        assertTrue(session.consumePendingKeepAlivePreparation())
        assertFalse(session.consumePendingKeepAlivePreparation())
    }

    @Test
    fun pausedPreparationDoesNotSurviveAStoppedConnection() {
        val session = connectedSession()
        session.deferKeepAlivePreparation()
        stopForBackground(session)
        session.deferKeepAlivePreparation()
        session.prepareForReconnect()
        session.markConnectionAttempted()
        session.markConnectionEstablished()

        assertFalse(session.consumePendingKeepAlivePreparation())
    }

    private fun stopForBackground(session: StreamSessionCoordinator): Boolean =
        session.beginStop(StreamStopReason.BACKGROUND_RESUME, false, true).keepAliveService

    private fun connectedSession(): StreamSessionCoordinator =
        StreamSessionCoordinator().also {
            check(it.markConnectionAttempted())
            check(it.markConnectionEstablished())
        }
}
