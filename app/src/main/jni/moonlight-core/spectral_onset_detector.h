/*
 * Moonlight for HarmonyOS / Android
 * Copyright (C) 2024-2025 Moonlight/AlkaidLab
 *
 * Self-contained spectral flux onset detector — zero external dependencies.
 *
 * Implements the same algorithm pipeline as aubio's onset detection:
 *   PCM → Phase Vocoder (STFT) → Adaptive Whitening → Log Compression
 *       → Spectral Flux → Peak Picker → Silence Gate → MinIOI → onset
 *
 * Key algorithms (all implemented from first principles):
 *   - Radix-2 Cooley-Tukey FFT (in-place, precomputed twiddles)
 *   - Hann window with overlap-save buffering
 *   - Half-wave rectified spectral flux
 *   - Per-bin exponential peak-tracking whitening
 *   - Logarithmic magnitude compression
 *   - Sliding-window median + mean adaptive threshold peak picker
 *   - Minimum inter-onset interval enforcement
 *
 * Thread model: called on the same audio decode thread as BassEnergyAnalyzer,
 *               no locking required.
 *
 * Memory: ~60KB inline state (zero heap allocation).
 *
 * This is a clean-room implementation referencing the published academic
 * algorithms (spectral flux, adaptive whitening) — no aubio code is used.
 *
 * References:
 *   [1] Bello et al., "A Tutorial on Onset Detection in Music Signals",
 *       IEEE Trans. Speech and Audio Processing, 2005.
 *   [2] Böck & Widmer, "Maximum Filter Vibrato Suppression for Onset
 *       Detection", DAFx-13, 2013.
 *   [3] Dixon, "Onset Detection Revisited", DAFx-06, 2006.
 */

#ifndef SPECTRAL_ONSET_DETECTOR_H
#define SPECTRAL_ONSET_DETECTOR_H

