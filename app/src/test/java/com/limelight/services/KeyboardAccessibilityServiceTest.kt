package com.limelight.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardAccessibilityServiceTest {
    @Test
    fun doesNotConsumeEventsWithoutARegisteredCallback() {
        assertFalse(shouldConsumeKeyEvent(interceptingEnabled = true, hasCallback = false))
    }

    @Test
    fun consumesEventsOnlyWhileInterceptingWithARegisteredCallback() {
        assertFalse(shouldConsumeKeyEvent(interceptingEnabled = false, hasCallback = true))
        assertTrue(shouldConsumeKeyEvent(interceptingEnabled = true, hasCallback = true))
    }
}
