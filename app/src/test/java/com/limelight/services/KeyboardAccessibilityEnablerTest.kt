package com.limelight.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardAccessibilityEnablerTest {
    private val enabler = KeyboardAccessibilityEnabler

    @Test
    fun startsOnlyWhenEnabledAuthorizedAndNotRunning() {
        assertTrue(enabler.shouldStartEnableAttempt(true, false, true, false))
        assertFalse(enabler.shouldStartEnableAttempt(false, false, true, false))
        assertFalse(enabler.shouldStartEnableAttempt(true, true, true, false))
        assertFalse(enabler.shouldStartEnableAttempt(true, false, false, false))
    }

    @Test
    fun oneForegroundCycleAttemptsOnce() {
        assertFalse(enabler.shouldStartEnableAttempt(true, false, true, true))
    }
}
