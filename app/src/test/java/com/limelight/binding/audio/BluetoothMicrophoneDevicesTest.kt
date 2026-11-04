package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class BluetoothMicrophoneDevicesTest {
    @Test fun ordinaryInputsKeepTheOriginalMicSource() {
        assertFalse(MicrophoneConfig.useVoiceCommunication())
        assertEquals(1, microphoneCaptureSource(false, MicrophoneConfig.useVoiceCommunication()))
        assertTrue(MicrophoneConfig.enableAcousticEchoCanceler())
        assertTrue(MicrophoneConfig.enableAutomaticGainControl())
        assertTrue(MicrophoneConfig.enableNoiseSuppressor())
    }

    @Test fun explicitCommunicationInputUsesTheCommunicationSourceForBluetoothRouting() {
        assertEquals(7, microphoneCaptureSource(true, false))
        assertEquals(7, microphoneCaptureSource(true, true))
        assertEquals(1, microphoneCaptureSource(false, false))
    }

    @Test fun classicBluetoothUsesTheVoiceCaptureRate() {
        val bluetooth = MicrophoneInputDevice("bt", 1, 7, "Headset", null, true)
        val leAudio = bluetooth.copy(type = 26)
        val builtIn = bluetooth.copy(communication = false, type = 15)
        assertEquals(16000, microphoneCaptureSampleRate(bluetooth))
        assertEquals(MicrophoneConfig.SAMPLE_RATE, microphoneCaptureSampleRate(leAudio))
        assertEquals(MicrophoneConfig.SAMPLE_RATE, microphoneCaptureSampleRate(builtIn))
    }

    @Test fun anExplicitVoiceSourceConfigurationIsStillHonored() {
        assertEquals(7, microphoneCaptureSource(false, true))
    }

    @Test fun actualScoAudioCanConfirmAMissedConnectedBroadcast() {
        assertTrue(confirmedLegacyScoState(false, true))
    }

    @Test fun actualDisconnectedAudioOverridesAnOldConnectedBroadcast() {
        assertFalse(confirmedLegacyScoState(true, false))
    }

    @Test fun unavailableProfileQueryDoesNotDiscardBroadcastEvidence() {
        assertTrue(confirmedLegacyScoState(true, null))
        assertFalse(confirmedLegacyScoState(false, null))
    }

    @Test fun hfpAudioIsTheGroundTruthForClassicScoChoices() {
        assertFalse(bluetoothScoAudioConfirmed(false, 7))
        assertTrue(bluetoothScoAudioConfirmed(true, 7))
    }

    @Test fun hfpConfirmationCanBeScopedToTheSelectedBluetoothDevice() {
        assertTrue(bluetoothAddressMatches("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff"))
        assertFalse(bluetoothAddressMatches("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"))
        assertTrue(bluetoothAddressMatches("", "11:22:33:44:55:66"))
    }

    @Test fun anUnqueryableClassicProfileCannotConfirmHfpAudio() {
        assertFalse(bluetoothScoAudioConfirmed(null, 7))
    }

    @Test fun leAudioChoicesNeverRequireAnHfpLink() {
        assertTrue(bluetoothScoAudioConfirmed(false, 26))
        assertTrue(bluetoothScoAudioConfirmed(null, 26))
    }

    private fun output(id: Int = 10, type: Int = 7, address: String = "headset-a", name: String = "Phone") =
        MicrophoneInputDescriptor(id, type, name, address)
    private fun input(id: Int = 20, type: Int = 7, address: String = "headset-a", name: String = "Phone") =
        microphoneInputChoices(listOf(output(id, type, address, name))).single()

    @Test fun onlyCommunicationHeadsetsAreMicrophoneCandidates() {
        val choices = communicationMicrophoneChoices(listOf(output(type = 7), output(11, 26),
            output(12, 8), output(13, 27), output(14, 2)))
        assertEquals(listOf(7, 26), choices.map { it.type })
        assertTrue(choices.all { it.communication })
    }

    @Test fun inputAndOutputWithTheSameIdNeverShareIdentity() {
        val comm = communicationMicrophoneChoices(listOf(output(20))).single()
        assertNotEquals(input().key, comm.key)
        assertNotEquals(input().persistentKey, comm.persistentKey)
        assertTrue(comm.persistentKey!!.startsWith("communication:"))
    }

    @Test fun phoneModelNameIsNotAStableBluetoothIdentity() {
        val comm = communicationMicrophoneChoices(listOf(output(address = ""))).single()
        assertNull(comm.persistentKey)
        assertEquals(DEFAULT_MICROPHONE_INPUT, persistedMicrophoneInputKey(comm))
    }

    @Test fun duplicateAddressesAreNotPersisted() {
        assertTrue(communicationMicrophoneChoices(listOf(output(), output(11))).all { it.persistentKey == null })
    }

    @Test fun newOutputIdCanRestoreUniqueSavedIdentityInNewManager() {
        val old = communicationMicrophoneChoices(listOf(output())).single()
        val replacement = communicationMicrophoneChoices(listOf(output(11))).single()
        assertEquals(replacement.key, MicrophoneInputSelection(old.persistentKey!!).resolve(listOf(replacement)))
        val selection = MicrophoneInputSelection(old.persistentKey!!)
        selection.resolve(listOf(old))
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.resolve(listOf(replacement)))
    }

    @Test fun actualInputUsesAddressNotOutputIdOrName() {
        val comm = communicationMicrophoneChoices(listOf(output())).single()
        val target = input(21, address = "headset-a", name = "Different label")
        assertEquals(target, matchingBluetoothInput(comm, listOf(input(10, address = "headset-b"), target)))
    }

    @Test fun explicitDifferentAddressCannotFallBackToTheOnlyInput() {
        val comm = communicationMicrophoneChoices(listOf(output())).single()
        assertNull(matchingBluetoothInput(comm, listOf(input(address = "headset-b"))))
    }

    @Test fun missingVendorAddressesRequireExactlyOneMatchingInput() {
        val comm = communicationMicrophoneChoices(listOf(output())).single()
        val target = input(address = "")
        assertEquals(target, matchingBluetoothInput(comm, listOf(target, input(22, type = 15))))
        assertNull(matchingBluetoothInput(comm, listOf(target, input(23, address = ""))))
    }

    @Test fun scoAndBleInputsCannotBeInterchanged() {
        val comm = communicationMicrophoneChoices(listOf(output(type = 26))).single()
        assertNull(matchingBluetoothInput(comm, listOf(input(type = 7))))
    }

    @Test fun oldPreferencesRemainValidButDoNotNameMatchCommunicationChoices() {
        val old = input().persistentKey!!
        assertEquals(old, validMicrophonePreference(old))
        val comm = communicationMicrophoneChoices(listOf(output())).single()
        assertEquals(comm.persistentKey, validMicrophonePreference(comm.persistentKey))
        assertEquals(DEFAULT_MICROPHONE_INPUT, resolvedMicrophoneInputKey(old, listOf(comm)))
        assertEquals(DEFAULT_MICROPHONE_INPUT, validMicrophonePreference("communication-session:7:10"))
    }

}
