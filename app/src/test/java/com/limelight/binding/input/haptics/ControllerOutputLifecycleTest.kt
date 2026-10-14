package com.limelight.binding.input.haptics

import org.junit.Assert.*
import org.junit.Test

class ControllerOutputLifecycleTest {
    @Test fun queuedOutputCannotReachAReusedSlot() {
        val lifecycle = ControllerOutputLifecycle()
        val old = lifecycle.capture(0)!!
        var deliveries = 0
        val queued = { if (lifecycle.isCurrent(0, old)) deliveries++ }
        lifecycle.retire(0)
        lifecycle.arrive(0)
        queued()
        assertEquals(0, deliveries)
        assertTrue(lifecycle.isCurrent(0, lifecycle.capture(0)!!))
    }

    @Test fun outputDuringSharingIsDiscardedWithoutAffectingOtherPlayers() {
        val lifecycle = ControllerOutputLifecycle()
        val other = lifecycle.capture(1)!!
        lifecycle.retire(0)
        assertNull(lifecycle.capture(0))
        assertTrue(lifecycle.isCurrent(1, other))
        lifecycle.arrive(0)
        assertNotNull(lifecycle.capture(0))
    }

    @Test fun repeatedArrivalPreservesFreshOutputButAnotherRetirementInvalidatesIt() {
        val lifecycle = ControllerOutputLifecycle()
        lifecycle.retire(0)
        lifecycle.arrive(0)
        val fresh = lifecycle.capture(0)!!
        lifecycle.arrive(0)
        assertTrue(lifecycle.isCurrent(0, fresh))
        lifecycle.retire(0)
        lifecycle.arrive(0)
        assertFalse(lifecycle.isCurrent(0, fresh))
        assertNull(lifecycle.capture(16))
    }
}
