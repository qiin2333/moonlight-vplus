package com.limelight.preferences

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySelectionMigrationTest {
    @Test
    fun legacyLowResolutionsKeepTheirActualSize() {
        listOf("360p" to "640x360", "480p" to "854x480").forEach { (legacy, expected) ->
            val values = mutableMapOf<String, Any?>(
                "list_resolution" to legacy,
                "list_fps" to "75",
                "display_selection_migration_version" to 1
            )
            PreferenceConfiguration.migrateDisplaySelections(preferences(values))
            assertEquals(expected, values["list_resolution"])
            assertEquals("75", values["list_fps"])
        }
    }

    @Test
    fun validSavedCustomResolutionAndHighFpsAreNotReplacedByPresets() {
        val values = mutableMapOf<String, Any?>(
            "list_resolution" to "3440x1440",
            "list_fps" to 240
        )
        val preferences = preferences(values)
        PreferenceConfiguration.migrateDisplaySelections(preferences)
        assertEquals("3440x1440", values["list_resolution"])
        assertEquals("240", values["list_fps"])
        val afterMigration = values.toMap()
        PreferenceConfiguration.migrateDisplaySelections(preferences)
        assertEquals(afterMigration, values)
    }

    /** Only the preference operations required by this migration; no Android runtime needed. */
    private fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
        val staged = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString", "putInt" -> {
                    staged[args!![0] as String] = args[1]
                    proxy
                }
                "apply" -> {
                    values.putAll(staged)
                    staged.clear()
                    null
                }
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getInt", "getString" -> values[args!![0] as String] ?: args[1]
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
    }
}
