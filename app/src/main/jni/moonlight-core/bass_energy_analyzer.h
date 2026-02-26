/*
 * Moonlight for HarmonyOS
 * Copyright (C) 2024-2025 Moonlight/AlkaidLab
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

/**
 * @file bass_energy_analyzer.h
 * @brief 低频能量分析器 v3 — 场景识别模式 + 精准低音检测
 *
 * v3 新增场景模式:
 * - 游戏/电影模式 (sceneMode=0): 原有行为，持续低频能量驱动振动
 *   → 爆炸、枪声、引擎轰鸣等低音冲击触发持续振动
 * - 音乐/节奏模式 (sceneMode=1): 鼓点/节拍 onset 检测
 *   → 检测低频能量突变（onset），在鼓点/节拍时触发短脉冲振动
 *   → 使用 spectral flux onset detection + adaptive threshold
 * - 自动模式 (sceneMode=2): 根据音频特征自动切换
 *   → 分析瞬态密度（transient density）判断内容类型
 *   → 高瞬态密度 → 音乐模式，低瞬态密度 → 游戏/电影模式
 *
 * 基础 DSP 管线 (v2 继承):
 * 1. 2 阶 Butterworth 低通滤波器（截止 80Hz，-12dB/oct）
 * 2. 攻击/释放包络跟踪器
 * 3. 自适应噪声门限
 * 4. RMS 绝对音量权重
 *
 * 设计原则:
 * - 零堆分配，所有状态内联
 * - 每帧 PCM 解码后在同一线程调用，无锁
 * - 节流控制：游戏模式 ~25次/秒，音乐模式 ~40次/秒
 */

#ifndef BASS_ENERGY_ANALYZER_H
#define BASS_ENERGY_ANALYZER_H

#include <cstdint>
#include <cmath>
#include <cstring>
#include <chrono>
#include <algorithm>
#include "spectral_onset_detector.h"

class BassEnergyAnalyzer {
public:
    // 场景模式常量
    static constexpr int SCENE_GAME   = 0;  // 游戏/电影（默认）
    static constexpr int SCENE_MUSIC  = 1;  // 音乐/节奏
    static constexpr int SCENE_AUTO   = 2;  // 自动

