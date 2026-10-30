package com.limelight.binding.input.haptics

import com.limelight.nvstream.HostGamepadSelection
import org.junit.Assert.*
import org.junit.Test

class Ds5SimulationModeTest {
    @Test fun existingAndUnknownPreferencesKeepTheLegacySimulation() {
        for (value in listOf(null, "", "legacy", "unknown")) {
            val mode = Ds5SimulationMode.fromPreference(value)
            assertEquals(Ds5SimulationMode.LEGACY, mode)
            for (host in HostGamepadSelection.entries) assertFalse(mode.requestsIr(false, false, host))
        }
    }

    @Test fun explicitIrChoiceAdvertisesOnlyForCompatibleSimulationSessions() {
        val mode = Ds5SimulationMode.fromPreference("ir_enhanced")
        assertEquals(mode, Ds5SimulationMode.fromPreference("ir_v3"))
        for (host in listOf(HostGamepadSelection.AUTOMATIC, HostGamepadSelection.HOST, HostGamepadSelection.DS5))
            assertTrue(mode.requestsIr(false, false, host))
        for (host in listOf(HostGamepadSelection.XBOX, HostGamepadSelection.DS4))
            assertFalse(mode.requestsIr(false, false, host))
    }

    @Test fun rawPcmAndControlOnlyAlwaysSuppressIrRegardlessOfTheSavedOption() {
        for (mode in Ds5SimulationMode.entries) for (host in HostGamepadSelection.entries) {
            assertFalse(mode.requestsIr(true, false, host))
            assertFalse(mode.requestsIr(false, true, host))
            assertFalse(mode.requestsIr(true, true, host))
        }
    }

    @Test fun changingTheSettingKeepsExistingConnectionSnapshotsIndependent() {
        val oldSession = Ds5SimulationMode.LEGACY.sessionOptions(false, false, HostGamepadSelection.DS5)
        val irSession = Ds5SimulationMode.IR_ENHANCED.sessionOptions(false, false, HostGamepadSelection.DS5)
        val nativeSession = Ds5SimulationMode.IR_ENHANCED.sessionOptions(true, false, HostGamepadSelection.DS5)
        assertFalse(oldSession.authoredIr)
        assertTrue(irSession.authoredIr)
        assertFalse(oldSession.nativePcm)
        assertTrue(nativeSession.nativePcm)
        assertFalse(nativeSession.authoredIr)
        assertFalse(Ds5SimulationMode.LEGACY.allowsNativeDeviceHaptics(GameRumbleMode.DEVICE, 100))
        assertFalse(Ds5SimulationMode.IR_ENHANCED.allowsNativeDeviceHaptics(GameRumbleMode.CONTROLLER, 100))
        assertFalse(Ds5SimulationMode.IR_ENHANCED.allowsNativeDeviceHaptics(GameRumbleMode.DEVICE, 0))
        assertTrue(Ds5SimulationMode.IR_ENHANCED.allowsNativeDeviceHaptics(GameRumbleMode.COORDINATED, 100))
    }
}
