package com.limelight.binding.input.haptics

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.limelight.LimeLog

/**
 * Behavioral tier of the phone body's actuator.
 *
 * Tiers are derived only from positively confirmed capabilities; unknown values always fall
 * back to the more conservative tier. Motor construction (ERM/LRA/piezo) is deliberately not
 * inferred - behavior follows what the framework promises, not what the hardware is named.
 */
internal enum class DeviceHapticsTier {
    /** No vibrator at all. */
    NONE,

    /** Vibrator without amplitude control: distinct amplitudes degrade to on/off. */
    BINARY,

    /** Amplitude control available; composition primitives not confirmed. */
    AMPLITUDE,

    /** Composition primitives confirmed: crisp short transients are plausible. */
    COMPOSITION;

    /**
     * Graded amplitude output: distinct non-zero levels survive the trip to the motor.
     * Routing decisions must use these predicates instead of comparing enum order.
     */
    val supportsGradedOutput: Boolean
        get() = this == AMPLITUDE || this == COMPOSITION

    /** Composition primitives positively confirmed. */
    val supportsComposition: Boolean
        get() = this == COMPOSITION
}

/**
 * One-shot capability snapshot of the phone body's vibrator, probed once per stream session.
 *
 * Every query behind this is a Binder call into the vibrator service, so callers must reuse the
 * snapshot instead of probing per rumble event. The tier gates coordinated-mode body
 * eligibility and the controller's transient yield; the probe log line also builds a device
 * corpus for future tuning.
 */
internal data class DeviceHapticsCapabilities(
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val supportsClickPrimitive: Boolean,
    val resonantFrequencyHz: Float?,
    val qFactor: Float?
) {
    val tier: DeviceHapticsTier
        get() = when {
            !hasVibrator -> DeviceHapticsTier.NONE
            !hasAmplitudeControl -> DeviceHapticsTier.BINARY
            !supportsClickPrimitive -> DeviceHapticsTier.AMPLITUDE
            else -> DeviceHapticsTier.COMPOSITION
        }

    companion object {
        fun probe(vibrator: Vibrator): DeviceHapticsCapabilities {
            val hasVibrator = vibrator.hasVibrator()
            val hasAmplitudeControl = hasVibrator &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                vibrator.hasAmplitudeControl()

            var clickPrimitive = false
            if (hasVibrator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                clickPrimitive = vibrator.arePrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK
                ).firstOrNull() ?: false
            }

            var resonantFrequencyHz: Float? = null
            var qFactor: Float? = null
            if (hasVibrator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Both report NaN when the HAL does not know.
                resonantFrequencyHz = vibrator.getResonantFrequency().takeUnless { it.isNaN() }
                qFactor = vibrator.getQFactor().takeUnless { it.isNaN() }
            }

            val capabilities = DeviceHapticsCapabilities(
                hasVibrator = hasVibrator,
                hasAmplitudeControl = hasAmplitudeControl,
                supportsClickPrimitive = clickPrimitive,
                resonantFrequencyHz = resonantFrequencyHz,
                qFactor = qFactor
            )
            LimeLog.info(
                "Device haptics tier ${capabilities.tier}: " +
                    "vibrator=$hasVibrator, amplitudeControl=$hasAmplitudeControl, " +
                    "clickPrimitive=$clickPrimitive, " +
                    "resonantFrequencyHz=$resonantFrequencyHz, qFactor=$qFactor"
            )
            return capabilities
        }
    }
}
