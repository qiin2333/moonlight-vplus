package com.limelight

import org.junit.Assert.assertEquals
import org.junit.Test

class PerfOverlayGestureControllerTest {
    private class Harness {
        private data class Scheduled(val task: Runnable, val at: Long)

        var now = 0L
        val events = mutableListOf<String>()
        private val scheduled = mutableListOf<Scheduled>()
        val controller = PerfOverlayGestureController(
            postDelayed = { task, delay -> scheduled.add(Scheduled(task, now + delay)); Unit },
            removeCallbacks = { task -> scheduled.removeAll { it.task === task }; Unit },
            onSingleTap = { x, y -> events.add("single:$x,$y") },
            onDoubleTap = { events.add("double") },
            onLongPress = { events.add("long") },
            onPressHighlight = { events.add("highlight") },
            longPressTimeoutMs = 500L,
        )

        fun down(
            x: Float = 10f, y: Float = 20f, rawX: Float = x, rawY: Float = y,
            single: Boolean = true, double: Boolean = true, longPress: Boolean = false,
        ) = controller.down(now, rawX, rawY, x, y, 40, single, double, longPress)

        fun up() = controller.up(now)

        fun advanceTo(target: Long) {
            while (true) {
                val next = scheduled.filter { it.at <= target }.minByOrNull { it.at } ?: break
                scheduled.remove(next)
                now = next.at
                next.task.run()
            }
            now = target
        }
    }

    @Test
    fun clickSwitchMatrixAndImmediateSingle() {
        for (single in listOf(false, true)) {
            for (double in listOf(false, true)) {
                val h = Harness()
                h.down(single = single, double = double)
                h.up()
                assertEquals(if (single && !double) listOf("single:10.0,20.0") else emptyList<String>(), h.events)
                h.advanceTo(301)
                assertEquals(if (single) listOf("single:10.0,20.0") else emptyList<String>(), h.events)
            }
        }
    }

    @Test
    fun doubleTapSuppressesSingleAndResetsSequence() {
        val h = Harness()
        h.down()
        h.up()
        h.advanceTo(200)
        h.down(x = 15f)
        h.up()
        h.advanceTo(700)
        assertEquals(listOf("double"), h.events)
        h.down()
        h.up()
        h.advanceTo(1001)
        assertEquals(listOf("double", "single:10.0,20.0"), h.events)
    }

    @Test
    fun secondDownSuspendsSingleUntilLongPressWins() {
        val h = Harness()
        h.down()
        h.up()
        h.advanceTo(200)
        h.down(longPress = true)
        h.advanceTo(301)
        assertEquals(emptyList<String>(), h.events)
        h.advanceTo(700)
        h.up()
        h.advanceTo(1000)
        assertEquals(listOf("highlight", "long"), h.events)
    }

    @Test
    fun secondDownInsideWindowCanCompleteAfterOriginalTimeout() {
        val h = Harness()
        h.down()
        h.up()
        h.advanceTo(250)
        h.down()
        h.advanceTo(400)
        assertEquals(emptyList<String>(), h.events)
        h.up()
        assertEquals(listOf("double"), h.events)
        h.advanceTo(1000)
        assertEquals(listOf("double"), h.events)
    }

    @Test
    fun distantClicksRemainIndependentAndKeepFirstCoordinates() {
        val h = Harness()
        h.down(x = 4f, y = 6f, rawX = 10f, rawY = 10f)
        h.up()
        h.advanceTo(150)
        h.down(x = 80f, y = 90f, rawX = 100f, rawY = 100f)
        assertEquals(listOf("single:4.0,6.0"), h.events)
        h.up()
        h.advanceTo(451)
        assertEquals(listOf("single:4.0,6.0", "single:80.0,90.0"), h.events)
    }

    @Test
    fun slopUsesRawPositionEvenWhenOverlayCoordinatesChange() {
        val h = Harness()
        h.down(x = 4f, rawX = 100f)
        h.up()
        h.advanceTo(100)
        h.down(x = 80f, rawX = 105f)
        h.up()
        h.advanceTo(500)
        assertEquals(listOf("double"), h.events)
    }

    @Test
    fun dragAndCancelClearMatchingDoubleTapSequence() {
        for (cancel in listOf(false, true)) {
            val h = Harness()
            h.down()
            h.up()
            h.advanceTo(100)
            h.down(longPress = true)
            if (cancel) h.controller.cancel() else h.controller.moveBeyondSlop()
            h.up()
            h.advanceTo(1000)
            assertEquals(emptyList<String>(), h.events)
        }
    }

    @Test
    fun cancelPendingAndActiveTasksCoversVisibilityLockAndLifecycleTransitions() {
        val h = Harness()
        h.down(longPress = true)
        h.up()
        h.controller.cancel()
        h.advanceTo(1000)
        h.down(longPress = true)
        h.controller.cancel()
        h.advanceTo(2000)
        h.up()
        assertEquals(emptyList<String>(), h.events)
    }

    @Test
    fun secondLongPressOutsideSlopDoesNotSuppressFirstSingle() {
        val h = Harness()
        h.down()
        h.up()
        h.advanceTo(100)
        h.down(rawX = 1000f, longPress = true)
        h.advanceTo(601)
        h.up()
        h.advanceTo(1000)
        assertEquals(listOf("single:10.0,20.0", "highlight", "long"), h.events)
    }

    @Test
    fun reentrantCancellationWhileDeliveringPreviousSingleDropsSecondPress() {
        val scheduled = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        lateinit var controller: PerfOverlayGestureController
        controller = PerfOverlayGestureController(
            postDelayed = { task, _ -> scheduled.add(task); Unit },
            removeCallbacks = { task -> scheduled.remove(task); Unit },
            onSingleTap = { _, _ -> events.add("single"); controller.cancel() },
            onDoubleTap = { events.add("double") },
            onLongPress = { events.add("long") },
            onPressHighlight = { events.add("highlight") },
            longPressTimeoutMs = 500,
        )
        controller.down(0, 0f, 0f, 0f, 0f, 40, true, true, true)
        controller.up(0)
        controller.down(100, 100f, 100f, 100f, 100f, 40, true, true, true)
        controller.up(150)
        scheduled.toList().forEach { it.run() }
        assertEquals(listOf("single"), events)
        assertEquals(0, scheduled.size)
    }

    @Test
    fun expiredPendingTapIsDeliveredBeforeNewDownEvenIfTimeoutWasDelayed() {
        val scheduled = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        val controller = PerfOverlayGestureController(
            postDelayed = { task, _ -> scheduled.add(task); Unit },
            removeCallbacks = { task -> scheduled.remove(task); Unit },
            onSingleTap = { x, _ -> events.add("single:$x") },
            onDoubleTap = { events.add("double") },
            onLongPress = { events.add("long") },
            onPressHighlight = { events.add("highlight") },
            longPressTimeoutMs = 500,
        )
        controller.down(0, 0f, 0f, 1f, 0f, 40, true, true, false)
        controller.up(0)
        controller.down(300, 0f, 0f, 2f, 0f, 40, true, true, false)
        assertEquals(listOf("single:1.0"), events)
        controller.up(301)
        scheduled.toList().forEach { it.run() }
        assertEquals(listOf("single:1.0", "single:2.0"), events)
    }

    @Test
    fun independentSinglesDoNotUseDoubleTapWindow() {
        val h = Harness()
        h.down(double = false)
        h.up()
        h.advanceTo(100)
        h.down(x = 50f, double = false)
        h.up()
        assertEquals(listOf("single:10.0,20.0", "single:50.0,20.0"), h.events)
    }
}
