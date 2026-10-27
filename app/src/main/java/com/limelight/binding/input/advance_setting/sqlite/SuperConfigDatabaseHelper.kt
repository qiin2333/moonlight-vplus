package com.limelight.binding.input.advance_setting.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.SerializedName
import com.limelight.binding.input.advance_setting.DirectConfigTransfer
import com.limelight.binding.input.advance_setting.config.PageConfigController
import com.limelight.binding.input.advance_setting.element.Element
import com.limelight.utils.ConfigurationSyncScheduler
import com.limelight.utils.MathUtils
import java.lang.reflect.Type
import java.util.regex.Pattern

class SuperConfigDatabaseHelper(context: Context?) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val appContext = requireNotNull(context).applicationContext
    private val writableDataBase = writableDatabase
    private val readableDataBase = readableDatabase

    private class ExportFile(
        @SerializedName("version") var version: Int,
        @SerializedName("settings") var settings: String,
        @SerializedName("elements") var elements: String,
        @SerializedName("md5") var md5: String = MathUtils.computeMD5(version.toString() + settings + elements)
    )

    inner class ContentValuesSerializer : JsonSerializer<ContentValues>, JsonDeserializer<ContentValues> {
        override fun serialize(src: ContentValues, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
            JsonObject().apply {
                for ((key, value) in src.valueSet()) {
                    when (value) {
                        is Int -> addProperty(key, value)
                        is Long -> addProperty(key, value)
                        is Double -> addProperty(key, value)
                        is String -> addProperty(key, value)
                        is ByteArray -> add(key, context.serialize(value))
                    }
                }
            }

        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): ContentValues =
            ContentValues().apply {
                for ((key, value) in json.asJsonObject.entrySet()) {
                    when {
                        value.isJsonPrimitive -> {
                            val primitive = value.asJsonPrimitive
                            when {
                                primitive.isNumber -> {
                                    if (primitive.asString.contains('.')) put(key, primitive.asDouble)
                                    else put(key, primitive.asLong)
                                }
                                primitive.isString -> put(key, primitive.asString)
                            }
                        }
                        value.isJsonArray -> put(key, context.deserialize<ByteArray>(value, ByteArray::class.java))
                    }
                }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS element (
                _id INTEGER PRIMARY KEY,
                element_id INTEGER,
                config_id INTEGER,
                element_type INTEGER,
                element_value TEXT,
                element_middle_value TEXT,
                element_up_value TEXT,
                element_down_value TEXT,
                element_left_value TEXT,
                element_right_value TEXT,
                element_layer INTEGER,
                element_mode INTEGER,
                element_sense INTEGER,
                element_central_x INTEGER,
                element_central_y INTEGER,
                element_width INTEGER,
                element_height INTEGER,
                element_area_width INTEGER,
                element_area_height INTEGER,
                element_text TEXT,
                element_click_text TEXT,
                element_background_icon TEXT,
                element_click_background_icon TEXT,
                element_radius INTEGER,
                element_opacity INTEGER,
                element_thick INTEGER,
                element_background_color INTEGER,
                element_color INTEGER,
                element_pressed_color INTEGER,
                ${Element.COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR} INTEGER,
                ${Element.COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR} INTEGER,
                ${Element.COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT} INTEGER,
                element_create_time INTEGER,
                ${Element.COLUMN_INT_ELEMENT_FLAG1} INTEGER DEFAULT 1,
                extra_attributes TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS config (
                _id INTEGER PRIMARY KEY,
                config_id INTEGER,
                config_name TEXT,
                touch_enable TEXT,
                touch_mode TEXT,
                touch_sense INTEGER,
                game_vibrator TEXT,
                button_vibrator TEXT,
                mouse_wheel_speed INTEGER,
                ${PageConfigController.COLUMN_APPLY_TOUCHPAD_SENSITIVITY} INTEGER DEFAULT 1,
                ${PageConfigController.COLUMN_APPLY_MOUSE_WHEEL_SPEED} INTEGER DEFAULT 1,
                ${PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH} TEXT DEFAULT 'false',
                ${PageConfigController.COLUMN_STRING_QUICK_ACTION_IDS} TEXT
            )
        """.trimIndent())
    }

    fun deleteTable(tableName: String) {
        writableDatabase.execSQL("DROP TABLE IF EXISTS $tableName")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // SQLiteOpenHelper wraps upgrades in a transaction. Check the actual schema
        // so partially migrated databases do not fail on duplicate columns.
        ensureMigratedColumns(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (db.isReadOnly) return

        // A current user_version does not guarantee that all older columns exist.
        // Repair the schema before the controller reads settings, preserving all rows.
        db.beginTransaction()
        try {
            ensureMigratedColumns(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun ensureMigratedColumns(db: SQLiteDatabase) {
        addMissingColumns(db, "config", CONFIG_MIGRATION_COLUMNS)
        addMissingColumns(db, "element", ELEMENT_MIGRATION_COLUMNS)
    }

    private fun addMissingColumns(db: SQLiteDatabase, table: String, definitions: List<Pair<String, String>>) {
        val existingColumns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) existingColumns.add(cursor.getString(nameIndex))
        }
        for ((name, definition) in definitions) {
            if (name !in existingColumns) db.execSQL("ALTER TABLE $table ADD COLUMN $name $definition")
        }
    }

    /** Upgrade imported JSON using the same historical defaults as database migrations. */
    private fun upgradeExportedConfig(exportFile: ExportFile, gson: Gson): Boolean {
        val version = exportFile.version
        if (version == DATABASE_VERSION) return true
        if (version !in 1..DATABASE_VERSION) return false

        val settingsJson = gson.fromJson(exportFile.settings, JsonObject::class.java)
        var elements = exportFile.elements
        if (version == 1) {
            elements = Pattern.compile("(\"element_type\":)\\s*51").matcher(elements).replaceAll("\$13")
        }
        val elementsJson = gson.fromJson(elements, JsonElement::class.java)
        if (version <= 2) {
            settingsJson.addProperty("game_vibrator", "false")
            settingsJson.addProperty("button_vibrator", "false")
        }
        if (version <= 3) settingsJson.addProperty("mouse_wheel_speed", 20)
        if (version <= 4) settingsJson.addProperty(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, "false")
        if (elementsJson.isJsonArray) {
            for (element in elementsJson.asJsonArray) {
                val attributes = element.asJsonObject
                if (version <= 5) attributes.addProperty(Element.COLUMN_INT_ELEMENT_FLAG1, 1)
                if (version <= 6) {
                    attributes.addProperty(Element.COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR, -1)
                    attributes.addProperty(Element.COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR, -3355444)
                    attributes.addProperty(Element.COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT, 25)
                }
                if (version <= 7) attributes.addProperty("extra_attributes", "{}")
            }
        }
        if (version <= 8) settingsJson.addProperty(PageConfigController.COLUMN_STRING_QUICK_ACTION_IDS, "")
        if (version <= 9) {
            settingsJson.addProperty(PageConfigController.COLUMN_APPLY_TOUCHPAD_SENSITIVITY, 1)
            settingsJson.addProperty(PageConfigController.COLUMN_APPLY_MOUSE_WHEEL_SPEED, 1)
        }
        exportFile.settings = gson.toJson(settingsJson)
        exportFile.elements = gson.toJson(elementsJson)
        exportFile.version = DATABASE_VERSION
        return true
    }

    fun insertElement(values: ContentValues) {
        writableDataBase.insert("element", null, values)
        notifyConfigSyncChanged()
    }

    fun deleteElement(configId: Long, elementId: Long) {
        writableDataBase.delete("element", "config_id = ? AND element_id = ?", arrayOf(configId.toString(), elementId.toString()))
        notifyConfigSyncChanged()
    }

    fun updateElement(configId: Long, elementId: Long, values: ContentValues) {
        writableDataBase.update("element", values, "config_id = ? AND element_id = ?", arrayOf(configId.toString(), elementId.toString()))
        notifyConfigSyncChanged()
    }

    fun queryAllElementIds(configId: Long): MutableList<Long> =
        readableDataBase.query("element", arrayOf("element_id", "element_layer"), "config_id = ?",
            arrayOf(configId.toString()), null, null, "element_id + (element_layer * 281474976710656) ASC").use { cursor ->
            mutableListOf<Long>().apply {
                val idIndex = cursor.getColumnIndexOrThrow("element_id")
                while (cursor.moveToNext()) add(cursor.getLong(idIndex))
            }
        }

    fun queryElementAttribute(configId: Long, elementId: Long, elementAttribute: String): Any? =
        queryAttribute("element", elementAttribute, "config_id = ? AND element_id = ?",
            arrayOf(configId.toString(), elementId.toString()))

    fun queryAllElementAttributes(configId: Long, elementId: Long): MutableMap<String, Any> =
        readableDataBase.query("element", null, "config_id = ? AND element_id = ?",
            arrayOf(configId.toString(), elementId.toString()), null, null, null).use { cursor ->
            hashMapOf<String, Any>().apply {
                if (cursor.moveToFirst()) {
                    for (index in 0 until cursor.columnCount) {
                        cursor.readValue(index)?.let { put(cursor.getColumnName(index), it) }
                    }
                }
            }
        }

    fun insertConfig(values: ContentValues) {
        for (column in listOf(
            PageConfigController.COLUMN_APPLY_TOUCHPAD_SENSITIVITY,
            PageConfigController.COLUMN_APPLY_MOUSE_WHEEL_SPEED
        )) {
            if (!values.containsKey(column)) values.put(column, 0)
        }
        writableDataBase.insert("config", null, values)
        notifyConfigSyncChanged()
    }

    fun deleteConfig(configId: Long) {
        val args = arrayOf(configId.toString())
        writableDataBase.delete("config", "config_id = ?", args)
        writableDataBase.delete("element", "config_id = ?", args)
        notifyConfigSyncChanged()
    }

    fun updateConfig(configId: Long, values: ContentValues) {
        writableDataBase.update("config", values, "config_id = ?", arrayOf(configId.toString()))
        notifyConfigSyncChanged()
    }

    private fun notifyConfigSyncChanged() {
        ConfigurationSyncScheduler.requestSyncSoon(appContext)
    }

    fun queryAllConfigIds(): MutableList<Long> =
        readableDataBase.query("config", arrayOf("config_id"), null, null, null, null, "config_id ASC").use { cursor ->
            mutableListOf<Long>().apply {
                val idIndex = cursor.getColumnIndexOrThrow("config_id")
                while (cursor.moveToNext()) add(cursor.getLong(idIndex))
            }
        }

    fun queryConfigAttribute(configId: Long, configAttribute: String, defaultValue: Any?): Any? =
        queryAttribute("config", configAttribute, "config_id = ?", arrayOf(configId.toString())) ?: defaultValue

    private fun queryAttribute(table: String, attribute: String, selection: String, args: Array<String>): Any? =
        readableDataBase.query(table, arrayOf(attribute), selection, args, null, null, null).use { cursor ->
            var result: Any? = null
            val index = cursor.getColumnIndexOrThrow(attribute)
            while (cursor.moveToNext()) cursor.readValue(index)?.let { result = it }
            result
        }

    private fun Cursor.readValue(index: Int): Any? = when (getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_STRING -> getString(index)
        Cursor.FIELD_TYPE_BLOB -> getBlob(index)
        else -> null
    }

    private fun Cursor.readContentValues(): ContentValues = ContentValues().apply {
        for (index in 0 until columnCount) {
            val name = getColumnName(index)
            if (name == "_id") continue
            when (val value = readValue(index)) {
                is Long -> put(name, value)
                is Double -> put(name, value)
                is String -> put(name, value)
                is ByteArray -> put(name, value)
            }
        }
    }

    private fun configGson(): Gson = GsonBuilder()
        .registerTypeAdapter(ContentValues::class.java, ContentValuesSerializer())
        .create()

    fun exportConfig(configId: Long): String {
        val args = arrayOf(configId.toString())
        val elements = readableDataBase.query("element", null, "config_id = ?", args, null, null, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.readContentValues())
            }
        }
        val settings = readableDataBase.query("config", null, "config_id = ?", args, null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.readContentValues() else ContentValues()
        }
        val gson = configGson()
        return gson.toJson(ExportFile(DATABASE_VERSION, gson.toJson(settings), gson.toJson(elements.toTypedArray())))
    }

    fun importConfig(configString: String): Int = importConfig(configString, false)

    fun importConfigForBackup(configString: String): Int = importConfig(configString, true)

    /** Assign new IDs to the profile and elements, then repair group and wheel references. */
    private fun importConfig(configString: String, fromBackup: Boolean): Int {
        val gson = configGson()
        val exportFile = try {
            gson.fromJson(configString, ExportFile::class.java) ?: return -1
        } catch (_: Exception) {
            return -1
        }
        if (!hasValidChecksum(exportFile)) return -2
        if (!upgradeExportedConfig(exportFile, gson)) return -3

        val settings = gson.fromJson(exportFile.settings, ContentValues::class.java)
        val elements = gson.fromJson(exportFile.elements, Array<ContentValues>::class.java)
        sanitizeDirectConfigActions(elements, fromBackup)
        val oldIdToObject = elements.filter { it.containsKey(Element.COLUMN_LONG_ELEMENT_ID) }
            .associateBy { it.getAsLong(Element.COLUMN_LONG_ELEMENT_ID) }

        val newConfigId = System.currentTimeMillis()
        settings.put(PageConfigController.COLUMN_LONG_CONFIG_ID, newConfigId)
        insertConfig(settings)
        var nextElementId = System.currentTimeMillis()
        for (element in elements) {
            element.put(Element.COLUMN_LONG_ELEMENT_ID, nextElementId++)
            element.put(Element.COLUMN_LONG_CONFIG_ID, newConfigId)
            insertElement(element)
        }

        remapElementReferences(newConfigId, elements, oldIdToObject)
        return 0
    }

    fun mergeConfig(configString: String, existConfigId: Long): Int {
        val gson = configGson()
        val exportFile = try {
            gson.fromJson(configString, ExportFile::class.java) ?: return -1
        } catch (_: Exception) {
            return -1
        }
        if (!hasValidChecksum(exportFile)) return -2
        if (!upgradeExportedConfig(exportFile, gson)) return -3

        val elements = gson.fromJson(exportFile.elements, Array<ContentValues>::class.java)
        sanitizeDirectConfigActions(elements, false)
        val oldIdToObject = elements.filter { it.containsKey(Element.COLUMN_LONG_ELEMENT_ID) }
            .associateBy { it.getAsLong(Element.COLUMN_LONG_ELEMENT_ID) }
        var nextElementId = System.currentTimeMillis()
        for (element in elements) {
            element.put(Element.COLUMN_LONG_ELEMENT_ID, nextElementId++)
            element.put(Element.COLUMN_LONG_CONFIG_ID, existConfigId)
            insertElement(element)
        }
        remapElementReferences(existConfigId, elements, oldIdToObject)
        return 0
    }

    /** Both entry points resolve source IDs against the newly assigned profile-local IDs. */
    private fun remapElementReferences(
        configId: Long, elements: Array<ContentValues>, oldIdToObject: Map<Long, ContentValues>
    ) {
        val resolveId: (Long) -> Long? = { oldId -> oldIdToObject[oldId]?.getAsLong(Element.COLUMN_LONG_ELEMENT_ID) }
        for (element in elements) {
            val oldValue = element.getAsString(Element.COLUMN_STRING_ELEMENT_VALUE)
            val newValue = when (element.getAsLong(Element.COLUMN_INT_ELEMENT_TYPE)?.toInt()) {
                Element.ELEMENT_TYPE_GROUP_BUTTON -> ElementReferenceRemapper.group(oldValue, resolveId)
                Element.ELEMENT_TYPE_WHEEL_PAD -> {
                    if (oldValue.isNullOrEmpty()) continue
                    ElementReferenceRemapper.wheel(oldValue, resolveId)
                }
                else -> continue
            }
            element.put(Element.COLUMN_STRING_ELEMENT_VALUE, newValue)
            updateElement(configId, element.getAsLong(Element.COLUMN_LONG_ELEMENT_ID), element)
        }
    }

    private fun hasValidChecksum(exportFile: ExportFile): Boolean =
        exportFile.md5 == MathUtils.computeMD5(exportFile.version.toString() + exportFile.settings + exportFile.elements)

    private fun sanitizeDirectConfigActions(elements: Array<ContentValues>, fromBackup: Boolean) {
        for (element in elements) {
            for (field in DirectConfigTransfer.FIELDS) {
                val value = element.get(field) as? String ?: continue
                element.put(field, DirectConfigTransfer.forImport(value, fromBackup))
            }
        }
    }

    fun restoreDirectConfigActions(configId: Long, replacements: Map<Long, Long?>, profiles: Map<String, Long>) {
        // Most profiles have no cross-config actions. Do not scan every element on each sync.
        val filter = DirectConfigTransfer.FIELDS.joinToString(" OR ", "config_id = ? AND (", ")") { "$it LIKE '%DCS:%'" }
        val affectedIds = readableDataBase.query("element", arrayOf(Element.COLUMN_LONG_ELEMENT_ID), filter,
            arrayOf(configId.toString()), null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        for (elementId in affectedIds) {
            val element = queryAllElementAttributes(configId, elementId)
            val changes = ContentValues()
            for (field in DirectConfigTransfer.FIELDS) {
                val value = element[field] as? String ?: continue
                val restored = DirectConfigTransfer.restoreValue(value, replacements, profiles)
                if (restored != value) changes.put(field, restored)
            }
            if (changes.size() > 0) updateElement(configId, elementId, changes)
        }
    }

    private companion object {
        const val DATABASE_NAME = "super_config.db"
        // Version 10 adds independent input-parameter application flags.
        const val DATABASE_VERSION = 10
        val CONFIG_MIGRATION_COLUMNS = listOf(
            PageConfigController.COLUMN_BOOLEAN_GAME_VIBRATOR to "TEXT DEFAULT 'false'",
            PageConfigController.COLUMN_BOOLEAN_BUTTON_VIBRATOR to "TEXT DEFAULT 'false'",
            "mouse_wheel_speed" to "INTEGER DEFAULT 20",
            PageConfigController.COLUMN_APPLY_TOUCHPAD_SENSITIVITY to "INTEGER DEFAULT 1",
            PageConfigController.COLUMN_APPLY_MOUSE_WHEEL_SPEED to "INTEGER DEFAULT 1",
            PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH to "TEXT DEFAULT 'false'",
            PageConfigController.COLUMN_STRING_QUICK_ACTION_IDS to "TEXT"
        )
        val ELEMENT_MIGRATION_COLUMNS = listOf(
            Element.COLUMN_INT_ELEMENT_FLAG1 to "INTEGER DEFAULT 1",
            Element.COLUMN_INT_ELEMENT_NORMAL_TEXT_COLOR to "INTEGER DEFAULT -1",
            Element.COLUMN_INT_ELEMENT_PRESSED_TEXT_COLOR to "INTEGER DEFAULT -3355444",
            Element.COLUMN_INT_ELEMENT_TEXT_SIZE_PERCENT to "INTEGER DEFAULT 25",
            "extra_attributes" to "TEXT"
        )
    }
}
