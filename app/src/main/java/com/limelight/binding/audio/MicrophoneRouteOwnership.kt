package com.limelight.binding.audio

/** All operations run on the shared route thread, including cleanup inherited from an old session. */
internal class MicrophoneRouteOwnership {
    class Lease(
        val owner: Any,
        private val clearRoute: () -> Unit,
        private val clearMode: () -> Unit,
        private val onFailure: (String, RuntimeException) -> Unit = { _, _ -> }
    ) {
        var routeRequested = false
        var modeRequested = false

        internal fun clear(): Boolean {
            if (routeRequested) {
                try {
                    clearRoute()
                    routeRequested = false
                } catch (e: RuntimeException) { onFailure("route", e) }
            }
            // MODE_NORMAL withdraws our process's request, even while a phone call owns the mode.
            if (modeRequested) {
                try {
                    clearMode()
                    modeRequested = false
                } catch (e: RuntimeException) { onFailure("mode", e) }
            }
            return !routeRequested && !modeRequested
        }
    }

    private var current: Lease? = null
    private var retiring = false

    fun acquire(lease: Lease): Boolean {
        if (current != null) return false
        current = lease
        retiring = false
        return true
    }

    fun release(owner: Any): Boolean {
        if (current?.owner !== owner) return true
        retiring = true
        return retryPendingCleanup()
    }

    /** Retry once per requested activation; an active session must never be taken over. */
    fun retryPendingCleanup(): Boolean {
        val lease = current ?: return true
        if (!retiring || !lease.clear()) return false
        current = null
        retiring = false
        return true
    }
}
