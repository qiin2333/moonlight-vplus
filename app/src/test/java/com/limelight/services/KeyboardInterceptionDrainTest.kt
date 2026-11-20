package com.limelight.services

import com.limelight.services.KeyboardInterceptionDrain.Companion.ACTION_DOWN
import com.limelight.services.KeyboardInterceptionDrain.Companion.ACTION_UP
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardInterceptionDrainTest {
    private data class Key(val deviceId: Int, val keyCode: Int, val scanCode: Int)

    private val state = KeyboardInterceptionDrain()
    private val a = Key(1, 29, 30)
    private val b = Key(1, 30, 48)

    private fun record(key: Key, downTime: Long, action: Int) =
        state.recordConsumed(key.deviceId, key.keyCode, key.scanCode, downTime, action)

    private fun drain(key: Key, downTime: Long, action: Int): Boolean =
        state.consumeDraining(key.deviceId, key.keyCode, key.scanCode, downTime, action)

    private fun recordPassed(key: Key, downTime: Long, action: Int) =
        state.recordPassed(key.deviceId, key.keyCode, key.scanCode, downTime, action)

    private fun pass(key: Key, downTime: Long, action: Int): Boolean =
        state.shouldPassThrough(key.deviceId, key.keyCode, key.scanCode, downTime, action)

    @Test
    fun releasedKeysAreNotDrained() {
        record(a, 100, ACTION_DOWN)
        record(a, 100, ACTION_DOWN) // repeat while interception is active
        record(a, 100, ACTION_UP)
        state.pause()

        assertFalse(drain(a, 100, ACTION_DOWN))
        assertFalse(drain(a, 100, ACTION_UP))
    }

    @Test
    fun pausedKeysDrainRepeatsAndUpIndependentlyAcrossDevices() {
        val otherDevice = Key(2, a.keyCode, a.scanCode)
        val otherScanCode = Key(1, a.keyCode, 31)
        record(a, 100, ACTION_DOWN)
        record(b, 101, ACTION_DOWN)
        record(otherDevice, 102, ACTION_DOWN)
        record(otherScanCode, 103, ACTION_DOWN)
        state.pause()

        assertTrue(drain(a, 100, ACTION_DOWN))
        assertTrue(drain(a, 100, ACTION_DOWN))
        assertTrue(drain(otherDevice, 102, ACTION_UP))
        assertTrue(drain(a, 100, ACTION_UP))
        assertFalse(drain(a, 100, ACTION_UP))
        assertTrue(drain(b, 101, ACTION_UP))
        assertTrue(drain(otherScanCode, 103, ACTION_UP))
    }

    @Test
    fun newKeysPassWhilePaused() {
        record(a, 100, ACTION_DOWN)
        state.pause()

        assertFalse(drain(b, 200, ACTION_DOWN))
        assertFalse(drain(b, 200, ACTION_UP))
        assertTrue(drain(a, 100, ACTION_UP))
    }

    @Test
    fun resumingDoesNotDiscardAnEarlierDrain() {
        record(a, 100, ACTION_DOWN)
        state.pause()

        // Interception resumes: a new key is recorded while the old key is held.
        assertFalse(drain(b, 200, ACTION_DOWN))
        record(b, 200, ACTION_DOWN)
        assertTrue(drain(a, 100, ACTION_DOWN))
        assertTrue(drain(a, 100, ACTION_UP))

        state.pause()
        assertTrue(drain(b, 200, ACTION_UP))
    }

    @Test
    fun repeatedPausesPreserveAndMergeDrainingKeys() {
        record(a, 100, ACTION_DOWN)
        state.pause()
        state.pause()
        record(b, 200, ACTION_DOWN)
        state.pause()
        state.pause()

        assertTrue(drain(a, 100, ACTION_UP))
        assertTrue(drain(b, 200, ACTION_UP))
    }

    @Test
    fun newDownTimeOnSameKeyEndsInterruptedDrain() {
        record(a, 100, ACTION_DOWN)
        state.pause()

        assertFalse(drain(a, 200, ACTION_DOWN))
        assertFalse(drain(a, 100, ACTION_UP))
        record(a, 200, ACTION_DOWN)
        state.pause()
        assertTrue(drain(a, 200, ACTION_DOWN))
        assertTrue(drain(a, 200, ACTION_UP))
    }

    @Test
    fun unrelatedUpAndOtherActionsDoNotEndDrain() {
        record(a, 100, ACTION_DOWN)
        state.pause()

        assertFalse(drain(a, 99, ACTION_UP))
        assertFalse(drain(a, 200, 2))
        assertFalse(drain(a, 100, 2))
        assertTrue(drain(a, 100, ACTION_UP))
    }

    @Test
    fun staleActiveUpDoesNotRemoveNewerDown() {
        record(a, 100, ACTION_DOWN)
        record(a, 200, ACTION_DOWN)
        record(a, 100, ACTION_UP)
        state.pause()

        assertFalse(drain(a, 100, ACTION_UP))
        assertTrue(drain(a, 200, ACTION_UP))
    }

    @Test
    fun recordingAnOldRepeatDoesNotRecreateACompletedDrain() {
        record(a, 100, ACTION_DOWN)
        state.pause()
        record(a, 100, ACTION_DOWN)
        assertTrue(drain(a, 100, ACTION_UP))

        state.pause()
        assertFalse(drain(a, 100, ACTION_UP))
    }

    @Test
    fun passedDownRemainsPassedAcrossResumeAndAnotherPause() {
        state.pause()
        recordPassed(a, 100, ACTION_DOWN)

        // Interception resumes, then pauses again while this key is still held.
        assertTrue(pass(a, 100, ACTION_DOWN))
        state.pause()
        assertTrue(pass(a, 100, ACTION_DOWN))
        assertTrue(pass(a, 100, ACTION_UP))
        assertFalse(pass(a, 100, ACTION_UP))
    }

    @Test
    fun drainedAndPassedKeysKeepTheirOwnersAcrossResume() {
        record(a, 100, ACTION_DOWN)
        state.pause()
        recordPassed(b, 200, ACTION_DOWN)

        // Interception resumes with one key owned by each side.
        assertTrue(drain(a, 100, ACTION_DOWN))
        assertTrue(pass(b, 200, ACTION_DOWN))
        state.pause()
        assertTrue(drain(a, 100, ACTION_UP))
        assertTrue(pass(b, 200, ACTION_UP))
    }

    @Test
    fun passedKeysRemainIndependentAcrossKeysAndDevices() {
        val otherDevice = Key(2, a.keyCode, a.scanCode)
        val otherScanCode = Key(1, a.keyCode, 31)
        state.pause()
        recordPassed(a, 100, ACTION_DOWN)
        recordPassed(b, 101, ACTION_DOWN)
        recordPassed(otherDevice, 102, ACTION_DOWN)
        recordPassed(otherScanCode, 103, ACTION_DOWN)

        assertTrue(pass(otherDevice, 102, ACTION_UP))
        assertTrue(pass(b, 101, ACTION_DOWN))
        assertTrue(pass(a, 100, ACTION_UP))
        assertFalse(pass(otherDevice, 102, ACTION_UP))
        assertTrue(pass(otherScanCode, 103, ACTION_UP))
        assertTrue(pass(b, 101, ACTION_UP))
    }

    @Test
    fun newDownAfterReconnectCanBeIntercepted() {
        state.pause()
        recordPassed(a, 100, ACTION_DOWN)

        // A new DOWN with another downTime interrupts the old passed sequence.
        assertFalse(pass(a, 200, ACTION_DOWN))
        assertFalse(pass(a, 100, ACTION_UP))
        record(a, 200, ACTION_DOWN)
        state.pause()
        assertTrue(drain(a, 200, ACTION_UP))
    }

    @Test
    fun newDownWhilePausedReplacesOldPassedSequence() {
        state.pause()
        recordPassed(a, 100, ACTION_DOWN)
        assertFalse(pass(a, 200, ACTION_DOWN))
        recordPassed(a, 200, ACTION_DOWN)

        assertFalse(pass(a, 100, ACTION_UP))
        assertTrue(pass(a, 200, ACTION_DOWN))
        assertTrue(pass(a, 200, ACTION_UP))
    }

    @Test
    fun staleUpAndOtherActionsDoNotEndPassedSequence() {
        recordPassed(a, 0, ACTION_DOWN)
        assertFalse(pass(a, 99, ACTION_UP))
        recordPassed(a, 99, ACTION_UP)
        assertFalse(pass(a, 99, 2))
        assertFalse(pass(a, 0, 2))
        assertTrue(pass(a, 0, ACTION_UP))
        assertFalse(pass(a, 0, ACTION_DOWN))
    }

    @Test
    fun completedPassedSequenceDoesNotReappearAfterPause() {
        recordPassed(a, 100, ACTION_DOWN)
        recordPassed(a, 100, ACTION_UP)
        state.pause()

        assertFalse(pass(a, 100, ACTION_DOWN))
        assertFalse(drain(a, 100, ACTION_UP))
    }

    @Test
    fun newPassedDownSupersedesInterruptedDrain() {
        record(a, 100, ACTION_DOWN)
        state.pause()
        recordPassed(a, 200, ACTION_DOWN)

        assertFalse(drain(a, 100, ACTION_UP))
        assertTrue(pass(a, 200, ACTION_UP))
    }

    @Test
    fun recordingPassedRepeatAsConsumedDoesNotChangeItsOwner() {
        recordPassed(a, 100, ACTION_DOWN)
        record(a, 100, ACTION_DOWN)
        state.pause()

        assertFalse(drain(a, 100, ACTION_UP))
        assertTrue(pass(a, 100, ACTION_UP))
    }
}
