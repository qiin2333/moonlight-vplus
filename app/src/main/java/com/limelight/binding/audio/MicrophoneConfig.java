package com.limelight.binding.audio;

import android.content.Context;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * 麦克风配置类
 * 管理麦克风相关的配置参数
 */
public class MicrophoneConfig {
    // 音频参数
    public static final int SAMPLE_RATE = 48000; // 采样率
    public static final int CHANNELS = 1; // 声道数（单声道）
    private static int opusBitrate = 64; // Opus编码比特率 (默认64 kbps)
    
    // 网络参数
    public static final int DEFAULT_MIC_PORT = 47996; // 默认麦克风端口
    public static final int MAX_QUEUE_SIZE = 5;
    public static final int HOST_REQUEST_CHECK_INTERVAL_MS = 500; // 检查主机请求状态的间隔
    
    // 权限请求码
    public static final int PERMISSION_REQUEST_MICROPHONE = 1001;
    
    // 延迟参数
    public static final int PERMISSION_DELAY_MS = 100; // 权限授予后的延迟时间
    
    // 音频连续性参数
    public static final int FRAME_SIZE_MS = 20; // Opus帧大小 (毫秒)
    public static final int SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_SIZE_MS / 1000; // 每帧采样数 (960)
    public static final int BYTES_PER_FRAME = SAMPLES_PER_FRAME * CHANNELS * 2; // 每帧字节数 (1920)
    
    // 发送线程参数
    public static final int SENDER_THREAD_SLEEP_MS = 5; // 发送线程睡眠时间 (从100减少到5)
    public static final int SENDER_ERROR_RETRY_MS = 5; // 发送错误重试时间 (从10减少到5)
    
    // 音频捕获优化参数
    public static final int CAPTURE_BUFFER_SIZE_MS = 40; // 捕获缓冲区大小 (毫秒)
    public static final int CAPTURE_BUFFER_SIZE = SAMPLE_RATE * CAPTURE_BUFFER_SIZE_MS / 1000 * CHANNELS * 2; // 捕获缓冲区字节数
    public static final int FRAME_INTERVAL_MS = 20; // 帧间隔时间 (毫秒)
    public static final long FRAME_INTERVAL_NS = FRAME_INTERVAL_MS * 1000000L; // 帧间隔纳秒
    
    // 音频质量参数
    public static final boolean ENABLE_AUDIO_SYNC = true; // 启用音频同步
    public static final int MAX_FRAME_DELAY_MS = 50; // 最大帧延迟 (毫秒)
    
    // 回声消除和音频处理参数
    private static boolean enableAEC = true; // 启用回声消除器
    private static boolean enableAGC = true; // 启用自动增益控制
    private static boolean enableNS = true; // 启用噪声抑制
    private static boolean useVoiceComm = false; // 使用VOICE_COMMUNICATION音频源（自动启用AEC+AGC+NS）
    
    /**
     * 获取当前配置的Opus比特率
     * @return 比特率（bps）
     */
    public static int getOpusBitrate() {
        return opusBitrate;
    }
    
    /**
     * 设置Opus比特率
     * @param bitrateKbps 比特率（kbps）
     */
    public static void setOpusBitrate(int bitrateKbps) {
        opusBitrate = bitrateKbps * 1000; // 转换为bps
    }
    
    /**
     * 从配置中更新比特率设置
     * @param context 上下文
     */
    public static void updateBitrateFromConfig(Context context) {
        if (context != null) {
            PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
            setOpusBitrate(config.micBitrate);
        }
    }
    
    // ========== 回声消除和音频处理配置方法 ==========
    
    /**
     * 是否启用回声消除器(AEC)
     */
    public static boolean enableAcousticEchoCanceler() {
        return enableAEC;
    }
    
    /**
     * 设置是否启用回声消除器(AEC)
     */
    public static void setEnableAcousticEchoCanceler(boolean enable) {
        enableAEC = enable;
    }
    
    /**
     * 是否启用自动增益控制(AGC)
     */
    public static boolean enableAutomaticGainControl() {
        return enableAGC;
    }
    
    /**
     * 设置是否启用自动增益控制(AGC)
     */
    public static void setEnableAutomaticGainControl(boolean enable) {
        enableAGC = enable;
    }
    
    /**
     * 是否启用噪声抑制(NS)
     */
    public static boolean enableNoiseSuppressor() {
        return enableNS;
    }
    
    /**
     * 设置是否启用噪声抑制(NS)
     */
    public static void setEnableNoiseSuppressor(boolean enable) {
        enableNS = enable;
    }
    
    /**
     * 是否使用VOICE_COMMUNICATION音频源
     * VOICE_COMMUNICATION会自动启用系统级的AEC、AGC、NS
     */
    public static boolean useVoiceCommunication() {
        return useVoiceComm;
    }
    
    /**
     * 设置是否使用VOICE_COMMUNICATION音频源
     */
    public static void setUseVoiceCommunication(boolean use) {
        useVoiceComm = use;
    }
    
    /**
     * 获取音频处理配置的摘要信息
     */
    public static String getAudioProcessingConfigSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("音频处理配置:\n");
        summary.append("音频源: ").append(useVoiceComm ? "VOICE_COMMUNICATION" : "MIC").append("\n");
        summary.append("回声消除(AEC): ").append(enableAEC ? "启用" : "禁用").append("\n");
        summary.append("自动增益(AGC): ").append(enableAGC ? "启用" : "禁用").append("\n");
        summary.append("噪声抑制(NS): ").append(enableNS ? "启用" : "禁用");
        return summary.toString();
    }
    
    private MicrophoneConfig() {
        // 私有构造函数，防止实例化
    }
} 