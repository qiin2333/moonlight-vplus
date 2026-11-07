package com.limelight.binding.input.haptics

import kotlin.math.sqrt

/** Amplitude-only fixture for tests that do not exercise frequency selection. */
internal fun nativeBodyWithGain(gain: Float): NativeAuthoredBody = NativeAuthoredBody.fromBands(
    ControllerRumbleState.normalizeAmplitude(gain) / sqrt(2f), 0f, 160f)
