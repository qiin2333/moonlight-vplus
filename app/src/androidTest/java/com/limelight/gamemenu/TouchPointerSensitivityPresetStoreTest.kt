package com.limelight.gamemenu

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.TouchPointerPresetPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchPointerSensitivityPresetStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val defaultPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)
    private val presetPreferences
        get() = context.getSharedPreferences(
            TouchPointerPresetPreferences.FILE_NAME,
            Context.MODE_PRIVATE
        )
    private lateinit var defaultPreferencesSnapshot: Map<String, *>
    private lateinit var presetPreferencesSnapshot: Map<String, *>

    @Before
    fun setUp() {
        defaultPreferencesSnapshot = defaultPreferences.all.toMap()
        presetPreferencesSnapshot = presetPreferences.all.toMap()
        clearTestPreferences()
    }

    @After
    fun tearDown() {
        restorePreferences(defaultPreferences, defaultPreferencesSnapshot)
        restorePreferences(presetPreferences, presetPreferencesSnapshot)
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
    fun malformedOrMissingPresetArrayLoadsAsEmpty() {
        val store = TouchPointerSensitivityPresetStore(context)

        listOf("not-json", "{}", "{\"presets\":{}}").forEach { malformed ->
            presetPreferences.edit()
                .putString(TouchPointerPresetPreferences.JSON_KEY, malformed)
                .commit()
            assertTrue(store.load { "Fallback$it" }.isEmpty())
        }
    }

    @Test
    fun validEmptyPresetArrayRemainsEmpty() {
        presetPreferences.edit()
            .putString(
                TouchPointerPresetPreferences.JSON_KEY,
                JSONObject().put("version", 1).put("presets", JSONArray()).toString()
            )
            .commit()

        assertTrue(TouchPointerSensitivityPresetStore(context).load { "Fallback$it" }.isEmpty())
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

        assertEquals(175, defaultPreferences.getInt("pointer_velocity_factor", -1))
        assertEquals(24, defaultPreferences.getInt("seekbar_flat_region_pixels", -1))
        assertEquals(63, defaultPreferences.getInt("enhanced_touch_zone_divider", -1))
        assertTrue(
            defaultPreferences.getBoolean("checkbox_enhanced_touch_on_which_side", false)
        )
    }

    private fun clearTestPreferences() {
        defaultPreferences.edit()
            .remove("pointer_velocity_factor")
            .remove("seekbar_flat_region_pixels")
            .remove("enhanced_touch_zone_divider")
            .remove("checkbox_enhanced_touch_on_which_side")
            .commit()
        presetPreferences.edit()
            .remove(TouchPointerPresetPreferences.JSON_KEY)
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
