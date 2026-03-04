package com.limelight.binding.audio

import android.content.Context
import com.limelight.LimeLog
import com.limelight.R

import java.util.concurrent.atomic.AtomicLong

/**
 * 音频诊断工具类
 * 用于监控和分析音频流的连续性
 */
object AudioDiagnostics {

    private val totalFramesCaptured = AtomicLong(0)
    private val totalFramesEncoded = AtomicLong(0)
    private val totalFramesSent = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val encodingErrors = AtomicLong(0)
    private val sendingErrors = AtomicLong(0)

    private var lastReportTime = 0L
    private const val REPORT_INTERVAL_MS = 5000L // 每5秒报告一次

    /**
     * 记录捕获的帧
     */
    @JvmStatic
    fun recordFrameCaptured() {
        totalFramesCaptured.incrementAndGet()
        checkAndReport()
    }

    /**
     * 记录编码的帧
     */
    @JvmStatic
    fun recordFrameEncoded() {
        totalFramesEncoded.incrementAndGet()
        checkAndReport()
    }

    /**
     * 记录发送的帧
     */
    @JvmStatic
    fun recordFrameSent() {
        totalFramesSent.incrementAndGet()
        checkAndReport()
    }

    /**
     * 记录丢弃的帧
     */
    @JvmStatic
    fun recordFrameDropped() {
        droppedFrames.incrementAndGet()
        checkAndReport()
    }

    /**
     * 记录编码错误
     */
    @JvmStatic
    fun recordEncodingError() {
        encodingErrors.incrementAndGet()
        checkAndReport()
    }

    /**
     * 记录发送错误
     */
    @JvmStatic
    fun recordSendingError() {
        sendingErrors.incrementAndGet()
        checkAndReport()
    }

    /**
     * 检查并报告统计信息
     */
    private fun checkAndReport() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastReportTime >= REPORT_INTERVAL_MS) {
            reportStatistics()
            lastReportTime = currentTime
        }
    }

    /**
     * 报告统计信息
     */
    @JvmStatic
    fun reportStatistics() {
        val captured = totalFramesCaptured.get()
        val encoded = totalFramesEncoded.get()
        val sent = totalFramesSent.get()
        val dropped = droppedFrames.get()
        val encErrors = encodingErrors.get()
        val sendErrors = sendingErrors.get()

        // 计算连续性指标
        val captureToEncodeRatio = if (captured > 0) encoded.toDouble() / captured else 0.0
        val encodeToSendRatio = if (encoded > 0) sent.toDouble() / encoded else 0.0
        val overallContinuity = if (captured > 0) sent.toDouble() / captured else 0.0

        // 使用单行详细日志
        LimeLog.info(
            String.format(
                "[音频诊断] 捕获:%d 编码:%d 发送:%d 丢弃:%d 编错:%d 发错:%d (捕编比:%.2f%% 编发比:%.2f%% 连续性:%.2f%%)",
                captured, encoded, sent, dropped, encErrors, sendErrors,
                captureToEncodeRatio * 100, encodeToSendRatio * 100, overallContinuity * 100
            )
        )

        // 分析问题
        if (captureToEncodeRatio < 0.95) {
            LimeLog.warning("编码效率较低，可能存在编码器问题")
        }
        if (encodeToSendRatio < 0.95) {
            LimeLog.warning("发送效率较低，可能存在网络或队列问题")
        }
        if (overallContinuity < 0.90) {
            LimeLog.warning("整体音频连续性较差，需要检查整个音频管道")
        }
        if (dropped > 0) {
            LimeLog.warning("检测到帧丢弃，可能存在缓冲区不足问题")
        }
    }

    /**
     * 重置统计信息
     */
    @JvmStatic
    fun resetStatistics() {
        totalFramesCaptured.set(0)
        totalFramesEncoded.set(0)
        totalFramesSent.set(0)
        droppedFrames.set(0)
        encodingErrors.set(0)
        sendingErrors.set(0)
        lastReportTime = 0
        LimeLog.info("音频诊断统计已重置")
    }

    /**
     * 获取当前统计信息
     */
    @JvmStatic
    fun getCurrentStats(): String {
        val captured = totalFramesCaptured.get()
        val encoded = totalFramesEncoded.get()
        val sent = totalFramesSent.get()
        val dropped = droppedFrames.get()

        val continuity = if (captured > 0) sent.toDouble() / captured else 0.0

        return String.format(
            "音频连续性: %.1f%% (捕获:%d 编码:%d 发送:%d 丢弃:%d)",
            continuity * 100, captured, encoded, sent, dropped
        )
    }

    /**
     * 获取当前统计信息（使用字符串资源）
     */
    @JvmStatic
    fun getCurrentStats(context: Context?): String {
        if (context == null) {
            return getCurrentStats()
        }

        val captured = totalFramesCaptured.get()
        val encoded = totalFramesEncoded.get()
        val sent = totalFramesSent.get()
        val dropped = droppedFrames.get()

        val continuity = if (captured > 0) sent.toDouble() / captured else 0.0

        return context.getString(
            R.string.mic_stats_continuity,
            continuity * 100, captured, encoded, sent, dropped
        )
    }
}