#include <cstdint>
#include <cmath>
#include <cstring>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class SpectralOnsetDetector {
public:
    // Maximum supported FFT size (2048 → supports hop sizes up to 512)
    static constexpr int MAX_FFT_SIZE = 2048;
    static constexpr int MAX_SPECTRUM_SIZE = MAX_FFT_SIZE / 2 + 1;

    // Peak picker sliding window (7 frames: 1 pre + 1 current + 5 post)
    // Matches aubio's win_pre=1, win_post=5 for specflux
    static constexpr int PICKER_WIN_SIZE = 7;
    static constexpr int PICKER_WIN_PRE = 1;
    static constexpr int PICKER_WIN_POST = 5;
    // We need a delay buffer of PICKER_WIN_POST frames to detect local maxima
    static constexpr int PICKER_DELAY = PICKER_WIN_POST;

    SpectralOnsetDetector() = default;
    ~SpectralOnsetDetector() { /* no heap alloc */ }

    // Disallow copy
    SpectralOnsetDetector(const SpectralOnsetDetector&) = delete;
    SpectralOnsetDetector& operator=(const SpectralOnsetDetector&) = delete;

    /**
     * Initialize the onset detector.
     *
     * @param sampleRate  Sample rate (typically 48000)
     * @param hopSize     Samples per hop (typically 240 for 5ms Opus frames)
     * @param method      Detection method (kept for API compat, always specflux)
     * @return true on success
     */
    bool Init(int sampleRate, int hopSize, const char* method = "specflux") {
        (void)method; // always specflux

        sampleRate_ = sampleRate;
        hopSize_ = static_cast<unsigned int>(hopSize);

        // FFT size: next power of 2 >= 4 × hopSize (standard for good resolution)
        fftSize_ = 1;
        while (fftSize_ < hopSize_ * 4) {
            fftSize_ *= 2;
        }
        if (fftSize_ > static_cast<unsigned int>(MAX_FFT_SIZE)) {
            fftSize_ = static_cast<unsigned int>(MAX_FFT_SIZE);
        }

        spectrumSize_ = fftSize_ / 2 + 1;

        // Precompute Hann window (zero-endpoint variant, "hanningz")
        for (unsigned int i = 0; i < fftSize_; i++) {
            window_[i] = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) * i / fftSize_));
        }

        // Precompute twiddle factors for radix-2 FFT
        for (unsigned int i = 0; i < fftSize_ / 2; i++) {
            double angle = -2.0 * M_PI * i / fftSize_;
            twiddleReal_[i] = static_cast<float>(std::cos(angle));
            twiddleImag_[i] = static_cast<float>(std::sin(angle));
        }

        // Configuration defaults (matching aubio specflux tuning)
        threshold_ = 0.3f;              // peak-picking threshold
        silenceThresholdDb_ = -70.0f;    // silence gate (dB)
        silenceThresholdLin_ = std::pow(10.0f, silenceThresholdDb_ / 20.0f);

        // Minimum inter-onset interval: 80ms (≈12.5 onsets/sec max)
        minIntervalSamples_ = static_cast<int>(0.08f * sampleRate);

        // Adaptive whitening: exponential peak decay
        // r_decay = 0.001^(hop/sr / relax_time)
        // relax_time = 100s (specflux default), floor = 1.0
        double hopDuration = static_cast<double>(hopSize_) / sampleRate_;
        whiteningDecay_ = static_cast<float>(std::pow(0.001, hopDuration / 100.0));
        whiteningFloor_ = 1.0f;
        whiteningEnabled_ = true;

        // Log compression: log(1 + λ·x), λ = 1.0
        compressionLambda_ = 1.0f;

        Reset();
        initialized_ = true;
        return true;
    }

    void Destroy() {
        initialized_ = false;
        Reset();
    }

    void Reset() {
        accumPos_ = 0;
        std::memset(accumBuf_, 0, sizeof(float) * hopSize_);
        std::memset(analysisBuf_, 0, sizeof(float) * fftSize_);
        std::memset(prevSpectrum_, 0, sizeof(float) * spectrumSize_);
        std::memset(whiteningPeaks_, 0, sizeof(float) * spectrumSize_);

        // Peak picker state
        std::memset(pickerRing_, 0, sizeof(pickerRing_));
        pickerRingPos_ = 0;
        pickerFrameCount_ = 0;

        framesSinceLastOnset_ = minIntervalSamples_; // allow first onset immediately
        lastDescriptor_ = 0.0f;
        lastThreshDescriptor_ = 0.0f;
        firstFrame_ = true;
    }

    /**
     * Process one frame of PCM data (int16, interleaved multi-channel).
     *
     * Internally accumulates samples until a full hop, then runs STFT +
     * spectral flux + peak picker.
     *
     * @param pcmData           Interleaved int16 PCM data
     * @param perChannelSamples Samples per channel in this frame
     * @param channelCount      Number of channels
     * @param outOnsetDetected  Output: true if onset detected
     * @return true if processing succeeded
     */
    bool ProcessFrame(const int16_t* pcmData, int perChannelSamples,
                      int channelCount, bool& outOnsetDetected) {
        if (!initialized_ || !pcmData) {
            outOnsetDetected = false;
            return false;
        }

        outOnsetDetected = false;

        // Convert interleaved int16 multichannel → mono float, accumulate
        for (int i = 0; i < perChannelSamples; i++) {
            float mono = 0.0f;
            for (int ch = 0; ch < channelCount; ch++) {
                mono += static_cast<float>(pcmData[i * channelCount + ch]);
            }
            mono /= (32768.0f * channelCount);

            accumBuf_[accumPos_] = mono;
            accumPos_++;

            // Full hop accumulated → run analysis
            if (accumPos_ >= static_cast<int>(hopSize_)) {
                bool onset = processHop();
                if (onset) outOnsetDetected = true;
                accumPos_ = 0;
            }
        }

        return true;
    }

    /**
     * Get the raw spectral flux descriptor from the last hop.
     */
    float GetDescriptor() const { return lastDescriptor_; }

    /**
     * Get the thresholded descriptor (flux - adaptive_threshold).
     * Positive values indicate onset candidates.
     */
    float GetThresholdedDescriptor() const { return lastThreshDescriptor_; }

    /**
     * Set the peak-picking threshold (default 0.3).
     * Lower → more sensitive (more onsets), higher → fewer onsets.
     */
    void SetThreshold(float threshold) { threshold_ = threshold; }

    /**
     * Set minimum inter-onset interval in milliseconds (default 80ms).
     */
    void SetMinInterval(float ms) {
        minIntervalSamples_ = static_cast<int>(ms / 1000.0f * sampleRate_);
    }

    bool IsInitialized() const { return initialized_; }