    /**
     * 初始化分析器
     * @param sampleRate 采样率 (通常 48000)
     * @param channelCount 声道数 (1, 2, 6, 8 等)
     */
    void Init(int sampleRate, int channelCount) {
        sampleRate_ = sampleRate;
        channelCount_ = channelCount;

        // ---- 2 阶 Butterworth LPF 系数计算 ----
        // 截止频率 80Hz，Q = 1/√2 (Butterworth 最大平坦)
        // 参考: Audio EQ Cookbook (Robert Bristow-Johnson)
        const double fc = 80.0;
        const double Q = 0.7071;  // 1/√2
        const double w0 = 2.0 * M_PI * fc / sampleRate;
        const double sinW0 = std::sin(w0);
        const double cosW0 = std::cos(w0);
        const double alpha = sinW0 / (2.0 * Q);

        // LPF 传递函数: H(z) = (b0 + b1*z^-1 + b2*z^-2) / (a0 + a1*z^-1 + a2*z^-2)
        const double a0 = 1.0 + alpha;
        bq_b0_ = static_cast<float>((1.0 - cosW0) / 2.0 / a0);
        bq_b1_ = static_cast<float>((1.0 - cosW0) / a0);
        bq_b2_ = bq_b0_;
        bq_a1_ = static_cast<float>(-2.0 * cosW0 / a0);
        bq_a2_ = static_cast<float>((1.0 - alpha) / a0);

        // ---- 攻击/释放包络系数 ----
        // attack ~5ms → 爆炸枪声起音锐利
        // release ~80ms → 衰减平滑自然
        const double attackMs = 5.0;
        const double releaseMs = 80.0;
        attackCoeff_ = static_cast<float>(1.0 - std::exp(-1.0 / (sampleRate * attackMs / 1000.0)));
        releaseCoeff_ = static_cast<float>(1.0 - std::exp(-1.0 / (sampleRate * releaseMs / 1000.0)));

        // ---- 噪声门限 ----
        noiseFloor_ = 0.0f;
        noiseFloorAlpha_ = static_cast<float>(1.0 - std::exp(-1.0 / (sampleRate * 2.0)));

        // ---- 音乐模式: onset 检测 ----
        // 短窗口 (~80ms): 快速追踪能量脉冲（全频段）
        // 长窗口 (~800ms): 追踪背景能量水平
        // onset = 短窗口能量 / 长窗口能量 > 动态阈值
        // Opus 解码每帧通常 5ms (240 samples @48kHz)，即每秒 ~200 帧
        const int framesPerSec = sampleRate / std::max(240, 1);  // ~200 fps
        shortWindowAlpha_ = 2.0f / (framesPerSec * 0.08f + 1);  // ~80ms EMA
        longWindowAlpha_  = 2.0f / (framesPerSec * 0.8f  + 1);  // ~800ms EMA
        shortWindowEnergy_ = 0.0f;
        longWindowEnergy_ = 0.0f;
        prevFrameEnergy_ = 0.0f;
        onsetCooldownRemaining_ = 0;
        onsetPulseIntensity_ = 0;
        onsetPulseDecay_ = 0;

        // onset 信号平滑追踪 (用于自适应阈值)
        onsetSignalAvg_ = 0.0f;
        onsetSignalAlpha_ = 2.0f / (framesPerSec * 2.0f + 1);  // ~2s 追踪

        // onset 冷却帧数: ~150ms
        onsetCooldownFrames_ = std::max(1, static_cast<int>(framesPerSec * 0.15f));

        // ---- 自动模式: 瞬态密度 + 周期性追踪 ----
        transientCount_ = 0;
        transientWindowFrames_ = 0;
        // 每 3 秒评估一次瞬态密度
        transientWindowSize_ = sampleRate * 3;
        autoDetectedMode_ = SCENE_GAME;  // 默认游戏模式

        // onset 时间戳缓冲区
        onsetHistoryWriteIdx_ = 0;
        onsetHistoryCount_ = 0;
        globalFrameCounter_ = 0;
        std::memset(onsetTimestamps_, 0, sizeof(onsetTimestamps_));

        // 滞后保护
        pendingAutoMode_ = SCENE_GAME;
        pendingAutoModeCount_ = 0;

        // 重置滤波器状态
        bq_x1_ = bq_x2_ = 0.0f;
        bq_y1_ = bq_y2_ = 0.0f;
        envelope_ = 0.0f;
        rmsEnvelope_ = 0.0f;
        lastIntensity_ = 0;
        lastCallbackTime_ = std::chrono::steady_clock::now();
        // 注意: 不重置 enabled_, sensitivity_, sceneMode_
        // 这些用户配置可能在 Init() 之前通过 Java 层设置
        // (Game.java 先调用 setBassEnergyEnabled，之后音频流启动才调 Init)

        // ---- spectral onset detector (self-contained FFT + specflux) ----
        // hop_size = 每帧 per-channel 样本数 (通常 240 @48kHz = 5ms Opus 帧)
        // 使用 specflux 方法：对音乐 onset 检测最佳
        int hopSize = std::max(240, 1);  // Opus 默认每帧 240 samples @48kHz
        onsetDetector_.Init(sampleRate, hopSize, "specflux");
    }

