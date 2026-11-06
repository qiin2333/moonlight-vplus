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
        session.markConnectionAttempted()

        assertTrue(session.markConnectionEstablished())
        assertEquals(StreamSessionState.CONNECTED, session.state)
    }

    @Test
    fun staleConnectionCallbackIsRejectedAfterStop() {
        val session = connectedSession()
        session.beginStop(StreamStopReason.CONNECTION_TERMINATED, false, true)

        assertFalse(session.markConnectionEstablished())
        assertEquals(StreamSessionState.TERMINAL, session.state)
    }

    @Test
    fun backgroundResumeKeepsServiceAndAllowsReconnect() {
        val session = connectedSession()
        session.requestBackgroundResume(resumeEnabled = true)

        assertTrue(session.shouldKeepAliveForBackgroundStop(false, true))
        assertTrue(
            session.beginStop(
                StreamStopReason.BACKGROUND_RESUME,
                isFinishing = false,
                resumeEnabled = true,
            ).keepAliveService
        )
        assertEquals(StreamSessionState.BACKGROUND_RESUME_PENDING, session.state)
        assertFalse(session.markConnectionEstablished())

        assertTrue(session.prepareForReconnect())
        session.markConnectionAttempted()
        assertTrue(session.markConnectionEstablished())
    }

    @Test
    fun extremeResumeKeepsOnlyAnActiveSessionInBackground() {
        val session = connectedSession()

        assertTrue(session.shouldKeepConnectionInBackground(true, false, true))
        session.markTerminalFailure()
        assertFalse(session.shouldKeepConnectionInBackground(true, false, true))
    }

    @Test
    fun terminalFailureCannotStartBackgroundKeepAliveOrAcceptOldCallback() {
        val session = connectedSession()
        session.markTerminalFailure()

        assertFalse(session.shouldKeepConnectionInBackground(true, false, true))
        assertFalse(session.beginStop(StreamStopReason.BACKGROUND_RESUME, false, true).keepAliveService)
        assertFalse(session.markConnectionEstablished())
    }

    @Test
    fun pausedConnectionPreparationIsConsumedOnceAfterResume() {
        val session = connectedSession()
        session.deferKeepAlivePreparation()

        assertTrue(session.consumePendingKeepAlivePreparation())
        assertFalse(session.consumePendingKeepAlivePreparation())
    }

    private fun connectedSession(): StreamSessionCoordinator =
        StreamSessionCoordinator().also {
            it.markConnectionAttempted()
            check(it.markConnectionEstablished())
        }
}
