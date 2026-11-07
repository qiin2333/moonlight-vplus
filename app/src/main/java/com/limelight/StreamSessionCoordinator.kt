package com.limelight

internal enum class StreamSessionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    BACKGROUND_RESUME_PENDING,
    TERMINAL,
}

/** Why the connection is stopping; only background resume retains the service. */
internal enum class StreamStopReason {
    BACKGROUND_RESUME,
    CONNECTION_TERMINATED,
    ACTIVITY_DESTROYED,
    SURFACE_DESTROYED,
    USER_REQUEST,
}

internal data class StreamStopDecision(val keepAliveService: Boolean)

/**
 * Owns session transitions on the UI thread, independently of Android resources.
 * Native callbacks may read connection state, but must post transitions to the UI thread.
 */
internal class StreamSessionCoordinator {
    @Volatile
    var state: StreamSessionState = StreamSessionState.IDLE
        private set

    private var pendingKeepAlivePreparation = false

    val isConnected: Boolean
        get() = state == StreamSessionState.CONNECTED

    val hasAttemptedConnection: Boolean
        get() = state == StreamSessionState.CONNECTING || isConnected

    val canStartConnection: Boolean
        get() = state == StreamSessionState.IDLE

    val shouldResumeSession: Boolean
        get() = state == StreamSessionState.BACKGROUND_RESUME_PENDING

    val isTerminal: Boolean
        get() = state == StreamSessionState.TERMINAL

    /** Claims one startup; Surface callbacks cannot restart a stopped connection. */
    fun markConnectionAttempted(): Boolean {
        if (!canStartConnection) return false
        state = StreamSessionState.CONNECTING
        return true
    }

    /** Only the current connection's accepted callback may complete startup. */
    fun markConnectionEstablished(): Boolean {
        if (state != StreamSessionState.CONNECTING) return false
        state = StreamSessionState.CONNECTED
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
    ): Boolean = !isFinishing && resumeEnabled && !isTerminal

    /** Repeated background stops preserve pending resume; terminal stops are irreversible. */
    fun beginStop(
        reason: StreamStopReason,
        isFinishing: Boolean,
        resumeEnabled: Boolean,
    ): StreamStopDecision {
        val keepAliveService = reason == StreamStopReason.BACKGROUND_RESUME &&
            shouldKeepAliveForBackgroundStop(isFinishing, resumeEnabled)

        pendingKeepAlivePreparation = false
        state = if (keepAliveService) {
            StreamSessionState.BACKGROUND_RESUME_PENDING
        } else {
            StreamSessionState.TERMINAL
        }
        return StreamStopDecision(keepAliveService)
    }

    /** Allows startup after the replacement connection and renderer are prepared. */
    fun prepareForReconnect(): Boolean {
        if (!shouldResumeSession) return false
        state = StreamSessionState.IDLE
        return true
    }

    fun deferKeepAlivePreparation() {
        if (isConnected) pendingKeepAlivePreparation = true
    }

    fun consumePendingKeepAlivePreparation(): Boolean {
        val shouldPrepare = pendingKeepAlivePreparation && isConnected
        pendingKeepAlivePreparation = false
        return shouldPrepare
    }
}
