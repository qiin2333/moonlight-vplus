package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneDeviceSnapshotTest {
    private fun input(id: Int, type: Int = 15, name: String = "Phone", address: String = "") =
        microphoneInputChoices(listOf(MicrophoneInputDescriptor(id, type, name, address))).single()

    private fun headset(id: Int, address: String = "headset", type: Int = 7) =
        communicationMicrophoneChoices(listOf(MicrophoneInputDescriptor(id, type, "Headset", address))).single()

    @Test fun keepsDistinctBuiltInInputsDespiteIdenticalProductNames() {
        val first = input(1)
        val second = input(2)
        val snapshot = MicrophoneDeviceSnapshot(listOf(second, first), emptyList())
        assertEquals(listOf(first, second), snapshot.choices)
        assertEquals(second, snapshot.displayDevice(2))
    }

    @Test fun bluetoothRawPortsAreNotChoicesWithoutCommunicationSupport() {
        val snapshot = MicrophoneDeviceSnapshot(listOf(input(1), input(2, 7), input(3, 26)), emptyList())
        assertEquals(listOf(1), snapshot.choices.map { it.id })
        // A default recording route can still be reported even when it is not manually selectable.
        assertEquals(7, snapshot.displayDevice(2)?.type)
    }

    @Test fun filtersNonRecordingPortsAndKeepsWiredAndUsbInputs() {
        val snapshot = MicrophoneDeviceSnapshot(listOf(input(1), input(2, 3), input(3, 11),
            input(4, 12), input(5, 22), input(6, 18), input(7, 25)), emptyList())
        assertEquals(listOf(2, 3, 4, 5, 1), snapshot.choices.map { it.id })
    }

    @Test fun oneHeadsetChoiceUsesCommunicationNameForItsActualInput() {
        val route = headset(10)
        val snapshot = MicrophoneDeviceSnapshot(listOf(input(20, 7, address = "headset")), listOf(route))
        assertEquals(listOf(route), snapshot.choices)
        assertEquals(route, snapshot.displayDevice(20))
        assertNull(snapshot.displayDevice(10))
    }

    @Test fun ambiguousCommunicationNamesCannotMislabelAnActualInput() {
        val raw = input(20, 7)
        val snapshot = MicrophoneDeviceSnapshot(listOf(raw), listOf(headset(10, ""), headset(11, "")))
        assertEquals(raw, snapshot.displayDevice(20))
        assertEquals(2, snapshot.choices.size)
    }

    @Test fun refreshedSnapshotDoesNotKeepADisconnectedInput() {
        val before = MicrophoneDeviceSnapshot(listOf(input(1), input(2, 11)), emptyList())
        val after = MicrophoneDeviceSnapshot(listOf(input(1)), emptyList())
        assertNotNull(before.displayDevice(2))
        assertNull(after.displayDevice(2))
        assertEquals(1, after.choices.size)
    }

    @Test fun rejectsWrongRolesAndDeduplicatesOnlyIdenticalSessionKeys() {
        val route = headset(10)
        val raw = input(1)
        val snapshot = MicrophoneDeviceSnapshot(listOf(raw, raw, route),
            listOf(raw, route, route, raw.copy(communication = true)))
        assertEquals(listOf(raw, route), snapshot.choices)
    }

    @Test fun explicitlyDifferentHeadsetAddressCannotRenameTheActualInput() {
        val raw = input(20, 7, address = "other")
        val snapshot = MicrophoneDeviceSnapshot(listOf(raw), listOf(headset(10)))
        assertEquals(raw, snapshot.displayDevice(20))
        assertNull(snapshot.displayDevice(null))
    }
}
