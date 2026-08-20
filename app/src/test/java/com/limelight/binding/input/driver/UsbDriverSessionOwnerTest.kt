package com.limelight.binding.input.driver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDriverSessionOwnerTest {
    @Test
    fun staleSessionCannotReleaseNewSession() {
        val owner = UsbDriverSessionOwner()
        val first = owner.acquire()
        val second = owner.acquire()

        assertTrue(owner.hasActiveSession())
        assertFalse(owner.release(first))
        assertTrue(owner.owns(second))
        assertTrue(owner.release(second))
        assertFalse(owner.hasActiveSession())
    }

    @Test
    fun activeSessionCanReleaseOnce() {
        val owner = UsbDriverSessionOwner()
        val token = owner.acquire()

        assertTrue(owner.release(token))
        assertFalse(owner.release(token))
        assertFalse(owner.owns(token))
    }

    @Test
    fun resetInvalidatesCurrentSessionAndAllowsLegacyAccess() {
        val owner = UsbDriverSessionOwner()
        val token = owner.acquire()

        owner.reset()

        assertFalse(owner.owns(token))
        assertFalse(owner.hasActiveSession())
    }
}
