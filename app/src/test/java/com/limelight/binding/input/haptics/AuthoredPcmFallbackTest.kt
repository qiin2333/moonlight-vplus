package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import com.limelight.nvstream.HostGamepadSelection
import org.junit.Assert.*
import org.junit.Test

class AuthoredPcmFallbackTest {
    private fun frame(seq: Int = 0, player: Short = 0, flags: Byte = 0, value: Int = 16384) =
        Ds5HapticsPcmFrame(player, flags, seq, 0, 48000, 48, 2, 16,
            ByteArray(192) { if (it % 2 == 0) value.toByte() else (value shr 8).toByte() })

    @Test fun signedEnergyDoesNotCancelAndIsConservative() {
        val positive = AuthoredPcmFallback().accept(frame())!!
        val negative = AuthoredPcmFallback().accept(frame(value = -16384))!!
        assertEquals(0.125f, positive.lowFrequency, 0.0001f)
        assertEquals(positive, negative)
        assertEquals(positive.lowFrequency, positive.highFrequency, 0f)
    }

    @Test fun sequencesAreIndependentAcrossPlayersAndHandleWrapAndRestart() {
        val reducer = AuthoredPcmFallback()
        assertNotNull(reducer.accept(frame(Int.MAX_VALUE)))
        assertNotNull(reducer.accept(frame(Int.MIN_VALUE)))
        assertNull(reducer.accept(frame(Int.MAX_VALUE)))
        assertNotNull(reducer.accept(frame(0, player = 1)))
        assertNotNull(reducer.accept(frame(0, flags = 1)))
        assertNull(reducer.accept(frame(0)))
    }

    @Test fun endStopsAndInvalidFramesNeverEnterRouting() {
        val reducer = AuthoredPcmFallback()
        assertEquals(ControllerRumbleState.ZERO, reducer.accept(frame(flags = 2)))
        assertNull(reducer.accept(frame(player = 16)))
        assertNull(reducer.accept(frame(flags = 8)))
        assertNull(reducer.accept(Ds5HapticsPcmFrame(0, 0, 1, 0, 44100, 48, 2, 16, ByteArray(192))))
        assertNull(reducer.accept(Ds5HapticsPcmFrame(0, 0, 1, 0, 48000, 48, 2, 16, ByteArray(191))))
    }

    @Test fun authoredExpiryAndStopPreserveOrdinaryHostRumbleAndOtherPlayers() {
        val mixer = ControllerHapticsMixer()
        val host = ControllerRumbleState(0.2f, 0.1f)
        mixer.submit(0, RumbleSource.HOST, host, 0)
        mixer.submit(0, RumbleSource.AUTHORED, ControllerRumbleState(0.5f, 0.5f), 0, 50)
        mixer.submit(1, RumbleSource.AUTHORED, ControllerRumbleState(0.4f, 0.4f), 0, 70)
        assertEquals(50L, mixer.nextExpiryAtMs())
        assertEquals(host, mixer.pruneExpired(50).single().output)
        assertFalse(mixer.mixedState(1, 50).isZero)
        assertTrue(mixer.pruneExpired(70).single().isZero)
        mixer.submit(0, RumbleSource.AUTHORED, ControllerRumbleState(0.5f, 0.5f), 80, 130)
        assertEquals(host, mixer.clearSource(0, RumbleSource.AUTHORED, 81).output)
    }

    @Test fun explicitHostChoiceOutranksAutomaticWaveformAndTouchpadHints() {
        assertEquals("ds5", HostGamepadSelection.AUTOMATIC.resolve(false, true))
        assertEquals("ds5", HostGamepadSelection.AUTOMATIC.resolve(true, false))
        assertNull(HostGamepadSelection.AUTOMATIC.resolve(false, false))
        assertNull(HostGamepadSelection.HOST.resolve(true, true))
        assertEquals("x360", HostGamepadSelection.XBOX.resolve(true, true))
        assertEquals("ds4", HostGamepadSelection.DS4.resolve(true, true))
        assertEquals("ds5", HostGamepadSelection.DS5.resolve(false, false))
        assertEquals(HostGamepadSelection.AUTOMATIC, HostGamepadSelection.fromPreference("invalid"))
    }
}
