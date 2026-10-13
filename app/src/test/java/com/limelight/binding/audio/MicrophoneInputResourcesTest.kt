package com.limelight.binding.audio

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import com.limelight.utils.ConfigurationSyncManager
import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputResourcesTest {
    @Test fun allSupportedLocalesHaveMicrophoneInputStringsWithSameFormatArguments() {
        val root = listOf(File("src/main/res"), File("app/src/main/res")).first(File::isDirectory)
        fun strings(file: File): Map<String, String> {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            return (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }
        val expected = strings(File(root, "values/microphone_input.xml")).filterKeys { it != "mic_input_named_device" }
        val arguments = Regex("%\\d+\\\$[sd]")
        root.listFiles()!!.filter { it.isDirectory && File(it, "strings.xml").isFile }.forEach { locale ->
            val values = strings(File(locale, "microphone_input.xml"))
            expected.forEach { (key, value) ->
                assertTrue("${locale.name}/$key missing or blank", values[key]?.isNotBlank() == true)
                assertEquals("${locale.name}/$key arguments", arguments.findAll(value).map { it.value }.toList(),
                    arguments.findAll(values.getValue(key)).map { it.value }.toList())
            }
        }
    }

    @Test fun hardwareRoutingPreferenceIsNotPortable() {
        assertFalse(ConfigurationSyncManager.isPortableSharedPreferenceKey("microphone_input_device", "selected_input_device_key"))
        assertFalse(ConfigurationSyncManager.isPortableDefaultPreferenceKey("selected_input_device_key"))
    }
}
