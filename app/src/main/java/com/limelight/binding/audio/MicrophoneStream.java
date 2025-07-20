package com.limelight.binding.audio;

import com.limelight.LimeLog;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.jni.MoonBridge;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MicrophoneStream implements MicrophoneCapture.MicrophoneDataCallback {
    
    private final NvConnection conn;
    private MicrophoneCapture capture;
    private OpusEncoder encoder;
    private Thread senderThread;
    private Thread checkHostRequestThread;
    private DatagramSocket socket;
    private InetAddress host;
    private final AtomicBoolean running = new AtomicBoolean(false); // 麦克风流是否运行
    private final AtomicBoolean micActive = new AtomicBoolean(false); // 麦克风是否实际在捕获
    private final AtomicBoolean hostRequested = new AtomicBoolean(false); // 主机是否请求麦克风
    private LinkedBlockingQueue<byte[]> packetQueue;
    private int sequenceNumber = 0;
    private int micPort;
    
    public MicrophoneStream(NvConnection conn) {
        this.conn = conn;
        this.packetQueue = new LinkedBlockingQueue<>(MicrophoneConfig.MAX_QUEUE_SIZE);
    }
    
    public boolean start() {
        try {
            // 重置音频诊断统计
            AudioDiagnostics.resetStatistics();
            
            // 如果还没有初始化，先初始化
            if (!running.get()) {
                // 启动请求检查线程
                startHostRequestCheck();
                
                // 如果主机已经请求麦克风，立即启动麦克风捕获
                if (MoonBridge.isMicrophoneRequested()) {
                    LimeLog.info("主机请求麦克风，开始捕获");
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
    
    private void startHostRequestCheck() {
        if (checkHostRequestThread != null && checkHostRequestThread.isAlive()) {
            return;
        }
        
        // 创建一个新状态变量，表示线程应该继续运行
        final AtomicBoolean checkThreadRunning = new AtomicBoolean(true);
        
        // 启动一个线程定期检查主机是否请求麦克风
        checkHostRequestThread = new Thread(() -> {
            LimeLog.info("麦克风请求检查线程已启动");
            
            while (checkThreadRunning.get()) {
                boolean isRequested = MoonBridge.isMicrophoneRequested();
                
                // 主机刚刚开始请求麦克风
                if (isRequested && !hostRequested.get()) {
                    hostRequested.set(true);
                    LimeLog.info("检测到主机请求麦克风，开始捕获");
                    try {
                        startMicrophoneCapture();
                    } catch (Exception e) {
                        LimeLog.severe("请求后启动麦克风失败: " + e.getMessage());
                    }
                } 
                // 主机刚刚停止请求麦克风
                else if (!isRequested && hostRequested.get()) {
                    hostRequested.set(false);
                    LimeLog.info("主机停止请求麦克风，暂停捕获");
                    stopMicrophoneCapture();
                }
                
                try {
                    Thread.sleep(MicrophoneConfig.HOST_REQUEST_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    checkThreadRunning.set(false);
                    break;
                }
            }
            
            LimeLog.info("麦克风请求检查线程已结束");
        }, "MicRequestChecker");
        
        checkHostRequestThread.start();
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
        
        if (socket != null) {
            socket.close();
            socket = null;
        }
        
        packetQueue.clear();
        
        // 不停止发送线程，因为我们将来可能需要重新启动麦克风
        LimeLog.info("麦克风捕获已暂停");
    }
    
    private boolean startMicrophoneCapture() {
        if (micActive.get()) {
            return true;
        }
        
        try {
            // 获取RTSP协商的麦克风端口
            micPort = MoonBridge.getMicPortNumber();
            if (micPort == 0) {
                micPort = MicrophoneConfig.DEFAULT_MIC_PORT;
                LimeLog.warning("未获取到协商的麦克风端口，使用默认端口: " + MicrophoneConfig.DEFAULT_MIC_PORT);
            } else {
                LimeLog.info("使用协商的麦克风端口: " + micPort);
            }
            
            // 创建发送socket
            socket = new DatagramSocket();
            host = InetAddress.getByName(conn.getHost());
            
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
        if (checkHostRequestThread != null) {
            checkHostRequestThread.interrupt();
            checkHostRequestThread = null;
        }
        
        if (!running.get()) {
            return;
        }
        
        running.set(false);
        micActive.set(false);
        hostRequested.set(false);
        
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        
        if (senderThread != null) {
            try {
                senderThread.join(500);
            } catch (InterruptedException e) {
                // 忽略
            }
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
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        
        if (socket != null) {
            socket.close();
            socket = null;
        }
        
        packetQueue.clear();
    }
    
    @Override
    public void onMicrophoneData(byte[] data, int offset, int length) {
        if (!running.get() || !micActive.get() || encoder == null) {
            return;
        }
        
        try {
            // 编码音频数据
            byte[] encoded = encoder.encode(data, offset, length);
            if (encoded != null) {
                // 记录编码成功
                AudioDiagnostics.recordFrameEncoded();
                
                // 检查队列大小，如果队列太满，丢弃一些旧数据
                if (packetQueue.size() >= MicrophoneConfig.MAX_QUEUE_SIZE * 0.8) {
                    // 丢弃最旧的数据包，为新数据腾出空间
                    packetQueue.poll();
                    AudioDiagnostics.recordFrameDropped();
                    LimeLog.warning("音频队列接近满，丢弃旧数据包");
                }
                
                // 将编码数据加入队列
                if (!packetQueue.offer(encoded)) {
                    // 队列已满，丢弃最旧的数据包
                    packetQueue.poll();
                    packetQueue.offer(encoded);
                    AudioDiagnostics.recordFrameDropped();
                    LimeLog.warning("音频队列已满，丢弃最旧数据包");
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
        
        while (running.get()) {
            try {
                if (!hostRequested.get() || !micActive.get()) {
                    Thread.sleep(MicrophoneConfig.SENDER_THREAD_SLEEP_MS);
                    continue;
                }

                // 控制发送频率，确保每20ms发送一帧
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSendTime < MicrophoneConfig.FRAME_INTERVAL_MS) {
                    Thread.sleep(1);
                    continue;
                }

                byte[] encoded = packetQueue.poll();
                if (encoded == null) {
                    // 没有数据可发送，短暂等待
                    Thread.sleep(1);
                    continue;
                }

                // 构建正确的麦克风数据包头部（12字节）
                ByteBuffer packetBuf = ByteBuffer.allocate(encoded.length + 12);
                packetBuf.order(ByteOrder.LITTLE_ENDIAN); // 使用小端字节序

                // flags (1字节)
                packetBuf.put((byte) 0x00);
                // packetType (1字节) - OPUS编码类型
                packetBuf.put((byte) 0x61); // MIC_PACKET_TYPE_OPUS
                // sequenceNumber (2字节)
                packetBuf.putShort((short) (sequenceNumber++ & 0xFFFF));
                // timestamp (4字节)
                packetBuf.putInt((int)(currentTime & 0xFFFFFFFF));
                // ssrc (4字节) - 同步源标识符
                packetBuf.putInt(0x12345678); // 使用固定SSRC

                // 添加opus编码数据
                packetBuf.put(encoded);

                DatagramPacket packet = new DatagramPacket(
                        packetBuf.array(), packetBuf.capacity(),
                        host, micPort);
                socket.send(packet);
                
                lastSendTime = currentTime;
                sendCount++;
                
                // 记录发送成功
                AudioDiagnostics.recordFrameSent();
                
                // 每100个包记录一次统计信息
                if (sendCount % 100 == 0) {
                    LimeLog.info("麦克风发送统计: " + sendCount + " 包, 队列大小: " + packetQueue.size());
                }
                
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                AudioDiagnostics.recordSendingError();
                LimeLog.warning("发送麦克风数据错误: " + e.getMessage());
                try {
                    Thread.sleep(MicrophoneConfig.SENDER_ERROR_RETRY_MS);
                } catch (InterruptedException ex) {
                    break;
                }
            }
        }
    }
}
