package com.limelight.binding.input.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadMultiFingerGestureTranslatorTest {
    private fun p(x: Float, y: Float) = TouchpadMultiFingerGestureTranslator.Point(x, y)

    @Test
    fun `two fingers moving together produce scroll`() {
        val translator = TouchpadMultiFingerGestureTranslator()
        translator.pointerDown(listOf(p(0f, 0f), p(100f, 0f)), 0)

        val actions = translator.move(listOf(p(20f, 30f), p(120f, 30f)))

        assertEquals(
            listOf(TouchpadMultiFingerGestureTranslator.Action.Scroll(20f, 30f)),
            actions
        )
    }

    @Test
    fun `two fingers changing separation produce pinch`() {
        val translator = TouchpadMultiFingerGestureTranslator()
        translator.pointerDown(listOf(p(0f, 0f), p(100f, 0f)), 0)

        val actions = translator.move(listOf(p(-20f, 0f), p(120f, 0f)))

        assertEquals(1, actions.size)
        assertTrue((actions.single() as TouchpadMultiFingerGestureTranslator.Action.Pinch).scale > 1f)
    }

    @Test
    fun `short stationary two finger contact produces tap`() {
        val translator = TouchpadMultiFingerGestureTranslator()
        val points = listOf(p(0f, 0f), p(100f, 0f))
        translator.pointerDown(points, 1_000)

        assertEquals(
            listOf(TouchpadMultiFingerGestureTranslator.Action.TwoFingerTap),
            translator.pointerUp(points, 1_200)
        )
    }

    @Test
    fun `three finger horizontal movement produces repeated swipe steps`() {
        val translator = TouchpadMultiFingerGestureTranslator()
        translator.pointerDown(listOf(p(0f, 0f), p(20f, 0f), p(40f, 0f)), 0)

        val first = translator.move(listOf(p(80f, 5f), p(100f, 5f), p(120f, 5f)))
        val second = translator.move(listOf(p(140f, 5f), p(160f, 5f), p(180f, 5f)))

        assertEquals(
            listOf(TouchpadMultiFingerGestureTranslator.Action.ThreeFingerSwipe(
                TouchpadMultiFingerGestureTranslator.Direction.RIGHT
            )),
            first
        )
        assertEquals(
            listOf(TouchpadMultiFingerGestureTranslator.Action.ThreeFingerSwipe(
                TouchpadMultiFingerGestureTranslator.Direction.RIGHT
            )),
            second
        )
    }

    @Test
    fun `short stationary three finger contact produces tap`() {
        val translator = TouchpadMultiFingerGestureTranslator()
        val points = listOf(p(0f, 0f), p(20f, 0f), p(40f, 0f))
        translator.pointerDown(points, 1_000)

        assertEquals(
            listOf(TouchpadMultiFingerGestureTranslator.Action.ThreeFingerTap),
            translator.pointerUp(points, 1_200)
        )
    }
}
