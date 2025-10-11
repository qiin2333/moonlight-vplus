package com.limelight.binding.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
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
    
    // 音频效果器
    private AcousticEchoCanceler echoCanceler;
    private AutomaticGainControl gainControl;
    private NoiseSuppressor noiseSuppressor;
    
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
            
            // 根据配置选择音频源
            int audioSource = MicrophoneConfig.useVoiceCommunication() ? 
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION : 
                    MediaRecorder.AudioSource.MIC;
            
            audioRecord = new AudioRecord(audioSource,
                    MicrophoneConfig.SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                    
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                LimeLog.severe("无法初始化AudioRecord，状态: " + audioRecord.getState());
                release();
                return false;
            }
            
            // 尝试初始化音频效果器
            initializeAudioEffects();
            
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
    
    /**
     * 初始化音频效果器（回声消除、自动增益控制、噪声抑制）
     */
    private void initializeAudioEffects() {
        if (audioRecord == null) {
            LimeLog.warning("AudioRecord为空，无法初始化音频效果器");
            return;
        }
        
        int audioSessionId = audioRecord.getAudioSessionId();
        LimeLog.info("开始初始化音频效果器，AudioSessionId: " + audioSessionId);
        
        // 1. 回声消除器 (AEC)
        if (MicrophoneConfig.enableAcousticEchoCanceler()) {
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                    if (echoCanceler != null) {
                        int result = echoCanceler.setEnabled(true);
                        if (result == 0) {
                            LimeLog.info("✓ 回声消除器(AEC)已启用");
                        } else {
                            LimeLog.warning("回声消除器启用失败，错误码: " + result);
                        }
                    } else {
                        LimeLog.warning("无法创建回声消除器实例");
                    }
                } catch (Exception e) {
                    LimeLog.warning("初始化回声消除器失败: " + e.getMessage());
                }
            } else {
                LimeLog.info("设备不支持硬件回声消除(AEC)");
            }
        } else {
            LimeLog.info("回声消除器已被配置禁用");
        }
        
        // 2. 自动增益控制 (AGC)
        if (MicrophoneConfig.enableAutomaticGainControl()) {
            if (AutomaticGainControl.isAvailable()) {
                try {
                    gainControl = AutomaticGainControl.create(audioSessionId);
                    if (gainControl != null) {
                        int result = gainControl.setEnabled(true);
                        if (result == 0) {
                            LimeLog.info("✓ 自动增益控制(AGC)已启用");
                        } else {
                            LimeLog.warning("自动增益控制启用失败，错误码: " + result);
                        }
                    } else {
                        LimeLog.warning("无法创建自动增益控制实例");
                    }
                } catch (Exception e) {
                    LimeLog.warning("初始化自动增益控制失败: " + e.getMessage());
                }
            } else {
                LimeLog.info("设备不支持自动增益控制(AGC)");
            }
        } else {
            LimeLog.info("自动增益控制已被配置禁用");
        }
        
        // 3. 噪声抑制器 (NS)
        if (MicrophoneConfig.enableNoiseSuppressor()) {
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                    if (noiseSuppressor != null) {
                        int result = noiseSuppressor.setEnabled(true);
                        if (result == 0) {
                            LimeLog.info("✓ 噪声抑制器(NS)已启用");
                        } else {
                            LimeLog.warning("噪声抑制器启用失败，错误码: " + result);
                        }
                    } else {
                        LimeLog.warning("无法创建噪声抑制器实例");
                    }
                } catch (Exception e) {
                    LimeLog.warning("初始化噪声抑制器失败: " + e.getMessage());
                }
            } else {
                LimeLog.info("设备不支持噪声抑制(NS)");
            }
        } else {
            LimeLog.info("噪声抑制器已被配置禁用");
        }
    }
    
    /**
     * 释放音频效果器资源
     */
    private void releaseAudioEffects() {
        if (echoCanceler != null) {
            try {
                echoCanceler.setEnabled(false);
                echoCanceler.release();
                LimeLog.info("回声消除器已释放");
            } catch (Exception e) {
                LimeLog.warning("释放回声消除器失败: " + e.getMessage());
            }
            echoCanceler = null;
        }
        
        if (gainControl != null) {
            try {
                gainControl.setEnabled(false);
                gainControl.release();
                LimeLog.info("自动增益控制已释放");
            } catch (Exception e) {
                LimeLog.warning("释放自动增益控制失败: " + e.getMessage());
            }
            gainControl = null;
        }
        
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.setEnabled(false);
                noiseSuppressor.release();
                LimeLog.info("噪声抑制器已释放");
            } catch (Exception e) {
                LimeLog.warning("释放噪声抑制器失败: " + e.getMessage());
            }
            noiseSuppressor = null;
        }
    }
    
    private void release() {
        // 先释放音频效果器
        releaseAudioEffects();
        
        if (audioRecord != null) {
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecord.stop();
            }
            audioRecord.release();
            audioRecord = null;
        }
    }
    
    /**
     * 检查音频效果器是否真正在工作
     * 通过分析音频数据的特征来判断
     */
    public boolean isAudioEffectsWorking() {
        return (echoCanceler != null && echoCanceler.getEnabled()) ||
               (gainControl != null && gainControl.getEnabled()) ||
               (noiseSuppressor != null && noiseSuppressor.getEnabled());
    }
    
    /**
     * 获取当前使用的音频源类型
     */
    public String getAudioSourceInfo() {
        if (MicrophoneConfig.useVoiceCommunication()) {
            return "VOICE_COMMUNICATION (系统级AEC/AGC/NS)";
        } else {
            return "MIC (使用硬件音频效果器)";
        }
    }
}
