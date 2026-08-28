package com.limelight.gamemenu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.preference.PreferenceManager
import com.limelight.preferences.PreferenceConfiguration
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchPointerSensitivityPresetStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val legacyPreferences
        get() = context.getSharedPreferences("game_menu_prefs", Context.MODE_PRIVATE)
    private val defaultPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    @Before
    fun setUp() {
        clearTestPreferences()
    }

    @After
    fun tearDown() {
        clearTestPreferences()
    }

    @Test
    fun migratesLegacyPercentagesToHiddenUuidPresets() {
        legacyPreferences.edit()
            .putStringSet("touch_pointer_sensitivity_presets", setOf("150", "50"))
            .commit()

        val presets = TouchPointerSensitivityPresetStore(context).load { "Preset$it" }

        assertEquals(listOf("Preset1", "Preset2"), presets.map { it.name })
        assertEquals(listOf("50", "150"), presets.map {
            it.values.getValue(TouchPointerPresetField.POINTER_SPEED.storageKey)
        })
        assertTrue(presets.all { runCatching { UUID.fromString(it.id) }.isSuccess })
        assertFalse(legacyPreferences.contains("touch_pointer_sensitivity_presets"))
        assertTrue(defaultPreferences.contains("touch_pointer_sensitivity_presets_json"))
    }

    @Test
    fun preservesUnknownFieldsAcrossSaveAndLoad() {
        val original = TouchPointerSensitivityPreset(
            id = UUID.randomUUID().toString(),
            name = "Preset1",
            values = linkedMapOf(
                TouchPointerPresetField.POINTER_SPEED.storageKey to "125",
                "future_touch_field" to "future-value"
            )
        )
        val store = TouchPointerSensitivityPresetStore(context)
        store.save(listOf(original))

        val restored = store.load { "Fallback$it" }.single()

        assertEquals(original.id, restored.id)
        assertEquals("future-value", restored.values["future_touch_field"])
    }

    @Test
    fun repairsDuplicateInternalIdsWithoutChangingVisibleNames() {
        val duplicateId = UUID.randomUUID().toString()
        val array = JSONArray()
        repeat(2) {
            array.put(
                JSONObject()
                    .put("id", duplicateId)
                    .put("name", "Preset1")
                    .put("values", JSONObject().put("pointer_velocity_factor", "100"))
            )
        }
        defaultPreferences.edit()
            .putString(
                "touch_pointer_sensitivity_presets_json",
                JSONObject().put("version", 1).put("presets", array).toString()
            )
            .commit()

        val presets = TouchPointerSensitivityPresetStore(context).load { "Fallback$it" }

        assertEquals(listOf("Preset1", "Preset1"), presets.map { it.name })
        assertEquals(2, presets.map { it.id }.distinct().size)
    }

    @Test
    fun migratesExistingNamedPresetsIntoDefaultPreferences() {
        val presetId = UUID.randomUUID().toString()
        val json = JSONObject()
            .put("version", 1)
            .put(
                "presets",
                JSONArray().put(
                    JSONObject()
                        .put("id", presetId)
                        .put("name", "Preset1")
                        .put(
                            "values",
                            JSONObject().put("pointer_velocity_factor", "125")
                        )
                )
            )
            .toString()
        legacyPreferences.edit()
            .putString("touch_pointer_sensitivity_presets_json", json)
            .commit()

        val presets = TouchPointerSensitivityPresetStore(context).load { "Fallback$it" }

        assertEquals(presetId, presets.single().id)
        assertEquals(
            json,
            defaultPreferences.getString("touch_pointer_sensitivity_presets_json", null)
        )
        assertFalse(legacyPreferences.contains("touch_pointer_sensitivity_presets_json"))
    }

    @Test
    fun unifiedPreferenceWritePersistsTouchSnapshotFields() {
        val config = PreferenceConfiguration().apply {
            pointerVelocityFactor = 175f
            longPressflatRegionPixels = 24
            enhanceTouchZoneDivider = 63
            enhancedTouchOnWhichSide = true
        }

        assertTrue(config.writePreferences(context, synchronous = true))

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals(175, prefs.getInt("pointer_velocity_factor", -1))
        assertEquals(24, prefs.getInt("seekbar_flat_region_pixels", -1))
        assertEquals(63, prefs.getInt("enhanced_touch_zone_divider", -1))
        assertTrue(prefs.getBoolean("checkbox_enhanced_touch_on_which_side", false))
    }

    private fun clearTestPreferences() {
        legacyPreferences.edit().clear().commit()
        defaultPreferences.edit()
            .remove("touch_pointer_sensitivity_presets_json")
            .remove("touch_pointer_sensitivity_presets")
            .remove("pointer_velocity_factor")
            .remove("seekbar_flat_region_pixels")
            .remove("enhanced_touch_zone_divider")
            .remove("checkbox_enhanced_touch_on_which_side")
            .commit()
    }
}