    /**
     * 启用/禁用分析器
     */
    void SetEnabled(bool enabled) {
        enabled_ = enabled;
        if (!enabled) {
            bq_x1_ = bq_x2_ = 0.0f;
            bq_y1_ = bq_y2_ = 0.0f;
            envelope_ = 0.0f;
            rmsEnvelope_ = 0.0f;
            noiseFloor_ = 0.0f;
            shortWindowEnergy_ = 0.0f;
            longWindowEnergy_ = 0.0f;
            prevFrameEnergy_ = 0.0f;
            onsetSignalAvg_ = 0.0f;
            onsetCooldownRemaining_ = 0;
            onsetPulseIntensity_ = 0;
            onsetPulseDecay_ = 0;
            transientCount_ = 0;
            transientWindowFrames_ = 0;
            onsetHistoryWriteIdx_ = 0;
            onsetHistoryCount_ = 0;
            globalFrameCounter_ = 0;
            pendingAutoMode_ = SCENE_GAME;
            pendingAutoModeCount_ = 0;
            lastIntensity_ = 0;
            onsetDetector_.Reset();
        }
    }

    bool IsEnabled() const { return enabled_; }

    /**
     * 设置灵敏度 (0.1 - 3.0, 默认 1.0)
     */
    void SetSensitivity(float sensitivity) {
        sensitivity_ = std::max(0.1f, std::min(3.0f, sensitivity));
    }

    /**
     * 设置场景模式
     * @param mode SCENE_GAME(0) / SCENE_MUSIC(1) / SCENE_AUTO(2)
     */
    void SetSceneMode(int mode) {
        if (mode < SCENE_GAME || mode > SCENE_AUTO) mode = SCENE_GAME;
        sceneMode_ = mode;
        // 切换模式时重置 onset 状态
        shortWindowEnergy_ = 0.0f;
        longWindowEnergy_ = 0.0f;
        prevFrameEnergy_ = 0.0f;
        onsetSignalAvg_ = 0.0f;
        onsetCooldownRemaining_ = 0;
        onsetPulseIntensity_ = 0;
        onsetPulseDecay_ = 0;
        transientCount_ = 0;
        transientWindowFrames_ = 0;
        onsetHistoryWriteIdx_ = 0;
        onsetHistoryCount_ = 0;
        globalFrameCounter_ = 0;
        pendingAutoMode_ = SCENE_GAME;
        pendingAutoModeCount_ = 0;
    }

    int GetSceneMode() const { return sceneMode_; }

