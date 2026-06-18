package com.limelight.binding.input

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

data class HardwareKeyMapping(
    val deviceKey: String,
    val deviceName: String,
    val sourceScanCode: Int,
    val sourceKeyCode: Int,
    val targetKeyCode: Int
) {
    fun matches(event: KeyEvent): Boolean {
        if (deviceKey != HardwareKeyMappingStore.deviceKey(event.device)) return false
        return if (sourceScanCode > 0) {
            event.scanCode == sourceScanCode
        } else {
            event.keyCode == sourceKeyCode
        }
    }
}

object HardwareKeyMappingStore {
    private const val PREFS_NAME = "hardware_key_mappings"
    private const val KEY_MAPPINGS = "mappings_v1"

    fun mappingFrom(event: KeyEvent, targetKeyCode: Int): HardwareKeyMapping {
        val device = event.device
        return HardwareKeyMapping(
            deviceKey = deviceKey(device),
            deviceName = device?.name ?: "Unknown keyboard",
            sourceScanCode = event.scanCode,
            sourceKeyCode = event.keyCode,
            targetKeyCode = targetKeyCode
        )
    }

    fun resolve(context: Context, event: KeyEvent): Int {
        return load(context).firstOrNull { it.matches(event) }?.targetKeyCode ?: event.keyCode
    }

    fun remap(context: Context, event: KeyEvent): KeyEvent {
        val targetKeyCode = resolve(context, event)
        if (targetKeyCode == event.keyCode) return event
        return KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            targetKeyCode,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source
        )
    }

    fun save(context: Context, mapping: HardwareKeyMapping) {
        val mappings = load(context).toMutableList()
        mappings.removeAll {
            it.deviceKey == mapping.deviceKey &&
                if (mapping.sourceScanCode > 0) {
                    it.sourceScanCode == mapping.sourceScanCode
                } else {
                    it.sourceScanCode <= 0 && it.sourceKeyCode == mapping.sourceKeyCode
                }
        }
        mappings.add(mapping)
        write(context, mappings)
    }

    fun remove(context: Context, mapping: HardwareKeyMapping) {
        write(context, load(context).filterNot { it == mapping })
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MAPPINGS)
            .apply()
    }

    fun load(context: Context): List<HardwareKeyMapping> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MAPPINGS, null) ?: return emptyList()
        return decode(json)
    }

    internal fun encode(mappings: List<HardwareKeyMapping>): String {
        val array = JSONArray()
        mappings.forEach { mapping ->
            array.put(JSONObject().apply {
                put("deviceKey", mapping.deviceKey)
                put("deviceName", mapping.deviceName)
                put("sourceScanCode", mapping.sourceScanCode)
                put("sourceKeyCode", mapping.sourceKeyCode)
                put("targetKeyCode", mapping.targetKeyCode)
            })
        }
        return array.toString()
    }

    internal fun decode(json: String): List<HardwareKeyMapping> {
        return try {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val target = item.optInt("targetKeyCode", KeyEvent.KEYCODE_UNKNOWN)
                    if (target == KeyEvent.KEYCODE_UNKNOWN) continue
                    add(
                        HardwareKeyMapping(
                            deviceKey = item.optString("deviceKey"),
                            deviceName = item.optString("deviceName", "Unknown keyboard"),
                            sourceScanCode = item.optInt("sourceScanCode"),
                            sourceKeyCode = item.optInt("sourceKeyCode"),
                            targetKeyCode = target
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun deviceKey(device: InputDevice?): String {
        if (device == null) return "unknown"
        return if (device.vendorId != 0 || device.productId != 0) {
            "%04x:%04x".format(device.vendorId, device.productId)
        } else {
            "name:${device.name}"
        }
    }

    private fun write(context: Context, mappings: List<HardwareKeyMapping>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAPPINGS, encode(mappings))
            .apply()
    }
}
