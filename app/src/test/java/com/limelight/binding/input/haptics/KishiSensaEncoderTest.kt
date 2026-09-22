package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class KishiSensaEncoderTest {
    @Test fun selectedStrengthScalesOutputAndZeroMutes() {
        fun rendered(gain: Double): List<Int> {
            var last = byteArrayOf()
            val encoder = KishiSensaEncoder()
            encoder.setStrength(gain)
            encoder.encode(frame(0, 400)) { last = it }
            return amplitudes(last)[0]
        }
        val quarter = rendered(0.25)
        val full = rendered(1.0)
        full.indices.forEach { assertTrue(kotlin.math.abs(full[it] - 4 * quarter[it]) <= 2) }
        assertTrue(rendered(0.0).all { it == 0 })
    }
    private fun frame(start: Int, count: Int, tone: Boolean = true): Ds5HapticsPcmFrame {
        val pcm = ByteArray(count * 4)
        if (tone) for (i in 0 until count) {
            val sample = (16000 * sin(2 * PI * 100 * (start + i) / 4000)).toInt()
            pcm[i * 4] = sample.toByte()
            pcm[i * 4 + 1] = (sample shr 8).toByte()
        }
        return Ds5HapticsPcmFrame(0, 0, 0, 0, 4000, count, 2, 16, pcm)
    }

    private fun amplitudes(report: ByteArray): List<List<Int>> {
        var bit = 5 * 8 + 7
        fun read(width: Int): Int {
            var value = 0
            repeat(width) { value = (value shl 1) or ((report[bit / 8].toInt() ushr (7 - bit++ % 8)) and 1) }
            return value
        }
        return List(2) {
            val values = mutableListOf<Int>()
            repeat(3) {
                assertEquals(1, read(1))
                repeat(4) { values.add(read(6)); read(7) }
            }
            assertEquals(0, read(1))
            values
        }
    }

    @Test fun stereoIsolationAndAmplitudeLimit() {
        val encoder = KishiSensaEncoder()
        val output = mutableListOf<ByteArray>()
        encoder.encode(frame(0, 400), output::add)
        assertEquals(10, output.size)
        val channels = amplitudes(output.last())
        assertTrue(channels[0].any { it > 0 })
        assertTrue(channels[1].all { it == 0 })
        assertTrue(channels.flatten().all { it in 0..16 })
    }

    @Test fun chunkBoundariesDoNotChangeSignal() {
        val together = mutableListOf<ByteArray>()
        KishiSensaEncoder().encode(frame(0, 400), together::add)
        val divided = mutableListOf<ByteArray>()
        val encoder = KishiSensaEncoder()
        repeat(40) { encoder.encode(frame(it * 10, 10), divided::add) }
        assertEquals(together.size, divided.size)
        together.indices.forEach { assertArrayEquals(together[it], divided[it]) }
    }

    @Test fun silenceAndResetRemovePreviousEnergy() {
        val encoder = KishiSensaEncoder()
        encoder.encode(frame(0, 400)) {}
        encoder.encode(frame(400, 40, false)) { assertTrue(amplitudes(it).flatten().all { amplitude -> amplitude == 0 }) }
        encoder.reset()
        encoder.encode(frame(0, 40, false)) { assertTrue(amplitudes(it).flatten().all { amplitude -> amplitude == 0 }) }
    }
}
