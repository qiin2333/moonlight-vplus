package com.limelight.framegen

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaCodec output capture for frame generation.
 *
 * ImageReader callbacks only acquire decoded AHardwareBuffers. The native LSFG
 * pipeline runs on a dedicated worker thread. If a new decoded frame arrives
 * while native is busy, keep a single latest pending frame instead of dropping
 * it immediately. This absorbs decoder output bursts without adding an
 * unbounded latency queue.
 */
class FramegenCapture private constructor(
    private val reader: ImageReader,
    private val callbackThread: HandlerThread,
    private val workerThread: HandlerThread,
    private val released: AtomicBoolean,
    private val releasePendingFrames: () -> Unit,
) {

    val surface: Surface = reader.surface

    fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            reader.setOnImageAvailableListener(null, null)
            releasePendingFrames()
            reader.close()
        } catch (t: Throwable) {
            Log.w(TAG, "ImageReader.close failed: ${t.message}")
        }
        callbackThread.quitSafely()
        workerThread.quitSafely()
        Log.i(TAG, "FramegenCapture released")
    }

    companion object {
        private const val TAG = "Framegen"
        private const val MAX_IMAGES = 3

        @JvmStatic
        fun create(width: Int, height: Int): FramegenCapture? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.w(TAG, "FramegenCapture requires API 29+, current=${Build.VERSION.SDK_INT}")
                return null
            }

            val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            val reader = try {
                ImageReader.newInstance(
                    width,
                    height,
                    ImageFormat.PRIVATE,
                    MAX_IMAGES,
                    usage
                )
            } catch (t: Throwable) {
                Log.e(TAG, "ImageReader.newInstance ${width}x${height} failed: ${t.message}", t)
                return null
            }

            val callbackThread = HandlerThread("FramegenCapture", Thread.MAX_PRIORITY).apply { start() }
            val callbackHandler = Handler(callbackThread.looper)
            val workerThread = HandlerThread("FramegenRender", Thread.MAX_PRIORITY).apply { start() }
            val workerHandler = Handler(workerThread.looper)
            val released = AtomicBoolean(false)
            val processing = AtomicBoolean(false)
            val pendingLock = Object()
            var pendingFrame: CapturedFrame? = null
            var queuedWhileBusy = 0L
            var replacedWhileBusy = 0L
            var lastImageTimestampNs = 0L
            var observedInputFps = 0f
            var processedByWorker = 0L

            FramegenInterceptor.nativeResetFrameCounter()
            Log.i(
                TAG,
                "FramegenCapture created ${width}x${height} maxImages=$MAX_IMAGES " +
                    "usage=0x${usage.toString(16)}"
            )

            fun closePendingFrame() {
                val frame = synchronized(pendingLock) {
                    val pending = pendingFrame
                    pendingFrame = null
                    pending
                }
                try {
                    frame?.image?.close()
                } catch (_: Throwable) {
                }
            }

            fun drainFramesOnWorker(firstFrame: CapturedFrame) {
                var frame: CapturedFrame? = firstFrame
                while (true) {
                    val current = frame ?: break
                    val image = current.image
                    val workerStartNs = SystemClock.elapsedRealtimeNanos()
                    val queueDelayMs = (workerStartNs - current.enqueueNs) / 1_000_000L

                    try {
                        if (!released.get()) {
                            val hb = try {
                                image.hardwareBuffer
                            } catch (t: IllegalStateException) {
                                Log.w(TAG, "Framegen image already closed before worker read buffer: ${t.message}")
                                null
                            }
                            if (hb == null) {
                                Log.w(TAG, "Image.hardwareBuffer == null")
                            } else {
                                try {
                                    val nativeStartNs = SystemClock.elapsedRealtimeNanos()
                                    val cnt = FramegenInterceptor.nativeOnFrameAvailable(
                                        hb,
                                        current.width,
                                        current.height,
                                        current.format,
                                        current.timestampNs,
                                        current.observedInputFps
                                    )
                                    val nativeElapsedMs =
                                        (SystemClock.elapsedRealtimeNanos() - nativeStartNs) / 1_000_000L

                                    processedByWorker += 1L
                                    if (processedByWorker == 1L ||
                                        processedByWorker % 120L == 0L ||
                                        nativeElapsedMs >= 16L ||
                                        queueDelayMs >= 8L
                                    ) {
                                        Log.i(
                                            TAG,
                                            "Framegen worker timing processed=$processedByWorker " +
                                                "native=${nativeElapsedMs}ms queue=${queueDelayMs}ms " +
                                                "observedFps=${"%.1f".format(current.observedInputFps)}"
                                        )
                                    }
                                    if (cnt == 1L) {
                                        Log.i(TAG, "First frame AHB received (${current.width}x${current.height})")
                                    }
                                } finally {
                                    hb.close()
                                }
                            }
                        }
                    } finally {
                        try {
                            image.close()
                        } catch (_: Throwable) {
                        }
                    }

                    frame = if (released.get()) {
                        closePendingFrame()
                        processing.set(false)
                        null
                    } else {
                        synchronized(pendingLock) {
                            val next = pendingFrame
                            pendingFrame = null
                            if (next == null) {
                                processing.set(false)
                            }
                            next
                        }
                    }
                }
            }

            reader.setOnImageAvailableListener({ r ->
                if (released.get()) return@setOnImageAvailableListener

                val image = try {
                    r.acquireLatestImage()
                } catch (t: Throwable) {
                    Log.w(TAG, "acquireLatestImage failed: ${t.message}")
                    null
                } ?: return@setOnImageAvailableListener

                if (released.get()) {
                    image.close()
                    return@setOnImageAvailableListener
                }

                val frameWidth: Int
                val frameHeight: Int
                val frameFormat: Int
                val timestampNs: Long
                try {
                    frameWidth = image.width
                    frameHeight = image.height
                    frameFormat = image.format
                    timestampNs = image.timestamp
                } catch (t: IllegalStateException) {
                    Log.w(TAG, "Framegen image closed before metadata capture: ${t.message}")
                    try {
                        image.close()
                    } catch (_: Throwable) {
                    }
                    return@setOnImageAvailableListener
                }
                if (timestampNs > 0L && lastImageTimestampNs > 0L && timestampNs > lastImageTimestampNs) {
                    val deltaNs = timestampNs - lastImageTimestampNs
                    if (deltaNs in 1_000_000L..250_000_000L) {
                        val instantFps = (1_000_000_000.0 / deltaNs).toFloat()
                        observedInputFps = if (observedInputFps <= 0f) {
                            instantFps
                        } else {
                            (observedInputFps * 0.88f) + (instantFps * 0.12f)
                        }
                    }
                }
                if (timestampNs > 0L) {
                    lastImageTimestampNs = timestampNs
                }

                val frame = CapturedFrame(
                    image = image,
                    width = frameWidth,
                    height = frameHeight,
                    format = frameFormat,
                    timestampNs = timestampNs,
                    observedInputFps = observedInputFps,
                    enqueueNs = SystemClock.elapsedRealtimeNanos()
                )

                while (!processing.compareAndSet(false, true)) {
                    var shouldRetry = false
                    synchronized(pendingLock) {
                        if (!processing.get()) {
                            shouldRetry = true
                        } else {
                            queuedWhileBusy += 1L
                            val previous = pendingFrame
                            if (previous != null) {
                                replacedWhileBusy += 1L
                                try {
                                    previous.image.close()
                                } catch (_: Throwable) {
                                }
                            }
                            pendingFrame = frame
                            if (queuedWhileBusy == 1L || queuedWhileBusy % 120L == 0L) {
                                Log.i(
                                    TAG,
                                    "Framegen worker busy; queued latest frame " +
                                        "count=$queuedWhileBusy replaced=$replacedWhileBusy"
                                )
                            }
                        }
                    }
                    if (!shouldRetry) return@setOnImageAvailableListener
                }

                val posted = workerHandler.post {
                    drainFramesOnWorker(frame)
                }
                if (!posted) {
                    image.close()
                    processing.set(false)
                }
            }, callbackHandler)

            return FramegenCapture(
                reader,
                callbackThread,
                workerThread,
                released,
                ::closePendingFrame
            )
        }

        private data class CapturedFrame(
            val image: Image,
            val width: Int,
            val height: Int,
            val format: Int,
            val timestampNs: Long,
            val observedInputFps: Float,
            val enqueueNs: Long,
        )
    }
}
