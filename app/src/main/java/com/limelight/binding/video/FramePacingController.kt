package com.limelight.binding.video

import android.annotation.SuppressLint
import android.app.Activity
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import com.limelight.LimeLog
import com.limelight.preferences.PreferenceConfiguration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.LockSupport

/**
 * Controls frame output timing for decoded video frames.
 * Supports Choreographer-based (balanced/experimental), PreciseSync (busy-wait),
 * and VsyncCallback (API 33+) modes.
 *
 * Extracted from MediaCodecDecoderRenderer for separation of concerns.
 */
internal class FramePacingController(
    private val callbacks: Callbacks,
    private val prefs: PreferenceConfiguration,
    private val activity: Activity,
) : Choreographer.FrameCallback {

    interface Callbacks {
        fun onFrameRendered()
        fun onDecoderException(e: IllegalStateException): Boolean
        fun onCodecRecoveryCheck(flag: Int): Boolean
    }

    private var videoDecoder: MediaCodec? = null
    private var refreshRate = 0

    @Volatile
    private var stopping = false

    // Output buffer queue for buffered pacing modes (BALANCED, EXPERIMENTAL, PRECISE_SYNC)
    val outputBufferQueue = LinkedBlockingQueue<Int>()

    // Choreographer state
    private var lastRenderedFrameTimeNanos = 0L
    private var choreographerHandlerThread: HandlerThread? = null
    private var choreographerHandler: Handler? = null

    // PreciseSync state
    private var surfaceFlingerThread: Thread? = null

    @Volatile
    private var surfaceFlingerActive = false
    private var surfaceFlingerLastFrameTime = 0L
    private var surfaceFlingerFrameInterval = 0L
    private var surfaceFlingerFrameCount = 0
    private var surfaceFlingerSkippedFrames = 0
    private var surfaceFlingerTargetTime = 0L
    private var surfaceFlingerTimingError = 0L

    fun start(decoder: MediaCodec, refreshRate: Int) {
        this.videoDecoder = decoder
        this.refreshRate = refreshRate
        this.stopping = false
        startChoreographerThread()
        startSurfaceFlingerThread()
    }

    fun updateDecoder(decoder: MediaCodec) {
        this.videoDecoder = decoder
    }

    fun hasActiveTimingThread(): Boolean =
        choreographerHandlerThread != null || surfaceFlingerThread != null

    fun prepareForStop() {
        stopping = true
        surfaceFlingerActive = false

        surfaceFlingerThread?.interrupt()

        choreographerHandler?.post {
            choreographerHandlerThread?.quit()
            Choreographer.getInstance().removeFrameCallback(this)
        }

        // Unblock any threads waiting on take()
        outputBufferQueue.add(-1)
    }

    fun joinThreads() {
        choreographerHandlerThread?.runCatching { join() }
        surfaceFlingerThread?.runCatching { join() }
    }

    fun clearBuffers() {
        outputBufferQueue.clear()
    }

    /**
     * Enqueues a decoded frame for pacing. If the queue is full, the oldest frame
     * is released without rendering to prevent decoder starvation.
     */
    fun offerOutputBuffer(bufferIndex: Int) {
        if (outputBufferQueue.size >= prefs.outputBufferQueueLimit) {
            try {
                videoDecoder?.releaseOutputBuffer(outputBufferQueue.take(), false)
            } catch (_: InterruptedException) {
                return
            }
        }
        outputBufferQueue.add(bufferIndex)
    }

    fun getSurfaceFlingerFrameCount(): Int = surfaceFlingerFrameCount

    fun getSurfaceFlingerSkippedFrames(): Int = surfaceFlingerSkippedFrames

    // ==================== Choreographer mode ====================

    override fun doFrame(frameTimeNanos: Long) {
        if (stopping) return

        @Suppress("DEPRECATION")
        var adjustedTime = frameTimeNanos -
            activity.windowManager.defaultDisplay.appVsyncOffsetNanos

        // Don't render unless a new frame is due. This prevents microstutter when streaming
        // at a frame rate that doesn't match the display (such as 60 FPS on 120 Hz).
        val actualFrameTimeDeltaNs = adjustedTime - lastRenderedFrameTimeNanos
        val expectedFrameTimeDeltaNs = 800_000_000L / refreshRate // within 80% of the next frame

        if (actualFrameTimeDeltaNs >= expectedFrameTimeDeltaNs) {
            val nextOutputBuffer = outputBufferQueue.poll()
            if (nextOutputBuffer != null && nextOutputBuffer >= 0) {
                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_EXPERIMENTAL_LOW_LATENCY) {
                    // 实验性低延迟模式：安全的提前量不超过V-Sync周期的1/2
                    adjustedTime -= 500_000_000L / refreshRate
                }
                try {
                    videoDecoder?.releaseOutputBuffer(nextOutputBuffer, adjustedTime)
                    lastRenderedFrameTimeNanos = adjustedTime
                    callbacks.onFrameRendered()
                } catch (_: IllegalStateException) {
                    try {
                        videoDecoder?.releaseOutputBuffer(nextOutputBuffer, false)
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        callbacks.onDecoderException(e)
                    }
                }
            }
        }

        // Attempt codec recovery even if we have nothing to render right now.
        callbacks.onCodecRecoveryCheck(MediaCodecDecoderRenderer.CR_FLAG_CHOREOGRAPHER)

        // Request another callback for next frame
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startChoreographerThread() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED &&
            prefs.framePacing != PreferenceConfiguration.FRAME_PACING_EXPERIMENTAL_LOW_LATENCY
        ) return

        val thread = HandlerThread(
            "Video - Choreographer",
            if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_EXPERIMENTAL_LOW_LATENCY)
                Process.THREAD_PRIORITY_DISPLAY
            else
                Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_MORE_FAVORABLE
        ).also { it.start() }

        choreographerHandlerThread = thread
        choreographerHandler = Handler(thread.looper).also { handler ->
            handler.post { Choreographer.getInstance().postFrameCallback(this) }
        }
    }

    // ==================== PreciseSync mode ====================

    private fun startSurfaceFlingerThread() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_PRECISE_SYNC) return

        LimeLog.info("启动精确同步模式")
        surfaceFlingerActive = true
        surfaceFlingerFrameInterval = (1_000_000_000.0 / refreshRate).toLong()
        surfaceFlingerTargetTime = System.nanoTime() + surfaceFlingerFrameInterval
        surfaceFlingerLastFrameTime = System.nanoTime()
        surfaceFlingerFrameCount = 0
        surfaceFlingerSkippedFrames = 0
        surfaceFlingerTimingError = 0

        @Suppress("DEPRECATION")
        var vsyncOffsetNs = 0L
        var presentationDeadlineNs = 0L
        try {
            @Suppress("DEPRECATION")
            vsyncOffsetNs = activity.windowManager.defaultDisplay.appVsyncOffsetNanos
        } catch (e: Exception) {
            LimeLog.warning("无法获取 Vsync 偏移: ${e.message}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                @Suppress("DEPRECATION")
                presentationDeadlineNs =
                    activity.windowManager.defaultDisplay.presentationDeadlineNanos
            } catch (e: Exception) {
                LimeLog.warning("无法获取 Presentation Deadline: ${e.message}")
            }
        }

        val fVsyncOffset = vsyncOffsetNs
        val fDeadline = presentationDeadlineNs

        surfaceFlingerThread = Thread {
            Thread.currentThread().name = "Video - Precise Sync"
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            } catch (e: Exception) {
                LimeLog.warning("无法设置精确同步线程优先级: ${e.message}")
            }
            runSurfaceFlingerLoop(fVsyncOffset, fDeadline)
            LimeLog.info("精确同步模式线程结束")
        }.also { it.start() }
    }

    @SuppressLint("DefaultLocale")
    private fun runSurfaceFlingerLoop(vsyncOffsetNs: Long, presentationDeadlineNs: Long) {
        while (surfaceFlingerActive && !stopping) {
            try {
                val currentTime = System.nanoTime()
                if (currentTime >= surfaceFlingerTargetTime) {
                    renderNextFrame(currentTime, vsyncOffsetNs, presentationDeadlineNs)
                    updateTargetTime(currentTime)
                }

                // Participate in codec recovery quiescence (same as Choreographer path)
                callbacks.onCodecRecoveryCheck(MediaCodecDecoderRenderer.CR_FLAG_CHOREOGRAPHER)

                waitForNextFrame()
            } catch (e: Exception) {
                LimeLog.warning("Surface Flinger线程异常: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private fun renderNextFrame(
        currentTime: Long, vsyncOffsetNs: Long, presentationDeadlineNs: Long
    ) {
        val nextOutputBuffer = outputBufferQueue.poll()
        if (nextOutputBuffer == null || nextOutputBuffer < 0) {
            surfaceFlingerSkippedFrames++
            return
        }
        try {
            val presentationTimeNs =
                calculatePresentationTime(currentTime, vsyncOffsetNs, presentationDeadlineNs)
            videoDecoder?.releaseOutputBuffer(nextOutputBuffer, presentationTimeNs)
            updateTimingStats(currentTime)
        } catch (e: IllegalStateException) {
            LimeLog.warning("精确同步模式渲染异常: ${e.message}")
            callbacks.onDecoderException(e)
        }
    }

    private fun calculatePresentationTime(
        currentTime: Long, vsyncOffsetNs: Long, presentationDeadlineNs: Long
    ): Long {
        if (vsyncOffsetNs == 0L) return 0

        val nextVsyncNs = ((currentTime - vsyncOffsetNs + surfaceFlingerFrameInterval - 1) /
            surfaceFlingerFrameInterval) * surfaceFlingerFrameInterval + vsyncOffsetNs

        if (presentationDeadlineNs > 0) {
            val timeUntilDeadline = nextVsyncNs - presentationDeadlineNs - currentTime
            if (timeUntilDeadline < 0) return 0
        }

        val timeUntilVsync = nextVsyncNs - currentTime
        if (timeUntilVsync < 0 || timeUntilVsync > 1_000_000_000L) {
            LimeLog.warning("时间戳无效 (距离: ${timeUntilVsync / 1_000_000}ms)，使用立即渲染")
            return 0
        }
        return nextVsyncNs
    }

    @SuppressLint("DefaultLocale")
    private fun updateTimingStats(currentTime: Long) {
        val actualInterval = currentTime - surfaceFlingerLastFrameTime
        if (actualInterval > 0) {
            surfaceFlingerTimingError += (actualInterval - surfaceFlingerFrameInterval)
        }
        surfaceFlingerLastFrameTime = currentTime
        surfaceFlingerFrameCount++
        callbacks.onFrameRendered()

        if (surfaceFlingerFrameCount % 12000 == 0) {
            val avgError = surfaceFlingerTimingError / 1_000_000.0f / surfaceFlingerFrameCount
            LimeLog.info(
                String.format(
                    "精确同步: %d帧, 跳帧: %d, 平均误差: %.3fms",
                    surfaceFlingerFrameCount, surfaceFlingerSkippedFrames, avgError
                )
            )
        }
    }

    private fun updateTargetTime(currentTime: Long) {
        surfaceFlingerTargetTime += surfaceFlingerFrameInterval
        val timeDrift = Math.abs(currentTime - surfaceFlingerTargetTime)
        if (timeDrift > surfaceFlingerFrameInterval * 2) {
            LimeLog.warning("精确同步: 时间漂移过大 (${timeDrift / 1_000_000}ms)，重新同步")
            surfaceFlingerTargetTime = currentTime + surfaceFlingerFrameInterval
            surfaceFlingerTimingError = 0
        }
    }

    private fun waitForNextFrame() {
        val sleepTimeNs = surfaceFlingerTargetTime - System.nanoTime()
        if (sleepTimeNs <= 0) return

        // Use LockSupport.parkNanos() for efficient waiting, wake early for precision
        if (sleepTimeNs > 1_000_000) { // > 1ms
            LockSupport.parkNanos(sleepTimeNs - 500_000) // Wake 0.5ms early
        } else if (sleepTimeNs > 100_000) { // > 0.1ms
            LockSupport.parkNanos(sleepTimeNs shr 1) // Wait half the time
        }

        // Busy-wait for sub-microsecond precision
        @Suppress("ControlFlowWithEmptyBody")
        while (System.nanoTime() < surfaceFlingerTargetTime) {
        }
    }
}
