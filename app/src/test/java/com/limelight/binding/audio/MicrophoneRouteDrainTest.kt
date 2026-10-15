package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneRouteDrainTest {
    @Test fun noPreviousScoRequestDoesNotWait() {
        assertEquals(true, MicrophoneRouteDrain().observe(0, false))
    }

    @Test fun nextRequestWaitsForActualDisconnection() {
        val drain = MicrophoneRouteDrain()
        drain.begin(100)
        assertNull(drain.observe(200, false))
        assertEquals(true, drain.observe(300, true))
        assertEquals(true, drain.observe(400, false))
    }

    @Test fun repeatedReleaseCannotExtendDeadlineForever() {
        val drain = MicrophoneRouteDrain()
        drain.begin(0)
        drain.begin(4000)
        assertEquals(false, drain.observe(5000, false))
    }

    @Test fun timeoutDoesNotPretendOldConnectionWasReleased() {
        val drain = MicrophoneRouteDrain()
        drain.begin(0)
        assertEquals(false, drain.observe(5000, false))
        assertEquals(false, drain.observe(6000, false))
        assertEquals(true, drain.observe(6100, true))
        drain.begin(6200)
        assertNull(drain.observe(6300, false))
    }
}
