package com.limelight

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamKeepAlivePolicyTest {
    @Test
    fun backgroundResumeStartsKeepAlive() {
        assertTrue(
            StreamKeepAlivePolicy.shouldStartForResume(
                isFinishing = false,
                shouldResumeSession = true,
                isResumeStreamEnabled = true,
            )
        )
    }

    @Test
    fun finishingNeverRestartsKeepAlive() {
        assertFalse(
            StreamKeepAlivePolicy.shouldStartForResume(
                isFinishing = true,
                shouldResumeSession = true,
                isResumeStreamEnabled = true,
            )
        )
    }

    @Test
    fun disabledOrUnrequestedResumeDoesNotStartKeepAlive() {
        assertFalse(StreamKeepAlivePolicy.shouldStartForResume(false, false, true))
        assertFalse(StreamKeepAlivePolicy.shouldStartForResume(false, true, false))
    }
}
