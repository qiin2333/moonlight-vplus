package com.limelight.binding.input.advance_setting.sqlite

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limelight.binding.input.advance_setting.element.Element
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.utils.MathUtils
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SuperConfigDatabaseHelperTest {
    // Isolate migration fixtures from the app's real controller profiles.
    private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        private val prefix = "schema-test-${System.nanoTime()}-"

        override fun getDatabasePath(name: String): File = baseContext.getDatabasePath(prefix + name)

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
        ): SQLiteDatabase = baseContext.openOrCreateDatabase(prefix + name, mode, factory)

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?
        ): SQLiteDatabase = baseContext.openOrCreateDatabase(prefix + name, mode, factory, errorHandler)

        override fun deleteDatabase(name: String): Boolean = baseContext.deleteDatabase(prefix + name)
    }

    @After
    fun cleanup() {
        context.deleteDatabase("super_config.db")
    }

    private fun createLegacyDatabase(version: Int, partiallyMigrated: Boolean = false) {
        context.openOrCreateDatabase("super_config.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""
                CREATE TABLE config (
                    _id INTEGER PRIMARY KEY, config_id INTEGER, config_name TEXT,
                    touch_enable TEXT, touch_mode TEXT, touch_sense INTEGER
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE element (
                    _id INTEGER PRIMARY KEY, config_id INTEGER, element_id INTEGER, element_value TEXT,
                    element_layer INTEGER DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("INSERT INTO config (config_id, config_name, touch_sense) VALUES (42, 'saved layout', 73)")
            db.execSQL("INSERT INTO element (config_id, element_id, element_value) VALUES (42, 123, 'DCS:0')")
            if (partiallyMigrated) {
                db.execSQL("ALTER TABLE config ADD COLUMN button_vibrator TEXT DEFAULT 'false'")
                db.execSQL("UPDATE config SET button_vibrator = 'true'")
                db.execSQL("ALTER TABLE element ADD COLUMN ${Element.COLUMN_INT_ELEMENT_FLAG1} INTEGER DEFAULT 1")
                db.execSQL("UPDATE element SET ${Element.COLUMN_INT_ELEMENT_FLAG1} = 7")
            }
            db.version = version
        }
    }

    private fun assertRepaired(helper: SuperConfigDatabaseHelper) {
        assertEquals(10, helper.readableDatabase.version)
        assertEquals(1L, helper.queryConfigAttribute(42, PageConfigController.COLUMN_APPLY_TOUCHPAD_SENSITIVITY, -1L))
        assertEquals(1L, helper.queryConfigAttribute(42, PageConfigController.COLUMN_APPLY_MOUSE_WHEEL_SPEED, -1L))
        assertEquals(listOf(42L), helper.queryAllConfigIds())
        assertEquals("saved layout", helper.queryConfigAttribute(42, "config_name", ""))
        assertEquals(73L, helper.queryConfigAttribute(42, "touch_sense", 0L))
        assertEquals("false", helper.queryConfigAttribute(42, "game_vibrator", "missing"))
        assertEquals(20L, helper.queryConfigAttribute(42, "mouse_wheel_speed", 0L))
        assertEquals("false", helper.queryConfigAttribute(42, "enhanced_touch", "missing"))
        assertEquals("", helper.queryConfigAttribute(42, "quick_action_ids", ""))
        assertEquals(listOf(123L), helper.queryAllElementIds(42))
        assertEquals("DCS:0", helper.queryElementAttribute(42, 123, "element_value"))
        assertEquals(-1L, helper.queryElementAttribute(42, 123, Element.COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR))
        assertEquals(-3355444L, helper.queryElementAttribute(42, 123, Element.COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR))
        assertEquals(25L, helper.queryElementAttribute(42, 123, Element.COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT))
        assertEquals(null, helper.queryElementAttribute(42, 123, "extra_attributes"))
    }

    @Test
    fun currentVersionRepairsMissingColumnsAndKeepsExistingValues() {
        createLegacyDatabase(version = 10, partiallyMigrated = true)
        SuperConfigDatabaseHelper(context).use { helper ->
            assertRepaired(helper)
            assertEquals("true", helper.queryConfigAttribute(42, "button_vibrator", "missing"))
            assertEquals(7L, helper.queryElementAttribute(42, 123, Element.COLUMN_INT_ELEMENT_FLAG1))
        }
    }

    @Test
    fun everyLegacyVersionRepairsSchemaWithoutLosingProfiles() {
        for (version in 1..9) {
            createLegacyDatabase(version)
            SuperConfigDatabaseHelper(context).use { helper ->
                assertRepaired(helper)
                assertEquals("false", helper.queryConfigAttribute(42, "button_vibrator", "missing"))
                assertEquals(1L, helper.queryElementAttribute(42, 123, Element.COLUMN_INT_ELEMENT_FLAG1))
            }
            cleanup()
        }
    }

    @Test
    fun partiallyMigratedOldVersionDoesNotAddDuplicateColumns() {
        createLegacyDatabase(version = 2, partiallyMigrated = true)
        SuperConfigDatabaseHelper(context).use { helper ->
            assertRepaired(helper)
            assertEquals("true", helper.queryConfigAttribute(42, "button_vibrator", "missing"))
            assertEquals(7L, helper.queryElementAttribute(42, 123, Element.COLUMN_INT_ELEMENT_FLAG1))
        }
    }

    @Test
    fun repairedColumnsRemainWritableAndSurviveRepeatedOpens() {
        createLegacyDatabase(version = 9)
        SuperConfigDatabaseHelper(context).use { helper ->
            assertRepaired(helper)
            assertEquals(1, helper.writableDatabase.update("config", ContentValues().apply {
                put("game_vibrator", "true")
                put("mouse_wheel_speed", 55)
                put("quick_action_ids", "menu")
            }, "config_id = ?", arrayOf("42")))
        }
        repeat(2) {
            SuperConfigDatabaseHelper(context).use { helper ->
                assertEquals("true", helper.queryConfigAttribute(42, "game_vibrator", "missing"))
                assertEquals(55L, helper.queryConfigAttribute(42, "mouse_wheel_speed", 0L))
                assertEquals("menu", helper.queryConfigAttribute(42, "quick_action_ids", ""))
                assertEquals("DCS:0", helper.queryElementAttribute(42, 123, "element_value"))
            }
        }
    }

    @Test
    fun freshDatabaseCanBeOpenedRepeatedly() {
        repeat(2) {
            SuperConfigDatabaseHelper(context).use { helper ->
                assertEquals(10, helper.readableDatabase.version)
                assertEquals("fallback", helper.queryConfigAttribute(42, "game_vibrator", "fallback"))
                assertEquals(emptyList<Long>(), helper.queryAllConfigIds())
            }
        }
    }

    @Test
    fun newProfilesOptOutAndBothFlagsSurviveBackupImportAndElementMerge() {
        val sensitivityKey = PageConfigController.COLUMN_APPLY_TOUCHPAD_SENSITIVITY
        val wheelKey = PageConfigController.COLUMN_APPLY_MOUSE_WHEEL_SPEED
        SuperConfigDatabaseHelper(context).use { helper ->
            helper.insertConfig(ContentValues().apply { put("config_id", 42L) })
            assertEquals(0L, helper.queryConfigAttribute(42, sensitivityKey, -1L))
            assertEquals(0L, helper.queryConfigAttribute(42, wheelKey, -1L))
            for (sensitivity in 0..1) for (wheel in 0..1) {
                helper.updateConfig(42, ContentValues().apply {
                    put(sensitivityKey, sensitivity)
                    put(wheelKey, wheel)
                    put("touch_sense", 321)
                    put("mouse_wheel_speed", 78)
                })
                val payload = helper.exportConfig(42)
                val before = helper.queryAllConfigIds().toSet()
                assertEquals(0, helper.importConfigForBackup(payload))
                val imported = helper.queryAllConfigIds().single { it !in before }
                assertEquals(sensitivity.toLong(), helper.queryConfigAttribute(imported, sensitivityKey, -1L))
                assertEquals(wheel.toLong(), helper.queryConfigAttribute(imported, wheelKey, -1L))
                assertEquals(321L, helper.queryConfigAttribute(imported, "touch_sense", -1L))
                assertEquals(78L, helper.queryConfigAttribute(imported, "mouse_wheel_speed", -1L))
                helper.deleteConfig(imported)
                assertEquals(0, helper.mergeConfig(payload, 42))
                assertEquals(sensitivity.toLong(), helper.queryConfigAttribute(42, sensitivityKey, -1L))
                assertEquals(wheel.toLong(), helper.queryConfigAttribute(42, wheelKey, -1L))
            }
        }
    }

    @Test
    fun exportImportAndMergeKeepGroupAndWheelReferences() {
        SuperConfigDatabaseHelper(context).use { helper ->
            helper.writableDatabase.execSQL("INSERT INTO config (config_id, config_name) VALUES (42, 'round trip')")
            fun insertElement(id: Long, type: Int, value: String) {
                helper.writableDatabase.insertOrThrow("element", null, ContentValues().apply {
                    put("config_id", 42L)
                    put("element_id", id)
                    put("element_type", type)
                    put("element_value", value)
                    put("element_layer", 0)
                })
            }
            insertElement(100, Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON, "65")
            insertElement(200, Element.ELEMENT_TYPE_GROUP_BUTTON, "-1,100")
            insertElement(300, Element.ELEMENT_TYPE_WHEEL_PAD, "gb200|Group,66|Key")

            val payload = helper.exportConfig(42)
            val envelope = JSONObject(payload)
            assertEquals(10, envelope.getInt("version"))
            assertEquals(MathUtils.computeMD5("10" + envelope.getString("settings") + envelope.getString("elements")),
                envelope.getString("md5"))
            assertEquals(0, helper.importConfig(payload))
            val importedId = helper.queryAllConfigIds().single { it != 42L }
            val imported = helper.queryAllElementIds(importedId).associateBy {
                helper.queryElementAttribute(importedId, it, "element_type") as Long
            }
            val childId = imported.getValue(Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON.toLong())
            val groupId = imported.getValue(Element.ELEMENT_TYPE_GROUP_BUTTON.toLong())
            val wheelId = imported.getValue(Element.ELEMENT_TYPE_WHEEL_PAD.toLong())
            assertEquals("-1,$childId", helper.queryElementAttribute(importedId, groupId, "element_value"))
            assertEquals("gb$groupId|Group,66|Key", helper.queryElementAttribute(importedId, wheelId, "element_value"))
            assertEquals("-1,100", helper.queryElementAttribute(42, 200, "element_value"))

            helper.writableDatabase.execSQL("INSERT INTO config (config_id, config_name) VALUES (43, 'merge')")
            assertEquals(0, helper.mergeConfig(payload, 43))
            val merged = helper.queryAllElementIds(43).associateBy {
                helper.queryElementAttribute(43, it, "element_type") as Long
            }
            val mergedChild = merged.getValue(Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON.toLong())
            val mergedGroup = merged.getValue(Element.ELEMENT_TYPE_GROUP_BUTTON.toLong())
            val mergedWheel = merged.getValue(Element.ELEMENT_TYPE_WHEEL_PAD.toLong())
            assertEquals("-1,$mergedChild", helper.queryElementAttribute(43, mergedGroup, "element_value"))
            assertEquals("gb$mergedGroup|Group,66|Key", helper.queryElementAttribute(43, mergedWheel, "element_value"))
        }
    }

    @Test
    fun blobElementValuesRoundTripWithoutChangingTheirType() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(42), byteArrayOf(0, 127, -1))) {
            SuperConfigDatabaseHelper(context).use { helper ->
                helper.writableDatabase.execSQL("INSERT INTO config (config_id, config_name) VALUES (42, 'blob')")
                helper.writableDatabase.insertOrThrow("element", null, ContentValues().apply {
                    put("config_id", 42L)
                    put("element_id", 100L)
                    put("element_type", Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON)
                    put("element_value", bytes)
                    put("element_layer", 0)
                })
                val payload = helper.exportConfig(42)
                val encoded = JSONArray(JSONObject(payload).getString("elements"))
                    .getJSONObject(0).getJSONArray("element_value")
                assertEquals(bytes.map { it.toInt() }, (0 until encoded.length()).map(encoded::getInt))
                assertEquals(0, helper.importConfig(payload))
                val importedId = helper.queryAllConfigIds().single { it != 42L }
                val elementId = helper.queryAllElementIds(importedId).single()
                assertArrayEquals(bytes, helper.queryElementAttribute(importedId, elementId, "element_value") as ByteArray)
            }
            cleanup()
        }
    }

    @Test
    fun missingAndMalformedGroupValuesAreSafeForImportBackupAndMerge() {
        for (value in listOf(null, "", "-1,,bad,100,9223372036854775808,999,")) {
            for (mode in listOf("import", "backup", "merge")) {
                SuperConfigDatabaseHelper(context).use { helper ->
                    val settings = JSONObject().put("config_id", 42).put("config_name", "group-$mode").toString()
                    val group = JSONObject().put("config_id", 42).put("element_id", 200)
                        .put("element_type", Element.ELEMENT_TYPE_GROUP_BUTTON).put("element_layer", 0)
                    if (value != null) group.put("element_value", value)
                    val elements = JSONArray().put(JSONObject()
                        .put("config_id", 42).put("element_id", 100)
                        .put("element_type", Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON)
                        .put("element_value", "65").put("element_layer", 0)).put(group).toString()
                    val payload = JSONObject().put("version", 9).put("settings", settings).put("elements", elements)
                        .put("md5", MathUtils.computeMD5("9$settings$elements")).toString()
                    val result = when (mode) {
                        "backup" -> helper.importConfigForBackup(payload)
                        "merge" -> {
                            helper.writableDatabase.execSQL("INSERT INTO config (config_id, config_name) VALUES (42, 'merge')")
                            helper.mergeConfig(payload, 42)
                        }
                        else -> helper.importConfig(payload)
                    }
                    assertEquals(0, result)
                    val configId = helper.queryAllConfigIds().single()
                    val ids = helper.queryAllElementIds(configId).associateBy {
                        helper.queryElementAttribute(configId, it, "element_type") as Long
                    }
                    val childId = ids.getValue(Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON.toLong())
                    val groupId = ids.getValue(Element.ELEMENT_TYPE_GROUP_BUTTON.toLong())
                    assertEquals(if (value.isNullOrEmpty()) "-1" else "-1,$childId",
                        helper.queryElementAttribute(configId, groupId, "element_value"))
                }
                cleanup()
            }
        }
    }

    @Test
    fun legacyJsonVersionsKeepTheirUpgradeDefaults() {
        SuperConfigDatabaseHelper(context).use { helper ->
            for (version in 1..9) {
                val settings = JSONObject().put("config_id", 42).put("config_name", "legacy-$version").toString()
                val elements = JSONArray().put(JSONObject()
                    .put("config_id", 42).put("element_id", 100).put("element_type", 51)
                    .put("element_value", "65").put("element_layer", 0)).toString()
                val payload = JSONObject().put("version", version).put("settings", settings).put("elements", elements)
                    .put("md5", MathUtils.computeMD5(version.toString() + settings + elements)).toString()
                val before = helper.queryAllConfigIds().toSet()
                assertEquals(0, helper.importConfig(payload))
                val importedId = helper.queryAllConfigIds().single { it !in before }
                assertEquals("legacy-$version", helper.queryConfigAttribute(importedId, "config_name", ""))
                assertEquals(1L, helper.queryConfigAttribute(importedId, PageConfigController.COLUMN_APPLY_TOUCHPAD_SENSITIVITY, -1L))
                assertEquals(1L, helper.queryConfigAttribute(importedId, PageConfigController.COLUMN_APPLY_MOUSE_WHEEL_SPEED, -1L))
                val elementId = helper.queryAllElementIds(importedId).single()
                assertEquals(if (version == 1) 3L else 51L,
                    helper.queryElementAttribute(importedId, elementId, "element_type"))
                if (version <= 2) assertEquals("false", helper.queryConfigAttribute(importedId, "game_vibrator", "missing"))
                if (version <= 3) assertEquals(20L, helper.queryConfigAttribute(importedId, "mouse_wheel_speed", 0L))
                if (version <= 5) assertEquals(1L, helper.queryElementAttribute(importedId, elementId, Element.COLUMN_INT_ELEMENT_FLAG1))
                if (version <= 6) assertEquals(25L, helper.queryElementAttribute(importedId, elementId, Element.COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT))
                if (version <= 7) assertEquals("{}", helper.queryElementAttribute(importedId, elementId, "extra_attributes"))
            }
        }
    }
}
