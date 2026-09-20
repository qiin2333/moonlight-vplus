package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Stateful stereo resampling and packetization. Called under the sink's queue lock. */
internal class KishiPcmEncoder(private val gain: Float = 0.25f) {
    private var rate = 0
    private var index = -1L
    private var nextTime = 0L // input position in units of 1/4000 frame
    private val history = Array(2) { DoubleArray(64) }
    private var coefficients = doubleArrayOf(1.0)
    private val previous = DoubleArray(2)
    private val current = DoubleArray(2)
    private var packet = ByteArray(64)
    private var packetFrames = 0

    init { require(gain.isFinite() && gain in 0f..1f) }

    fun reset() {
        rate = 0
        index = -1
        nextTime = 0
        history.forEach { it.fill(0.0) }
        previous.fill(0.0)
        packet = ByteArray(64)
        packetFrames = 0
    }

    fun accepts(frame: Ds5HapticsPcmFrame): Boolean = frame.channelCount.toInt() == 2 &&
        frame.bitsPerSample.toInt() == 16 && frame.sampleRate in 3000..48000 &&
        frame.frameCount in 0..480 && frame.pcm.size == frame.frameCount * 4

    fun encode(frame: Ds5HapticsPcmFrame, emit: (ByteArray) -> Unit) {
        require(accepts(frame))
        if (rate != frame.sampleRate) {
            reset()
            rate = frame.sampleRate
            coefficients = lowPass(rate)
        }
        for (i in 0 until frame.frameCount) {
            index++
            current.fill(0.0)
            for (channel in 0..1) {
                val offset = i * 4 + channel * 2
                val sample = ((frame.pcm[offset].toInt() and 255) or
                    (frame.pcm[offset + 1].toInt() shl 8)).toShort()
                history[channel][(index % 64).toInt()] = sample.toDouble()
                for (tap in coefficients.indices) {
                    current[channel] += coefficients[tap] * history[channel][((index - tap + 64) % 64).toInt()]
                }
            }
            while (nextTime <= index * 4000) {
                val fraction = if (index == 0L) 1.0 else (nextTime - (index - 1) * 4000) / 4000.0
                for (channel in 0..1) {
                    val value = ((previous[channel] + (current[channel] - previous[channel]) * fraction) * gain)
                        .roundToInt().coerceIn(-32768, 32767)
                    val offset = 10 + packetFrames * 4 + channel * 2
                    packet[offset] = value.toByte()
                    packet[offset + 1] = (value shr 8).toByte()
                }
                packetFrames++
                if (packetFrames == 12) {
                    finishPacket(packet)
                    emit(packet)
                    packet = ByteArray(64)
                    packetFrames = 0
                }
                nextTime += rate
            }
            current.copyInto(previous)
        }
    }

    private fun lowPass(inputRate: Int): DoubleArray {
        if (inputRate <= 4000) return doubleArrayOf(1.0)
        // Windowed-sinc anti-alias filter, independent history for each channel.
        val cutoff = 1600.0 / inputRate
        val taps = DoubleArray(63) { n ->
            val x = n - 31
            val sinc = if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            sinc * (0.54 - 0.46 * cos(2 * PI * n / 62))
        }
        val sum = taps.sum()
        return DoubleArray(taps.size) { taps[it] / sum }
    }

    companion object {
        fun silence(): ByteArray = ByteArray(64).also(::finishPacket)
        private fun finishPacket(bytes: ByteArray) {
            bytes[0] = 0x55
            bytes[1] = 0xaa.toByte()
            bytes[7] = 48
            bytes[8] = 0xfe.toByte()
            bytes[9] = 0x79
            var checksum = 0
            for (i in 2..57) checksum = checksum xor bytes[i].toInt()
            bytes[58] = checksum.toByte()
        }
    }
}
