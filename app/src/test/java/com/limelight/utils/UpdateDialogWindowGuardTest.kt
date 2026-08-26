package com.limelight.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDialogWindowGuardTest {
    @Test
    fun `allows a visible focused activity window`() {
        assertTrue(windowUsable())
    }

    @Test
    fun `rejects an activity that is exiting or recreating`() {
        assertFalse(windowUsable(isFinishing = true))
        assertFalse(windowUsable(isDestroyed = true))
        assertFalse(windowUsable(isChangingConfigurations = true))
    }

    @Test
    fun `rejects a detached hidden or background window`() {
        assertFalse(windowUsable(isAttachedToWindow = false))
        assertFalse(windowUsable(hasWindowToken = false))
        assertFalse(windowUsable(isWindowVisible = false))
        assertFalse(windowUsable(hasWindowFocus = false))
    }

    private fun windowUsable(
        isFinishing: Boolean = false,
        isDestroyed: Boolean = false,
        isChangingConfigurations: Boolean = false,
        isAttachedToWindow: Boolean = true,
        hasWindowToken: Boolean = true,
        isWindowVisible: Boolean = true,
        hasWindowFocus: Boolean = true
    ): Boolean = UpdateManager.isUpdateDialogWindowUsable(
        isFinishing,
        isDestroyed,
        isChangingConfigurations,
        isAttachedToWindow,
        hasWindowToken,
        isWindowVisible,
        hasWindowFocus
    )
}
