package com.limelight.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteImeAutoShowPolicyTest {
    @Test
    fun `auto show is unavailable below Android 11`() {
        assertFalse(RemoteImeAutoShowPolicy.isSupported(29))
    }

    @Test
    fun `auto show is available on Android 11`() {
        assertTrue(RemoteImeAutoShowPolicy.isSupported(30))
    }

    @Test
    fun `auto show remains available on Android 13 and newer`() {
        assertTrue(RemoteImeAutoShowPolicy.isSupported(33))
        assertTrue(RemoteImeAutoShowPolicy.isSupported(35))
    }
}