private:
    // ====================================================================
    //  Core Processing: STFT → Spectral Flux → Peak Picker
    // ====================================================================

    bool processHop() {
        // ---- 1. Phase Vocoder: overlap-save STFT ----

        // Slide the analysis buffer: keep (fftSize - hopSize) old samples,
        // append hopSize new samples at the end
        if (fftSize_ > hopSize_) {
            std::memmove(analysisBuf_,
                         analysisBuf_ + hopSize_,
                         (fftSize_ - hopSize_) * sizeof(float));
        }
        std::memcpy(analysisBuf_ + (fftSize_ - hopSize_),
                     accumBuf_,
                     hopSize_ * sizeof(float));

        // Apply Hann window → fftReal; zero imaginary part
        for (unsigned int i = 0; i < fftSize_; i++) {
            fftReal_[i] = analysisBuf_[i] * window_[i];
            fftImag_[i] = 0.0f;
        }

        // Zero-phase shift: rotate buffer by fftSize/2 so the window center
        // is at index 0 (avoids phase discontinuity, matches aubio's fvec_shift)
        {
            unsigned int half = fftSize_ / 2;
            for (unsigned int i = 0; i < half; i++) {
                float tmp = fftReal_[i];
                fftReal_[i] = fftReal_[i + half];
                fftReal_[i + half] = tmp;
            }
        }

        // ---- 2. FFT (Radix-2 Cooley-Tukey) ----
        fft(fftReal_, fftImag_, fftSize_);

        // ---- 3. Compute magnitude spectrum ----
        float spectrum[MAX_SPECTRUM_SIZE];
        float energySum = 0.0f;

        for (unsigned int i = 0; i < spectrumSize_; i++) {
            spectrum[i] = std::sqrt(fftReal_[i] * fftReal_[i] +
                                    fftImag_[i] * fftImag_[i]);
            energySum += spectrum[i];
        }

        // ---- 4. Silence gate ----
        // Use mean magnitude as a proxy for level
        float meanMag = energySum / spectrumSize_;
        if (meanMag < silenceThresholdLin_) {
            // Frame is silent — update state but don't produce onset
            std::memcpy(prevSpectrum_, spectrum, sizeof(float) * spectrumSize_);
            framesSinceLastOnset_ += hopSize_;
            feedPicker(0.0f);
            firstFrame_ = false;
            return false;
        }

        // ---- 5. Adaptive Whitening ----
        // Per-bin exponential peak tracking: norm each bin by its peak.
        // peak[i] = max(current[i], decay * prev_peak[i])
        // whitened[i] = norm[i] / peak[i]
        if (whiteningEnabled_) {
            for (unsigned int i = 0; i < spectrumSize_; i++) {
                float decayed = std::max(whiteningDecay_ * whiteningPeaks_[i],
                                         whiteningFloor_);
                whiteningPeaks_[i] = std::max(spectrum[i], decayed);
                spectrum[i] /= whiteningPeaks_[i];
            }
        }

        // ---- 6. Log compression: log(1 + λ·x) ----
        if (compressionLambda_ > 0.0f) {
            for (unsigned int i = 0; i < spectrumSize_; i++) {
                spectrum[i] = std::log1p(compressionLambda_ * spectrum[i]);
            }
        }

        // ---- 7. Spectral Flux (half-wave rectified) ----
        // Only accumulate positive differences (energy increases)
        float flux = 0.0f;
        if (!firstFrame_) {
            for (unsigned int i = 0; i < spectrumSize_; i++) {
                float diff = spectrum[i] - prevSpectrum_[i];
                if (diff > 0.0f) {
                    flux += diff;
                }
            }
        }

        // Save current spectrum for next frame
        std::memcpy(prevSpectrum_, spectrum, sizeof(float) * spectrumSize_);
        lastDescriptor_ = flux;

        // ---- 8. Peak Picker (adaptive threshold + local max) ----
        bool isOnset = false;
        if (!firstFrame_) {
            isOnset = feedPicker(flux);
        }

        firstFrame_ = false;
        framesSinceLastOnset_ += hopSize_;

        return isOnset;
    }

    // ====================================================================
    //  Peak Picker: sliding window, median+mean adaptive threshold
    // ====================================================================
    //
    //  Algorithm (following Bello et al. & Dixon):
    //    1. Maintain a ring buffer of the last PICKER_WIN_SIZE flux values.
    //    2. For frame at position [PICKER_WIN_PRE], check if it's a local max
    //       within the window AND exceeds the adaptive threshold.
    //    3. Adaptive threshold = median(window) + mean(window) × threshold_
    //    4. Enforce minimum inter-onset interval.
    //
    //  This introduces PICKER_WIN_POST frames of latency (~25ms @48kHz/240hop),
    //  acceptable for haptic feedback.

    bool feedPicker(float value) {
        // Push new value into ring buffer
        pickerRing_[pickerRingPos_] = value;
        pickerRingPos_ = (pickerRingPos_ + 1) % PICKER_WIN_SIZE;
        pickerFrameCount_++;

        // Need at least a full window of frames before we can pick
        if (pickerFrameCount_ < PICKER_WIN_SIZE) {
            return false;
        }

        // The candidate frame is PICKER_WIN_POST frames ago
        // (so we have enough "future" frames to check local max)
        int candidateIdx = (pickerRingPos_ - 1 - PICKER_WIN_POST + PICKER_WIN_SIZE)
                           % PICKER_WIN_SIZE;
        float candidateVal = pickerRing_[candidateIdx];

        // ---- Local maximum check ----
        // Candidate must be >= all values in the window
        bool isLocalMax = true;
        for (int i = 0; i < PICKER_WIN_SIZE; i++) {
            if (i != candidateIdx && pickerRing_[i] > candidateVal) {
                isLocalMax = false;
                break;
            }
        }

        if (!isLocalMax) {
            lastThreshDescriptor_ = 0.0f;
            return false;
        }

        // ---- Adaptive threshold: median + mean × threshold_ ----
        float sorted[PICKER_WIN_SIZE];
        float sum = 0.0f;
        for (int i = 0; i < PICKER_WIN_SIZE; i++) {
            sorted[i] = pickerRing_[i];
            sum += pickerRing_[i];
        }
        std::sort(sorted, sorted + PICKER_WIN_SIZE);
        float median = sorted[PICKER_WIN_SIZE / 2];
        float mean = sum / PICKER_WIN_SIZE;
        float adaptiveThresh = median + mean * threshold_;

        float thresholded = candidateVal - adaptiveThresh;
        lastThreshDescriptor_ = thresholded;

        // ---- Threshold check + MinIOI ----
        if (thresholded > 0.0f && framesSinceLastOnset_ >= minIntervalSamples_) {
            framesSinceLastOnset_ = 0;
            return true;
        }

        return false;
    }

    // ====================================================================
    //  Radix-2 Cooley-Tukey FFT (in-place, Decimation-In-Time)
    // ====================================================================
    //
    //  Standard DIT FFT with precomputed twiddle factors.
    //  Complexity: O(N log N), N ≤ 2048.
    //  Uses pre-stored cos/sin tables to avoid recomputation.

    void fft(float* real, float* imag, unsigned int n) {
        // ---- Bit-reversal permutation ----
        unsigned int j = 0;
        for (unsigned int i = 0; i < n - 1; i++) {
            if (i < j) {
                std::swap(real[i], real[j]);
                std::swap(imag[i], imag[j]);
            }
            unsigned int m = n >> 1;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        // ---- Butterfly stages ----
        for (unsigned int stage = 1; stage < n; stage <<= 1) {
            unsigned int twiddleStep = n / (stage << 1);
            for (unsigned int group = 0; group < n; group += stage << 1) {
                for (unsigned int pair = 0; pair < stage; pair++) {
                    unsigned int twiddleIdx = pair * twiddleStep;
                    float wr = twiddleReal_[twiddleIdx];
                    float wi = twiddleImag_[twiddleIdx];

                    unsigned int idx0 = group + pair;
                    unsigned int idx1 = idx0 + stage;

                    float tr = wr * real[idx1] - wi * imag[idx1];
                    float ti = wr * imag[idx1] + wi * real[idx1];

                    real[idx1] = real[idx0] - tr;
                    imag[idx1] = imag[idx0] - ti;
                    real[idx0] = real[idx0] + tr;
                    imag[idx0] = imag[idx0] + ti;
                }
            }
        }
    }

    // ====================================================================
    //  State
    // ====================================================================

    // Configuration
    int sampleRate_ = 48000;
    unsigned int hopSize_ = 240;
    unsigned int fftSize_ = 1024;
    unsigned int spectrumSize_ = 513;

    // Thresholds
    float threshold_ = 0.3f;           // Peak picker sensitivity
    float silenceThresholdDb_ = -70.0f; // Silence gate (dB)
    float silenceThresholdLin_ = 0.0f;  // Silence gate (linear)
    int minIntervalSamples_ = 3840;     // Min inter-onset interval (samples)

    // Whitening
    bool whiteningEnabled_ = true;
    float whiteningDecay_ = 0.0f;       // Per-hop exponential decay
    float whiteningFloor_ = 1.0f;       // Minimum peak value

    // Log compression
    float compressionLambda_ = 1.0f;    // log(1 + λ·x) coefficient

    // Hann window (precomputed)
    float window_[MAX_FFT_SIZE] = {};

    // Twiddle factors for FFT (precomputed cos/sin)
    float twiddleReal_[MAX_FFT_SIZE / 2] = {};
    float twiddleImag_[MAX_FFT_SIZE / 2] = {};

    // Analysis buffer (overlap-save: holds last fftSize samples)
    float analysisBuf_[MAX_FFT_SIZE] = {};

    // Accumulation buffer (incoming hop samples before full hop)
    float accumBuf_[MAX_FFT_SIZE] = {};  // Only hopSize_ elements used
    int accumPos_ = 0;

    // FFT scratch buffers (reused each hop, avoids stack allocation)
    float fftReal_[MAX_FFT_SIZE] = {};
    float fftImag_[MAX_FFT_SIZE] = {};

    // Previous frame magnitude spectrum (for spectral flux)
    float prevSpectrum_[MAX_SPECTRUM_SIZE] = {};

    // Whitening: per-bin peak tracking
    float whiteningPeaks_[MAX_SPECTRUM_SIZE] = {};

    // Peak picker ring buffer
    float pickerRing_[PICKER_WIN_SIZE] = {};
    int pickerRingPos_ = 0;
    int pickerFrameCount_ = 0;

    // Onset tracking
    int framesSinceLastOnset_ = 0;
    float lastDescriptor_ = 0.0f;          // Raw spectral flux
    float lastThreshDescriptor_ = 0.0f;    // After threshold subtraction

    bool firstFrame_ = true;
    bool initialized_ = false;
};

#endif // SPECTRAL_ONSET_DETECTOR_H
