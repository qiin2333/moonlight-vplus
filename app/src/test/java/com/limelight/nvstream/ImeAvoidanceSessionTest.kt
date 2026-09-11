package com.limelight.nvstream

import org.junit.Assert.*
import org.junit.Test

class ImeAvoidanceSessionTest {
    private fun context(id: Long = 2) = RemoteTextContext(
        0xB3, 1, id, 3, 2, 1, 800, 1000,
        0, 0, 1920, 1080, 0, 0, 0, 0, 1920, 1080,
    )

    @Test fun consumesRecentTargetOnlyOnce() {
        val s = ImeAvoidanceSession()
        val c = context()
        s.offer(c, 100)
        s.updateVisibility(true, 200)
        assertEquals(c, s.target)
        s.updateVisibility(false, 300)
        s.updateVisibility(true, 400)
        assertNull(s.target)
    }

    @Test fun rejectsExpiredAndFutureReceiptTimes() {
        for (now in listOf(99L, 5101L)) {
            val s = ImeAvoidanceSession()
            s.offer(context(), 100)
            s.updateVisibility(true, now)
            assertNull(s.target)
        }
    }

    @Test fun freezesTargetAndManualControlUntilDismissal() {
        val s = ImeAvoidanceSession()
        val c = context()
        s.offer(c, 100)
        s.updateVisibility(true, 200)
        s.takeControl()
        s.offer(context(9), 300)
        s.updateVisibility(true, 400)
        assertTrue(s.userControlled)
        assertEquals(c, s.target)
        s.updateVisibility(false, 500)
        assertFalse(s.userControlled)
        s.updateVisibility(true, 600)
        assertNull(s.target)
    }

    @Test fun nextCycleCanUseNewContextAndResetDiscardsEverything() {
        val s = ImeAvoidanceSession()
        s.updateVisibility(true, 100)
        s.updateVisibility(false, 200)
        s.offer(context(), 300)
        s.updateVisibility(true, 400)
        assertNotNull(s.target)
        s.reset()
        assertNull(s.target)
        assertFalse(s.visible)
        assertFalse(s.userControlled)
    }

    @Test fun invalidationDoesNotTakeControlBackFromUser() {
        val s = ImeAvoidanceSession()
        s.offer(context(), 100)
        s.updateVisibility(true, 200)
        s.takeControl()
        s.invalidateTarget()
        assertNull(s.target)
        assertTrue(s.userControlled)
    }
}
