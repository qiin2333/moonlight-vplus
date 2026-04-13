package com.limelight.binding.audio

import android.content.Context
import com.limelight.preferences.PreferenceConfiguration

/**
 * 麦克风配置类
 * 管理麦克风相关的配置参数
 */
object MicrophoneConfig {
    // 音频参数
    const val SAMPLE_RATE = 48000 // 采样率
    const val CHANNELS = 1 // 声道数（单声道）
    private var opusBitrateValue = 64 // Opus编码比特率 (默认64 kbps)

    // 网络参数
    const val MAX_QUEUE_SIZE = 5

    // 权限请求码
    const val PERMISSION_REQUEST_MICROPHONE = 1001

    // 延迟参数
    const val PERMISSION_DELAY_MS = 100 // 权限授予后的延迟时间

    // 音频连续性参数
    const val FRAME_SIZE_MS = 20 // Opus帧大小 (毫秒)
    const val SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 每帧采样数 (960)
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * CHANNELS * 2 // 每帧字节数 (1920)

    // 发送线程参数
    const val SENDER_THREAD_SLEEP_MS = 5 // 发送线程睡眠时间

    // 音频捕获优化参数
    const val CAPTURE_BUFFER_SIZE_MS = 40 // 捕获缓冲区大小 (毫秒)
    const val CAPTURE_BUFFER_SIZE = SAMPLE_RATE * CAPTURE_BUFFER_SIZE_MS / 1000 * CHANNELS * 2 // 捕获缓冲区字节数
    const val FRAME_INTERVAL_MS = 20 // 帧间隔时间 (毫秒)
    const val FRAME_INTERVAL_NS = FRAME_INTERVAL_MS * 1000000L // 帧间隔纳秒

    // 音频质量参数
    const val ENABLE_AUDIO_SYNC = true // 启用音频同步

    // 回声消除和音频处理参数
    private var enableAEC = true // 启用回声消除器
    private var enableAGC = true // 启用自动增益控制
    private var enableNS = true // 启用噪声抑制
    private var useVoiceComm = false // 使用VOICE_COMMUNICATION音频源（自动启用AEC+AGC+NS）

    /**
     * 获取当前配置的Opus比特率
     * @return 比特率（bps）
     */
    fun getOpusBitrate(): Int {
        return opusBitrateValue
    }

    /**
     * 设置Opus比特率
     * @param bitrateKbps 比特率（kbps）
     */
    fun setOpusBitrate(bitrateKbps: Int) {
        opusBitrateValue = bitrateKbps * 1000 // 转换为bps
    }

    /**
     * 从配置中更新比特率设置
     * @param context 上下文
     */
    fun updateBitrateFromConfig(context: Context?) {
        if (context != null) {
            val config = PreferenceConfiguration.readPreferences(context)
            setOpusBitrate(config.micBitrate)
        }
    }

    // ========== 回声消除和音频处理配置方法 ==========

    /**
     * 是否启用回声消除器(AEC)
     */
    fun enableAcousticEchoCanceler(): Boolean {
        return enableAEC
    }

    /**
     * 设置是否启用回声消除器(AEC)
     */
    fun setEnableAcousticEchoCanceler(enable: Boolean) {
        enableAEC = enable
    }

    /**
     * 是否启用自动增益控制(AGC)
     */
    fun enableAutomaticGainControl(): Boolean {
        return enableAGC
    }

    /**
     * 设置是否启用自动增益控制(AGC)
     */
    fun setEnableAutomaticGainControl(enable: Boolean) {
        enableAGC = enable
    }

    /**
     * 是否启用噪声抑制(NS)
     */
    fun enableNoiseSuppressor(): Boolean {
        return enableNS
    }

    /**
     * 设置是否启用噪声抑制(NS)
     */
    fun setEnableNoiseSuppressor(enable: Boolean) {
        enableNS = enable
    }

    /**
     * 是否使用VOICE_COMMUNICATION音频源
     * VOICE_COMMUNICATION会自动启用系统级的AEC、AGC、NS
     */
    fun useVoiceCommunication(): Boolean {
        return useVoiceComm
    }

    /**
     * 设置是否使用VOICE_COMMUNICATION音频源
     */
    fun setUseVoiceCommunication(use: Boolean) {
        useVoiceComm = use
    }

    /**
     * 获取音频处理配置的摘要信息
     */
    fun getAudioProcessingConfigSummary(): String {
        return buildString {
            append("音频处理配置:\n")
            append("音频源: ").append(if (useVoiceComm) "VOICE_COMMUNICATION" else "MIC").append("\n")
            append("回声消除(AEC): ").append(if (enableAEC) "启用" else "禁用").append("\n")
            append("自动增益(AGC): ").append(if (enableAGC) "启用" else "禁用").append("\n")
            append("噪声抑制(NS): ").append(if (enableNS) "启用" else "禁用")
        }
    }
}
