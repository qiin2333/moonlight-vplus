package com.limelight.binding.input.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
    fun failedEnhancedDownCanRollBackOwnership() {
        val owner = EnhancedTouchGestureRouteOwner()
        owner.begin(config(2f))

        owner.finish()

        assertFalse(owner.ownsContinuation())
    }

    @Test
    fun settingChangeBeforeAdditionalPointerKeepsGestureSensitivity() {
        val owner = EnhancedTouchGestureRouteOwner()
        val gestureConfig = config(2f)
        owner.begin(gestureConfig)

        val changedLiveConfig = config(5f)
        val secondPointer = EnhancedTouchPointerState(
            initialX = 800f,
            initialY = 500f,
            config = requireNotNull(owner.currentPointerConfig())
        )
        secondPointer.update(810f, 500f)

        assertSame(gestureConfig, owner.currentPointerConfig())
        assertNotSame(changedLiveConfig, owner.currentPointerConfig())
        assertEquals(820f, secondPointer.selectedX(), 0.0001f)
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