    /**
     * 处理一帧 PCM 数据，返回是否应该触发回调
     * @param pcmData PCM 数据 (int16, 交错多声道)
     * @param sampleCount 每声道采样数 (per-channel frame count)
     * @param outIntensity 输出振动强度 (0-100)
     * @return true 如果应该触发 TSFN 回调（经过节流控制）
     */
    bool ProcessFrame(const int16_t* pcmData, int sampleCount, int& outIntensity) {
        if (!enabled_ || pcmData == nullptr || sampleCount <= 0) {
            outIntensity = 0;
            return false;
        }

        const int frameCount = sampleCount;

        // ---- 共用 DSP 管线: LPF + 包络 + RMS ----
        float sumSquares = 0.0f;
        float frameEnergy = 0.0f;     // 本帧低频能量 (LPF 后)
        float fullBandEnergy = 0.0f;  // 本帧全频段能量 (未滤波，用于音乐模式 onset)

        for (int i = 0; i < frameCount; i++) {
            float sample = 0.0f;
            for (int ch = 0; ch < channelCount_; ch++) {
                sample += static_cast<float>(pcmData[i * channelCount_ + ch]);
            }
            sample /= (channelCount_ * 32768.0f);
            sumSquares += sample * sample;

            // 全频段能量 (含鼓点的中高频瞬态)
            fullBandEnergy += std::fabs(sample);

            // 2 阶 Biquad LPF
            float filtered = bq_b0_ * sample + bq_b1_ * bq_x1_ + bq_b2_ * bq_x2_
                           - bq_a1_ * bq_y1_ - bq_a2_ * bq_y2_;
            bq_x2_ = bq_x1_;
            bq_x1_ = sample;
            bq_y2_ = bq_y1_;
            bq_y1_ = filtered;

            // 攻击/释放包络跟踪
            float rectified = std::fabs(filtered);
            if (rectified > envelope_) {
                envelope_ += attackCoeff_ * (rectified - envelope_);
            } else {
                envelope_ += releaseCoeff_ * (rectified - envelope_);
            }

            // 累积本帧低频能量
            frameEnergy += rectified;
        }

        // 帧平均
        frameEnergy = (frameCount > 0) ? (frameEnergy / frameCount) : 0.0f;
        fullBandEnergy = (frameCount > 0) ? (fullBandEnergy / frameCount) : 0.0f;

        // RMS 计算
        float rms = (frameCount > 0) ? std::sqrt(sumSquares / frameCount) : 0.0f;
        if (rms > rmsEnvelope_) {
            rmsEnvelope_ += 0.3f * (rms - rmsEnvelope_);
        } else {
            rmsEnvelope_ += 0.02f * (rms - rmsEnvelope_);
        }

        // 绝对音量权重
        const float rmsLow = 0.002f;
        const float rmsHigh = 0.015f;
        float volumeWeight;
        if (rmsEnvelope_ <= rmsLow) {
            volumeWeight = 0.0f;
        } else if (rmsEnvelope_ >= rmsHigh) {
            volumeWeight = 1.0f;
        } else {
            float t = (rmsEnvelope_ - rmsLow) / (rmsHigh - rmsLow);
            volumeWeight = t * t * (3.0f - 2.0f * t);
        }

        // 噪声门限
        if (envelope_ < noiseFloor_ || noiseFloor_ < 1e-8f) {
            noiseFloor_ = envelope_;
        } else {
            noiseFloor_ += noiseFloorAlpha_ * (envelope_ - noiseFloor_);
        }

        // ---- 确定当前实际使用的模式 ----
        int activeMode = sceneMode_;
        if (sceneMode_ == SCENE_AUTO) {
            activeMode = autoDetectMode(fullBandEnergy, frameCount);
        }

        // ---- spectral onset 检测 (音乐模式/自动模式时运行) ----
        bool onsetDetected = false;
        if (activeMode == SCENE_MUSIC && onsetDetector_.IsInitialized()) {
            onsetDetector_.ProcessFrame(pcmData, sampleCount, channelCount_, onsetDetected);
        }

        // ---- 分模式处理 ----
        int intensity;
        if (activeMode == SCENE_MUSIC) {
            // 音乐模式: spectral onset + 我们的能量分析计算强度
            intensity = processMusicMode(fullBandEnergy, frameEnergy, volumeWeight,
                                         onsetDetected);
        } else {
            intensity = processGameMode(volumeWeight);
        }

        outIntensity = intensity;

        // ---- 节流控制 ----
        auto now = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastCallbackTime_).count();

        // 音乐模式需要更快的回调频率 (~40次/秒, 25ms)
        int minInterval = (activeMode == SCENE_MUSIC) ? 25 : 40;
        if (elapsed < minInterval) {
            return false;
        }

        int delta = std::abs(intensity - lastIntensity_);
        bool shouldCallback = (lastIntensity_ > 0 && intensity == 0)
                           || (intensity > 0 && lastIntensity_ == 0)
                           || (delta >= 5);

        if (!shouldCallback) {
            return false;
        }

        lastIntensity_ = intensity;
        lastCallbackTime_ = now;
        return true;
    }

    int GetCurrentIntensity() const { return lastIntensity_; }

