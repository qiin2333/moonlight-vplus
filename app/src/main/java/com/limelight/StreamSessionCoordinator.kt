package com.limelight

/** Lifecycle state owned by the active stream session. */
internal enum class StreamSessionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    BACKGROUND_RESUME_PENDING,
    TERMINAL,
}

/** Why a connection is being stopped. The reason determines whether resume is valid. */
internal enum class StreamStopReason {
    BACKGROUND_RESUME,
    CONNECTION_TERMINATED,
    ACTIVITY_DESTROYED,
    SURFACE_DESTROYED,
    USER_REQUEST,
}

internal data class StreamStopDecision(val keepAliveService: Boolean)

/**
 * Keeps connection and background-resume state in one place.
 * This class has no Android dependencies so lifecycle ordering can be tested directly.
 */
internal class StreamSessionCoordinator {
    var state: StreamSessionState = StreamSessionState.IDLE
        private set

    private var backgroundResumeRequested = false
    private var pendingKeepAlivePreparation = false

    val isConnected: Boolean
        get() = state == StreamSessionState.CONNECTED

    val hasAttemptedConnection: Boolean
        get() = state == StreamSessionState.CONNECTING || isConnected

    val shouldResumeSession: Boolean
        get() = backgroundResumeRequested || state == StreamSessionState.BACKGROUND_RESUME_PENDING

    val isTerminal: Boolean
        get() = state == StreamSessionState.TERMINAL

    @Synchronized
    fun markConnectionAttempted() {
        if (state == StreamSessionState.IDLE) {
            state = StreamSessionState.CONNECTING
        }
    }

    /** Returns false for a stale callback from a stopped or terminal connection. */
    @Synchronized
    fun markConnectionEstablished(): Boolean {
        if (state != StreamSessionState.CONNECTING) return false
        state = StreamSessionState.CONNECTED
        backgroundResumeRequested = false
        return true
    }

    @Synchronized
    fun requestBackgroundResume(resumeEnabled: Boolean): Boolean {
        if (!resumeEnabled || state == StreamSessionState.IDLE || isTerminal) return false
        backgroundResumeRequested = true
        return true
    }

    fun shouldKeepConnectionInBackground(
        extremeResumeEnabled: Boolean,
        isFinishing: Boolean,
        resumeEnabled: Boolean,
    ): Boolean =
        extremeResumeEnabled && !isFinishing && resumeEnabled && isConnected

    fun shouldKeepAliveForBackgroundStop(
        isFinishing: Boolean,
        resumeEnabled: Boolean,
    ): Boolean =
        !isFinishing && resumeEnabled && shouldResumeSession && !isTerminal

    @Synchronized
    fun beginStop(
        reason: StreamStopReason,
        isFinishing: Boolean,
        resumeEnabled: Boolean,
    ): StreamStopDecision {
        val keepAliveService =
            reason == StreamStopReason.BACKGROUND_RESUME &&
                !isFinishing &&
                resumeEnabled &&
                !isTerminal

        pendingKeepAlivePreparation = false
        backgroundResumeRequested = false
        state = if (keepAliveService) {
            StreamSessionState.BACKGROUND_RESUME_PENDING
        } else {
            StreamSessionState.TERMINAL
        }
        return StreamStopDecision(keepAliveService)
    }

    @Synchronized
    fun prepareForReconnect(): Boolean {
        if (state != StreamSessionState.BACKGROUND_RESUME_PENDING) return false
        state = StreamSessionState.IDLE
        backgroundResumeRequested = false
        pendingKeepAlivePreparation = false
        return true
    }

    @Synchronized
    fun resumedWithActiveConnection() {
        if (isConnected) backgroundResumeRequested = false
    }

    @Synchronized
    fun markTerminalFailure() {
        state = StreamSessionState.TERMINAL
        backgroundResumeRequested = false
        pendingKeepAlivePreparation = false
    }

    @Synchronized
    fun deferKeepAlivePreparation() {
        if (isConnected) pendingKeepAlivePreparation = true
    }

    @Synchronized
    fun consumePendingKeepAlivePreparation(): Boolean {
        val shouldPrepare = pendingKeepAlivePreparation && isConnected
        pendingKeepAlivePreparation = false
        return shouldPrepare
    }
}
