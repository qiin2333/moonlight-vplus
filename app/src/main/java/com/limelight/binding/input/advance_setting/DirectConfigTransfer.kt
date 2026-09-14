package com.limelight.binding.input.advance_setting

import com.limelight.utils.MathUtils
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Backup references use the sync system's existing profile identity, never a foreign local ID. */
object DirectConfigTransfer {
    private const val PORTABLE = "DCS:p:"
    const val DISABLED = "DCS:disabled"

    @JvmField
    val FIELDS = arrayOf("element_value", "element_middle_value", "element_up_value",
        "element_down_value", "element_left_value", "element_right_value")

    private fun mapValue(value: String, action: (String) -> String): String =
        value.split(',').joinToString(",") { segment ->
            val separator = segment.indexOf('|')
            val keys = if (separator < 0) segment else segment.substring(0, separator)
            val label = if (separator < 0) "" else segment.substring(separator)
            keys.split('+').joinToString("+") { key ->
                if (key.trim().startsWith(DirectConfigAction.PREFIX)) action(key.trim()) else key
            } + label
        }

    @JvmStatic
    fun forImport(value: String, fromBackup: Boolean): String = mapValue(value) { action ->
        if (fromBackup && portableId(action) != null) action else DISABLED
    }

    @JvmStatic
    fun restoreValue(value: String, replacements: Map<Long, Long?>, profiles: Map<String, Long>): String =
        mapValue(value) { action ->
            if (action.startsWith(PORTABLE)) {
                portableId(action)?.let(profiles::get)?.let(DirectConfigAction::encode) ?: DISABLED
            } else {
                val id = DirectConfigAction.parse(action)
                if (id != null && replacements.containsKey(id)) {
                    replacements[id]?.let(DirectConfigAction::encode) ?: DISABLED
                } else action
            }
        }

    fun forBackup(payload: String, profiles: Map<Long, String>): String {
        if (!payload.contains(DirectConfigAction.PREFIX)) return payload
        val root = JSONObject(payload)
        val elements = JSONArray(root.getString("elements"))
        var changed = false
        for (index in 0 until elements.length()) {
            val element = elements.getJSONObject(index)
            for (field in FIELDS) {
                val value = element.opt(field) as? String ?: continue
                val mapped = mapValue(value) { action ->
                    DirectConfigAction.parse(action)?.let(profiles::get)?.let {
                        PORTABLE + URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    } ?: DISABLED
                }
                if (mapped != value) {
                    element.put(field, mapped)
                    changed = true
                }
            }
        }
        if (!changed) return payload
        val serialized = elements.toString()
        root.put("elements", serialized)
        root.put("md5", MathUtils.computeMD5("${root.getInt("version")}${root.getString("settings")}$serialized"))
        return root.toString()
    }

    private fun portableId(action: String): String? {
        if (!action.startsWith(PORTABLE)) return null
        return try {
            URLDecoder.decode(action.substring(PORTABLE.length), "UTF-8").takeIf { it.isNotBlank() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