private:
    // ==================== 游戏/电影模式处理 ====================
    int processGameMode(float volumeWeight) {
        float threshold = noiseFloor_ * 8.0f + 0.005f;
        float effectiveEnergy = std::max(0.0f, envelope_ - threshold);

        float normalized = effectiveEnergy * sensitivity_ * 10.0f;
        normalized = std::min(normalized, 1.0f);

        // pow(x, 2.5) 非线性映射 — 只有强冲击才能突破
        float mappedIntensity = std::pow(normalized, 2.5f) * volumeWeight * 100.0f;
        int intensity = static_cast<int>(mappedIntensity);
        return std::max(0, std::min(100, intensity));
    }

    // ==================== 音乐/节奏模式处理 ====================
    /**
     * spectral onset + 能量分析 混合方案:
     *
     * 1. SpectralOnsetDetector (specflux) 做主 onset 检测 — FFT + 自适应白化 + peak-picking
     * 2. 我们的瞬时能量比率做辅助/备用检测 — 零延迟
     * 3. 振动强度由能量分析计算 — onset 只提供 onset 时机
     *
     * SpectralOnsetDetector 的算法:
     * - Radix-2 FFT + Hann 窗 STFT
     * - 自适应频谱白化 (每频点独立峰值跟踪)
     * - 对数压缩 + 半波整流频谱通量
     * - 滑动窗口 median + mean 自适应阈值 peak-picking
     */
    int processMusicMode(float fullBandEnergy, float lowFreqEnergy, float volumeWeight,
                         bool onsetDetected) {
        // ---- 更新窗口 (用于强度计算 + 备用检测) ----
        shortWindowEnergy_ += shortWindowAlpha_ * (fullBandEnergy - shortWindowEnergy_);
        longWindowEnergy_  += longWindowAlpha_  * (fullBandEnergy - longWindowEnergy_);

        // 冷却期递减
        if (onsetCooldownRemaining_ > 0) {
            onsetCooldownRemaining_--;
        }

        // ---- 备用 onset 检测 (当 spectral onset 未触发时的补充) ----
        float onsetSignal = (longWindowEnergy_ > 1e-6f)
            ? (fullBandEnergy / longWindowEnergy_)
            : 0.0f;
        float smoothedOnset = (longWindowEnergy_ > 1e-6f)
            ? (shortWindowEnergy_ / longWindowEnergy_)
            : 0.0f;
        onsetSignalAvg_ += onsetSignalAlpha_ * (smoothedOnset - onsetSignalAvg_);
        float adaptiveThreshold = std::max(onsetSignalAvg_ * 1.5f, 1.5f);

        float energyDelta = fullBandEnergy - prevFrameEnergy_;
        prevFrameEnergy_ = fullBandEnergy;

        // 备用检测: 只有在 spectral onset 未触发时，且瞬时能量远超背景
        bool fallbackOnset = !onsetDetected
                          && (onsetSignal > adaptiveThreshold)
                          && (onsetCooldownRemaining_ <= 0)
                          && (energyDelta > 0.0f);

        // ---- 综合 onset 判定 ----
        bool isOnset = false;
        if (onsetDetected && onsetCooldownRemaining_ <= 0) {
            isOnset = true;  // spectral onset 触发 (高优先级)
        } else if (fallbackOnset) {
            isOnset = true;  // 备用触发
        }

        if (isOnset) {
            // 振动强度: 基于当前能量与背景的比率
            float ratio = (longWindowEnergy_ > 1e-6f)
                ? (fullBandEnergy / longWindowEnergy_) : 1.0f;
            float pulseStrength = std::min((ratio - 1.0f) / 2.0f, 1.0f);
            pulseStrength = std::pow(std::max(pulseStrength, 0.1f), 0.5f);

            // 低频加成
            float lowFreqBoost = 1.0f;
            if (lowFreqEnergy > 0.001f) {
                lowFreqBoost = 1.0f + std::min(lowFreqEnergy * 20.0f, 0.4f);
            }

            onsetPulseIntensity_ = static_cast<int>(
                pulseStrength * lowFreqBoost * sensitivity_ * volumeWeight * 100.0f
            );
            onsetPulseIntensity_ = std::max(15, std::min(100, onsetPulseIntensity_));

            // 衰减步数: 5 步 ≈ 125ms
            onsetPulseDecay_ = 5;

            // 冷却期
            onsetCooldownRemaining_ = onsetCooldownFrames_;
        }

        // ---- 脉冲输出 + 衰减 ----
        if (onsetPulseDecay_ > 0) {
            float decayFactor = static_cast<float>(onsetPulseDecay_) / 5.0f;
            decayFactor = decayFactor * decayFactor;  // 二次衰减
            int output = static_cast<int>(onsetPulseIntensity_ * decayFactor);
            onsetPulseDecay_--;
            return std::max(0, std::min(100, output));
        }

        return 0;
    }

    // ==================== 自动模式: 场景检测 ====================
    /**
     * 瞬态密度 + 周期性分析 + 滞后保护:
     *
     * 1. 每帧检测瞬态（能量 > 背景 × 1.8）
     * 2. 记录瞬态时间戳到循环缓冲区 (最近 16 次)
     * 3. 每 3 秒评估:
     *    a. 瞬态密度: 6~30次/3s → 可能是音乐
     *    b. 周期性: 计算 onset 间隔的 CV (变异系数):
     *       - CV < 0.5 = 规律 → 有节拍 → 音乐
     *       - CV > 0.5 = 随机 → 游戏/电影
     *    c. 两个条件都满足 → 判定音乐
     * 4. 滞后保护: 连续 2 次相同判定才真正切换
     */
    int autoDetectMode(float frameEnergy, int frameCount) {
        globalFrameCounter_ += frameCount;
        transientWindowFrames_ += frameCount;

        // 简易瞬态检测
        if (frameEnergy > longWindowEnergy_ * 1.8f + 0.002f) {
            transientCount_++;

            // 记录 onset 时间戳
            onsetTimestamps_[onsetHistoryWriteIdx_] = globalFrameCounter_;
            onsetHistoryWriteIdx_ = (onsetHistoryWriteIdx_ + 1) % ONSET_HISTORY_SIZE;
            if (onsetHistoryCount_ < ONSET_HISTORY_SIZE) {
                onsetHistoryCount_++;
            }
        }

        // 每 3 秒评估
        if (transientWindowFrames_ >= transientWindowSize_) {
            int detectedMode = SCENE_GAME;  // 默认游戏

            if (transientCount_ >= 6 && transientCount_ <= 30) {
                // 密度在音乐范围内，进一步检查周期性
                if (onsetHistoryCount_ >= 4) {
                    float regularity = computeOnsetRegularity();
                    // CV < 0.5 = 节拍间隔较规律
                    if (regularity < 0.5f) {
                        detectedMode = SCENE_MUSIC;
                    }
                }
            }

            // 滞后保护: 连续相同判定才切换
            if (detectedMode == pendingAutoMode_) {
                pendingAutoModeCount_++;
                if (pendingAutoModeCount_ >= 2) {
                    autoDetectedMode_ = detectedMode;
                }
            } else {
                pendingAutoMode_ = detectedMode;
                pendingAutoModeCount_ = 1;
            }

            transientCount_ = 0;
            transientWindowFrames_ = 0;
        }

        return autoDetectedMode_;
    }

    /**
     * 计算 onset 间隔的变异系数 (CV = stddev / mean)
     * CV 接近 0 → 间隔非常规律（音乐节拍）
     * CV 接近 1+ → 间隔随机（游戏音效）
     */
    float computeOnsetRegularity() {
        if (onsetHistoryCount_ < 4) return 1.0f;

        // 提取间隔
        float intervals[ONSET_HISTORY_SIZE];
        int n = 0;
        for (int i = 1; i < onsetHistoryCount_; i++) {
            int curIdx = (onsetHistoryWriteIdx_ - onsetHistoryCount_ + i + ONSET_HISTORY_SIZE)
                         % ONSET_HISTORY_SIZE;
            int prevIdx = (curIdx - 1 + ONSET_HISTORY_SIZE) % ONSET_HISTORY_SIZE;
            float interval = static_cast<float>(
                onsetTimestamps_[curIdx] - onsetTimestamps_[prevIdx]
            );
            if (interval > 0.0f) {
                intervals[n++] = interval;
            }
        }
        if (n < 3) return 1.0f;

        // 均值
        float mean = 0.0f;
        for (int i = 0; i < n; i++) mean += intervals[i];
        mean /= static_cast<float>(n);
        if (mean < 1.0f) return 1.0f;

        // 方差
        float variance = 0.0f;
        for (int i = 0; i < n; i++) {
            float d = intervals[i] - mean;
            variance += d * d;
        }
        variance /= static_cast<float>(n);

        // CV = stddev / mean
        return std::sqrt(variance) / mean;
    }

    // 配置
    int sampleRate_ = 48000;
    int channelCount_ = 2;
    bool enabled_ = false;
    float sensitivity_ = 1.0f;
    int sceneMode_ = SCENE_GAME;

    // 2 阶 Biquad LPF 系数
    float bq_b0_ = 0.0f, bq_b1_ = 0.0f, bq_b2_ = 0.0f;
    float bq_a1_ = 0.0f, bq_a2_ = 0.0f;

    // Biquad 状态 (Direct Form I)
    float bq_x1_ = 0.0f, bq_x2_ = 0.0f;
    float bq_y1_ = 0.0f, bq_y2_ = 0.0f;

    // 攻击/释放包络
    float attackCoeff_ = 0.0f;
    float releaseCoeff_ = 0.0f;
    float envelope_ = 0.0f;

    // RMS 包络
    float rmsEnvelope_ = 0.0f;

    // 噪声门限
    float noiseFloor_ = 0.0f;
    float noiseFloorAlpha_ = 0.0f;

    // ---- 音乐模式: Onset 检测 (双窗口 + 自适应阈值) ----
    float shortWindowAlpha_ = 0.0f;  // 短窗口 EMA 系数 (~80ms)
    float shortWindowEnergy_ = 0.0f; // 短窗口平均能量（瞬时水平）
    float longWindowAlpha_ = 0.0f;   // 长窗口 EMA 系数 (~800ms)
    float longWindowEnergy_ = 0.0f;  // 长窗口平均能量（背景水平）
    float onsetSignalAvg_ = 0.0f;    // onset 信号滑动平均（~2s）
    float onsetSignalAlpha_ = 0.0f;  // onset 信号 EMA 系数
    float prevFrameEnergy_ = 0.0f;   // 上一帧能量（用于增量检测）
    int onsetCooldownRemaining_ = 0; // onset 冷却计数器
    int onsetCooldownFrames_ = 0;    // 冷却帧数 (~150ms)
    int onsetPulseIntensity_ = 0;    // 当前脉冲强度
    int onsetPulseDecay_ = 0;        // 脉冲衰减计数器

    // ---- 自动模式: 瞬态密度 + 周期性分析 ----
    int transientCount_ = 0;         // 当前窗口内瞬态数
    int transientWindowFrames_ = 0;  // 当前窗口已处理帧数
    int transientWindowSize_ = 0;    // 窗口大小（采样数）
    int autoDetectedMode_ = SCENE_GAME;  // 自动检测结果

    // onset 时间戳循环缓冲区 (周期性分析用)
    static const int ONSET_HISTORY_SIZE = 16;
    int onsetTimestamps_[ONSET_HISTORY_SIZE] = {};  // 帧索引
    int onsetHistoryWriteIdx_ = 0;
    int onsetHistoryCount_ = 0;
    int globalFrameCounter_ = 0;

    // 滞后保护: 连续相同判定才切换
    int pendingAutoMode_ = SCENE_GAME;
    int pendingAutoModeCount_ = 0;

    // 节流
    int lastIntensity_ = 0;
    std::chrono::steady_clock::time_point lastCallbackTime_;

    // ---- spectral flux onset detector (self-contained, no external deps) ----
    SpectralOnsetDetector onsetDetector_;
};

#endif // BASS_ENERGY_ANALYZER_H
