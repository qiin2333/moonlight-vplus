package com.limelight.binding.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PyrowaveHdrMetadataTest {
    @Test
    fun parsesValidHdr10MetadataWithoutInventingValues() {
        val metadata = PyrowaveHdrMetadata.fromSunshineBytes(payload(
            35400, 14600, 8500, 39850, 6550, 2300,
            15635, 16450, 1000, 1, 1200, 400, 600,
        ))

        assertNotNull(metadata)
        assertEquals(0.708f, metadata!!.displayPrimaries[0].x, 0.0001f)
        assertEquals(1000f, metadata.maxDisplayLuminance, 0f)
        assertEquals(0.0001f, metadata.minDisplayLuminance, 0.00001f)
        assertEquals(1200f, metadata.maxContentLightLevel, 0f)
        assertEquals(400f, metadata.maxFrameAverageLightLevel, 0f)
        assertEquals(600f, metadata.maxFullFrameLuminance, 0f)
    }

    @Test
    fun missingMetadataIsNotReplacedWithHdr10Defaults() {
        assertNull(PyrowaveHdrMetadata.fromSunshineBytes(null))
        assertNull(PyrowaveHdrMetadata.fromSunshineBytes(ByteArray(PyrowaveHdrMetadata.SS_HDR_METADATA_SIZE - 1)))
        assertNull(PyrowaveHdrMetadata.fromSunshineBytes(ByteArray(PyrowaveHdrMetadata.SS_HDR_METADATA_SIZE)))
        assertTrue(PyrowaveHdrMetadata.isAbsent(ByteArray(PyrowaveHdrMetadata.SS_HDR_METADATA_SIZE)))
    }

    @Test
    fun zeroContentLightLevelsAreAllowedWhenMasteringMetadataIsValid() {
        val metadata = PyrowaveHdrMetadata.fromSunshineBytes(payload(
            35400, 14600, 8500, 39850, 6550, 2300,
            15635, 16450, 1000, 0, 0, 0, 0,
        ))
        assertNotNull(metadata)
        assertEquals(0f, metadata!!.maxContentLightLevel, 0f)
        assertEquals(0f, metadata.maxFrameAverageLightLevel, 0f)
        assertEquals(0f, metadata.maxFullFrameLuminance, 0f)
    }

    @Test
    fun rejectsOutOfRangeCoordinatesAndNonFiniteValues() {
        assertNull(PyrowaveHdrMetadata.fromSunshineBytes(payload(
            50001, 14600, 8500, 39850, 6550, 2300,
            15635, 16450, 1000, 1, 0, 0, 0,
        )))

        val invalid = PyrowaveHdrMetadata(
            displayPrimaries = listOf(
                PyrowaveHdrMetadata.Point(Float.NaN, 0.2f),
                PyrowaveHdrMetadata.Point(0.1f, 0.7f),
                PyrowaveHdrMetadata.Point(0.1f, 0.05f),
            ),
            whitePoint = PyrowaveHdrMetadata.Point(0.3127f, 0.329f),
            maxDisplayLuminance = -1f,
            minDisplayLuminance = 0f,
            maxContentLightLevel = 0f,
            maxFrameAverageLightLevel = 0f,
            maxFullFrameLuminance = 0f,
        )
        assertFalse(invalid.isValidForHdr10())
    }

    @Test
    fun hlgUsesTheSameOptionalStaticMetadataContract() {
        val metadata = PyrowaveHdrMetadata.fromSunshineBytes(payload(
            35400, 14600, 8500, 39850, 6550, 2300,
            15635, 16450, 1000, 1, 0, 0, 0,
        ))
        assertTrue(metadata?.isValidForHdr10() == true)
        // A missing HLG snapshot is represented by null and is handled by the
        // Vulkan layer without fabricating PQ/HDR10 metadata.
        assertNull(PyrowaveHdrMetadata.fromSunshineBytes(ByteArray(PyrowaveHdrMetadata.SS_HDR_METADATA_SIZE)))
    }

    private fun payload(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(PyrowaveHdrMetadata.SS_HDR_METADATA_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }
}
