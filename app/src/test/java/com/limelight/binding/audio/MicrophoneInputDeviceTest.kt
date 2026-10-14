package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputDeviceTest {
    @Test fun reconnectUsesIdentityRatherThanTransientId() {
        val original = microphoneInputChoices(listOf(MicrophoneInputDescriptor(1, 11, "USB mic", "port:1"))).single()
        val reconnected = microphoneInputChoices(listOf(MicrophoneInputDescriptor(99, 11, "USB mic", "port:1"))).single()
        assertEquals(original.persistentKey, reconnected.persistentKey)
        assertNotEquals(original.key, reconnected.key)
        assertTrue(original.persistent)
        assertFalse(original.persistentKey!!.contains("port:1"))
    }

    @Test fun ambiguousAndUnnamedDevicesAreNeverSavedForAnotherSession() {
        val inputs = microphoneInputChoices(listOf(
            MicrophoneInputDescriptor(1, 11, "USB mic", ""),
            MicrophoneInputDescriptor(2, 11, "USB mic", ""),
            MicrophoneInputDescriptor(3, 11, "", "")))
        assertEquals(3, inputs.map { it.key }.distinct().size)
        inputs.forEach {
            assertFalse(it.persistent)
            assertEquals(DEFAULT_MICROPHONE_INPUT, persistedMicrophoneInputKey(it))
        }
    }

    @Test fun addressDisambiguatesDevicesWithSameName() {
        val inputs = microphoneInputChoices(listOf(
            MicrophoneInputDescriptor(1, 11, "USB mic", "one"),
            MicrophoneInputDescriptor(2, 11, "USB mic", "two")))
        assertNotEquals(inputs[0].persistentKey, inputs[1].persistentKey)
        assertTrue(inputs.all { it.persistent })
    }

    @Test fun remainingAttachmentKeepsSelectionWhenDuplicateIsRemoved() {
        val descriptor = MicrophoneInputDescriptor(1, 11, "USB mic", "")
        val duplicate = microphoneInputChoices(listOf(descriptor, descriptor.copy(id = 2))).first()
        val remaining = microphoneInputChoices(listOf(descriptor)).single()
        assertEquals(remaining.key, resolvedMicrophoneInputKey(duplicate.key, listOf(remaining)))
        assertEquals(DEFAULT_MICROPHONE_INPUT, persistedMicrophoneInputKey(duplicate))
    }

    @Test fun duplicateAdditionAndRemovalDoNotChangeTheSelectedAttachment() {
        val descriptor = MicrophoneInputDescriptor(1, 11, "USB mic", "")
        val original = microphoneInputChoices(listOf(descriptor)).single()
        val selection = MicrophoneInputSelection(DEFAULT_MICROPHONE_INPUT)
        val saved = selection.select(original)
        assertEquals(original.persistentKey, saved)
        val duplicates = microphoneInputChoices(listOf(descriptor, descriptor.copy(id = 2)))
        assertEquals(original.key, duplicates.first().key)
        assertEquals(original.key, selection.resolve(duplicates))
        assertEquals(original.key, selection.selectedKey)
        assertEquals(original.key, selection.resolve(listOf(original)))
    }

    @Test fun savedSelectionBindsOnceAndSurvivesADuplicateAttachingBeforeRecording() {
        val descriptor = MicrophoneInputDescriptor(1, 11, "USB mic", "")
        val original = microphoneInputChoices(listOf(descriptor)).single()
        val selection = MicrophoneInputSelection(persistedMicrophoneInputKey(original))
        assertEquals(original.key, selection.resolve(listOf(original)))
        val duplicates = microphoneInputChoices(listOf(descriptor, descriptor.copy(id = 2)))
        assertEquals(original.key, selection.resolve(duplicates))
    }

    @Test fun removalDoesNotRedirectSelectionToTheOtherNamesake() {
        val descriptor = MicrophoneInputDescriptor(1, 11, "USB mic", "")
        val original = microphoneInputChoices(listOf(descriptor)).single()
        val selection = MicrophoneInputSelection(persistedMicrophoneInputKey(original))
        selection.resolve(listOf(original))
        selection.resolve(microphoneInputChoices(listOf(descriptor, descriptor.copy(id = 2))))
        val other = microphoneInputChoices(listOf(descriptor.copy(id = 2))).single()
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.resolve(listOf(other)))
        assertEquals(original.key, selection.selectedKey)
        selection.select(other)
        assertEquals(other.key, selection.resolve(listOf(other)))
    }

    @Test fun nextManagerRestoresUniqueIdentityWithNewAttachmentId() {
        val descriptor = MicrophoneInputDescriptor(1, 11, "USB mic", "port:1")
        val original = microphoneInputChoices(listOf(descriptor)).single()
        val selection = MicrophoneInputSelection(DEFAULT_MICROPHONE_INPUT)
        val saved = selection.select(original)
        val reconnected = microphoneInputChoices(listOf(descriptor.copy(id = 99))).single()
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.resolve(listOf(reconnected)))
        assertEquals(reconnected.key, MicrophoneInputSelection(saved).resolve(listOf(reconnected)))
    }

    @Test fun ambiguousSavedIdentityWaitsForAnUnambiguousDevice() {
        val descriptor = MicrophoneInputDescriptor(1, 11, "USB mic", "")
        val original = microphoneInputChoices(listOf(descriptor)).single()
        val selection = MicrophoneInputSelection(persistedMicrophoneInputKey(original))
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.resolve(
            microphoneInputChoices(listOf(descriptor, descriptor.copy(id = 2)))))
        assertEquals(original.key, selection.resolve(listOf(original)))
    }

    @Test fun sessionOnlySelectionStaysLocalAndDefaultExplicitlyClearsIt() {
        val descriptor = MicrophoneInputDescriptor(1, 11, "USB mic", "")
        val duplicates = microphoneInputChoices(listOf(descriptor, descriptor.copy(id = 2)))
        val choice = duplicates.first()
        val selection = MicrophoneInputSelection(DEFAULT_MICROPHONE_INPUT)
        val saved = selection.select(choice)
        assertEquals(DEFAULT_MICROPHONE_INPUT, saved)
        assertEquals(choice.key, selection.resolve(duplicates))
        assertEquals(DEFAULT_MICROPHONE_INPUT, MicrophoneInputSelection(saved).resolve(duplicates))
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.select(null))
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.resolve(duplicates))
    }

    @Test fun absentSelectionFallsBackWithoutOverwritingSavedChoice() {
        val selected = microphoneInputChoices(listOf(MicrophoneInputDescriptor(1, 7, "Headset", ""))).single()
        assertEquals(DEFAULT_MICROPHONE_INPUT, resolvedMicrophoneInputKey(selected.key, emptyList()))
        assertEquals(selected.key, resolvedMicrophoneInputKey(selected.key, listOf(selected)))
    }

    @Test fun selectionWhileMutedDoesNotRequestRecordingOrRouting() {
        val input = microphoneInputChoices(listOf(MicrophoneInputDescriptor(1, 15, "Phone", ""))).single()
        var saved: MicrophoneInputDevice? = null
        assertTrue(selectMicrophoneInput(input.key, listOf(input), false,
            requestRoute = { fail("Muted selection must not start or route audio"); false }, save = { saved = it }))
        assertEquals(input, saved)
    }

    @Test fun rejectedOrRemovedInputDoesNotChangePreference() {
        val input = microphoneInputChoices(listOf(MicrophoneInputDescriptor(1, 11, "USB mic", ""))).single()
        assertFalse(selectMicrophoneInput(input.key, listOf(input), true, { false }, { fail("Must not save") }))
        assertFalse(selectMicrophoneInput(input.key, emptyList(), true,
            { fail("Removed input must not be routed"); true }, { fail("Must not save") }))
    }

    @Test fun defaultAndActiveSelectionWaitForConfirmationBeforeSaving() {
        val events = mutableListOf<String>()
        var complete: ((Boolean) -> Unit)? = null
        assertTrue(selectMicrophoneInput(DEFAULT_MICROPHONE_INPUT, emptyList(), true,
            { complete = it; events.add("route"); true }, { events.add("save"); assertNull(it) }))
        assertEquals(listOf("route"), events)
        complete!!(true)
        assertEquals(listOf("route", "save"), events)
    }

    @Test fun acceptedButFailedSwitchKeepsThePreviousPreference() {
        var complete: ((Boolean) -> Unit)? = null
        var result: Boolean? = null
        assertTrue(selectMicrophoneInput(DEFAULT_MICROPHONE_INPUT, emptyList(), true,
            { complete = it; true }, { fail("Failed verification must not save") },
            onComplete = { result = it }))
        complete!!(false)
        assertEquals(false, result)
    }

    @Test fun pauseOrNewStreamInvalidatesALateSuccessAndFailure() {
        for (success in listOf(true, false)) {
            var complete: ((Boolean) -> Unit)? = null
            var valid = true
            assertTrue(selectMicrophoneInput(DEFAULT_MICROPHONE_INPUT, emptyList(), true,
                { complete = it; true }, { fail("Stale result must not save") },
                canComplete = { valid }, onComplete = { fail("Stale result must not update UI") }))
            valid = false
            complete!!(success)
        }
    }

    @Test fun verifiedExplicitChoiceIsSavedOnlyAfterCompletion() {
        val device = microphoneInputChoices(listOf(MicrophoneInputDescriptor(1, 15, "", ""))).single()
        var complete: ((Boolean) -> Unit)? = null
        var saved: MicrophoneInputDevice? = null
        assertTrue(selectMicrophoneInput(device.key, listOf(device), true,
            { complete = it; true }, { saved = it }))
        assertNull(saved)
        complete!!(true)
        assertEquals(device, saved)
    }
}
