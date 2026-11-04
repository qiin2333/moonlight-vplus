package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputSessionTest {
    private val bluetooth = communicationMicrophoneChoices(listOf(
        MicrophoneInputDescriptor(10, 7, "Headset", "output-address"))).single()
    private val phone = microphoneInputChoices(listOf(
        MicrophoneInputDescriptor(20, 15, "Phone", ""))).single()
    private val phoneInput = MicrophoneObservedInput(20, 15)
    private val bluetoothInput = MicrophoneObservedInput(30, 7)

    @Test fun unavailableSavedBluetoothCannotResolveAsSystemDefault() {
        assertEquals(MicrophoneInputResolution.Unavailable,
            resolveMicrophoneInput(bluetooth.persistentKey!!, listOf(phone)))
        assertEquals(MicrophoneInputResolution.Default,
            resolveMicrophoneInput(DEFAULT_MICROPHONE_INPUT, listOf(phone)))
    }

    @Test fun savedTargetCanResolveItsAvailableSessionWithoutUsingItsName() {
        val resolved = resolveMicrophoneInput(bluetooth.persistentKey!!, listOf(phone, bluetooth))
        assertEquals(MicrophoneInputResolution.Available(bluetooth), resolved)
    }

    @Test fun disconnectedAttachmentDoesNotBecomeDefaultWhenAnotherHeadsetAppears() {
        val selection = MicrophoneInputSelection(bluetooth.persistentKey!!)
        selection.resolve(listOf(bluetooth))
        val other = communicationMicrophoneChoices(listOf(
            MicrophoneInputDescriptor(11, 7, "Headset", "other-address"))).single()
        assertEquals(MicrophoneInputResolution.Unavailable,
            resolveMicrophoneInput(selection.selectedKey, listOf(other)))
    }


    @Test fun selectedCommunicationOutputCannotValidatePhonePcm() {
        val contract = MicrophoneInputContract(bluetooth) { true }
        assertFalse(contract.matches(phoneInput))
        assertNull(contract.expectedId)
        assertFalse(contract.matches(bluetoothInput))
    }

    @Test fun boundCommunicationOutputCanValidateBluetoothPcm() {
        val contract = MicrophoneInputContract(bluetooth, { bluetoothInput.id }) { true }
        assertTrue(contract.matches(bluetoothInput))
    }

    @Test fun selectedCommunicationInputRejectsAnotherBluetoothInputOfTheSameType() {
        val expectedId = bluetoothInput.id
        val contract = MicrophoneInputContract(bluetooth, { expectedId }) { true }
        assertTrue(contract.matches(bluetoothInput))
        assertFalse(contract.matches(bluetoothInput.copy(id = 31)))
    }

    @Test fun classicCommunicationInputRequiresARecordingBinding() {
        val contract = MicrophoneInputContract(bluetooth, { null }) { true }
        assertFalse(contract.matches(bluetoothInput))
        assertFalse(contract.matches(bluetoothInput.copy(id = 31)))
    }

    @Test fun leAudioCommunicationInputKeepsItsTypeOnlyFallback() {
        val ble = bluetooth.copy(type = 26)
        val bleInput = MicrophoneObservedInput(31, 26)
        val contract = MicrophoneInputContract(ble, { null }) { true }
        assertTrue(contract.matches(bleInput))
        assertTrue(contract.matches(bleInput.copy(id = 32)))
    }

    @Test fun recordingBindingUpdatesTheCommunicationContract() {
        var expectedId: Int? = null
        val contract = MicrophoneInputContract(bluetooth, { expectedId }) { true }
        assertFalse(contract.matches(bluetoothInput))
        expectedId = bluetoothInput.id
        assertTrue(contract.matches(bluetoothInput))
        assertFalse(contract.matches(bluetoothInput.copy(id = 31)))
    }

    @Test fun missingPreferredDeviceDoesNotEraseKnownRecordingBinding() {
        val input = MicrophoneCaptureInput(bluetooth, { true },
            expectedCommunicationInputId = bluetoothInput.id)
        input.bindCommunicationInput(null)
        assertTrue(input.contract.matches(bluetoothInput))
        assertFalse(input.contract.matches(bluetoothInput.copy(id = 31)))
    }

    @Test fun communicationOutputIdIsNeverComparedToRecordingInputId() {
        val contract = MicrophoneInputContract(bluetooth, { bluetoothInput.id }) { true }
        assertNotEquals(bluetooth.id, bluetoothInput.id)
        assertTrue(contract.matches(bluetoothInput))
    }

    @Test fun bluetoothRequiresBothActualInputEvidenceAndAReadyCommunicationRoute() {
        var ready = false
        val contract = MicrophoneInputContract(bluetooth, { bluetoothInput.id }) { ready }
        assertFalse(contract.matches(bluetoothInput))
        ready = true
        assertFalse(contract.matches(MicrophoneObservedInput(null, null)))
        assertFalse(contract.matches(bluetoothInput.copy(silenced = true)))
        assertTrue(contract.matches(bluetoothInput))
        ready = false
        assertFalse(contract.matches(bluetoothInput))
    }

    @Test fun scoAndBleRemainDifferentCommunicationTargets() {
        val ble = bluetooth.copy(type = 26)
        assertFalse(MicrophoneInputContract(ble).matches(bluetoothInput))
        assertTrue(MicrophoneInputContract(ble).matches(MicrophoneObservedInput(31, 26)))
    }

    @Test fun explicitOrdinaryInputsStillRequireTheirActualId() {
        val contract = MicrophoneInputContract(phone)
        assertEquals(phone.id, contract.expectedId)
        assertTrue(contract.matches(phoneInput))
        assertFalse(contract.matches(phoneInput.copy(id = 21)))
        assertFalse(contract.matches(phoneInput.copy(type = 7)))
    }

    @Test fun systemDefaultKeepsItsPlatformFallbackWithoutVendorRouteReporting() {
        assertTrue(MicrophoneInputContract(null).matches(MicrophoneObservedInput(null, null)))
    }

    @Test fun conflictingRecordingConfigurationCannotValidateBluetooth() {
        val observed = reconcileMicrophoneInput(bluetoothInput, phoneInput)
        assertFalse(MicrophoneInputContract(bluetooth).matches(observed))
        assertNull(observed.id)
    }

    @Test fun consistentOrUnavailableRecordingConfigurationPreservesEvidence() {
        assertEquals(bluetoothInput, reconcileMicrophoneInput(bluetoothInput, bluetoothInput))
        assertEquals(bluetoothInput, reconcileMicrophoneInput(bluetoothInput, null))
        assertEquals(bluetoothInput, reconcileMicrophoneInput(MicrophoneObservedInput(null, null), bluetoothInput))
    }

    @Test fun recordingConfigurationSilenceRevokesBluetoothEvidence() {
        val observed = reconcileMicrophoneInput(bluetoothInput, bluetoothInput.copy(silenced = true))
        assertFalse(MicrophoneInputContract(bluetooth).matches(observed))
    }

    @Test fun restoredPhoneSelectionIsPublishedBeforeAudioCanResume() {
        val events = mutableListOf<String>()
        var saved: MicrophoneInputDevice? = bluetooth
        completeMicrophoneInput(MicrophoneInputResult.Restored(MicrophoneActiveInput(phone, phoneInput)),
            save = { saved = it; events.add("save") },
            onComplete = {
                assertEquals(phone, saved)
                assertEquals(phoneInput, it.active!!.observed)
                events.add("resume")
            })
        assertEquals(listOf("save", "resume"), events)
    }

    @Test fun failedInitialBluetoothDoesNotPublishDefaultAsAWorkingSource() {
        var saved: MicrophoneInputDevice? = bluetooth
        var sending = false
        completeMicrophoneInput(MicrophoneInputResult.Stopped,
            save = { saved = it }, onComplete = { sending = it.active != null })
        assertEquals(bluetooth, saved)
        assertFalse(sending)
    }

    @Test fun handoffRecoveryAndPersistenceUseTheActualRestoredSource() {
        val selection = MicrophoneInputSelection(phone.persistentKey!!)
        selection.resolve(listOf(phone, bluetooth))
        var completion: ((MicrophoneInputResult) -> Unit)? = null
        var sending = false
        assertTrue(selectMicrophoneInput(bluetooth.key, listOf(phone, bluetooth), true,
            requestRoute = { completion = it; true }, save = { selection.select(it) },
            onComplete = { sending = it.active?.let { active ->
                MicrophoneInputContract(active.choice).matches(active.observed)
            } == true }))
        val target = MicrophoneInputContract(bluetooth)
        val recovery = MicrophoneSwitchRecovery(null, phone.id, phone.id, true, 0)
        // Phone PCM is withheld, even while communication selection says Bluetooth.
        assertFalse(target.matches(phoneInput))
        assertEquals(MicrophoneSwitchRecovery.Action.RECREATE, recovery.observe(2000, phone.id, 0))
        assertFalse(sending)
        recovery.recoveryStarted(2100, phone.id)
        assertEquals(MicrophoneSwitchRecovery.Action.FAILED,
            recovery.observe(2200, phone.id, MicrophoneConfig.BYTES_PER_FRAME * 5))
        completion!!(MicrophoneInputResult.Restored(MicrophoneActiveInput(phone, phoneInput)))
        assertTrue(sending)
        assertEquals(phone.key, selection.resolve(listOf(phone, bluetooth)))
    }

    @Test fun aLateRestoredResultCannotOverwriteANewSession() {
        var current = true
        lateinit var complete: (MicrophoneInputResult) -> Unit
        assertTrue(selectMicrophoneInput(bluetooth.key, listOf(bluetooth), true,
            requestRoute = { complete = it; true },
            save = { fail("Old result must not save") }, canComplete = { current },
            onComplete = { fail("Old result must not reopen sending") }))
        current = false
        complete(MicrophoneInputResult.Restored(MicrophoneActiveInput(phone, phoneInput)))
    }
}
