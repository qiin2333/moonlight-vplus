package com.limelight.binding.audio;

import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MicrophoneStream implements MicrophoneCapture.MicrophoneDataCallback {
    
    private final NvConnection conn;
    private MicrophoneCapture capture;
    private OpusEncoder encoder;
    private final Object encoderLock = new Object();
    private Thread senderThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean micActive = new AtomicBoolean(false);
    private final AtomicBoolean hostRequested = new AtomicBoolean(false);
    private LinkedBlockingQueue<byte[]> packetQueue;
    
    public MicrophoneStream(NvConnection conn) {
        this.conn = conn;
        this.packetQueue = new LinkedBlockingQueue<>(MicrophoneConfig.MAX_QUEUE_SIZE);
        LimeLog.info("初始化麦克风流");
    }
    
    public boolean start() {
        try {
            // 重置音频诊断统计
            AudioDiagnostics.resetStatistics();
            
            // 如果还没有初始化，先初始化
            if (!running.get()) {
                // 如果主机已经请求麦克风，立即启动麦克风捕获
                if (MoonBridge.isMicrophoneRequested()) {
                    LimeLog.info("主机请求麦克风，开始捕获");
                    
                    // 检查加密状态（仅用于日志）
                    if (MoonBridge.isMicrophoneEncryptionEnabled()) {
                        LimeLog.info("麦克风加密已启用");
                    }
                    
                    hostRequested.set(true);
                    return startMicrophoneCapture();
                } else {
                    LimeLog.info("主机未请求麦克风，将等待请求");
                    return true;
                }
            } else {
                // 如果已经初始化，直接启动麦克风捕获
                if (!micActive.get()) {
                    LimeLog.info("重新启动麦克风捕获");
                    return startMicrophoneCapture();
                } else {
                    LimeLog.info("麦克风已经在运行");
                    return true;
                }
            }
        } catch (Exception e) {
            LimeLog.severe("启动麦克风流失败: " + e.getMessage());
            cleanup();
            return false;
        }
    }
    
    public boolean isMicrophoneAvailable() {
        return MoonBridge.isMicrophoneRequested();
    }
    
    private void stopMicrophoneCapture() {
        if (!micActive.get()) {
            return;
        }
        
        micActive.set(false);
        
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        
        packetQueue.clear();
        
        // 不停止发送线程，因为我们将来可能需要重新启动麦克风
    }
    
    private boolean startMicrophoneCapture() {
        if (micActive.get()) {
            return true;
        }
        
        try {
            // 记录协商的麦克风端口（仅用于日志）
            int micPort = MoonBridge.getMicPortNumber();
            if (micPort == 0) {
                LimeLog.warning("未获取到协商的麦克风端口");
            } else {
                LimeLog.info("使用协商的麦克风端口: " + micPort);
            }
            
            // 创建编码器
            encoder = new OpusEncoder(MicrophoneConfig.SAMPLE_RATE, MicrophoneConfig.CHANNELS, MicrophoneConfig.getOpusBitrate());
            
            // 创建并启动麦克风捕获
            capture = new MicrophoneCapture(this);
            if (!capture.start()) {
                LimeLog.severe("无法启动麦克风捕获");
                cleanup();
                return false;
            }
            
            // 如果发送线程还没有启动，启动它
            if (senderThread == null || !senderThread.isAlive()) {
                running.set(true);
                senderThread = new Thread(this::senderThreadProc, "MicSender");
                senderThread.setPriority(Thread.MAX_PRIORITY);
                senderThread.start();
            }
            
            micActive.set(true);
            LimeLog.info("麦克风捕获已启动");
            return true;
        } catch (SecurityException e) {
            LimeLog.severe("麦克风权限不足: " + e.getMessage());
            cleanup();
            return false;
        } catch (Exception e) {
            LimeLog.severe("启动麦克风捕获失败: " + e.getMessage());
            cleanup();
            return false;
        }
    }
    
    public void stop() {
        running.set(false);
        micActive.set(false);
        hostRequested.set(false);
        
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        
        if (senderThread != null) {
            try {
                senderThread.join(300); // 等待最多300ms
            } catch (InterruptedException e) {}
            senderThread = null;
        }
        
        cleanup();
        LimeLog.info("麦克风流已停止");
    }
    
    /**
     * 暂停麦克风捕获（保持流运行）
     */
    public void pause() {
        if (micActive.get()) {
            stopMicrophoneCapture();
            LimeLog.info("麦克风捕获已暂停");
        }
    }
    
    /**
     * 恢复麦克风捕获
     */
    public boolean resume() {
        if (!micActive.get() && running.get()) {
            LimeLog.info("尝试恢复麦克风捕获");
            boolean result = startMicrophoneCapture();
            if (result) {
                LimeLog.info("麦克风捕获恢复成功");
            } else {
                LimeLog.warning("麦克风捕获恢复失败");
            }
            return result;
        }
        return false;
    }
    
    public boolean isRunning() {
        return running.get() && micActive.get();
    }
    
    /**
     * 检查麦克风流是否已初始化
     */
    public boolean isInitialized() {
        return running.get();
    }
    
    /**
     * 获取当前音频连续性状态
     */
    public String getAudioContinuityStatus() {
        return AudioDiagnostics.getCurrentStats();
    }
    
    /**
     * 强制生成诊断报告
     */
    public void generateDiagnosticReport() {
        AudioDiagnostics.reportStatistics();
    }
    
    private void cleanup() {
        synchronized (encoderLock) {
            if (encoder != null) {
                encoder.release();
                encoder = null;
            }
        }
        
        packetQueue.clear();
    }

    @Override
    public void onMicrophoneData(byte[] data, int offset, int length) {
        if (!running.get() || !micActive.get()) {
            return;
        }
        
        try {
            byte[] encoded = null;
            
            // Synchronized access to encoder
            synchronized (encoderLock) {
                if (encoder != null) {
                    encoded = encoder.encode(data, offset, length);
                }
            }
            
            if (encoded != null) {
                // 记录编码成功
                AudioDiagnostics.recordFrameEncoded();
                
                int queueSize = packetQueue.size();
                
                if (queueSize >= MicrophoneConfig.MAX_QUEUE_SIZE) {
                    // 队列已满，丢弃最旧的数据包
                    packetQueue.poll();
                    AudioDiagnostics.recordFrameDropped();
                    LimeLog.warning("音频队列已满，丢弃最旧数据包");
                }
                
                // 将编码数据加入队列
                if (!packetQueue.offer(encoded)) {
                    // 如果仍然无法加入队列，丢弃当前数据包
                    AudioDiagnostics.recordFrameDropped();
                    LimeLog.warning("无法将编码数据加入队列，丢弃当前数据包");
                }
            }
        } catch (Exception e) {
            AudioDiagnostics.recordEncodingError();
            LimeLog.warning("音频编码错误: " + e.getMessage());
        }
    }
    
    private void senderThreadProc() {
        long lastSendTime = 0;
        long sendCount = 0;
        long totalLatency = 0;
        long maxLatency = 0;
        long lastStatsTime = System.currentTimeMillis();
        
        while (running.get()) {
            try {
                // 检查连接状态和麦克风状态
                if (!hostRequested.get() || !micActive.get()) {
                    Thread.sleep(MicrophoneConfig.SENDER_THREAD_SLEEP_MS);
                    continue;
                }
                
                // 额外检查：如果连接已断开，立即停止发送
                if (conn == null || !isConnectionActive()) {
                    LimeLog.info("检测到连接断开，停止麦克风发送");
                    break;
                }

                long currentTime = System.currentTimeMillis();
                
                // 动态调整发送频率，基于队列大小
                int queueSize = packetQueue.size();
                long targetInterval = MicrophoneConfig.FRAME_INTERVAL_MS;
                
                // 如果队列过大，加快发送频率
                if (queueSize > MicrophoneConfig.MAX_QUEUE_SIZE * 0.7) {
                    targetInterval = Math.max(5, MicrophoneConfig.FRAME_INTERVAL_MS / 2);
                } else if (queueSize < MicrophoneConfig.MAX_QUEUE_SIZE * 0.3) {
                    // 如果队列较小，可以稍微放慢
                    targetInterval = MicrophoneConfig.FRAME_INTERVAL_MS;
                }
                
                if (currentTime - lastSendTime < targetInterval) {
                    Thread.sleep(1);
                    continue;
                }

                byte[] encoded = packetQueue.poll();
                if (encoded == null) {
                    // 没有数据可发送，短暂等待
                    Thread.sleep(1);
                    continue;
                }

                // 计算发送延迟
                long sendLatency = currentTime - lastSendTime;
                totalLatency += sendLatency;
                maxLatency = Math.max(maxLatency, sendLatency);

                // 发送 Opus 数据
                int result = MoonBridge.sendMicrophoneOpusData(encoded);
                if (result < 0) {
                    AudioDiagnostics.recordSendingError();
                    LimeLog.warning("麦克风数据发送失败: " + result);
                    continue;
                }
                
                lastSendTime = currentTime;
                sendCount++;
                
                // 记录发送成功
                AudioDiagnostics.recordFrameSent();
                
                // 每100个包记录一次详细统计信息
                if (sendCount % 100 == 0) {
                    long currentStatsTime = System.currentTimeMillis();
                    long statsInterval = currentStatsTime - lastStatsTime;
                    double avgLatency = sendCount > 0 ? (double) totalLatency / sendCount : 0;
                    
                    LimeLog.info(String.format("麦克风发送统计: 包数=%d, 队列大小=%d, 平均延迟=%.1fms, 最大延迟=%dms, 统计间隔=%dms", 
                        sendCount, queueSize, avgLatency, maxLatency, statsInterval));
                    
                    // 重置统计
                    lastStatsTime = currentStatsTime;
                    totalLatency = 0;
                    maxLatency = 0;
                }
                
            } catch (InterruptedException e) {
                break;
            }
        }
        
        LimeLog.info("麦克风发送线程已结束");
    }
    
    /**
     * 检查连接是否仍然活跃
     */
    private boolean isConnectionActive() {
        try {
            // 尝试获取主机地址，如果失败说明连接已断开
            String hostAddress = conn.getHost();
            return hostAddress != null && !hostAddress.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
