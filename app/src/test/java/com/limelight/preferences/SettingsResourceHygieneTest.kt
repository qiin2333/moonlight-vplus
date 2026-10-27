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

        listOf("values", "values-zh-rCN", "values-zh-rTW", "values-ru").forEach { directory ->
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
    fun englishAndRussianCoverSettingsChoicesAndDynamicMessages() {
        fun resources(directory: String) = File(resourceDir, directory).listFiles().orEmpty()
            .filter { it.isFile && it.extension == "xml" }
            .flatMap { parse(it).documentElement.childNodes.asElementSequence().toList() }
            .filter { it.tagName in setOf("string", "string-array", "array", "plurals") }
            .associateBy { it.getAttribute("name") }

        val defaults = resources("values")
        val russian = resources("values-ru")
        val referenced = linkedSetOf<String>()
        val referencePattern = Regex("@(?:string|array)/([A-Za-z0-9_]+)")
        fun collectReferences(text: String) {
            referencePattern.findAll(text).forEach { referenced.add(it.groupValues[1]) }
        }

        collectReferences(File(resourceDir, "xml/preferences.xml").readText())
        val sourceDir = File(resourceDir.parentFile, "java/com/limelight/preferences")
        assertTrue("Unable to locate settings sources", sourceDir.isDirectory)
        val sourceReferences = Regex("(?<![\\w.])R\\.(?:string|array)\\.([A-Za-z0-9_]+)")
        val layoutReferences = Regex("(?<![\\w.])R\\.layout\\.([A-Za-z0-9_]+)")
        sourceDir.walkTopDown().filter { it.extension in setOf("kt", "java") }.forEach { source ->
            val text = source.readText()
            sourceReferences.findAll(text).forEach { referenced.add(it.groupValues[1]) }
            layoutReferences.findAll(text).forEach { match ->
                File(resourceDir, "layout/${match.groupValues[1]}.xml")
                    .takeIf(File::isFile)?.let { collectReferences(it.readText()) }
            }
        }

        val pending = java.util.ArrayDeque(referenced)
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            val resource = defaults[name]
            assertNotNull("Missing default settings resource: $name", resource)
            val previous = referenced.toSet()
            collectReferences(resource!!.textContent)
            pending.addAll(referenced - previous)
        }

        // A literal percentage followed by prose (for example, "100% keeps") is not a format token.
        val placeholders = Regex(
            "%(?:(\\d+)\\$)?([-#+ 0,(<]*\\d*(?:\\.\\d+)?" +
                "(?:[bBhHsScCdoxXeEfgGaA]|[tT][HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc]|[%n]))(?![a-zA-Z])",
        )
        fun formats(text: String): List<String> {
            var implicitArgument = 0
            var previousArgument = 0
            return placeholders.findAll(text).mapNotNull { match ->
                val format = match.groupValues[2]
                if (format.last() in setOf('%', 'n')) return@mapNotNull null
                val argument = when {
                    match.groupValues[1].isNotEmpty() -> match.groupValues[1].toInt()
                    '<' in format -> previousArgument
                    else -> ++implicitArgument
                }
                previousArgument = argument
                "$argument:${format.replace("<", "")}"
            }.sorted().toList()
        }
        referenced.map { defaults.getValue(it) }
            .filter { it.tagName == "string" && it.getAttribute("translatable") != "false" }
            .forEach { original ->
                val name = original.getAttribute("name")
                val translated = russian[name]
                assertNotNull("Missing Russian settings text: $name", translated)
                if (!name.startsWith("suffix_")) {
                    assertTrue("Blank English settings text: $name", original.textContent.isNotBlank())
                    assertTrue("Blank Russian settings text: $name", translated!!.textContent.isNotBlank())
                }
                if (original.getAttribute("formatted") != "false") {
                    assertEquals("Russian settings formatting mismatch: $name",
                        formats(original.textContent), formats(translated!!.textContent))
                }
            }
    }

    @Test
    fun pyrowaveDiagnosticAndFallbackStringsCoverEveryExistingLocale() {
        val defaultEntries = parse(File(resourceDir, "values/strings.xml"))
            .documentElement.childNodes.asElementSequence()
            .filter { it.tagName == "string" && it.getAttribute("translatable") != "false" }
            .filter {
                it.getAttribute("name").startsWith("pyrowave_diag_") ||
                    it.getAttribute("name") in setOf(
                        "title_pyrowave_capability_diagnostic", "summary_pyrowave_capability_diagnostic",
                        "pyrowave_negotiation_fallback",
                    )
            }.associate { it.getAttribute("name") to it.textContent }
        assertTrue("No PyroWave strings found", defaultEntries.isNotEmpty())
        val placeholders = Regex("%(?:\\d+\\$)?[a-zA-Z]")
        resourceDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").isFile }
            .forEach { directory ->
                val entries = parse(File(directory, "strings.xml"))
                    .documentElement.childNodes.asElementSequence()
                    .filter { it.tagName == "string" }
                    .groupBy { it.getAttribute("name") }
                defaultEntries.forEach { (name, defaultValue) ->
                    val matches = entries[name].orEmpty()
                    assertEquals("${directory.name}: missing or duplicate $name", 1, matches.size)
                    val value = matches.single().textContent
                    assertTrue("${directory.name}: blank $name", value.isNotBlank())
                    assertEquals("${directory.name}: formatting mismatch in $name",
                        placeholders.findAll(defaultValue).map { it.value }.toList(),
                        placeholders.findAll(value).map { it.value }.toList())
                }
            }
    }

    @Test
    fun mouseAxisCorrectionStringsCoverEveryExistingLocale() {
        val keys = listOf(
            "title_list_mouse_axis_correction",
            "summary_list_mouse_axis_correction",
            "mouse_axis_correction_off",
            "mouse_axis_correction_90",
            "mouse_axis_correction_180",
            "mouse_axis_correction_270",
        )

        resourceDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").isFile }
            .forEach { directory ->
                val entries = stringValues(directory.name)
                keys.forEach { key ->
                    val value = entries[key]
                    assertNotNull("${directory.name}: missing $key", value)
                    assertTrue("${directory.name}: blank $key", value!!.isNotBlank())
                }
            }
    }

    @Test
    fun userFacingSettingsArraysUseStringResources() {
        val arrays = parse(File(resourceDir, "values/arrays.xml"))
        val localizedArrays = setOf(
            "abr_mode_names",
            "fec_mode_names",
            "analog_scrolling_names",
            "audio_codec_names",
            "audio_config_names",
            "audio_passthrough_buffer_names",
            "background_source_names",
            "background_stream_behavior_names",
            "quit_behavior_names",
            "dualsense_output_mode_names",
            "decoder_input_buffer_mode_names",
            "float_ball_action_names",
            "hdr_mode_entries",
            "mic_menu_action_mode_entries",
            "mic_initial_state_entries",
            "native_mouse_mode_preset_names",
            "video_frame_pacing_names",
            "screen_position_names",
            "perf_overlay_orientation_names",
            "perf_overlay_position_horizontal_names",
            "perf_overlay_position_vertical_names",
            "perf_overlay_display_items_names",
            "mic_icon_color_entries",
            "mouse_axis_correction_names",
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
        listOf("values-zh-rCN", "values-zh-rTW", "values-ru").forEach { directory ->
            val localizedValues = stringValues(directory)
            localizedLabelNames.forEach { name ->
                assertTrue("$directory is missing settings list label $name", name in localizedValues)
            }
        }
    }

    @Test
    fun requiredSettingsRemainDiscoverable() {
        val preferences = parse(File(resourceDir, "xml/preferences.xml"))
        val actualKeys = preferences.documentElement.getElementsByTagName("*")
            .asElementSequence()
            .map { it.getAttributeNS(ANDROID_NAMESPACE, "key") }
            .toSet()
        val requiredKeysByCategory = mapOf(
            "category_basic_settings" to setOf(
                "checkbox_adaptive_bitrate", "list_abr_mode", "seekbar_bitrate_kbps",
                "list_fec_mode", "seekbar_fec_percentage",
            ),
            "category_host_settings" to setOf(
                "list_background_stream_behavior", "list_quit_behavior", "checkbox_resume_stream",
                "checkbox_extreme_resume", "checkbox_background_audio", "checkbox_enable_stun",
            ),
            "category_screen_position" to setOf(
                "video_format", "checkbox_enable_hdr", "checkbox_enable_hdr_high_brightness",
                "checkbox_hdr_brightness_override", "seekbar_hdr_peak_brightness_nits", "list_hdr_mode",
                "checkbox_full_range", "capability_diagnostic", "pyrowave_capability_diagnostic",
            ),
            "category_display_behavior" to setOf("checkbox_reverse_resolution"),
            "category_ui_settings" to setOf(
                "settings_float_ball",
            ),
            "category_microphone_settings" to setOf(
                "checkbox_enable_mic", "list_mic_initial_state", "list_mic_menu_action_mode",
                "checkbox_show_mic_button", "list_mic_button_position", "seekbar_mic_bitrate_kbps",
                "list_mic_icon_color", "list_mic_volume_processing_mode", "checkbox_mic_volume_processing",
                "checkbox_mic_gain", "seekbar_mic_gain_db", "checkbox_mic_balance",
                "seekbar_mic_balance_target", "checkbox_mic_voice_enhancement",
            ),
            "category_input_settings" to setOf(
                "checkbox_enable_enhanced_touch", "seekbar_flat_region_pixels",
                "checkbox_enhanced_touch_on_which_side", "enhanced_touch_zone_divider", "pointer_velocity_factor",
            ),
            "category_gamepad_settings" to setOf(
                "list_dualsense_output_mode", "checkbox_dualsense_direct_bluetooth", "checkbox_dualsense_wireless_bridge",
            ),
        )
        val requiredKeys = requiredKeysByCategory.values.flatten().toSet()
        val missingKeys = requiredKeys - actualKeys
        assertTrue("Missing settings: $missingKeys", missingKeys.isEmpty())

        val categoriesByKey = preferences.documentElement.childNodes.asElementSequence()
            .filter { it.tagName == "PreferenceCategory" }
            .associateBy { it.getAttributeNS(ANDROID_NAMESPACE, "key") }
        requiredKeysByCategory.forEach { (categoryKey, expectedKeys) ->
            val category = categoriesByKey[categoryKey]
            assertNotNull("Missing settings category: $categoryKey", category)
            val childKeys = category!!.getElementsByTagName("*").asElementSequence()
                .map { it.getAttributeNS(ANDROID_NAMESPACE, "key") }
                .toSet()
            val misplacedKeys = expectedKeys - childKeys
            assertTrue("Settings missing from $categoryKey: $misplacedKeys", misplacedKeys.isEmpty())
        }
        val floatBallScreen = preferences.documentElement.getElementsByTagName("PreferenceScreen")
            .asElementSequence()
            .single { it.getAttributeNS(ANDROID_NAMESPACE, "key") == "settings_float_ball" }
        val floatBallKeys = floatBallScreen.getElementsByTagName("*").asElementSequence()
            .map { it.getAttributeNS(ANDROID_NAMESPACE, "key") }.toSet()
        assertTrue("Floating ball settings must remain discoverable in their feature screen",
            floatBallKeys.containsAll(setOf(
                "checkbox_enable_float_ball", "list_float_ball_position", "seekbar_float_ball_auto_hide_delay",
                "list_float_ball_single_click_action", "list_float_ball_double_click_action",
                "list_float_ball_long_click_action",
            )))
    }

    @Test
    fun featurePagesKeepNavigationShallowAndDoNotPersistValues() {
        val preferences = parse(File(resourceDir, "xml/preferences.xml"))
        val root = preferences.documentElement
        val pages = root.getElementsByTagName("PreferenceScreen").asElementSequence().toList()
        val expectedPageKeys = setOf(
            "settings_frame_generation",
            "settings_hdr", "settings_picture_layout", "settings_float_ball",
            "settings_performance_overlay", "settings_audio_passthrough", "settings_audio_vibration",
            "settings_gamepad", "settings_input",
        )
        assertEquals("Each feature must have one settings page", expectedPageKeys.size, pages.size)
        assertEquals(
            "Feature pages must remain available from the main settings page",
            expectedPageKeys,
            pages.map { it.getAttributeNS(ANDROID_NAMESPACE, "key") }.toSet(),
        )

        pages.forEach { page ->
            val key = page.getAttributeNS(ANDROID_NAMESPACE, "key")
            assertEquals("$key must be in a main-page category", "PreferenceCategory", page.parentNode.nodeName)
            assertTrue("$key must be reachable from the main page", page.parentNode.parentNode === root)
            assertEquals("$key must not introduce another navigation level", 0,
                page.getElementsByTagName("PreferenceScreen").length)
            assertEquals("$key is navigation and must not persist a value", "false",
                page.getAttributeNS(ANDROID_NAMESPACE, "persistent"))
            assertEquals("$key must show its navigation affordance", "@layout/preference_widget_navigation",
                page.getAttributeNS(ANDROID_NAMESPACE, "widgetLayout"))
        }

        root.childNodes.asElementSequence().filter { it.tagName == "PreferenceCategory" }
            .forEach { category ->
                val directMainSettings = category.getElementsByTagName("*").asElementSequence()
                    .filter { it.tagName !in setOf("PreferenceScreen", "PreferenceCategory") }
                    .filter { element ->
                        generateSequence(element.parentNode) { it.parentNode }
                            .takeWhile { it !== category }
                            .none { it.nodeName == "PreferenceScreen" }
                    }
                    .filter { it.getAttributeNS(ANDROID_NAMESPACE, "selectable") != "false" }
                    .toList()
                assertTrue(
                    "${category.getAttributeNS(ANDROID_NAMESPACE, "key")} must retain a setting or action on the main page",
                    directMainSettings.isNotEmpty(),
                )
            }
    }

    @Test
    fun commonSettingsAndFlatSectionsStayOnTheMainPage() {
        val root = parse(File(resourceDir, "xml/preferences.xml")).documentElement
        val mainKeys = root.getElementsByTagName("*").asElementSequence()
            .filter { element ->
                generateSequence(element.parentNode) { it.parentNode }
                    .takeWhile { it !== root }
                    .none { it.nodeName == "PreferenceScreen" }
            }
            .map { it.getAttributeNS(ANDROID_NAMESPACE, "key") }
            .toSet()
        val requiredMainKeys = setOf(
            "checkbox_enable_mic", "list_mic_initial_state", "list_mic_menu_action_mode", "checkbox_show_mic_button",
            "seekbar_mic_bitrate_kbps", "list_mic_button_position", "list_mic_icon_color",
            "list_mic_volume_processing_mode", "checkbox_mic_volume_processing", "checkbox_mic_gain",
            "seekbar_mic_gain_db", "checkbox_mic_balance", "seekbar_mic_balance_target", "checkbox_mic_voice_enhancement",
            "controller_diagnostic", "controller_mouse_settings", "seekbar_deadzone", "stick_calibration",
            "list_native_mouse_mode_preset", "checkbox_touch_keyboard_auto_invoke", "checkbox_remote_ime_auto_show",
            "checkbox_enable_sops", "list_screen_combination_mode", "checkbox_host_audio",
            "list_quit_behavior", "list_background_stream_behavior",
            "checkbox_control_only", "checkbox_lock_screen_after_disconnect", "checkbox_swap_quit_and_disconnect",
            "checkbox_resume_stream", "checkbox_extreme_resume", "checkbox_background_audio",
            "checkbox_clipboard_sync_text", "checkbox_clipboard_sync_image", "checkbox_enable_stun",
            "pref_framegen_pick_lossless_dll", "checkbox_framegen_enabled", "checkbox_framegen_adaptive_enabled",
            "list_framegen_quality_preset", "pref_framegen_selftest", "checkbox_enable_hdr",
            "seekbar_output_buffer_queue_limit", "checkbox_force_mtk_max_operating_rate", "list_hevc_low_latency_mode",
            "background_source", "background_image_url", "local_image_picker", "reset_background_image",
            "checkbox_show_onscreen_controls", "list_osc_layout", "checkbox_only_show_L3R3",
            "checkbox_show_guide_button", "checkbox_half_height_osc_portrait", "seekbar_osc_opacity", "reset_osc",
            "config_sync_export", "config_sync_import", "config_sync_select_directory",
            "checkbox_config_sync_background_sync", "config_sync_import_external_snapshot",
            "documentation_handbook", "about_us", "open_source_notices", "check_for_updates",
            "pref_developer_unlock", "sponsor_panel",
        )
        val hiddenBehindPages = requiredMainKeys - mainKeys
        assertTrue("Settings must not require an extra navigation step: $hiddenBehindPages",
            hiddenBehindPages.isEmpty())
    }

    @Test
    fun settingsAreNotHiddenBehindExpandButtons() {
        val preferences = parse(File(resourceDir, "xml/preferences.xml"))
        val elements = sequenceOf(preferences.documentElement) +
            preferences.documentElement.getElementsByTagName("*").asElementSequence()
        elements.forEach { element ->
            val expandedCount = element.getAttributeNS(APP_NAMESPACE, "initialExpandedChildrenCount")
            assertTrue(
                "${element.getAttributeNS(ANDROID_NAMESPACE, "key")} must not collapse settings behind an expand button",
                expandedCount.isEmpty() || expandedCount == Int.MAX_VALUE.toString(),
            )
        }
    }

    @Test
    fun uiOnlyPreferencesDoNotPersistValues() {
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
            "pyrowave_capability_diagnostic",
        ).forEach { key ->
            val preference = preferencesByKey[key]
            assertNotNull("Missing UI-only preference: $key", preference)
            assertTrue(
                "$key must not persist a separate UI value",
                preference!!.getAttributeNS(ANDROID_NAMESPACE, "persistent") == "false",
            )
        }
    }

    private fun stringValues(directory: String): Map<String, String> =
        File(resourceDir, directory).listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.extension == "xml" }
            .sortedBy { it.name }
            .flatMap { file ->
                parse(file).documentElement.childNodes.asElementSequence()
                    .filter { it.tagName == "string" }
            }
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
        private const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    }
}
