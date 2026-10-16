package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneRouteOwnershipTest {
    private class AudioState {
        var phoneCall = false
        var ownMode = true
        var routeClears = 0
        var modeClears = 0
        var failRoute = false
        var failMode = false
        val errors = mutableListOf<String>()

        val mode: Int get() = if (phoneCall) 2 else if (ownMode) 3 else 0

        fun lease(owner: Any) = MicrophoneRouteOwnership.Lease(owner,
            clearRoute = {
                routeClears++
                check(!failRoute) { "Audio service unavailable" }
            },
            clearMode = {
                modeClears++
                check(!failMode) { "Mode request unavailable" }
                ownMode = false
            },
            onFailure = { phase, _ -> errors.add(phase) }
        ).apply {
            modeRequested = true
            routeRequested = true
        }
    }

    @Test fun phoneCallDoesNotPreventWithdrawalOfOurModeRequest() {
        val ownership = MicrophoneRouteOwnership()
        val owner = Any()
        val audio = AudioState()
        assertTrue(ownership.acquire(audio.lease(owner)))
        audio.phoneCall = true
        assertEquals(2, audio.mode)

        assertTrue(ownership.release(owner))
        assertFalse(audio.ownMode)
        assertEquals(2, audio.mode)
        audio.phoneCall = false
        assertEquals(0, audio.mode)
        assertEquals(1, audio.modeClears)
    }

    @Test fun routeFailureStillWithdrawsModeAndBlocksNewOwnershipUntilRecovered() {
        val ownership = MicrophoneRouteOwnership()
        val oldOwner = Any()
        val audio = AudioState().apply { failRoute = true }
        ownership.acquire(audio.lease(oldOwner))
        assertFalse(ownership.release(oldOwner))
        assertFalse(audio.ownMode)
        assertEquals(listOf("route"), audio.errors)

        val nextOwner = Any()
        val nextAudio = AudioState()
        val nextLease = nextAudio.lease(nextOwner)
        assertFalse(ownership.acquire(nextLease))
        assertFalse(ownership.retryPendingCleanup())
        assertEquals(2, audio.routeClears)
        assertEquals(1, audio.modeClears)

        audio.failRoute = false
        assertTrue(ownership.retryPendingCleanup())
        assertEquals(3, audio.routeClears)
        assertEquals(1, audio.modeClears)
        assertTrue(ownership.acquire(nextLease))
        assertEquals(0, nextAudio.routeClears)
    }

    @Test fun nextSessionCanRecoverModeFailureWithoutRepeatingRouteCleanup() {
        val ownership = MicrophoneRouteOwnership()
        val oldOwner = Any()
        val audio = AudioState().apply { failMode = true }
        ownership.acquire(audio.lease(oldOwner))
        assertFalse(ownership.release(oldOwner))
        assertEquals(1, audio.routeClears)
        assertEquals(listOf("mode"), audio.errors)

        audio.failMode = false
        assertTrue(ownership.retryPendingCleanup())
        assertEquals(1, audio.routeClears)
        assertEquals(2, audio.modeClears)
        assertTrue(ownership.acquire(AudioState().lease(Any())))
    }

    @Test fun activeSessionCannotBeCleanedByAnotherSession() {
        val ownership = MicrophoneRouteOwnership()
        val owner = Any()
        val audio = AudioState()
        ownership.acquire(audio.lease(owner))
        assertFalse(ownership.retryPendingCleanup())
        assertTrue(ownership.release(Any()))
        assertFalse(ownership.acquire(AudioState().lease(Any())))
        assertEquals(0, audio.routeClears)
        assertEquals(0, audio.modeClears)
    }

    @Test fun oldReleaseAfterRecoveryCannotClearTheNewSession() {
        val ownership = MicrophoneRouteOwnership()
        val oldOwner = Any()
        val oldAudio = AudioState().apply { failRoute = true }
        ownership.acquire(oldAudio.lease(oldOwner))
        assertFalse(ownership.release(oldOwner))
        oldAudio.failRoute = false
        assertTrue(ownership.retryPendingCleanup())
        val nextOwner = Any()
        val nextAudio = AudioState()
        assertTrue(ownership.acquire(nextAudio.lease(nextOwner)))

        assertTrue(ownership.release(oldOwner))
        assertEquals(0, nextAudio.routeClears)
        assertEquals(0, nextAudio.modeClears)
        assertTrue(ownership.release(nextOwner))
        assertEquals(1, nextAudio.routeClears)
        assertEquals(1, nextAudio.modeClears)
    }

    @Test fun repeatedReleaseDoesNotRepeatCompletedNativeOperations() {
        val ownership = MicrophoneRouteOwnership()
        val owner = Any()
        val audio = AudioState()
        ownership.acquire(audio.lease(owner))
        assertTrue(ownership.release(owner))
        assertTrue(ownership.release(owner))
        assertTrue(ownership.retryPendingCleanup())
        assertEquals(1, audio.routeClears)
        assertEquals(1, audio.modeClears)
    }

    @Test fun failureBeforeAnyRequestDoesNotChangeSystemAudio() {
        val ownership = MicrophoneRouteOwnership()
        val owner = Any()
        val audio = AudioState().apply { phoneCall = true }
        ownership.acquire(audio.lease(owner).apply {
            modeRequested = false
            routeRequested = false
        })
        assertTrue(ownership.release(owner))
        assertEquals(0, audio.routeClears)
        assertEquals(0, audio.modeClears)
        assertEquals(2, audio.mode)
    }

    @Test fun modeSetupFailureDoesNotStopAScoRouteThatWasNeverRequested() {
        val ownership = MicrophoneRouteOwnership()
        val owner = Any()
        val audio = AudioState()
        ownership.acquire(audio.lease(owner).apply { routeRequested = false })
        assertTrue(ownership.release(owner))
        assertEquals(0, audio.routeClears)
        assertEquals(1, audio.modeClears)
    }

    @Test fun bothFailuresAreRetainedAndEachRetryMakesOnlyOneAttempt() {
        val ownership = MicrophoneRouteOwnership()
        val owner = Any()
        val audio = AudioState().apply { failRoute = true; failMode = true }
        ownership.acquire(audio.lease(owner))
        assertFalse(ownership.release(owner))
        assertEquals(listOf("route", "mode"), audio.errors)
        assertFalse(ownership.retryPendingCleanup())
        assertEquals(2, audio.routeClears)
        assertEquals(2, audio.modeClears)
        audio.failRoute = false
        audio.failMode = false
        assertTrue(ownership.retryPendingCleanup())
    }

    @Test fun inheritedScoCleanupStillRequiresPhysicalDisconnection() {
        val ownership = MicrophoneRouteOwnership()
        val drain = MicrophoneRouteDrain()
        val owner = Any()
        var fail = true
        val lease = MicrophoneRouteOwnership.Lease(owner,
            clearRoute = { drain.begin(0); check(!fail) }, clearMode = {})
            .apply { routeRequested = true }
        ownership.acquire(lease)
        assertFalse(ownership.release(owner))
        fail = false
        assertTrue(ownership.retryPendingCleanup())
        assertNull(drain.observe(100, false))
        assertEquals(true, drain.observe(200, true))
    }
}
