package com.limelight.gamemenu

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.preference.PreferenceManager
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.TouchPointerPresetPreferences
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
    private val presetPreferences
        get() = context.getSharedPreferences(
            TouchPointerPresetPreferences.FILE_NAME,
            Context.MODE_PRIVATE
        )
    private lateinit var defaultPreferencesSnapshot: Map<String, *>
    private lateinit var legacyPreferencesSnapshot: Map<String, *>
    private lateinit var presetPreferencesSnapshot: Map<String, *>

    @Before
    fun setUp() {
        defaultPreferencesSnapshot = defaultPreferences.all.toMap()
        legacyPreferencesSnapshot = legacyPreferences.all.toMap()
        presetPreferencesSnapshot = presetPreferences.all.toMap()
        clearTestPreferences()
    }

    @After
    fun tearDown() {
        restorePreferences(defaultPreferences, defaultPreferencesSnapshot)
        restorePreferences(legacyPreferences, legacyPreferencesSnapshot)
        restorePreferences(presetPreferences, presetPreferencesSnapshot)
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
        assertTrue(presetPreferences.contains(TouchPointerPresetPreferences.JSON_KEY))
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
        presetPreferences.edit()
            .putString(
                TouchPointerPresetPreferences.JSON_KEY,
                JSONObject().put("version", 1).put("presets", array).toString()
            )
            .commit()

        val presets = TouchPointerSensitivityPresetStore(context).load { "Fallback$it" }

        assertEquals(listOf("Preset1", "Preset1"), presets.map { it.name })
        assertEquals(2, presets.map { it.id }.distinct().size)
    }

    @Test
    fun migratesExistingNamedPresetsIntoDedicatedPreferences() {
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
            presetPreferences.getString(TouchPointerPresetPreferences.JSON_KEY, null)
        )
        assertFalse(legacyPreferences.contains("touch_pointer_sensitivity_presets_json"))
    }

    @Test
    fun migratesCurrentDefaultPreferenceStorageIntoDedicatedPreferences() {
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
                            JSONObject().put("pointer_velocity_factor", "150")
                        )
                )
            )
            .toString()
        defaultPreferences.edit()
            .putString(TouchPointerPresetPreferences.JSON_KEY, json)
            .commit()

        val presets = TouchPointerSensitivityPresetStore(context).load { "Fallback$it" }

        assertEquals(presetId, presets.single().id)
        assertEquals(json, presetPreferences.getString(TouchPointerPresetPreferences.JSON_KEY, null))
        assertFalse(defaultPreferences.contains(TouchPointerPresetPreferences.JSON_KEY))
    }

    @Test
    fun unifiedPreferenceWritePersistsTouchSnapshotFields() {
        val config = PreferenceConfiguration.readPreferences(context).apply {
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
        legacyPreferences.edit()
            .remove("touch_pointer_sensitivity_presets_json")
            .remove("touch_pointer_sensitivity_presets")
            .commit()
        defaultPreferences.edit()
            .remove(TouchPointerPresetPreferences.JSON_KEY)
            .remove("touch_pointer_sensitivity_presets")
            .remove("pointer_velocity_factor")
            .remove("seekbar_flat_region_pixels")
            .remove("enhanced_touch_zone_divider")
            .remove("checkbox_enhanced_touch_on_which_side")
            .commit()
        presetPreferences.edit()
            .remove(TouchPointerPresetPreferences.JSON_KEY)
            .remove("touch_pointer_sensitivity_presets")
            .commit()
    }

    private fun restorePreferences(
        preferences: SharedPreferences,
        snapshot: Map<String, *>
    ) {
        val editor = preferences.edit().clear()
        snapshot.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(editor.commit())
    }
}
