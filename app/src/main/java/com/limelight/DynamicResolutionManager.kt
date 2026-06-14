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
 * 4. Not currently in-flight (suppressed until echo arrives or a 3-second timeout clears it).
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

    private var pendingRunnable: Runnable? = null

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
        scheduleRequest(size.x, size.y)
    }

    /** Called when the active external display changes (DeX dock, screen mirroring). */
    fun requestFromExternalDisplay(display: Display) {
        if (!prefConfig.followExternalDisplayResolution) return
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        scheduleRequest(size.x, size.y)
    }

    /** Called from SurfaceHolder.surfaceChanged — physical pixel dimensions of the surface. */
    fun requestFromSurface(width: Int, height: Int) {
        if (!prefConfig.followSurfaceResizeResolution) return
        scheduleRequest(width, height)
    }

    /**
     * Called when a host→client 0x5507/0x5506-RESOLUTION echo arrives (via Game.onResolutionChanged).
     * Clears the in-flight flag so the next trigger can proceed.
     */
    fun onServerResolutionEcho(width: Int, height: Int) {
        if (inFlight) {
            LimeLog.info("DynamicRes: server echo received ${width}x${height}, clearing in-flight")
            clearInflight()
        }
    }

    /** Call on disconnect / prepareConnection reset to clean up state. */
    fun reset() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        inflightClearRunnable?.let { handler.removeCallbacks(it) }
        inflightClearRunnable = null
        inFlight = false
        lastSentWidth = 0
        lastSentHeight = 0
    }

    fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        pendingRunnable = null
        inflightClearRunnable = null
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun scheduleRequest(rawW: Int, rawH: Int) {
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
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun doSend(w: Int, h: Int) {
        pendingRunnable = null
        if (!connected) {
            LimeLog.info("DynamicRes: skip send — not connected")
            return
        }
        if (inFlight) {
            LimeLog.info("DynamicRes: skip send — previous request still in-flight")
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
            // Safety timeout: if the echo never comes, clear in-flight after 3 s
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
    }

    private fun clamp(v: Int): Int = v.coerceIn(MIN_DIM, maxOf(MAX_W, MAX_H))
}
