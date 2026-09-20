package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class SensaRumbleEncoderTest {
    private fun points(report: ByteArray): List<List<Pair<Int, Int>>> {
        var bit = 5 * 8
        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((report[bit / 8].toInt() shr (7 - bit % 8)) and 1)
                bit++
            }
            return value
        }
        assertEquals(40, read(7))
        return List(2) {
            assertEquals(1, read(1))
            List(4) { read(6) to read(7) }.also {
                assertEquals(0, read(1)); assertEquals(0, read(1))
            }
        }
    }

    @Test fun separatesChannelsAndRampsAmplitude() {
        val encoder = SensaRumbleEncoder()
        val left = points(encoder.encode(1f, 0f, 1.0, 100.0))
        assertEquals(listOf(16, 32, 47, 63), left[0].map { it.first })
        assertTrue(left[1].all { it.first == 0 })
        val right = points(encoder.encode(0f, 0.5f, 1.0, 100.0))
        assertTrue(right[0].all { it.first == 0 })
        assertEquals(32, right[1].last().first)
    }

    @Test fun frequencyDoesNotChangeStrength() {
        val encoder = SensaRumbleEncoder()
        encoder.encode(1f, 1f, 0.2, 30.0)
        val low = points(encoder.encode(1f, 1f, 0.2, 30.0))
        val high = points(encoder.encode(1f, 1f, 0.2, 400.0))
        assertEquals(low.flatten().map { it.first }, high.flatten().map { it.first })
        assertTrue(low.flatten().all { it.first == 13 && it.second == 0 })
        assertTrue(high.flatten().all { it.second == 127 })
    }

    @Test fun zeroAndMuteStopImmediately() {
        val encoder = SensaRumbleEncoder()
        encoder.encode(1f, 1f, 1.0, 100.0)
        assertTrue(points(encoder.encode(0f, 0f, 1.0, 100.0)).flatten().all { it.first == 0 })
        encoder.encode(1f, 1f, 1.0, 100.0)
        assertTrue(points(encoder.encode(1f, 1f, 0.0, 100.0)).flatten().all { it.first == 0 })
    }

    @Test fun clampsAndResets() {
        val encoder = SensaRumbleEncoder()
        encoder.encode(2f, -1f, 2.0, 1000.0)
        val saturated = points(encoder.encode(2f, -1f, 2.0, 1000.0))
        assertTrue(saturated[0].all { it.first == 63 && it.second == 127 })
        assertTrue(saturated[1].all { it.first == 0 })
        encoder.reset()
        assertEquals(16, points(encoder.encode(1f, 0f, 1.0, 100.0))[0][0].first)
    }
}
