package com.limelight.binding.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MicrophoneVolumeProcessorTest {

    @Test
    fun disabledProcessingLeavesPcmUnchanged() {
        val pcm = pcmOf(-32768, -1000, 0, 1000, 32767)
        val original = pcm.copyOf()
        val processor = MicrophoneVolumeProcessor()

        processor.configure(
            enabled = false,
            gainEnabled = true,
            gainDb = 20,
            balanceEnabled = false,
            balanceTargetPercent = 50,
            voiceEnhancementEnabled = true
        )
        processor.processFrame(pcm, 0, pcm.size)

        assertArrayEquals(original, pcm)
    }

    @Test
    fun fixedGainAppliesConfiguredDecibels() {
        val pcm = pcmOf(-1000, 0, 1000)
        val processor = MicrophoneVolumeProcessor()

        processor.configure(
            enabled = true,
            gainEnabled = true,
            gainDb = 6,
            balanceEnabled = false,
            balanceTargetPercent = 50,
            voiceEnhancementEnabled = false
        )
        processor.processFrame(pcm, 0, pcm.size)

        assertEquals(-1995, sampleAt(pcm, 0))
        assertEquals(0, sampleAt(pcm, 1))
        assertEquals(1995, sampleAt(pcm, 2))
    }

    @Test
    fun balanceLimiterKeepsStartupTransientBelowMinusOneDbfs() {
        val pcm = pcmOf(*IntArray(MicrophoneConfig.SAMPLES_PER_FRAME) { Short.MAX_VALUE.toInt() })
        val processor = MicrophoneVolumeProcessor()

        processor.configure(
            enabled = true,
            gainEnabled = false,
            gainDb = 0,
            balanceEnabled = true,
            balanceTargetPercent = 100,
            voiceEnhancementEnabled = false
        )
        processor.processFrame(pcm, 0, pcm.size)

        val peak = (0 until MicrophoneConfig.SAMPLES_PER_FRAME)
            .maxOf { abs(sampleAt(pcm, it)) }
        assertTrue("peak=$peak", peak <= 29_204)
    }

    @Test
    fun balanceLimiterHandlesPartialBlock() {
        val pcm = pcmOf(Short.MAX_VALUE.toInt())
        val processor = MicrophoneVolumeProcessor()

        processor.configure(
            enabled = true,
            gainEnabled = false,
            gainDb = 0,
            balanceEnabled = true,
            balanceTargetPercent = 100,
            voiceEnhancementEnabled = false
        )
        processor.processFrame(pcm, 0, pcm.size)

        assertTrue(abs(sampleAt(pcm, 0)) <= 29_204)
    }

    @Test
    fun balanceCompressorSettlesWithoutDuplicateLimiterReduction() {
        var pcm = ByteArray(0)
        val processor = MicrophoneVolumeProcessor()

        processor.configure(
            enabled = true,
            gainEnabled = false,
            gainDb = 0,
            balanceEnabled = true,
            balanceTargetPercent = 100,
            voiceEnhancementEnabled = false
        )
        repeat(50) {
            pcm = pcmOf(*IntArray(MicrophoneConfig.SAMPLES_PER_FRAME) { Short.MAX_VALUE.toInt() })
            processor.processFrame(pcm, 0, pcm.size)
        }

        val settledSample = sampleAt(pcm, MicrophoneConfig.SAMPLES_PER_FRAME - 1)
        assertTrue("settledSample=$settledSample", settledSample in 20_000..21_500)
    }

    private fun pcmOf(vararg samples: Int): ByteArray {
        return ByteArray(samples.size * 2).also { data ->
            samples.forEachIndexed { index, sample ->
                data[index * 2] = (sample and 0xFF).toByte()
                data[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
        }
    }

    private fun sampleAt(data: ByteArray, sampleIndex: Int): Int {
        val byteIndex = sampleIndex * 2
        return (data[byteIndex].toInt() and 0xFF) or (data[byteIndex + 1].toInt() shl 8)
    }
}
