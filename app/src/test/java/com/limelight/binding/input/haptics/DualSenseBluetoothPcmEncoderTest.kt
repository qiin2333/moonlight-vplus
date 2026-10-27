package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DualSenseBluetoothPcmEncoderTest {
    private fun frame(bytes: ByteArray) = Ds5HapticsPcmFrame(0, 0, 0, 0, 48000, bytes.size / 4, 2, 16, bytes)
    private fun tone(frames: Int, hz: Int, right: Boolean = false): ByteArray = ByteArray(frames * 4).apply {
        repeat(frames) { i ->
            val sample = (sin(2 * PI * hz * i / 48000) * 16384).toInt()
            val offset = i * 4 + if (right) 2 else 0
            this[offset] = sample.toByte(); this[offset + 1] = (sample shr 8).toByte()
        }
    }
    private fun encode(bytes: ByteArray, chunkFrames: Int): ByteArray {
        val encoder = DualSenseBluetoothPcmEncoder()
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkFrames * 4, bytes.size)
            encoder.encode(frame(bytes.copyOfRange(offset, end)), packets::add)
            offset = end
        }
        encoder.finish(packets::add)
        return packets.flatMap { it.toList() }.toByteArray()
    }

    @Test fun packetBoundariesDoNotChangeTheWaveformAndChannelsStaySeparate() {
        val bytes = tone(4800, 120)
        val whole = encode(bytes, 480)
        assertArrayEquals(whole, encode(bytes, 17))
        assertArrayEquals(whole, encode(bytes, 240))
        assertTrue(whole.filterIndexed { i, _ -> i % 2 == 0 }.any { it != 0.toByte() })
        assertTrue(whole.filterIndexed { i, _ -> i % 2 == 1 }.all { it == 0.toByte() })
        val right = encode(tone(4800, 120, right = true), 240)
        assertArrayEquals(whole.filterIndexed { i, _ -> i % 2 == 0 }.toByteArray(),
            right.filterIndexed { i, _ -> i % 2 == 1 }.toByteArray())
    }

    @Test fun shortEffectAndFilterTailAreFlushedWithoutFourPacketPreroll() {
        val encoder = DualSenseBluetoothPcmEncoder()
        val packets = mutableListOf<ByteArray>()
        encoder.encode(frame(tone(240, 120)), packets::add)
        assertTrue(packets.isEmpty())
        encoder.finish(packets::add)
        assertTrue(packets.isNotEmpty())
        assertTrue(packets.size < 4)
        assertTrue(packets.all { it.size == 64 })
        assertTrue(packets.any { block -> block.any { it != 0.toByte() } })
        val count = packets.size
        encoder.finish(packets::add)
        assertEquals(count, packets.size)
    }

    @Test fun preservesPassbandAmplitudeAndRejectsAliasing() {
        fun rms(hz: Int): Double {
            val bytes = encode(tone(9600, hz), 240)
            val samples = bytes.filterIndexed { i, _ -> i % 2 == 0 }.drop(64).dropLast(64)
            return sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        }
        assertEquals(64.0 / sqrt(2.0), rms(120), 1.0)
        assertTrue(rms(1800) < 0.5)
        assertTrue(rms(1500) < 0.5)
    }

    @Test fun resetCannotCarryOldSamplesIntoAnotherStream() {
        val encoder = DualSenseBluetoothPcmEncoder()
        encoder.encode(frame(tone(240, 120))) {}
        encoder.reset()
        val packets = mutableListOf<ByteArray>()
        encoder.encode(frame(ByteArray(480 * 4)), packets::add)
        encoder.finish(packets::add)
        assertTrue(packets.flatMap { it.toList() }.all { it == 0.toByte() })
        assertFalse(encoder.accepts(frame(ByteArray(4)).let {
            Ds5HapticsPcmFrame(0, 0, 0, 0, 44100, 1, 2, 16, it.pcm)
        }))
    }
}
