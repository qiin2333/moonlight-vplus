package com.limelight.binding.input.touch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedTouchGestureRouteOwnerTest {
    @Test
    fun claimedGestureRemainsOwnedUntilTerminalEvent() {
        val owner = EnhancedTouchGestureRouteOwner()

        assertTrue(owner.begin(routeStarted = true))
        assertTrue(owner.ownsContinuation())

        owner.finish()

        assertFalse(owner.ownsContinuation())
    }

    @Test
    fun failedEnhancedDownDoesNotClaimContinuation() {
        val owner = EnhancedTouchGestureRouteOwner()

        assertFalse(owner.begin(routeStarted = false))

        assertFalse(owner.ownsContinuation())
    }

    @Test
    fun newDownCanReplaceStaleOwnership() {
        val owner = EnhancedTouchGestureRouteOwner()
        owner.begin(routeStarted = true)

        owner.begin(routeStarted = false)

        assertFalse(owner.ownsContinuation())
    }
}
