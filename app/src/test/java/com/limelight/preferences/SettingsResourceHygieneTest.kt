package com.limelight.preferences

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsResourceHygieneTest {
    private val resourceDir: File by lazy {
        listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull(File::isDirectory)
            ?: error("Unable to locate app/src/main/res")
    }

    @Test
    fun preferenceTextResourcesAreMeaningful() {
        val preferences = parse(File(resourceDir, "xml/preferences.xml"))
        val referencedText = preferences.documentElement
            .getElementsByTagName("*")
            .asElementSequence()
            .flatMap { element ->
                sequenceOf("title", "summary", "dialogMessage").mapNotNull { attribute ->
                    element.getAttributeNS(ANDROID_NAMESPACE, attribute)
                        .takeIf { it.startsWith("@string/") }
                        ?.removePrefix("@string/")
                }
            }
            .toSet()

        listOf("values", "values-zh-rCN", "values-zh-rTW").forEach { directory ->
            val valuesByName = stringValues(directory)
            referencedText.forEach { name ->
                val value = valuesByName[name]
                if (directory == "values") {
                    assertNotNull("Missing default string: $name", value)
                } else if (!name.startsWith("suffix_")) {
                    assertNotNull("$directory is missing settings text $name", value)
                }
                if (value != null) {
                    assertTrue("$directory/$name must not be blank", value.isNotBlank())
                    assertFalse(
                        "$directory/$name must not use a tab as a placeholder",
                        value.trim() == "\\t",
                    )
                    assertFalse(
                        "$directory/$name contains a generated identifier fragment",
                        "configurationItem" in value,
                    )
                    assertTrue(
                        "$directory/$name has accidental leading or trailing whitespace",
                        value == value.trim(),
                    )
                }
            }
        }
    }

    @Test
    fun userFacingSettingsArraysUseStringResources() {
        val arrays = parse(File(resourceDir, "values/arrays.xml"))
        val localizedArrays = setOf(
            "abr_mode_names",
            "analog_scrolling_names",
            "audio_codec_names",
            "audio_config_names",
            "audio_passthrough_buffer_names",
            "background_source_names",
            "float_ball_action_names",
            "hdr_mode_entries",
            "mic_menu_action_mode_entries",
            "native_mouse_mode_preset_names",
            "video_frame_pacing_names",
            "screen_position_names",
            "perf_overlay_orientation_names",
            "perf_overlay_position_horizontal_names",
            "perf_overlay_position_vertical_names",
            "perf_overlay_display_items_names",
            "mic_icon_color_entries",
            "audio_vibration_mode_names",
            "audio_vibration_scene_names",
        )

        val arraysByName = arrays.documentElement.childNodes.asElementSequence()
            .filter { it.tagName == "string-array" }
            .associateBy { it.getAttribute("name") }

        localizedArrays.forEach { name ->
            val array = arraysByName[name]
            assertNotNull("Missing settings array: $name", array)
            array!!
            array.childNodes.asElementSequence()
                .filter { it.tagName == "item" }
                .forEach { item ->
                    assertTrue(
                        "$name contains a non-localizable label: ${item.textContent}",
                        item.textContent.trim().startsWith("@string/"),
                    )
                }
        }

        val localizedLabelNames = localizedArrays.flatMap { name ->
            arraysByName.getValue(name).childNodes.asElementSequence()
                .filter { it.tagName == "item" }
                .map { it.textContent.trim().removePrefix("@string/") }
                .toList()
        }.toSet()
        listOf("values-zh-rCN", "values-zh-rTW").forEach { directory ->
            val localizedValues = stringValues(directory)
            localizedLabelNames.forEach { name ->
                assertTrue("$directory is missing settings list label $name", name in localizedValues)
            }
        }
    }

    private fun stringValues(directory: String): Map<String, String> =
        parse(File(resourceDir, "$directory/strings.xml"))
            .documentElement.childNodes.asElementSequence()
            .filter { it.tagName == "string" }
            .associate { it.getAttribute("name") to it.textContent }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(file)

    private fun org.w3c.dom.NodeList.asElementSequence() = sequence {
        for (index in 0 until length) {
            val element = item(index) as? org.w3c.dom.Element ?: continue
            yield(element)
        }
    }

    companion object {
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
