package com.limelight.grid

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardAccentColorsTest {
    @Test
    fun primaryColorsMatchD65ReferenceValues() {
        val samples = listOf(
            0xFF0000 to doubleArrayOf(53.23, 80.11, 67.22),
            0x00FF00 to doubleArrayOf(87.74, -86.18, 83.18),
            0x0000FF to doubleArrayOf(32.30, 79.20, -107.86),
            0xFFFFFF to doubleArrayOf(100.0, 0.0, 0.0),
        )
        for ((rgb, expected) in samples) {
            val actual = CardAccentColors.toLab(rgb)
            expected.indices.forEach { assertEquals(expected[it], actual[it], .03) }
        }
    }

    @Test
    fun rgbLabRoundTripPreservesDarkAndSaturatedColors() {
        for (r in listOf(0, 1, 12, 64, 128, 227, 255)) {
            for (g in listOf(0, 12, 107, 128, 255)) {
                for (b in listOf(0, 12, 64, 157, 242, 255)) {
                    val rgb = (r shl 16) or (g shl 8) or b
                    val (l, a, labB) = CardAccentColors.toLab(rgb)
                    val out = CardAccentColors.fromLab(l, a, labB)
                    for (shift in listOf(0, 8, 16)) {
                        assertTrue("Round trip failed for ${rgb.toString(16)}", abs((rgb ushr shift and 255) - (out ushr shift and 255)) <= 1)
                    }
                }
            }
        }
    }

    @Test
    fun allBucketsRetainGlowLightnessChromaAndAlpha() {
        for (color in listOf(0x40FF6B9D, 0x25FF8FA3, 0x15FFA3C7)) {
            val sourceL = CardAccentColors.toLab(color)[0]
            for (bucket in 0..11) {
                val result = CardAccentColors.forBucket(color, bucket)
                val (l, a, b) = CardAccentColors.toLab(result)
                assertEquals(color ushr 24, result ushr 24)
                assertEquals(sourceL, l, .3)
                assertTrue("Glow must not become gray in bucket $bucket", hypot(a, b) > 10.0)
            }
        }
    }

    @Test
    fun neutralColorsAndInvalidBucketsRemainUnchanged() {
        for (color in listOf(0xFFFFFFFF.toInt(), 0x001C1C1C, 0x40808080, 0x00000000)) {
            for (bucket in 0..11) assertEquals(color, CardAccentColors.forBucket(color, bucket))
        }
        for (bucket in listOf(Int.MIN_VALUE, -1, 12)) {
            assertEquals(0x40FF6B9D, CardAccentColors.forBucket(0x40FF6B9D, bucket))
        }
    }

    @Test
    fun nearWhiteOutOfGamutColorFallsBackToLightNeutralInsteadOfBlack() {
        val result = CardAccentColors.fromLab(99.0, -200.0, -200.0)
        assertEquals(99.0, CardAccentColors.toLab(result)[0], .3)
    }
}
