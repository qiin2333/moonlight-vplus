package com.limelight.binding.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16SampleRateConverterTest {
    @Test fun convertsAcrossReadBoundariesWithoutDroppingTheInterpolationInterval() {
        val converter = Pcm16SampleRateConverter(16000, 48000)
        val first = converter.process(samples(0, 3000))
        val second = converter.process(samples(6000))

        assertArrayEquals(samples(0, 1000, 2000), first)
        assertArrayEquals(samples(3000, 4000, 5000), second)
    }

    @Test fun flushRepeatsTheLastSampleToCompleteTheFinalFrame() {
        val converter = Pcm16SampleRateConverter(16000, 48000)
        converter.process(samples(1234, 2345))

        assertArrayEquals(samples(2345, 2345, 2345), converter.flush())
        assertEquals(0, converter.flush().size)
    }

    @Test fun retainsAnOddInputByteUntilTheNextRead() {
        val converter = Pcm16SampleRateConverter(16000, 48000)
        val sample = samples(-1200)
        assertEquals(0, converter.process(sample, 0, 1).size)
        assertEquals(0, converter.process(sample, 1, 1).size)
        assertArrayEquals(samples(-1200, -1200, -1200), converter.flush())
    }

    private fun samples(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { output ->
        values.forEachIndexed { index, value ->
            output[index * 2] = value.toShort().toByte()
            output[index * 2 + 1] = (value.toShort().toInt() shr 8).toByte()
        }
    }
}
