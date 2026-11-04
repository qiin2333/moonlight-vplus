package com.limelight.binding.audio

import kotlin.math.max

/**
 * Streaming PCM16 converter for the classic Bluetooth voice path.
 * Android HFP commonly supplies mono 16 kHz PCM, while the existing
 * microphone/Opus contract remains fixed at 48 kHz.
 */
internal class Pcm16SampleRateConverter(
    private val inputRate: Int,
    private val outputRate: Int
) {
    private var previousSample: Int? = null
    private var pendingByte: Int? = null

    init {
        require(inputRate > 0 && outputRate > 0 && outputRate % inputRate == 0) {
            "Only integer PCM sample-rate conversion is supported"
        }
    }

    fun reset() {
        previousSample = null
        pendingByte = null
    }

    /** Converts little-endian PCM16 and keeps the interval at each chunk boundary. */
    fun process(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size)
        val ratio = outputRate / inputRate
        val output = ByteArray(max(0, (length / 2 + 1) * ratio * 2))
        var outputOffset = 0
        var inputOffset = offset
        val end = offset + length

        pendingByte?.let { low ->
            if (inputOffset < end) {
                outputOffset = acceptSample(
                    (low or ((data[inputOffset++].toInt() and 0xff) shl 8)).toShort().toInt(),
                    ratio, output, outputOffset
                )
                pendingByte = null
            }
        }

        val completeEnd = end - ((end - inputOffset) and 1)
        while (inputOffset < completeEnd) {
            val sample = (data[inputOffset].toInt() and 0xff) or
                ((data[inputOffset + 1].toInt() and 0xff) shl 8)
            outputOffset = acceptSample(sample.toShort().toInt(), ratio, output, outputOffset)
            inputOffset += 2
        }
        if (inputOffset < end) pendingByte = data[inputOffset].toInt() and 0xff
        return output.copyOf(outputOffset)
    }

    private fun acceptSample(sample: Int, ratio: Int, output: ByteArray, offset: Int): Int {
        val previous = previousSample
        previousSample = sample
        if (previous == null) return offset

        var outputOffset = offset
        for (step in 0 until ratio) {
            val interpolated = (previous * (ratio - step) + sample * step) / ratio
            output[outputOffset++] = interpolated.toByte()
            output[outputOffset++] = (interpolated shr 8).toByte()
        }
        return outputOffset
    }

    fun flush(): ByteArray {
        val sample = previousSample ?: return ByteArray(0)
        val output = ByteArray((outputRate / inputRate) * 2)
        var offset = 0
        repeat(outputRate / inputRate) {
            output[offset++] = sample.toByte()
            output[offset++] = (sample shr 8).toByte()
        }
        previousSample = null
        pendingByte = null
        return output
    }
}
