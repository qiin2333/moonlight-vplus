package com.limelight.preferences

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
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
            "background_stream_behavior_names",
            "quit_behavior_names",
            "dualsense_output_mode_names",
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

    @Test
    fun settingsCategoriesKeepEveryPreferenceKey() {
        val preferences = parse(File(resourceDir, "xml/preferences.xml"))
        val categories = preferences.documentElement
            .getElementsByTagName("PreferenceCategory")
            .asElementSequence()
            .associateBy { it.getAttributeNS(ANDROID_NAMESPACE, "key") }

        setOf(
            "category_enhanced_touch",
            "category_float_ball",
            "category_connection_settings",
        ).forEach { retiredKey ->
            assertFalse("Retired category is still present: $retiredKey", retiredKey in categories)
        }

        val expectedKeysByCategory = mapOf(
            "category_screen_position" to setOf(
                "video_format",
                "checkbox_enable_hdr",
                "checkbox_enable_hdr_high_brightness",
                "checkbox_hdr_brightness_override",
                "seekbar_hdr_peak_brightness_nits",
                "list_hdr_mode",
                "checkbox_full_range",
                "capability_diagnostic",
            ),
            "category_host_settings" to setOf(
                "list_background_stream_behavior",
                "list_quit_behavior",
                "checkbox_resume_stream",
                "checkbox_extreme_resume",
                "checkbox_background_audio",
                "checkbox_enable_stun",
            ),
            "category_ui_settings" to setOf(
                "checkbox_enable_float_ball",
                "list_float_ball_position",
                "seekbar_float_ball_auto_hide_delay",
                "list_float_ball_single_click_action",
                "list_float_ball_double_click_action",
                "list_float_ball_long_click_action",
            ),
            "category_microphone_settings" to setOf(
                "checkbox_enable_mic",
                "list_mic_menu_action_mode",
                "checkbox_show_mic_button",
                "list_mic_button_position",
                "seekbar_mic_bitrate_kbps",
                "list_mic_icon_color",
                "list_mic_volume_processing_mode",
                "checkbox_mic_volume_processing",
                "checkbox_mic_gain",
                "seekbar_mic_gain_db",
                "checkbox_mic_balance",
                "seekbar_mic_balance_target",
                "checkbox_mic_voice_enhancement",
            ),
            "category_input_settings" to setOf(
                "checkbox_enable_enhanced_touch",
                "seekbar_flat_region_pixels",
                "checkbox_enhanced_touch_on_which_side",
                "enhanced_touch_zone_divider",
                "pointer_velocity_factor",
            ),
            "category_gamepad_settings" to setOf(
                "list_dualsense_output_mode",
                "checkbox_dualsense_direct_bluetooth",
                "checkbox_dualsense_wireless_bridge",
            ),
        )

        listOf(
            "category_basic_settings",
            "category_screen_position",
            "category_host_settings",
            "category_display_behavior",
            "category_advanced_features",
            "category_framegen_settings",
            "category_audio_settings",
            "category_microphone_settings",
            "category_gamepad_settings",
            "category_input_settings",
            "category_onscreen_controls",
            "category_crown_features",
            "category_ui_settings",
            "category_backup_restore",
            "category_help",
        ).forEachIndexed { order, categoryKey ->
            assertEquals(
                "Unexpected sidebar order for $categoryKey",
                order.toString(),
                categories[categoryKey]?.getAttributeNS(ANDROID_NAMESPACE, "order"),
            )
        }

        expectedKeysByCategory.forEach { (categoryKey, expectedKeys) ->
            val category = categories[categoryKey]
            assertNotNull("Missing target category: $categoryKey", category)
            val actualKeys = category!!.childNodes.asElementSequence()
                .map { it.getAttributeNS(ANDROID_NAMESPACE, "key") }
                .filter(String::isNotEmpty)
                .toSet()
            expectedKeys.forEach { preferenceKey ->
                assertTrue(
                    "$preferenceKey is missing from $categoryKey",
                    preferenceKey in actualKeys,
                )
            }
        }
    }

    @Test
    fun legacyBackedModeSelectorsDoNotPersistSyntheticKeys() {
        val preferences = parse(File(resourceDir, "xml/preferences.xml"))
        val preferencesByKey = preferences.documentElement
            .getElementsByTagName("*")
            .asElementSequence()
            .associateBy { it.getAttributeNS(ANDROID_NAMESPACE, "key") }

        listOf(
            "list_background_stream_behavior",
            "list_quit_behavior",
            "list_dualsense_output_mode",
            "list_native_mouse_mode_preset",
            "list_mic_volume_processing_mode",
        ).forEach { key ->
            val preference = preferencesByKey[key]
            assertNotNull("Missing synthetic mode selector: $key", preference)
            assertTrue(
                "$key must remain non-persistent so compatibility storage is explicit",
                preference!!.getAttributeNS(ANDROID_NAMESPACE, "persistent") == "false",
            )
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
