package com.limelight.binding.input.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadGestureTranslatorTest {
    @Test
    fun `contact movement becomes high resolution scroll`() {
        val translator = TouchpadGestureTranslator()

        translator.begin(100f, 200f)

        assertEquals(
            TouchpadGestureTranslator.ScrollDelta(horizontal = 12, vertical = -30),
            translator.move(112f, 170f)
        )
    }

    @Test
    fun `short stationary contact becomes a tap`() {
        val translator = TouchpadGestureTranslator()

        translator.begin(100f, 200f, eventTime = 1_000)

        assertEquals(
            TouchpadGestureTranslator.EndResult.TAP,
            translator.end(104f, 203f, eventTime = 1_120)
        )
    }

    @Test
    fun `scrolling contact does not become a tap`() {
        val translator = TouchpadGestureTranslator()

        translator.begin(100f, 200f, eventTime = 1_000)
        translator.move(100f, 240f)

        assertEquals(
            TouchpadGestureTranslator.EndResult.NONE,
            translator.end(100f, 240f, eventTime = 1_120)
        )
    }

    @Test
    fun `slightly moving medium length contact remains a tap`() {
        val translator = TouchpadGestureTranslator(
            tapTimeoutMs = 500,
            movementThresholdPx = 24f
        )

        translator.begin(100f, 200f, eventTime = 1_000)
        translator.move(118f, 214f)

        assertEquals(
            TouchpadGestureTranslator.EndResult.TAP,
            translator.end(118f, 214f, eventTime = 1_420)
        )
    }

    @Test
    fun `custom tap settings change gesture classification`() {
        val translator = TouchpadGestureTranslator(
            tapTimeoutMs = 250,
            movementThresholdPx = 8f
        )

        translator.begin(100f, 200f, eventTime = 1_000)
        translator.move(109f, 200f)

        assertEquals(
            TouchpadGestureTranslator.EndResult.NONE,
            translator.end(109f, 200f, eventTime = 1_200)
        )
    }

    @Test
    fun `cancelled contact cannot produce a tap`() {
        val translator = TouchpadGestureTranslator()
        translator.begin(100f, 200f, eventTime = 1_000)

        translator.cancel()

        assertEquals(
            TouchpadGestureTranslator.EndResult.NONE,
            translator.end(100f, 200f, eventTime = 1_100)
        )
    }
}
