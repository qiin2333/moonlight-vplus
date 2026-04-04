package com.limelight.binding.video

import android.os.SystemClock

/**
 * 环形缓冲区记录帧渲染间隔，用于计算 1% low FPS（P99 帧间隔的倒数）。
 * 线程安全：写入在渲染线程，读取在统计线程。
 */
class FrameIntervalTracker(capacity: Int = 600) {
    private val intervals = FloatArray(capacity)
    private var writeIndex = 0
    private var count = 0
    @Volatile private var lastTimestamp = 0L

    fun recordFrame() {
        val now = SystemClock.uptimeMillis()
        val last = lastTimestamp
        lastTimestamp = now
        if (last <= 0L) return

        val interval = (now - last).toFloat()
        synchronized(this) {
            intervals[writeIndex] = interval
            writeIndex = (writeIndex + 1) % intervals.size
            if (count < intervals.size) count++
        }
    }

    fun getOnePercentLowFps(): Float {
        val snapshot: FloatArray
        synchronized(this) {
            if (count < 10) return 0f
            snapshot = FloatArray(count)
            val start = if (count < intervals.size) 0 else writeIndex
            for (i in 0 until count) {
                snapshot[i] = intervals[(start + i) % intervals.size]
            }
        }
        snapshot.sort()
        val p99 = snapshot[(snapshot.size * 0.99).toInt().coerceAtMost(snapshot.size - 1)]
        return if (p99 > 0f) 1000f / p99 else 0f
    }

    fun clear() {
        synchronized(this) {
            count = 0
            writeIndex = 0
            lastTimestamp = 0L
        }
    }
}
