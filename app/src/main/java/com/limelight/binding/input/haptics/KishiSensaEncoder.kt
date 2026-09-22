package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import kotlin.math.*

/** Experimental PCM-to-Sensa spectral approximation, not lossless PCM playback.
 * 40 ms Hann windows estimate three frequency bands independently per channel.
 * Standalone default gain is 25%; the sink supplies the user's strength for playback.
 * Output is emitted every 10 ms. Phase is not preserved: the result is a small set
 * of amplitude/frequency envelopes, not the original waveform or a vendor decoder.
 */
internal class KishiSensaEncoder(private var gain: Double = 0.25) {
    init { require(gain.isFinite() && gain in 0.0..1.0) }
    fun setStrength(value: Double) {
        require(value.isFinite() && value in 0.0..1.0)
        gain = value
    }
    private val samples = Array(2) { DoubleArray(1920) }
    private var rate = 0
    private var cursor = 0
    private var pending = 0
    private var filled = 0
    private var weights = DoubleArray(0)
    private val previous = Array(2) { DoubleArray(3) }

    fun accepts(frame: Ds5HapticsPcmFrame) = frame.channelCount.toInt() == 2 &&
        frame.bitsPerSample.toInt() == 16 && frame.sampleRate in 3000..48000 && frame.sampleRate % 100 == 0 &&
        frame.frameCount in 0..480 && frame.pcm.size == frame.frameCount * 4

    fun reset() {
        rate = 0; cursor = 0; pending = 0; filled = 0
        samples.forEach { it.fill(0.0) }
        previous.forEach { it.fill(0.0) }
    }

    fun encode(frame: Ds5HapticsPcmFrame, emit: (ByteArray) -> Unit) {
        require(accepts(frame))
        if (rate != frame.sampleRate) {
            reset(); rate = frame.sampleRate
            weights = DoubleArray(rate / 25) { 0.5 - 0.5 * cos(2 * PI * it / (rate / 25 - 1)) }
        }
        // A trailing 40 ms ring window gives low-frequency estimates enough history,
        // while the 10 ms hop matches one firmware packet without buffering a burst.
        val window = rate / 25
        val step = rate / 100
        for (i in 0 until frame.frameCount) {
            for (channel in 0..1) {
                val offset = i * 4 + channel * 2
                samples[channel][cursor] = ((frame.pcm[offset].toInt() and 255) or
                    (frame.pcm[offset + 1].toInt() shl 8)).toShort() / 32768.0
            }
            cursor = (cursor + 1) % window
            filled = minOf(window, filled + 1)
            if (++pending >= step) {
                pending = 0
                val channels = (0..1).map { channel ->
                    val bands = listOf(30..90, 100..190, 200..400).mapIndexed { band, frequencies ->
                        var bestAmplitude = 0.0
                        var bestFrequency = frequencies.first.toDouble()
                        // Goertzel evaluates a sparse frequency grid. Select one peak
                        // per band because the negotiated layout allows three bands.
                        for (frequency in frequencies step 10) {
                            var q1 = 0.0
                            var q2 = 0.0
                            val coefficient = 2 * cos(2 * PI * frequency / rate)
                            for (j in 0 until window) {
                                val sample = samples[channel][(cursor + j) % window]
                                val q0 = sample * weights[j] + coefficient * q1 - q2
                                q2 = q1
                                q1 = q0
                            }
                            val amplitude = 4 * sqrt(maxOf(0.0, q1 * q1 + q2 * q2 - coefficient * q1 * q2)) / window
                            if (amplitude > bestAmplitude) { bestAmplitude = amplitude; bestFrequency = frequency.toDouble() }
                        }
                        // Gate silence immediately, rather than replaying a window's old energy.
                        var recentPeak = 0.0
                        for (j in 1..step) recentPeak = maxOf(recentPeak,
                            abs(samples[channel][(cursor - j + window) % window]))
                        val amplitude = if (recentPeak < 0.002 || filled < step) 0.0
                            else (bestAmplitude * gain).coerceIn(0.0, gain)
                        val from = previous[channel][band]
                        previous[channel][band] = amplitude
                        List(4) { point -> KishiSensaPacket.Point(
                            if (amplitude == 0.0) 0.0 else from + (amplitude - from) * (point + 1) / 4,
                            bestFrequency) }
                    }
                    bands
                }
                emit(KishiSensaPacket.report(0x0e, KishiSensaPacket.frame(channels)))
            }
        }
    }
}
