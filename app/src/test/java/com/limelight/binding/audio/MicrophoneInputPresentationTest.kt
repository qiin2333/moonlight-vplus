package com.limelight.binding.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputPresentationTest {
    private fun devices(vararg types: Int) = microphoneInputChoices(types.mapIndexed { index, type ->
        MicrophoneInputDescriptor(index + 1, type, "Input", "port:$index")
    })

    @Test fun radioTelephonyAndRemoteSubmixAreNotMicrophoneChoices() {
        val all = devices(14, 16, 17, 18, 25, 15, 3, 11, 12, 22, 7, 26)
        assertEquals(setOf(15, 3, 11, 12, 22, 7, 26),
            selectableMicrophoneInputs(all).map { it.type }.toSet())
        assertEquals(12, all.size)
    }

    @Test fun unknownAndVendorInputsAreNotDiscardedWithoutEvidence() {
        assertEquals(setOf(0, 5, 6, 13, 20, 21, 31),
            selectableMicrophoneInputs(devices(0, 5, 6, 13, 20, 21, 31)).map { it.type }.toSet())
    }

    @Test fun displayOrderDoesNotChooseOrChangeTheDefaultRoute() {
        val all = devices(7, 15, 11, 3, 26)
        val selection = MicrophoneInputSelection(DEFAULT_MICROPHONE_INPUT)
        assertEquals(listOf(11, 3, 15, 7, 26), selectableMicrophoneInputs(all).map { it.type })
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.resolve(selectableMicrophoneInputs(all)))
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.selectedKey)
    }

    @Test fun separateBuiltInAttachmentsRemainIndividuallySelectable() {
        val builtIn = selectableMicrophoneInputs(devices(15, 15))
        assertEquals(2, builtIn.size)
        assertNotEquals(builtIn[0].key, builtIn[1].key)
        val selection = MicrophoneInputSelection(DEFAULT_MICROPHONE_INPUT)
        selection.select(builtIn[1])
        assertEquals(builtIn[1].key, selection.resolve(builtIn))
    }

    @Test fun identicalLabelsAreNumberedWithoutChangingIdentityAndIgnoreEnumerationOrder() {
        val all = devices(15, 15)
        val labels = microphoneInputLabels(all, { "Built-in microphone" }, { name, number -> "$name ($number)" })
        assertEquals("Built-in microphone (1)", labels[all[0].key])
        assertEquals("Built-in microphone (2)", labels[all[1].key])
        assertEquals(labels, microphoneInputLabels(all.reversed(), { "Built-in microphone" },
            { name, number -> "$name ($number)" }))
        assertEquals(all[1].key, resolvedMicrophoneInputKey(persistedMicrophoneInputKey(all[1]), all))
    }

    @Test fun uniqueLabelsKeepTheirNamesAndNumbersDoNotLeakIntoPersistence() {
        val all = devices(11, 15)
        val labels = microphoneInputLabels(all, { "Input ${it.type}" },
            { _, _ -> fail("Unique labels should not be numbered"); "" })
        assertEquals(listOf("Input 11", "Input 15"), all.map { labels[it.key] })
        all.forEach { assertTrue(persistedMicrophoneInputKey(it).startsWith("device:")) }
    }

    @Test fun hiddenInputsCannotBeSelectedOrSavedWhileMutedOrCapturing() {
        for (device in devices(14, 16, 17, 18, 25)) {
            for (capturing in listOf(false, true)) {
                assertFalse(selectMicrophoneInput(device.key, listOf(device), capturing,
                    requestRoute = { fail("Hidden input must not be routed"); true },
                    save = { fail("Hidden input must not be saved") }))
            }
        }
    }

    @Test fun savedHiddenInputIsUnavailableWithoutDeletingThePreference() {
        val hidden = devices(18).single()
        val saved = persistedMicrophoneInputKey(hidden)
        val selection = MicrophoneInputSelection(saved)
        assertEquals(DEFAULT_MICROPHONE_INPUT, selection.resolve(selectableMicrophoneInputs(listOf(hidden))))
        assertEquals(saved, selection.selectedKey)
    }
}
