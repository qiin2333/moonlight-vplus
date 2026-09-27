package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import kotlin.math.sqrt

/** Conservative energy fallback, not a reconstruction of authored actuator waveforms. */
internal class AuthoredPcmFallback {
    private val sequences = mutableMapOf<Short, Int>()

    @Synchronized
    fun accept(frame: Ds5HapticsPcmFrame): ControllerRumbleState? {
        if (frame.controllerNumber.toInt() !in 0..15 || frame.sampleRate != 48000 ||
            frame.channelCount.toInt() != 2 || frame.bitsPerSample.toInt() != 16 ||
            frame.frameCount !in 0..480 || frame.pcm.size != frame.frameCount * 4 ||
            (frame.flags.toInt() and 7.inv()) != 0) return null
        val restart = frame.flags.toInt() and 5 != 0
        if (!restart && sequences[frame.controllerNumber]?.let { frame.sequenceNumber - it <= 0 } == true)
            return null
        sequences[frame.controllerNumber] = frame.sequenceNumber
        if (frame.flags.toInt() and 2 != 0 || frame.frameCount == 0) return ControllerRumbleState.ZERO
        var energy = 0.0
        for (i in frame.pcm.indices step 2) {
            val sample = ((frame.pcm[i].toInt() and 255) or (frame.pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
            energy += sample * sample
        }
        // Stereo actuator lanes are not low/high-frequency motors. Fold their energy equally.
        val amplitude = (sqrt(energy / (frame.frameCount * 2)) * 0.25).toFloat()
        return ControllerRumbleState(amplitude, amplitude)
    }
}
