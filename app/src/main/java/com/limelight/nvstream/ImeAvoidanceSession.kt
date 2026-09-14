package com.limelight.nvstream

/** Local keyboard cycle; receipt freshness is not a claim about host snapshot age. */
class ImeAvoidanceSession {
    private var pending: RemoteTextContext? = null
    private var receivedAt = 0L
    var visible = false
        private set
    var userControlled = false
        private set
    var target: RemoteTextContext? = null
        private set

    fun offer(context: RemoteTextContext, now: Long) {
        // Do not change targets or queue a stale target for the next opening.
        if (visible) return
        pending = context
        receivedAt = now
    }

    fun updateVisibility(isVisible: Boolean, now: Long) {
        if (visible == isVisible) return
        visible = isVisible
        userControlled = false
        target = if (isVisible && now - receivedAt in 0..MAX_PENDING_AGE_MS) pending else null
        pending = null
    }

    fun takeControl() {
        if (visible) userControlled = true
    }

    fun invalidateTarget() {
        pending = null
        target = null
    }

    fun reset() {
        invalidateTarget()
        visible = false
        userControlled = false
    }

    companion object {
        const val MAX_PENDING_AGE_MS = 5000L
    }
}
