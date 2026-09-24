package com.limelight.binding.input.touchpad

import com.limelight.binding.input.touchpad.CompatibilityTouchpadGesture.Action
import com.limelight.binding.input.touchpad.CompatibilityTouchpadGesture.Direction
import com.limelight.binding.input.touchpad.CompatibilityTouchpadGesture.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTouchpadGestureTest {
    private val gesture = CompatibilityTouchpadGesture(slop = 16f)
    private fun points(vararg coordinates: Pair<Float, Float>) =
        coordinates.mapIndexed { id, (x, y) -> Point(id, x, y) }
    private fun one(x: Float = 0f, y: Float = 0f) = points(x to y)
    private val two = points(0f to 0f, 100f to 0f)
    private val three = points(0f to 0f, 100f to 0f, 200f to 0f)
    private fun shiftedThree(x: Float, y: Float = 0f) = points(x to y, x + 100f to y, x + 200f to y)

    @Test fun `Android delayed tap release still clicks once`() {
        gesture.down(one(), 1000)
        assertEquals(listOf(Action.Click(1)), gesture.up(one(), 1301))
        assertTrue(gesture.up(one(), 1302).isEmpty())
    }

    @Test fun `synthetic single contact scroll preserves the initial movement`() {
        gesture.down(one(), 1000)
        assertTrue(gesture.move(one(3f, 4f)).isEmpty())
        assertEquals(listOf(Action.Scroll(0, 20)), gesture.move(one(12f, 20f)))
        assertFalse(gesture.up(one(12f, 20f), 1100).any { it is Action.Click })
    }

    @Test fun `raw touchpad single contact moves the pointer without waiting for scroll slop`() {
        gesture.down(one(), 1000, singleContactScroll = false)
        assertEquals(listOf(Action.Move(3, 4)), gesture.move(one(3f, 4f)))
        assertEquals(listOf(Action.Move(17, 16)), gesture.move(one(20f, 20f)))
        assertTrue(gesture.up(one(20f, 20f), 1100).isEmpty())
    }

    @Test fun `raw touchpad rebases when a second contact begins scrolling`() {
        gesture.down(one(), 1000, singleContactScroll = false)
        gesture.move(one(20f))
        gesture.pointerDown(two)
        assertEquals(listOf(Action.Scroll(0, 30)), gesture.move(points(0f to 30f, 100f to 30f)))
        assertTrue(gesture.up(points(0f to 30f, 100f to 30f), 1100).isEmpty())
    }

    @Test fun `slow subpixel scroll accumulates instead of disappearing`() {
        gesture.down(one(), 0)
        gesture.move(one(16f))
        val scroll = (1..16).flatMap { gesture.move(one(16f + it * 0.25f)) }
            .filterIsInstance<Action.Scroll>().sumOf { it.x.toInt() }
        assertEquals(4, scroll)
    }

    @Test fun `negative subpixel scroll keeps the same precision`() {
        gesture.down(one(), 0)
        gesture.move(one(-16f))
        val scroll = (1..16).flatMap { gesture.move(one(-16f - it * 0.25f)) }
            .filterIsInstance<Action.Scroll>().sumOf { it.x.toInt() }
        assertEquals(-4, scroll)
    }

    @Test fun `last frame movement prevents an accidental right click`() {
        gesture.down(two, 1000)
        assertEquals(listOf(Action.Scroll(0, 100)),
            gesture.up(points(0f to 100f, 100f to 100f), 1100))
    }

    @Test fun `three fingers moving out of tap tolerance and back do not click`() {
        gesture.down(three, 1000)
        gesture.move(points(30f to 0f, 130f to 0f, 230f to 0f))
        gesture.move(three)
        assertTrue(gesture.up(three, 1200).isEmpty())
    }

    @Test fun `raw two finger tap does not become a left click on the final lift`() {
        gesture.down(one(), 1000)
        gesture.pointerDown(two)
        assertEquals(listOf(Action.Click(2)), gesture.up(two, 1100))
        assertTrue(gesture.move(one(50f)).isEmpty())
        assertTrue(gesture.up(one(50f), 1150).isEmpty())
    }

    @Test fun `three finger tap is a middle click`() {
        gesture.down(one(), 1000)
        gesture.pointerDown(two)
        gesture.pointerDown(three)
        assertEquals(listOf(Action.Click(3)), gesture.up(three, 1150))
    }

    @Test fun `parallel raw contacts scroll without zooming`() {
        gesture.down(two, 0)
        assertEquals(listOf(Action.Scroll(0, 30)), gesture.move(points(20f to 30f, 120f to 30f)))
    }

    @Test fun `vertical scroll ignores lateral drift and reverses without changing axis`() {
        gesture.down(one(), 0)
        assertEquals(listOf(Action.Scroll(0, 20)), gesture.move(one(2f, 20f)))
        assertEquals(listOf(Action.Scroll(0, 10)), gesture.move(one(4f, 30f)))
        assertTrue(gesture.move(one(30f, 30f)).isEmpty())
        assertEquals(listOf(Action.Scroll(0, -10)), gesture.move(one(31f, 20f)))
        assertTrue(gesture.up(one(31f, 20f), 100).isEmpty())
    }

    @Test fun `horizontal raw two finger scroll ignores vertical drift`() {
        gesture.down(two, 0, singleContactScroll = false)
        assertEquals(listOf(Action.Scroll(20, 0)), gesture.move(points(20f to 2f, 120f to 2f)))
        assertEquals(listOf(Action.Scroll(10, 0)), gesture.move(points(30f to 4f, 130f to 4f)))
        assertTrue(gesture.move(points(30f to 30f, 130f to 30f)).isEmpty())
        assertEquals(listOf(Action.Scroll(-10, 0)), gesture.move(points(20f to 31f, 120f to 31f)))
    }

    @Test fun `a new contact sequence chooses its own scroll axis`() {
        gesture.down(one(), 0)
        gesture.move(one(2f, 20f))
        gesture.up(one(2f, 20f), 100)
        gesture.down(one(), 200)
        assertEquals(listOf(Action.Scroll(20, 0)), gesture.move(one(20f, 2f)))
        gesture.cancel()
        gesture.down(one(), 300)
        assertEquals(listOf(Action.Scroll(0, -20)), gesture.move(one(2f, -20f)))
    }

    @Test fun `adding a raw second finger does not reuse the pointer direction`() {
        gesture.down(one(), 0, singleContactScroll = false)
        assertEquals(listOf(Action.Move(20, 2)), gesture.move(one(20f, 2f)))
        gesture.pointerDown(two)
        assertEquals(listOf(Action.Scroll(0, 20)), gesture.move(points(2f to 20f, 102f to 20f)))
    }

    @Test fun `pinch outputs wheel steps with no held modifier state`() {
        gesture.down(two, 0)
        assertEquals(listOf(Action.Zoom(360)), gesture.move(points(-20f to 0f, 120f to 0f)))
        gesture.cancel()
        gesture.down(one(), 1000)
        assertEquals(listOf(Action.Click(1)), gesture.up(one(), 1100))
    }

    @Test fun `a third finger starts a new mode without reusing pinch remainder`() {
        gesture.down(two, 0)
        gesture.move(points(-20f to 0f, 120f to 0f))
        gesture.pointerDown(three)
        assertEquals(listOf(Action.Swipe(Direction.RIGHT)),
            gesture.move(points(80f to 0f, 180f to 0f, 280f to 0f)))
        assertEquals(listOf(Action.EndSwitch(cancelled = false)), gesture.up(shiftedThree(80f), 120))
    }

    @Test fun `a swipe triggers only once per contact sequence`() {
        gesture.down(three, 0)
        assertEquals(listOf(Action.Swipe(Direction.UP)),
            gesture.move(points(0f to -80f, 100f to -80f, 200f to -80f)))
        assertTrue(gesture.move(points(0f to -160f, 100f to -160f, 200f to -160f)).isEmpty())
        assertTrue(gesture.up(three, 200).isEmpty())
    }

    @Test fun `horizontal selection stays open while fingers are stationary and commits on lift`() {
        gesture.down(three, 0)
        assertEquals(listOf(Action.Swipe(Direction.RIGHT)), gesture.move(shiftedThree(60f)))
        repeat(20) { assertTrue(gesture.move(shiftedThree(60f)).isEmpty()) }
        assertEquals(listOf(Action.EndSwitch(cancelled = false)), gesture.up(shiftedThree(60f), 2000))
        assertTrue(gesture.up(two, 2010).isEmpty())
        assertTrue(gesture.up(one(), 2020).isEmpty())
        assertTrue(gesture.cancel().isEmpty())
    }

    @Test fun `horizontal selection accumulates small movements and can reverse direction`() {
        gesture.down(three, 0)
        gesture.move(shiftedThree(60f))
        assertTrue(gesture.move(shiftedThree(70f)).isEmpty())
        assertTrue(gesture.move(shiftedThree(80f)).isEmpty())
        assertEquals(listOf(Action.Swipe(Direction.RIGHT)), gesture.move(shiftedThree(108f)))
        assertEquals(List(2) { Action.Swipe(Direction.RIGHT) }, gesture.move(shiftedThree(204f)))
        assertEquals(listOf(Action.Swipe(Direction.LEFT)), gesture.move(shiftedThree(156f)))
    }

    @Test fun `horizontal selection does not turn into a vertical shortcut`() {
        gesture.down(three, 0)
        gesture.move(shiftedThree(60f))
        assertTrue(gesture.move(shiftedThree(60f, -200f)).isEmpty())
        assertEquals(listOf(Action.EndSwitch(cancelled = false)), gesture.up(shiftedThree(60f, -200f), 200))
    }

    @Test fun `reversing does not first consume unfinished forward travel`() {
        gesture.down(three, 0)
        gesture.move(shiftedThree(60f))
        assertEquals(listOf(Action.Swipe(Direction.RIGHT)), gesture.move(shiftedThree(120f)))
        assertTrue(gesture.move(shiftedThree(108f)).isEmpty())
        assertEquals(listOf(Action.Swipe(Direction.LEFT)), gesture.move(shiftedThree(72f)))
    }

    @Test fun `cancelling selection closes it once without confirming or clicking`() {
        gesture.down(three, 0)
        gesture.move(shiftedThree(-60f))
        assertEquals(listOf(Action.EndSwitch(cancelled = true)), gesture.cancel())
        assertTrue(gesture.cancel().isEmpty())
        assertTrue(gesture.up(three, 100).isEmpty())
    }

    @Test fun `adding a fourth finger cancels selection and drains remaining contacts`() {
        gesture.down(three, 0)
        gesture.move(shiftedThree(60f))
        val four = shiftedThree(60f) + Point(3, 360f, 0f)
        assertEquals(listOf(Action.EndSwitch(cancelled = true)), gesture.pointerDown(four))
        assertTrue(gesture.up(four, 100).isEmpty())
        assertTrue(gesture.move(three).isEmpty())
        assertTrue(gesture.cancel().isEmpty())
    }

    @Test fun `cancelled or orphaned releases never click`() {
        assertTrue(gesture.up(one(), 100).isEmpty())
        gesture.down(one(), 1000)
        gesture.cancel()
        assertTrue(gesture.up(one(), 1100).isEmpty())
    }

    @Test fun `long contacts do not become taps`() {
        gesture.down(one(), 1000)
        assertTrue(gesture.up(one(), 1700).isEmpty())
    }

    @Test fun `four fingers drain without producing a three finger action`() {
        gesture.down(three, 0)
        val four = points(0f to 0f, 100f to 0f, 200f to 0f, 300f to 0f)
        gesture.pointerDown(four)
        assertTrue(gesture.move(four).isEmpty())
        assertTrue(gesture.up(four, 100).isEmpty())
    }
}
