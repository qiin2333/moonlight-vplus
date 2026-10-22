package com.limelight.binding.input.touch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedTouchGestureRouteOwnerTest {
    @Test
    fun claimedGestureRemainsOwnedUntilTerminalEvent() {
        val owner = EnhancedTouchGestureRouteOwner()
        val config = config(2f)

        owner.begin(config)
        assertTrue(owner.ownsContinuation())
        assertSame(config, owner.currentPointerConfig())

        owner.finish()

        assertFalse(owner.ownsContinuation())
        assertNull(owner.currentPointerConfig())
    }

    @Test
    fun newDownCanReplaceStaleOwnership() {
        val owner = EnhancedTouchGestureRouteOwner()
        owner.begin(config(2f))
        val replacement = config(5f)

        owner.begin(replacement)

        assertSame(replacement, owner.currentPointerConfig())
    }

    private fun config(velocityFactor: Float) = EnhancedTouchPointerConfig(
        screenWidth = 1_000f,
        initialZonePixels = 0f,
        enhancedTouchDirection = 1,
        enhancedTouchZoneDivider = 0.5f,
        pointerVelocityFactor = velocityFactor
    )
}
