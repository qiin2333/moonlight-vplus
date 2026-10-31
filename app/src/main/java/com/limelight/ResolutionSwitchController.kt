package com.limelight

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.http.NvHTTP
import com.limelight.utils.ResolutionTransitionOverlay

/**
 * Owns the hot-switch attempt, retained frame and lifecycle recovery on the UI thread.
 * The native control thread may only read [acceptsNotification].
 */
internal class ResolutionSwitchController(
    private val activity: Activity,
    private val reconnect: () -> Unit,
    private val onPresented: (Int, Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var current: ResolutionSwitchSession? = null
    @Volatile private var reconnectOnStart = false
    private var transition: ResolutionTransitionOverlay? = null
    private var pendingRenderer: MediaCodecDecoderRenderer? = null

    val isApplying: Boolean get() = current != null || reconnectOnStart

    /** May be read by the native control thread; notifications cannot complete a switch. */
    fun acceptsNotification(width: Int, height: Int): Boolean {
        val attempt = current
        return !reconnectOnStart && (attempt == null || attempt.isWaitingFor(width, height))
    }

    /** Starts an eligible request; [isCurrentStream] binds callbacks to its originating stream. */
    fun start(
        connection: NvConnection,
        renderer: MediaCodecDecoderRenderer,
        requested: DisplayReconnectSettings,
        previousBitrate: Int,
        surface: SurfaceView?,
        isCurrentStream: () -> Boolean,
    ) {
        if (isApplying || activity.isFinishing || activity.isDestroyed) return
        val attempt = ResolutionSwitchSession(requested.width, requested.height)
        current = attempt
        pendingRenderer = renderer
        val overlay = ResolutionTransitionOverlay(activity, attempt.width, attempt.height)
        transition = overlay
        ResolutionTransitionOverlay.captureFrame(activity, surface) { frame ->
            if (!isWaiting(attempt)) {
                frame?.recycle()
                return@captureFrame
            }
            overlay.setFrame(frame)
            if (!isCurrentStream()) {
                fallback(attempt, "stream replaced")
                return@captureFrame
            }
            renderer.awaitResolutionFrame(attempt.width, attempt.height) { width, height ->
                activity.runOnUiThread {
                    if (!isWaiting(attempt) || !attempt.isWaitingFor(width, height)) return@runOnUiThread
                    if (!isCurrentStream()) {
                        fallback(attempt, "stream replaced")
                        return@runOnUiThread
                    }
                    if (requested.bitrate == previousBitrate) {
                        complete(attempt, connection)
                    } else {
                        // Replace the frame timer with a callback watchdog. HTTP cancellation
                        // cannot unblock every operation (e.g. system DNS), so a callback may stall.
                        handler.removeCallbacksAndMessages(null)
                        handler.postDelayed(
                            { fallback(attempt, "bitrate callback timeout") },
                            NvHTTP.TimeoutConfig.DEFAULT.bitrateCallTimeoutMs.toLong() + BITRATE_CALLBACK_GRACE_MS
                        )
                        connection.setBitrate(requested.bitrate, object : NvConnection.BitrateAdjustmentCallback {
                            override fun onSuccess(newBitrate: Int) {
                                activity.runOnUiThread {
                                    if (isWaiting(attempt) && isCurrentStream()) complete(attempt, connection)
                                }
                            }
                            override fun onFailure(errorMessage: String) {
                                activity.runOnUiThread { fallback(attempt, "bitrate adjustment failed") }
                            }
                        })
                    }
                }
            }
            handler.postDelayed({ fallback(attempt, "new frame timeout") }, FRAME_CONFIRM_TIMEOUT_MS)
            if (!connection.requestResolutionChange(attempt.width, attempt.height)) {
                fallback(attempt, "request unsupported or failed")
            }
        }
    }

    /** Called on the UI thread alongside frame completion, before normal termination handling. */
    fun connectionTerminated(errorCode: Int): Boolean {
        if (reconnectOnStart) return true
        val attempt = current ?: return false
        if (attempt.state != ResolutionSwitchSession.State.WAITING) return false
        fallback(attempt, "connection terminated: $errorCode")
        return true
    }

    fun onStop(isReconnecting: Boolean) {
        val attempt = current ?: return
        if (isReconnecting) return
        reconnectOnStart = !activity.isFinishing && attempt.state == ResolutionSwitchSession.State.WAITING
        cancel()
    }

    /** Returns true when foreground recovery has redirected the Activity to reconnect. */
    fun onStart(): Boolean {
        if (!reconnectOnStart) return false
        reconnectOnStart = false
        reconnect()
        return true
    }

    fun takeBackdrop() = transition?.takeBackdrop()

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        current?.cancel()
        current = null
        pendingRenderer?.cancelResolutionFrameWait()
        pendingRenderer = null
        transition?.dispose()
        transition = null
    }

    private fun isWaiting(attempt: ResolutionSwitchSession): Boolean =
        current === attempt && attempt.state == ResolutionSwitchSession.State.WAITING &&
            !activity.isFinishing && !activity.isDestroyed

    private fun complete(attempt: ResolutionSwitchSession, connection: NvConnection) {
        if (!isWaiting(attempt) || !attempt.complete()) return
        handler.removeCallbacksAndMessages(null)
        connection.applyResolutionLocally(attempt.width, attempt.height)
        onPresented(attempt.width, attempt.height)
        LimeLog.info("Resolution hot switch presented: ${attempt.width}x${attempt.height}")
        transition?.reveal {
            if (current === attempt) {
                current = null
                pendingRenderer = null
                transition = null
            }
        }
    }

    private fun fallback(attempt: ResolutionSwitchSession, reason: String) {
        if (!isWaiting(attempt) || !attempt.fallback()) return
        LimeLog.info("Resolution hot switch falling back to reconnect: $reason")
        handler.removeCallbacksAndMessages(null)
        pendingRenderer?.cancelResolutionFrameWait()
        transition?.reconnecting()
        reconnect()
    }

    private companion object {
        // Initial budget for display reconfiguration, encoder restart and confirmed presentation.
        const val FRAME_CONFIRM_TIMEOUT_MS = 5_000L
        // Give the HTTP timeout's failure callback time to reach the UI thread first.
        const val BITRATE_CALLBACK_GRACE_MS = 1_000L
    }
}
