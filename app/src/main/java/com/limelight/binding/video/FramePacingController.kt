package com.limelight.binding.video

import android.annotation.SuppressLint
import android.app.Activity
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import com.limelight.BuildConfig
import com.limelight.LimeLog
import com.limelight.preferences.PreferenceConfiguration
import java.util.concurrent.ConcurrentHashMap
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

    // ---- PRECISE_SYNC 两步 host-cadence 呈现（step1: host-PI 去抖 → step2: snap 到本地 vsync）----
    // 默认关闭：置 true 前，PRECISE_SYNC 完全走原有本地网格逻辑，行为零变化。
    // 开启后仅在 PRECISE_SYNC 用 host PTS 复现主机出帧节奏(step1)，再 snap 到最近 vsync 上沿(step2)，
    // 消除"本地自由网格 vs 主机节奏"错拍的周期性判抖/重复丢帧；因有 snap 兜底，cushion 取低档(0.5×MAD, floor0)，
    // 延迟增量约 +1~3ms(已离线仿真验证)。仅本线程访问，故 HostCadenceClock 无需线程安全。
    // TODO(on-device): 接入设置项/按网络自适应，并在真机标定 cushion。
    private val useHostCadencePreciseSync = false
    private val preciseHostCadenceClock =
        HostCadenceClock(cushionMul = 0.5, cushionFloorNs = 0L, enableDebugStats = BuildConfig.DEBUG)

    // 旁挂 host PTS(微秒)通道，键为 bufferIndex；不改 outputBufferQueue 的 Int 类型，
    // 故 BALANCED/EXPERIMENTAL 路径零影响。仅上述开关开启且 PRECISE_SYNC 时填充，poll/丢弃/清空时同步移除，无泄漏。
    private val hostPtsByIndex = ConcurrentHashMap<Int, Long>()

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
        hostPtsByIndex.clear()
    }

    /**
     * Enqueues a decoded frame for pacing. If the queue is full, the oldest frame
     * is released without rendering to prevent decoder starvation.
     *
     * @param hostPtsUs host PTS(微秒，以首帧为原点)；仅 PRECISE_SYNC 两步呈现开启时使用，
     *                  其余模式/未传入(-1)时忽略，行为与旧签名一致。
     */
    fun offerOutputBuffer(bufferIndex: Int, hostPtsUs: Long = -1L) {
        if (outputBufferQueue.size >= prefs.outputBufferQueueLimit) {
            try {
                val dropped = outputBufferQueue.take()
                hostPtsByIndex.remove(dropped)
                videoDecoder?.releaseOutputBuffer(dropped, false)
            } catch (_: InterruptedException) {
                return
            } catch (_: IllegalStateException) {
                // Buffer index may be stale after codec recovery
            }
        }
        if (useHostCadencePreciseSync && hostPtsUs >= 0 &&
            prefs.framePacing == PreferenceConfiguration.FRAME_PACING_PRECISE_SYNC
        ) {
            hostPtsByIndex[bufferIndex] = hostPtsUs
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
        val hostPtsUs = hostPtsByIndex.remove(nextOutputBuffer) ?: -1L
        try {
            val presentationTimeNs =
                if (useHostCadencePreciseSync && hostPtsUs >= 0 && vsyncOffsetNs != 0L) {
                    computeHostCadenceSnapTime(
                        hostPtsUs, currentTime, vsyncOffsetNs, presentationDeadlineNs
                    )
                } else {
                    calculatePresentationTime(currentTime, vsyncOffsetNs, presentationDeadlineNs)
                }
            videoDecoder?.releaseOutputBuffer(nextOutputBuffer, presentationTimeNs)
            updateTimingStats(currentTime)
        } catch (e: IllegalStateException) {
            LimeLog.warning("精确同步模式渲染异常: ${e.message}")
            callbacks.onDecoderException(e)
        }
    }

    /**
     * PRECISE_SYNC 两步呈现的呈现时刻计算。
     * step1: 用 host-PI 去抖时钟由 host PTS 复现主机节奏目标(本地单调钟)；迟到帧回退为立即。
     * step2: 将该目标 snap 向上取整到最近 vsync 上沿(复用 calculatePresentationTime 的边界/截止判定)。
     */
    private fun computeHostCadenceSnapTime(
        hostPtsUs: Long, currentTime: Long, vsyncOffsetNs: Long, presentationDeadlineNs: Long
    ): Long {
        // step1: host-PI 去抖，得到主机出帧节奏目标
        val hostTargetNs = preciseHostCadenceClock.presentTimeNs(hostPtsUs, surfaceFlingerFrameInterval)
        // 迟到帧：目标已过去 → 以当前时刻为基准 snap（等价立即呈现语义），绝不把网格拽回到达时刻
        val rawNs = if (hostTargetNs < currentTime) currentTime else hostTargetNs

        // step2: snap 向上取整到最近 vsync 边界
        val nextVsyncNs = ((rawNs - vsyncOffsetNs + surfaceFlingerFrameInterval - 1) /
            surfaceFlingerFrameInterval) * surfaceFlingerFrameInterval + vsyncOffsetNs

        if (presentationDeadlineNs > 0) {
            val timeUntilDeadline = nextVsyncNs - presentationDeadlineNs - currentTime
            if (timeUntilDeadline < 0) return 0
        }
        val timeUntilVsync = nextVsyncNs - currentTime
        if (timeUntilVsync < 0 || timeUntilVsync > 1_000_000_000L) {
            LimeLog.warning("host-cadence 时间戳无效 (距离: ${timeUntilVsync / 1_000_000}ms)，使用立即渲染")
            return 0
        }
        return nextVsyncNs
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
