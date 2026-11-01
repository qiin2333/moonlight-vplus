package com.limelight

/**
 * Decides who may change the stream resolution: a menu hot switch ([ResolutionSwitchController])
 * or automatic dynamic resolution ([automatic]). Kept free of Android types so the hand-off can be
 * unit-tested. Called on the UI thread, except [onHostReport], which the native control thread calls.
 *
 * A switch, a reconnect that replaces it, or a switch interrupted in the background holds automatic
 * requests. Starting a switch drops an automatic size that hasn't been sent yet, as the menu choice
 * is newer. When a switch ends without a reconnect, the latest size requested meanwhile is sent.
 */
internal class StreamResolutionOwnership(
    sendRequest: (width: Int, height: Int) -> Int,
    private val isReconnecting: () -> Boolean,
    scheduler: DynamicResolutionManager.Scheduler = DynamicResolutionManager.MainLooperScheduler(),
) {
    val automatic = DynamicResolutionManager(sendRequest, scheduler, isHeld = { isApplying })

    /** The hot switch that owns the stream, if any. */
    @Volatile var current: ResolutionSwitchSession? = null
        private set
    /** A switch was stopped in the background before it finished; it reconnects on start. */
    @Volatile var reconnectOnStart = false
        private set

    val isApplying: Boolean get() = isReconnecting() || current != null || reconnectOnStart

    fun begin(attempt: ResolutionSwitchSession): Boolean {
        if (isApplying) return false
        automatic.cancelPending()
        current = attempt
        return true
    }

    /**
     * The host reported its display size. Automatic resolution tracks every report, so it compares
     * held sizes with the real stream; returns whether the stream may act on it, i.e. not while
     * reconnecting or while a switch waits for a different size or is finishing.
     */
    fun onHostReport(width: Int, height: Int, baseWidth: Int, baseHeight: Int, hostSupportsAutomatic: Boolean): Boolean {
        if (hostSupportsAutomatic) automatic.onHostResolutionChanged(width, height)
        val attempt = current
        return !isReconnecting() && !reconnectOnStart && (attempt == null || attempt.isWaitingFor(baseWidth, baseHeight))
    }

    /** [attempt]'s new frame has been revealed; false if it no longer owns the stream. */
    fun finish(attempt: ResolutionSwitchSession): Boolean {
        if (current !== attempt) return false
        current = null
        automatic.onHoldReleased()
        return true
    }

    /** The Activity stopped: a switch still waiting for its frame reconnects on start. */
    fun stop(isFinishing: Boolean) {
        val attempt = current ?: return
        reconnectOnStart = !isFinishing && attempt.state == ResolutionSwitchSession.State.WAITING
    }

    /** Drops the switch that owns the stream; false if there was none. */
    fun release(): Boolean {
        val attempt = current ?: return false
        attempt.cancel()
        current = null
        // A disposed transition never finishes its reveal, so this is where the switch ends.
        if (!isApplying) automatic.onHoldReleased()
        return true
    }

    fun takeReconnectOnStart(): Boolean {
        if (!reconnectOnStart) return false
        reconnectOnStart = false
        return true
    }
}
