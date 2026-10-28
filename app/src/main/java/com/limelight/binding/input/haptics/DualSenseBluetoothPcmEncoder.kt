package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 48 kHz stereo S16LE to the DualSense's 3 kHz stereo S8 transport. No envelope reduction. */
internal class DualSenseBluetoothPcmEncoder {
    private val history = Array(2) { DoubleArray(TAPS) }
    private var position = 0
    private var phase = 0
    private var packet = ByteArray(PACKET_BYTES)
    private var used = 0
    private var hasInput = false

    val hasPendingSamples: Boolean get() = hasInput

    fun accepts(frame: Ds5HapticsPcmFrame): Boolean = frame.sampleRate == 48000 &&
        frame.channelCount.toInt() == 2 && frame.bitsPerSample.toInt() == 16 &&
        frame.frameCount in 0..480 && frame.pcm.size == frame.frameCount * 4 &&
        frame.flags.toInt() and 7.inv() == 0

    fun reset() {
        history.forEach { it.fill(0.0) }
        position = 0
        phase = 0
        packet = ByteArray(PACKET_BYTES)
        used = 0
        hasInput = false
    }

    fun encode(frame: Ds5HapticsPcmFrame, emit: (ByteArray) -> Unit) {
        require(accepts(frame))
        for (offset in frame.pcm.indices step 4) {
            push(read(frame.pcm, offset), read(frame.pcm, offset + 2), emit)
        }
        if (frame.frameCount > 0) hasInput = true
    }

    /** Preserve short effects and the FIR tail even if the producer stops before a full packet. */
    fun finish(emit: (ByteArray) -> Unit) {
        if (!hasInput) return
        repeat(TAPS - 1) { push(0, 0, emit) }
        if (used > 0) emit(packet)
        reset()
    }

    private fun push(left: Int, right: Int, emit: (ByteArray) -> Unit) {
        history[0][position] = left.toDouble()
        history[1][position] = right.toDouble()
        if (++phase == 16) {
            phase = 0
            for (channel in 0..1) {
                var value = 0.0
                var index = position
                for (tap in coefficients.indices) {
                    value += history[channel][index] * coefficients[tap]
                    if (--index < 0) index = TAPS - 1
                }
                packet[used++] = (value / 256.0).roundToInt().coerceIn(-128, 127).toByte()
            }
            if (used == PACKET_BYTES) {
                emit(packet)
                packet = ByteArray(PACKET_BYTES)
                used = 0
            }
        }
        position = (position + 1) % TAPS
    }

    companion object {
        const val PACKET_BYTES = 64 // 32 stereo frames = 10 2/3 ms
        private const val TAPS = 511
        // Blackman-windowed sinc: unity DC gain, 1.2 kHz cutoff, 5.3125 ms group delay.
        // Compute only at decimation points; preserve independent channel histories across packets.
        private val coefficients = DoubleArray(TAPS) { n ->
            val x = n - (TAPS - 1) / 2
            val cutoff = 1200.0 / 48000.0
            val sinc = if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            sinc * (0.42 - 0.5 * cos(2 * PI * n / (TAPS - 1)) +
                0.08 * cos(4 * PI * n / (TAPS - 1)))
        }.let { taps -> val sum = taps.sum(); DoubleArray(TAPS) { taps[it] / sum } }

        private fun read(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}
