package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputDeviceTest {
    @Test fun reconnectUsesIdentityRatherThanTransientId() {
        val original = microphoneInputChoices(listOf(MicrophoneInputDescriptor(1, 11, "USB mic", "port:1"))).single()
        val reconnected = microphoneInputChoices(listOf(MicrophoneInputDescriptor(99, 11, "USB mic", "port:1"))).single()
        assertEquals(original.key, reconnected.key)
        assertTrue(original.persistent)
        assertFalse(original.key.contains("port:1"))
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
        assertNotEquals(inputs[0].key, inputs[1].key)
        assertTrue(inputs.all { it.persistent })
    }

    @Test fun remainingAttachmentKeepsSelectionWhenDuplicateIsRemoved() {
        val descriptor = MicrophoneInputDescriptor(1, 11, "USB mic", "")
        val duplicate = microphoneInputChoices(listOf(descriptor, descriptor.copy(id = 2))).first()
        val remaining = microphoneInputChoices(listOf(descriptor)).single()
        assertEquals(remaining.key, resolvedMicrophoneInputKey(duplicate.key, listOf(remaining)))
        assertEquals(DEFAULT_MICROPHONE_INPUT, persistedMicrophoneInputKey(duplicate))
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

    @Test fun defaultAndActiveSelectionApplyOnceBeforeSaving() {
        val events = mutableListOf<String>()
        assertTrue(selectMicrophoneInput(DEFAULT_MICROPHONE_INPUT, emptyList(), true,
            { events.add("route"); true }, { events.add("save"); assertNull(it) }))
        assertEquals(listOf("route", "save"), events)
    }
}
