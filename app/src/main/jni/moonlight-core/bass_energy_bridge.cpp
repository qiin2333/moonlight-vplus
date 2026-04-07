/*
 * C++ implementation of bass energy bridge.
 * Wraps BassEnergyAnalyzer in a C-compatible API.
 *
 * Compiled as C++ (.cpp) and linked into moonlight-core.
 */

#include "bass_energy_analyzer.h"
#include "bass_energy_bridge.h"

static BassEnergyAnalyzer g_bassAnalyzer;

extern "C" {

void bass_energy_init(int sampleRate, int channelCount) {
    g_bassAnalyzer.Init(sampleRate, channelCount);
}

void bass_energy_set_enabled(int enabled) {
    g_bassAnalyzer.SetEnabled(enabled != 0);
}

void bass_energy_set_sensitivity(float sensitivity) {
    g_bassAnalyzer.SetSensitivity(sensitivity);
}

void bass_energy_set_scene_mode(int mode) {
    g_bassAnalyzer.SetSceneMode(mode);
}

int bass_energy_process_frame(const int16_t* pcmData, int sampleCount, int* outIntensity, int* outLowFreqRatio) {
    int intensity = 0;
    int lowFreqRatio = 50;
    bool shouldCallback = g_bassAnalyzer.ProcessFrame(pcmData, sampleCount, intensity, lowFreqRatio);
    if (outIntensity) {
        *outIntensity = intensity;
    }
    if (outLowFreqRatio) {
        *outLowFreqRatio = lowFreqRatio;
    }
    return shouldCallback ? 1 : 0;
}

} // extern "C"
