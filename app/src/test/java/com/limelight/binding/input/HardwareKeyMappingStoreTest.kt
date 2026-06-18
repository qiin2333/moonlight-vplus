package com.limelight.binding.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareKeyMappingStoreTest {
    @Test
    fun roundTripsMappings() {
        val mapping = HardwareKeyMapping(
            deviceKey = "17ef:61ba",
            deviceName = "Lenovo keyboard",
            sourceScanCode = 224,
            sourceKeyCode = KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
            targetKeyCode = KeyEvent.KEYCODE_F1
        )

        assertEquals(listOf(mapping), HardwareKeyMappingStore.decode(HardwareKeyMappingStore.encode(listOf(mapping))))
    }

    @Test
    fun ignoresCorruptJson() {
        assertTrue(HardwareKeyMappingStore.decode("not-json").isEmpty())
    }
}
