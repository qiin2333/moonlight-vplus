package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class KishiPcmEncoderTest {
    private fun frame(rate: Int, count: Int, start: Int = 0, sample: (Int, Int) -> Int) =
        Ds5HapticsPcmFrame(0, 0, 0, 0, rate, count, 2, 16, ByteArray(count * 4).also { bytes ->
            for (i in 0 until count) for (channel in 0..1) {
                val value = sample(start + i, channel)
                bytes[i * 4 + channel * 2] = value.toByte()
                bytes[i * 4 + channel * 2 + 1] = (value shr 8).toByte()
            }
        })

    private fun samples(packets: List<ByteArray>, channel: Int) = packets.flatMap { packet ->
        (0 until 12).map {
            val offset = 10 + it * 4 + channel * 2
            ((packet[offset].toInt() and 255) or (packet[offset + 1].toInt() shl 8)).toShort().toInt()
        }
    }

    @Test fun leftImpulseNeverLeaksIntoRightChannel() {
        val packets = mutableListOf<ByteArray>()
        val encoder = KishiPcmEncoder(1f)
        encoder.encode(frame(48000, 480) { i, ch -> if (i == 0 && ch == 0) 30000 else 0 }, packets::add)
        assertTrue(samples(packets, 0).any { it != 0 })
        assertTrue(samples(packets, 1).all { it == 0 })
    }

    @Test fun splittingFramesDoesNotChangeOutput() {
        for (rate in listOf(3000, 4000, 44100, 48000)) {
            val signal: (Int, Int) -> Int = { i, ch -> ((i * 79 + ch * 173) % 20000) - 10000 }
            val whole = mutableListOf<ByteArray>()
            KishiPcmEncoder().encode(frame(rate, 480, sample = signal), whole::add)
            val split = mutableListOf<ByteArray>()
            val encoder = KishiPcmEncoder()
            for (i in 0 until 480 step 15) encoder.encode(frame(rate, 15, i, signal), split::add)
            assertEquals(whole.size, split.size)
            whole.indices.forEach { assertArrayEquals(whole[it], split[it]) }
        }
    }

    @Test fun packetHasStereoPayloadChecksumAndZeroPadding() {
        val packets = mutableListOf<ByteArray>()
        KishiPcmEncoder(1f).encode(frame(4000, 12) { _, ch -> if (ch == 0) 1234 else -2345 }, packets::add)
        val packet = packets.single()
        assertEquals(64, packet.size)
        assertArrayEquals(byteArrayOf(0x55, 0xaa.toByte(), 0, 0, 0, 0, 0, 48, 0xfe.toByte(), 0x79), packet.copyOfRange(0, 10))
        assertEquals(List(12) { 1234 }, samples(packets, 0))
        assertEquals(List(12) { -2345 }, samples(packets, 1))
        assertEquals((2..57).fold(0) { x, i -> x xor packet[i].toInt() }.toByte(), packet[58])
        assertTrue(packet.copyOfRange(59, 64).all { it == 0.toByte() })
    }

    @Test fun resetDiscardsPartialPacketAndFilterHistory() {
        val packets = mutableListOf<ByteArray>()
        val encoder = KishiPcmEncoder()
        encoder.encode(frame(48000, 100) { _, _ -> 30000 }, packets::add)
        assertTrue(packets.isEmpty())
        encoder.reset()
        encoder.encode(frame(48000, 480) { _, _ -> 0 }, packets::add)
        assertTrue(samples(packets, 0).all { it == 0 })
    }

    @Test fun downsamplingRejectsAboveNyquistEnergy() {
        fun rms(frequency: Double): Double {
            val packets = mutableListOf<ByteArray>()
            val encoder = KishiPcmEncoder(1f)
            for (i in 0 until 10) encoder.encode(frame(48000, 480, i * 480) { n, _ ->
                (20000 * sin(2 * PI * frequency * n / 48000)).toInt()
            }, packets::add)
            val samples = samples(packets, 0).drop(24)
            return sqrt(samples.map { it.toDouble() * it }.average())
        }
        assertTrue(rms(8000.0) < rms(200.0) * 0.03)
    }

    @Test fun malformedPcmIsRejected() {
        val encoder = KishiPcmEncoder()
        assertFalse(encoder.accepts(Ds5HapticsPcmFrame(0, 0, 0, 0, 48000, 20, 2, 16, ByteArray(1))))
        assertFalse(encoder.accepts(frame(0, 12) { _, _ -> 0 }))
    }

}
