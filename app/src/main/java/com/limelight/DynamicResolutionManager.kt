package com.limelight

import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.limelight.nvstream.NvConnection
import com.limelight.preferences.PreferenceConfiguration

/**
 * Client-driven dynamic resolution: when the local display size changes (rotation,
 * external display, surface resize) we ask the host to switch to the new resolution
 * mid-stream via LiSendResolutionChangeRequest (0x5506).
 *
 * Gating rules (all must pass before sending):
 * 1. Per-trigger pref flag is enabled (followRotationResolution / followExternalDisplayResolution
 *    / followSurfaceResizeResolution).
 * 2. connected == true (set by ConnectionCallbackHandler).
 * 3. NvConnection.sendResolutionChangeRequest performs a server-capability check from
 *    ConnectionContext.supportsClientResolutionChange (parsed from serverinfo tag
 *    <ClientResolutionChange>1</ClientResolutionChange> — see NvHTTP note).
 * 4. If a request is already in-flight, the new size is held in a one-slot conflated
 *    queue; clearInflight() dispatches it so no resize is silently dropped.
 *
 * Debounce: 400 ms. This absorbs fold/unfold bursts, DeX plug/unplug jitter, and
 * multi-window drag storms before we actually send anything to the host.
 */
class DynamicResolutionManager(
    private val prefConfig: PreferenceConfiguration,
) {
    private val handler = Handler(Looper.getMainLooper())

    /** Set to true by ConnectionCallbackHandler.connectionStarted(), false on disconnect. */
    @Volatile var connected = false

    /** NvConnection reference; set after prepareConnection() in Game.kt. */
    @Volatile var connection: NvConnection? = null

    // Dimensions of the last request we sent; used to suppress identical repeat sends.
    private var lastSentWidth = 0
    private var lastSentHeight = 0

    // In-flight: a request has been sent but we haven't received the echo yet.
    @Volatile private var inFlight = false
    private var inflightClearRunnable: Runnable? = null

    // One-slot conflated queue: latest desired size while a request is in-flight.
    private var hasQueued = false
    private var queuedWidth = 0
    private var queuedHeight = 0

    private var pendingRunnable: Runnable? = null

    // True when the current pending debounce was triggered from the surface-change path.
    // Allows canceling only surface-origin requests on Extreme Resume without disturbing
    // rotation or external-display debounces already in the queue.
    private var pendingIsSurface = false

    companion object {
        private const val DEBOUNCE_MS = 400L
        private const val INFLIGHT_TIMEOUT_MS = 3000L

        // Clamp to sane dimension bounds
        private const val MIN_DIM = 256
        private const val MAX_W = 7680
        private const val MAX_H = 4320
    }

    // ── Triggers ───────────────────────────────────────────────────────────────

    /** Called when device rotation changes. Reads the real display size and requests it. */
    fun requestFromRotation(display: Display) {
        if (!prefConfig.followRotationResolution) return
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        scheduleRequest(size.x, size.y, isSurface = false)
    }

    /** Called when the active external display changes (DeX dock, screen mirroring). */
    fun requestFromExternalDisplay(display: Display) {
        if (!prefConfig.followExternalDisplayResolution) return
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        scheduleRequest(size.x, size.y, isSurface = false)
    }

    /** Called from SurfaceHolder.surfaceChanged — physical pixel dimensions of the surface. */
    fun requestFromSurface(width: Int, height: Int) {
        if (!prefConfig.followSurfaceResizeResolution) return
        scheduleRequest(width, height, isSurface = true)
    }

    /**
     * Cancels any pending surface-origin debounce without disturbing rotation or
     * external-display requests. Call from surfaceDestroyed when taking the Extreme
     * Resume path (connection stays alive; the surface will be recreated shortly).
     */
    fun cancelPendingSurfaceRequest() {
        if (pendingIsSurface) {
            pendingRunnable?.let { handler.removeCallbacks(it) }
            pendingRunnable = null
            pendingIsSurface = false
            LimeLog.info("DynamicRes: cancelled pending surface request (Extreme Resume)")
        }
    }

    /**
     * Called when any host→client resolution echo arrives (0x5507 IDX_RESOLUTION_CHANGE
     * or 0x5506 IDX_DYNAMIC_PARAM_CHANGE with param_type RESOLUTION).
     * Clears the in-flight flag; if a queued request is waiting it will be dispatched.
     */
    fun onServerResolutionEcho(width: Int, height: Int) {
        if (inFlight) {
            LimeLog.info("DynamicRes: server echo ${width}x${height} — clearing in-flight")
            clearInflight()
        }
    }

    /** Call on disconnect / prepareConnection reset to clean up state. */
    fun reset() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        pendingIsSurface = false
        inflightClearRunnable?.let { handler.removeCallbacks(it) }
        inflightClearRunnable = null
        inFlight = false
        hasQueued = false
        lastSentWidth = 0
        lastSentHeight = 0
    }

    fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        pendingRunnable = null
        inflightClearRunnable = null
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun scheduleRequest(rawW: Int, rawH: Int, isSurface: Boolean) {
        val w = clamp(rawW and 1.inv())   // round to even + clamp
        val h = clamp(rawH and 1.inv())
        if (w < MIN_DIM || h < MIN_DIM) {
            LimeLog.warning("DynamicRes: ignoring too-small dims ${w}x${h}")
            return
        }

        // Cancel any pending debounce and reschedule
        pendingRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { doSend(w, h) }
        pendingRunnable = runnable
        pendingIsSurface = isSurface
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun doSend(w: Int, h: Int) {
        pendingRunnable = null
        pendingIsSurface = false
        if (!connected) {
            LimeLog.info("DynamicRes: skip send — not connected")
            return
        }
        if (inFlight) {
            // Conflate into the one-slot queue; clearInflight() will dispatch it.
            LimeLog.info("DynamicRes: request in-flight, queuing ${w}x${h}")
            hasQueued = true
            queuedWidth = w
            queuedHeight = h
            return
        }
        if (w == lastSentWidth && h == lastSentHeight) {
            LimeLog.info("DynamicRes: skip send — same dims ${w}x${h} already sent")
            return
        }
        val conn = connection ?: run {
            LimeLog.warning("DynamicRes: skip send — connection is null")
            return
        }

        LimeLog.info("DynamicRes: requesting ${w}x${h}")
        val rc = conn.sendResolutionChangeRequest(w, h)
        if (rc == 0) {
            inFlight = true
            lastSentWidth = w
            lastSentHeight = h
            hasQueued = false
            // Safety timeout: if the echo never arrives, clear in-flight after 3 s
            val timeout = Runnable { clearInflight() }
            inflightClearRunnable = timeout
            handler.postDelayed(timeout, INFLIGHT_TIMEOUT_MS)
        } else {
            LimeLog.warning("DynamicRes: sendResolutionChangeRequest returned $rc")
        }
    }

    private fun clearInflight() {
        inFlight = false
        inflightClearRunnable?.let { handler.removeCallbacks(it) }
        inflightClearRunnable = null
        if (hasQueued && connected) {
            val w = queuedWidth
            val h = queuedHeight
            hasQueued = false
            if (w != lastSentWidth || h != lastSentHeight) {
                LimeLog.info("DynamicRes: dispatching queued ${w}x${h} after in-flight cleared")
                // Reuse the debounce slot so back-to-back echo storms do not spam the host.
                val runnable = Runnable { doSend(w, h) }
                pendingRunnable = runnable
                pendingIsSurface = false
                handler.postDelayed(runnable, DEBOUNCE_MS)
            } else {
                LimeLog.info("DynamicRes: queued ${w}x${h} matches last sent, skipping")
                hasQueued = false
            }
        }
    }

    private fun clamp(v: Int): Int = v.coerceIn(MIN_DIM, maxOf(MAX_W, MAX_H))
}
