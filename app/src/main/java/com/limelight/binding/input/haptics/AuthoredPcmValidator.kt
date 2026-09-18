package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame

/** Validates format and per-player ordering before delivering authored samples. */
internal class AuthoredPcmValidator {
    private val sequences = mutableMapOf<Short, Int>()

    @Synchronized
    fun accept(frame: Ds5HapticsPcmFrame): Boolean {
        if (frame.controllerNumber.toInt() !in 0..15 || frame.sampleRate != 48000 ||
            frame.channelCount.toInt() != 2 || frame.bitsPerSample.toInt() != 16 ||
            frame.frameCount !in 0..480 || frame.pcm.size != frame.frameCount * 4 ||
            (frame.flags.toInt() and 7.inv()) != 0) return false
        val restart = frame.flags.toInt() and 5 != 0
        if (!restart && sequences[frame.controllerNumber]?.let { frame.sequenceNumber - it <= 0 } == true)
            return false
        sequences[frame.controllerNumber] = frame.sequenceNumber
        return true
    }
}
