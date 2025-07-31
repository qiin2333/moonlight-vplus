package com.limelight.binding.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class MicrophoneCapture {
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    private Thread captureThread;
    private AudioRecord audioRecord;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int bufferSize;
    private MicrophoneDataCallback dataCallback;
    
    // 音频帧缓冲
    private byte[] frameBuffer;
    private int frameBufferPos = 0;
    
    // 时序控制
    private long lastFrameTime = 0;
    private long frameCount = 0;
    
    public interface MicrophoneDataCallback {
        void onMicrophoneData(byte[] data, int offset, int length);
    }
    
    public MicrophoneCapture(MicrophoneDataCallback callback) {
        this.dataCallback = callback;
        // 使用更小的缓冲区进行更频繁的读取
        this.bufferSize = MicrophoneConfig.CAPTURE_BUFFER_SIZE;
        // 初始化帧缓冲区
        this.frameBuffer = new byte[MicrophoneConfig.BYTES_PER_FRAME];
    }
    
    public boolean start() {
        if (running.get()) {
            return true; // 已经运行中
        }
        
        try {
            // 检查最小缓冲区大小
            int minBufferSize = AudioRecord.getMinBufferSize(MicrophoneConfig.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                LimeLog.severe("不支持的音频参数");
                return false;
            } else if (minBufferSize == AudioRecord.ERROR) {
                LimeLog.severe("无法获取最小缓冲区大小");
                return false;
            }
            
            // 确保缓冲区大小足够
            bufferSize = Math.max(minBufferSize * 2, MicrophoneConfig.CAPTURE_BUFFER_SIZE);
            
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    MicrophoneConfig.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                    
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                LimeLog.severe("无法初始化AudioRecord，状态: " + audioRecord.getState());
                release();
                return false;
            }
            
            running.set(true);
            lastFrameTime = SystemClock.elapsedRealtimeNanos();
            
            captureThread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                
                ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
                byte[] data = new byte[bufferSize];
                
                try {
                    audioRecord.startRecording();
                    LimeLog.info("麦克风捕获已启动，缓冲区大小: " + bufferSize + " 字节");
                    
                    while (running.get()) {
                        int bytesRead = audioRecord.read(buffer, bufferSize);
                        if (bytesRead > 0) {
                            buffer.get(data, 0, bytesRead);
                            buffer.clear();
                            
                            // 处理音频数据，确保帧的连续性
                            processAudioData(data, 0, bytesRead);
                        } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                            LimeLog.warning("AudioRecord读取错误: ERROR_INVALID_OPERATION");
                            break;
                        } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                            LimeLog.warning("AudioRecord读取错误: ERROR_BAD_VALUE");
                            break;
                        }
                    }
                } catch (SecurityException e) {
                    LimeLog.severe("麦克风权限不足: " + e.getMessage());
                } catch (Exception e) {
                    LimeLog.severe("麦克风捕获出错: " + e.getMessage());
                } finally {
                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        try {
                            audioRecord.stop();
                        } catch (Exception e) {
                            LimeLog.warning("停止AudioRecord时出错: " + e.getMessage());
                        }
                    }
                }
            }, "MicrophoneCapture");
            
            captureThread.start();
            return true;
        } catch (SecurityException e) {
            LimeLog.severe("麦克风权限不足: " + e.getMessage());
            release();
            return false;
        } catch (Exception e) {
            LimeLog.severe("无法创建麦克风捕获: " + e.getMessage());
            release();
            return false;
        }
    }
    
    /**
     * 处理音频数据，确保发送完整的帧
     */
    private void processAudioData(byte[] data, int offset, int length) {
        int remainingBytes = length;
        int dataOffset = offset;
        
        while (remainingBytes > 0) {
            // 计算当前帧还需要多少字节
            int bytesNeeded = MicrophoneConfig.BYTES_PER_FRAME - frameBufferPos;
            int bytesToCopy = Math.min(remainingBytes, bytesNeeded);
            
            // 复制数据到帧缓冲区
            System.arraycopy(data, dataOffset, frameBuffer, frameBufferPos, bytesToCopy);
            frameBufferPos += bytesToCopy;
            dataOffset += bytesToCopy;
            remainingBytes -= bytesToCopy;
            
            // 如果帧缓冲区满了，发送完整的帧
            if (frameBufferPos >= MicrophoneConfig.BYTES_PER_FRAME) {
                // 检查时序
                long currentTime = SystemClock.elapsedRealtimeNanos();
                long timeDiff = currentTime - lastFrameTime;
                
                if (MicrophoneConfig.ENABLE_AUDIO_SYNC && 
                    timeDiff < MicrophoneConfig.FRAME_INTERVAL_NS * 0.8) {
                    // 帧间隔太短，等待一下
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                
                // 发送帧
                dataCallback.onMicrophoneData(frameBuffer, 0, MicrophoneConfig.BYTES_PER_FRAME);
                frameBufferPos = 0; // 重置缓冲区位置
                lastFrameTime = currentTime;
                frameCount++;
                
                // 记录诊断信息
                AudioDiagnostics.recordFrameCaptured();
                
                // 每100帧记录一次统计信息
                if (frameCount % 100 == 0) {
                    LimeLog.info("麦克风帧统计: " + frameCount + " 帧, 平均间隔: " + 
                               (timeDiff / 1000000) + "ms");
                }
            }
        }
    }
    
    public void stop() {
        running.set(false);
        
        if (captureThread != null) {
            try {
                captureThread.join(300);
            } catch (InterruptedException e) {
                // 忽略
            }
            captureThread = null;
        }
        
        release();
    }
    
    private void release() {
        if (audioRecord != null) {
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecord.stop();
            }
            audioRecord.release();
            audioRecord = null;
        }
    }
}
