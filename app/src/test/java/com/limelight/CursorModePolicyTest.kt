package com.limelight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorModePolicyTest {
    @Test
    fun `system pointer keeps the default arrow until host cursor state is known`() {
        assertTrue(
            CursorModePolicy.shouldShowPlaceholderSystemCursor(
                cursorStateKnown = false,
                hostCursorVisible = false,
                hasCursorShape = false,
                usesSystemMousePointer = true
            )
        )
        assertFalse(
            CursorModePolicy.shouldShowPlaceholderSystemCursor(
                cursorStateKnown = true,
                hostCursorVisible = false,
                hasCursorShape = false,
                usesSystemMousePointer = true
            )
        )
        assertFalse(
            CursorModePolicy.shouldShowPlaceholderSystemCursor(
                cursorStateKnown = false,
                hostCursorVisible = true,
                hasCursorShape = true,
                usesSystemMousePointer = false
            )
        )
    }

    @Test
    fun `visible system cursor without a shape uses the default arrow`() {
        assertTrue(
            CursorModePolicy.shouldShowPlaceholderSystemCursor(
                cursorStateKnown = true,
                hostCursorVisible = true,
                hasCursorShape = false,
                usesSystemMousePointer = true
            )
        )
        assertFalse(
            CursorModePolicy.shouldShowPlaceholderSystemCursor(
                cursorStateKnown = true,
                hostCursorVisible = true,
                hasCursorShape = true,
                usesSystemMousePointer = true
            )
        )
        assertFalse(
            CursorModePolicy.shouldShowPlaceholderSystemCursor(
                cursorStateKnown = true,
                hostCursorVisible = false,
                hasCursorShape = false,
                usesSystemMousePointer = true
            )
        )
    }

    @Test
    fun `external mouse uses video cursor even when touchpad local cursor is disabled`() {
        assertFalse(
            CursorModePolicy.shouldUseLocalMode(
                nativePointerSupported = true,
                nativePointerEnabled = true,
                touchpadEnabled = false,
                localCursorEnabled = false,
                hasCursorOverlay = true,
                externalDisplayActive = true
            )
        )
    }

    @Test
    fun `external touchpad uses video cursor despite saved local cursor preference`() {
        val localModeActive = CursorModePolicy.shouldUseLocalMode(
            nativePointerSupported = true,
            nativePointerEnabled = false,
            touchpadEnabled = true,
            localCursorEnabled = true,
            hasCursorOverlay = true,
            externalDisplayActive = true
        )
        assertFalse(localModeActive)
        assertFalse(
            CursorModePolicy.shouldRenderTouchpadOverlay(
                localModeActive = localModeActive,
                nativePointerSupported = true,
                touchpadEnabled = true,
                localCursorEnabled = true,
                nativePointerEnabled = false
            )
        )
    }

    @Test
    fun `local output restores saved cursor modes after disconnect or presentation fallback`() {
        // The effective output controls this policy, even if external output is preferred.
        for (nativePointer in listOf(true, false)) {
            fun usesLocalCursor(externalDisplayActive: Boolean) = CursorModePolicy.shouldUseLocalMode(
                nativePointerSupported = true,
                nativePointerEnabled = nativePointer,
                touchpadEnabled = !nativePointer,
                localCursorEnabled = true,
                hasCursorOverlay = true,
                externalDisplayActive = externalDisplayActive
            )

            assertTrue(usesLocalCursor(externalDisplayActive = false))
            assertFalse(usesLocalCursor(externalDisplayActive = true))
            assertTrue(usesLocalCursor(externalDisplayActive = false))
        }
    }

    @Test
    fun `touchpad uses host cursor when local cursor is disabled`() {
        assertFalse(
            CursorModePolicy.shouldUseLocalMode(
                nativePointerSupported = true,
                nativePointerEnabled = false,
                touchpadEnabled = true,
                localCursorEnabled = false,
                hasCursorOverlay = true
            )
        )
        assertFalse(
            CursorModePolicy.shouldRenderTouchpadOverlay(
                localModeActive = true,
                nativePointerSupported = true,
                touchpadEnabled = true,
                localCursorEnabled = false,
                nativePointerEnabled = false
            )
        )
    }

    @Test
    fun `touchpad can opt in to local cursor when an overlay is available`() {
        assertTrue(
            CursorModePolicy.shouldUseLocalMode(
                nativePointerSupported = true,
                nativePointerEnabled = false,
                touchpadEnabled = true,
                localCursorEnabled = true,
                hasCursorOverlay = true
            )
        )
        assertTrue(
            CursorModePolicy.shouldRenderTouchpadOverlay(
                localModeActive = true,
                nativePointerSupported = true,
                touchpadEnabled = true,
                localCursorEnabled = true,
                nativePointerEnabled = false
            )
        )
    }

    @Test
    fun `local cursor is not enabled without an overlay`() {
        assertFalse(
            CursorModePolicy.shouldUseLocalMode(
                nativePointerSupported = true,
                nativePointerEnabled = false,
                touchpadEnabled = true,
                localCursorEnabled = true,
                hasCursorOverlay = false
            )
        )
    }

    @Test
    fun `native pointer remains independent from touchpad local cursor setting`() {
        assertTrue(
            CursorModePolicy.shouldUseLocalMode(
                nativePointerSupported = true,
                nativePointerEnabled = true,
                touchpadEnabled = false,
                localCursorEnabled = false,
                hasCursorOverlay = false
            )
        )
        assertFalse(
            CursorModePolicy.shouldRenderTouchpadOverlay(
                localModeActive = true,
                nativePointerSupported = true,
                touchpadEnabled = true,
                localCursorEnabled = true,
                nativePointerEnabled = true
            )
        )
    }

    @Test
    fun `native pointer requires platform support`() {
        assertFalse(
            CursorModePolicy.shouldUseLocalMode(
                nativePointerSupported = false,
                nativePointerEnabled = true,
                touchpadEnabled = false,
                localCursorEnabled = false,
                hasCursorOverlay = true
            )
        )
    }

    @Test
    fun `unsupported native pointer preference does not suppress touchpad overlay`() {
        assertTrue(
            CursorModePolicy.shouldUseLocalMode(
                nativePointerSupported = false,
                nativePointerEnabled = true,
                touchpadEnabled = true,
                localCursorEnabled = true,
                hasCursorOverlay = true
            )
        )
        assertTrue(
            CursorModePolicy.shouldRenderTouchpadOverlay(
                localModeActive = true,
                nativePointerSupported = false,
                touchpadEnabled = true,
                localCursorEnabled = true,
                nativePointerEnabled = true
            )
        )
    }
}
