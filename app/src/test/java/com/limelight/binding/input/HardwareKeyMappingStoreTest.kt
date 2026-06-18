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

    @Test
    fun ignoresMappingsWithoutADevice() {
        val json = """
            [{
                "deviceKey": "",
                "deviceName": "Broken keyboard",
                "sourceScanCode": 224,
                "sourceKeyCode": ${KeyEvent.KEYCODE_BRIGHTNESS_DOWN},
                "targetKeyCode": ${KeyEvent.KEYCODE_F1}
            }]
        """.trimIndent()

        assertTrue(HardwareKeyMappingStore.decode(json).isEmpty())
    }

    @Test
    fun ignoresMappingsWithoutASourceKey() {
        val json = """
            [{
                "deviceKey": "17ef:61ba",
                "deviceName": "Broken keyboard",
                "sourceScanCode": 0,
                "sourceKeyCode": ${KeyEvent.KEYCODE_UNKNOWN},
                "targetKeyCode": ${KeyEvent.KEYCODE_F1}
            }]
        """.trimIndent()

        assertTrue(HardwareKeyMappingStore.decode(json).isEmpty())
    }

    @Test
    fun reusesDecodedMappingsUntilStoredJsonChanges() {
        val cache = HardwareKeyMappingCache()
        var decodeCount = 0
        val decode: (String) -> List<HardwareKeyMapping> = {
            decodeCount++
            emptyList()
        }

        cache.load("[]", decode)
        cache.load("[]", decode)
        cache.load("[ ]", decode)

        assertEquals(2, decodeCount)
    }
}
