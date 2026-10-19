package com.limelight

import android.os.Handler
import android.os.Looper

/**
 * Client-driven dynamic resolution: asks the host to re-render at a new size mid-stream
 * (LiRequestResolutionChange, control message 0x5506) when the area the stream is shown in
 * changes, e.g. a window resize, fold/unfold or moving to an external display.
 *
 * Host behavior this follows: every stream starts encoding at the negotiated size, and the host
 * reports its display size (not the stream size) once at stream start and whenever the display
 * changes. It applies requests, and its stream follows its display, only while it streams the
 * display 1:1 (in either orientation). Requests it ignores, including no-ops, get no answer.
 *
 * Callers report the target size in physical pixels via [request]. This class:
 * - maps it the way the launch request is built: applies the resolution scale, shrinks it to fit
 *   [MAX_DIM] keeping the aspect ratio and rounds down to even. A size under [MIN_DIM] (e.g. PiP)
 *   is not sent and drops the pending one;
 * - debounces, so only the settled size of a burst (window drag, fold/unfold) is sent;
 * - sends only while the host streams its display 1:1, otherwise holds the size until the next
 *   host report;
 * - keeps at most one request in flight. A newer size waits until the host reports (or
 *   [IN_FLIGHT_TIMEOUT_MS] passes) and is then debounced again, which also keeps requests clear
 *   of the host's 250 ms rate limit;
 * - skips the size being streamed and never repeats a request until the stream size changes.
 *
 * Must be used from the main thread, except [onHostResolutionChanged].
 */
class DynamicResolutionManager(
    private val sendRequest: (width: Int, height: Int) -> Int,
    private val scheduler: Scheduler = MainLooperScheduler(),
) {
    interface Scheduler {
        fun post(task: Runnable)
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
    }

    internal data class StreamSize(val width: Int, val height: Int) {
        fun transposed() = StreamSize(height, width)
    }

    private var active = false
    private var scalePercent = 100
    private var launchWidth = 0
    private var launchHeight = 0
    private var stream: StreamSize? = null
    private var hostDisplay: StreamSize? = null
    private var pending: StreamSize? = null
    private var requested: StreamSize? = null
    private var inFlight = false

    // Host reports are posted from a native thread; drop any that were queued before a disconnect.
    @Volatile private var connectionGeneration = 0

    private val sendTask = Runnable { sendPending() }
    private val inFlightTimeoutTask = Runnable {
        LimeLog.warning("DynamicRes: no answer from the host, no longer waiting")
        finishInFlight()
    }

    /** Starts accepting requests for a stream launched at [width]x[height] before [resolutionScale]. */
    fun onConnected(width: Int, height: Int, resolutionScale: Int) {
        reset()
        scalePercent = resolutionScale
        launchWidth = width
        launchHeight = height
        // Matches StreamConfiguration.reqWidth/reqHeight. The host's stream-start report may
        // already have arrived, so hostDisplay is kept.
        stream = StreamSize(width * resolutionScale / 100, height * resolutionScale / 100)
        requested = null
        active = true
    }

    fun onDisconnected() {
        reset()
        active = false
        stream = null
        hostDisplay = null
        connectionGeneration++
    }

    /** Asks the host to stream at [width]x[height] physical pixels (before the resolution scale). */
    fun request(width: Int, height: Int) {
        if (!active) return
        val size = mapToStreamSize(width, height, scalePercent)
        if (size == null) {
            LimeLog.info("DynamicRes: ignoring ${width}x$height, below ${MIN_DIM}px")
            cancelPending()
            return
        }
        pending = size
        scheduleSend()
    }

    /** Asks the host to go back to the size the stream was launched at. */
    fun requestLaunchSize() = request(launchWidth, launchHeight)

    /** Drops a size that hasn't been sent yet, e.g. when the window enters PiP. */
    fun cancelPending() {
        scheduler.cancel(sendTask)
        pending = null
    }

    /** The host reported its display size. May be called from any thread. */
    fun onHostResolutionChanged(width: Int, height: Int) {
        val generation = connectionGeneration
        scheduler.post {
            if (generation == connectionGeneration) onHostDisplayReport(StreamSize(width, height))
        }
    }

    private fun onHostDisplayReport(reported: StreamSize) {
        val streamFollowsDisplay = isHostStreamingDisplay1To1()
        hostDisplay = reported
        if (streamFollowsDisplay && reported != stream) {
            stream = reported
            requested = null
        }
        // Any report answers the request in flight, and may make a held size sendable.
        finishInFlight()
    }

    private fun isHostStreamingDisplay1To1(): Boolean {
        val display = hostDisplay ?: return false
        return display == stream || display == stream?.transposed()
    }

    private fun scheduleSend() {
        scheduler.cancel(sendTask)
        scheduler.postDelayed(sendTask, DEBOUNCE_MS)
    }

    private fun finishInFlight() {
        inFlight = false
        scheduler.cancel(inFlightTimeoutTask)
        if (pending != null) scheduleSend()
    }

    private fun sendPending() {
        // While in flight or not 1:1, pending stays queued for the next host report.
        if (!active || inFlight) return
        val size = pending ?: return
        if (size == stream || size == requested) {
            pending = null
            return
        }
        if (!isHostStreamingDisplay1To1()) {
            LimeLog.info("DynamicRes: host isn't streaming its display 1:1, holding ${size.width}x${size.height}")
            return
        }
        pending = null

        LimeLog.info("DynamicRes: requesting ${size.width}x${size.height}")
        val rc = sendRequest(size.width, size.height)
        if (rc != 0) {
            LimeLog.warning("DynamicRes: resolution change request failed: $rc")
            if (rc == ERR_UNSUPPORTED) active = false
            return
        }
        requested = size
        inFlight = true
        scheduler.postDelayed(inFlightTimeoutTask, IN_FLIGHT_TIMEOUT_MS)
    }

    private fun reset() {
        scheduler.cancel(sendTask)
        scheduler.cancel(inFlightTimeoutTask)
        pending = null
        inFlight = false
    }

    private class MainLooperScheduler : Scheduler {
        private val handler = Handler(Looper.getMainLooper())
        override fun post(task: Runnable) { handler.post(task) }
        override fun postDelayed(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
        override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
    }

    companion object {
        internal const val DEBOUNCE_MS = 400L
        // A host display mode change can take a few seconds.
        internal const val IN_FLIGHT_TIMEOUT_MS = 5000L
        internal const val MIN_DIM = 256
        internal const val MAX_DIM = 7680

        // LiRequestResolutionChange() results: the host can't take requests for this session,
        // or the request couldn't be sent.
        internal const val ERR_UNSUPPORTED = -2
        internal const val ERR_SEND_FAILED = -4

        internal fun mapToStreamSize(width: Int, height: Int, scalePercent: Int): StreamSize? {
            var w = width.toLong() * scalePercent / 100
            var h = height.toLong() * scalePercent / 100
            val longest = maxOf(w, h)
            if (longest > MAX_DIM) {
                w = w * MAX_DIM / longest
                h = h * MAX_DIM / longest
            }
            val size = StreamSize(w.toInt() and 1.inv(), h.toInt() and 1.inv())
            return size.takeIf { it.width >= MIN_DIM && it.height >= MIN_DIM }
        }
    }
}
