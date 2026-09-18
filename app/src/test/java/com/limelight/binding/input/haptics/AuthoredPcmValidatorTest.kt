package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame
import com.limelight.nvstream.HostGamepadSelection
import org.junit.Assert.*
import org.junit.Test

class AuthoredPcmValidatorTest {
    private fun frame(seq: Int = 0, player: Short = 0, flags: Byte = 0, value: Int = 16384) =
        Ds5HapticsPcmFrame(player, flags, seq, 0, 48000, 48, 2, 16,
            ByteArray(192) { if (it % 2 == 0) value.toByte() else (value shr 8).toByte() })

    @Test fun sequencesAreIndependentAcrossPlayersAndHandleWrapAndRestart() {
        val reducer = AuthoredPcmValidator()
        assertTrue(reducer.accept(frame(Int.MAX_VALUE)))
        assertTrue(reducer.accept(frame(Int.MIN_VALUE)))
        assertFalse(reducer.accept(frame(Int.MAX_VALUE)))
        assertTrue(reducer.accept(frame(0, player = 1)))
        assertTrue(reducer.accept(frame(0, flags = 1)))
        assertFalse(reducer.accept(frame(0)))
    }

    @Test fun endStopsAndInvalidFramesNeverEnterRouting() {
        val reducer = AuthoredPcmValidator()
        assertTrue(reducer.accept(frame(flags = 2)))
        assertFalse(reducer.accept(frame(player = 16)))
        assertFalse(reducer.accept(frame(flags = 8)))
        assertFalse(reducer.accept(Ds5HapticsPcmFrame(0, 0, 1, 0, 44100, 48, 2, 16, ByteArray(192))))
        assertFalse(reducer.accept(Ds5HapticsPcmFrame(0, 0, 1, 0, 48000, 48, 2, 16, ByteArray(191))))
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
        assertNull(HostGamepadSelection.AUTOMATIC.resolve())
        assertNull(HostGamepadSelection.HOST.resolve())
        assertEquals("x360", HostGamepadSelection.XBOX.resolve())
        assertEquals("ds4", HostGamepadSelection.DS4.resolve())
        assertEquals("ds5", HostGamepadSelection.DS5.resolve())
        assertEquals(HostGamepadSelection.AUTOMATIC, HostGamepadSelection.fromPreference("invalid"))
    }
}
