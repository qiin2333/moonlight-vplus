package com.limelight.binding.audio;

import android.content.Context;
import com.limelight.LimeLog;
import com.limelight.R;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 音频诊断工具类
 * 用于监控和分析音频流的连续性
 */
public class AudioDiagnostics {
    
    private static final AtomicLong totalFramesCaptured = new AtomicLong(0);
    private static final AtomicLong totalFramesEncoded = new AtomicLong(0);
    private static final AtomicLong totalFramesSent = new AtomicLong(0);
    private static final AtomicLong droppedFrames = new AtomicLong(0);
    private static final AtomicLong encodingErrors = new AtomicLong(0);
    private static final AtomicLong sendingErrors = new AtomicLong(0);
    
    private static long lastReportTime = 0;
    private static final long REPORT_INTERVAL_MS = 5000; // 每5秒报告一次
    
    /**
     * 记录捕获的帧
     */
    public static void recordFrameCaptured() {
        totalFramesCaptured.incrementAndGet();
        checkAndReport();
    }
    
    /**
     * 记录编码的帧
     */
    public static void recordFrameEncoded() {
        totalFramesEncoded.incrementAndGet();
        checkAndReport();
    }
    
    /**
     * 记录发送的帧
     */
    public static void recordFrameSent() {
        totalFramesSent.incrementAndGet();
        checkAndReport();
    }
    
    /**
     * 记录丢弃的帧
     */
    public static void recordFrameDropped() {
        droppedFrames.incrementAndGet();
        checkAndReport();
    }
    
    /**
     * 记录编码错误
     */
    public static void recordEncodingError() {
        encodingErrors.incrementAndGet();
        checkAndReport();
    }
    
    /**
     * 记录发送错误
     */
    public static void recordSendingError() {
        sendingErrors.incrementAndGet();
        checkAndReport();
    }
    
    /**
     * 检查并报告统计信息
     */
    private static void checkAndReport() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastReportTime >= REPORT_INTERVAL_MS) {
            reportStatistics();
            lastReportTime = currentTime;
        }
    }
    
    /**
     * 报告统计信息
     */
    public static void reportStatistics() {
        long captured = totalFramesCaptured.get();
        long encoded = totalFramesEncoded.get();
        long sent = totalFramesSent.get();
        long dropped = droppedFrames.get();
        long encErrors = encodingErrors.get();
        long sendErrors = sendingErrors.get();
        
        // 计算连续性指标
        double captureToEncodeRatio = captured > 0 ? (double) encoded / captured : 0;
        double encodeToSendRatio = encoded > 0 ? (double) sent / encoded : 0;
        double overallContinuity = captured > 0 ? (double) sent / captured : 0;
        
        LimeLog.info("=== 音频诊断报告 ===");
        LimeLog.info("捕获帧数: " + captured);
        LimeLog.info("编码帧数: " + encoded);
        LimeLog.info("发送帧数: " + sent);
        LimeLog.info("丢弃帧数: " + dropped);
        LimeLog.info("编码错误: " + encErrors);
        LimeLog.info("发送错误: " + sendErrors);
        LimeLog.info("捕获到编码比例: " + String.format("%.2f%%", captureToEncodeRatio * 100));
        LimeLog.info("编码到发送比例: " + String.format("%.2f%%", encodeToSendRatio * 100));
        LimeLog.info("整体连续性: " + String.format("%.2f%%", overallContinuity * 100));
        
        // 分析问题
        if (captureToEncodeRatio < 0.95) {
            LimeLog.warning("编码效率较低，可能存在编码器问题");
        }
        if (encodeToSendRatio < 0.95) {
            LimeLog.warning("发送效率较低，可能存在网络或队列问题");
        }
        if (overallContinuity < 0.90) {
            LimeLog.warning("整体音频连续性较差，需要检查整个音频管道");
        }
        if (dropped > 0) {
            LimeLog.warning("检测到帧丢弃，可能存在缓冲区不足问题");
        }
    }
    
    /**
     * 重置统计信息
     */
    public static void resetStatistics() {
        totalFramesCaptured.set(0);
        totalFramesEncoded.set(0);
        totalFramesSent.set(0);
        droppedFrames.set(0);
        encodingErrors.set(0);
        sendingErrors.set(0);
        lastReportTime = 0;
        LimeLog.info("音频诊断统计已重置");
    }
    
    /**
     * 获取当前统计信息
     */
    public static String getCurrentStats() {
        long captured = totalFramesCaptured.get();
        long encoded = totalFramesEncoded.get();
        long sent = totalFramesSent.get();
        long dropped = droppedFrames.get();
        
        double continuity = captured > 0 ? (double) sent / captured : 0;
        
        return String.format("音频连续性: %.1f%% (捕获:%d 编码:%d 发送:%d 丢弃:%d)", 
                           continuity * 100, captured, encoded, sent, dropped);
    }
    
    /**
     * 获取当前统计信息（使用字符串资源）
     */
    public static String getCurrentStats(Context context) {
        if (context == null) {
            return getCurrentStats();
        }
        
        long captured = totalFramesCaptured.get();
        long encoded = totalFramesEncoded.get();
        long sent = totalFramesSent.get();
        long dropped = droppedFrames.get();
        
        double continuity = captured > 0 ? (double) sent / captured : 0;
        
        return context.getString(R.string.mic_stats_continuity, 
                               continuity * 100, captured, encoded, sent, dropped);
    }
} 