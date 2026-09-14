package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolutionFormatTest {

    @Test
    fun ratioTextSimplifiesCommonRatios() {
        assertEquals("16:9", ratioText(3840, 2160))
        assertEquals("16:10", ratioText(1920, 1200))
        assertEquals("4:3", ratioText(640, 480))
        assertEquals("9:16", ratioText(1080, 1920))
    }

    @Test
    fun ratioTextNormalizesUglyReductionsRelativeTo9() {
        // 3440:1440 化简为 43:18,改为相对 9 展示
        assertEquals("21.5:9", ratioText(3440, 1440))
        assertEquals("21.3:9", ratioText(2560, 1080))
        // 3360:1440 恰好是 21:9
        assertEquals("21:9", ratioText(3360, 1440))
    }

    @Test
    fun validateResolutionInputCatchesEachError() {
        val existing = listOf(Resolution(1920, 1080))

        assertEquals(
            ResolutionInputError(ResolutionField.WIDTH, ResolutionInputReason.EMPTY),
            validateResolutionInput(null, 1080, existing)
        )
        assertEquals(
            ResolutionInputError(ResolutionField.HEIGHT, ResolutionInputReason.EMPTY),
            validateResolutionInput(1920, null, existing)
        )
        assertEquals(
            ResolutionInputError(ResolutionField.WIDTH, ResolutionInputReason.OUT_OF_RANGE),
            validateResolutionInput(8000, 1440, existing)
        )
        assertEquals(
            ResolutionInputError(ResolutionField.HEIGHT, ResolutionInputReason.OUT_OF_RANGE),
            validateResolutionInput(3840, 200, existing)
        )
        assertEquals(
            ResolutionInputError(ResolutionField.WIDTH, ResolutionInputReason.ODD),
            validateResolutionInput(1921, 1080, existing)
        )
        assertEquals(
            ResolutionInputError(ResolutionField.HEIGHT, ResolutionInputReason.ODD),
            validateResolutionInput(1920, 1081, existing)
        )
        assertEquals(
            ResolutionInputError(null, ResolutionInputReason.DUPLICATE),
            validateResolutionInput(1920, 1080, existing)
        )
    }

    @Test
    fun validateResolutionInputAcceptsValidValue() {
        assertNull(validateResolutionInput(2560, 1440, listOf(Resolution(1920, 1080))))
    }

    @Test
    fun ratioGlyphSizeFitsBoxAndKeepsRatio() {
        // 16:9 宽于 36:24 的框,贴宽
        val (wide, tall) = ratioGlyphSize(1920, 1080)
        assertEquals(36f, wide, 0.01f)
        assertEquals(20.25f, tall, 0.01f)

        // 9:16 贴高
        val (pW, pH) = ratioGlyphSize(1080, 1920)
        assertEquals(24f, pH, 0.01f)
        assertEquals(13.5f, pW, 0.01f)

        // 超宽比例最小边抬到 6dp
        val (uW, uH) = ratioGlyphSize(3840, 240)
        assertEquals(6f, uH, 0.01f)
        assertEquals(6f * 16f, uW, 0.1f)
    }
}
