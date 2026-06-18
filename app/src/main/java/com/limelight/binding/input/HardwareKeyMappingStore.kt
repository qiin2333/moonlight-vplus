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

internal class HardwareKeyMappingCache {
    private var initialized = false
    private var cachedJson: String? = null
    private var cachedMappings: List<HardwareKeyMapping> = emptyList()

    @Synchronized
    fun load(
        json: String?,
        decoder: (String) -> List<HardwareKeyMapping>
    ): List<HardwareKeyMapping> {
        if (!initialized || json != cachedJson) {
            cachedJson = json
            cachedMappings = json?.let(decoder) ?: emptyList()
            initialized = true
        }
        return cachedMappings
    }

    @Synchronized
    fun update(json: String?, mappings: List<HardwareKeyMapping>) {
        cachedJson = json
        cachedMappings = mappings
        initialized = true
    }
}

object HardwareKeyMappingStore {
    private const val PREFS_NAME = "hardware_key_mappings"
    private const val KEY_MAPPINGS = "mappings_v1"
    private val cache = HardwareKeyMappingCache()

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
        cache.update(null, emptyList())
    }

    fun load(context: Context): List<HardwareKeyMapping> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MAPPINGS, null)
        return cache.load(json, ::decode)
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
                    val deviceKey = item.optString("deviceKey")
                    val sourceScanCode = item.optInt("sourceScanCode")
                    val sourceKeyCode = item.optInt("sourceKeyCode", KeyEvent.KEYCODE_UNKNOWN)
                    val target = item.optInt("targetKeyCode", KeyEvent.KEYCODE_UNKNOWN)
                    if (
                        deviceKey.isBlank() ||
                        (sourceScanCode <= 0 && sourceKeyCode <= KeyEvent.KEYCODE_UNKNOWN) ||
                        target <= KeyEvent.KEYCODE_UNKNOWN
                    ) {
                        continue
                    }
                    add(
                        HardwareKeyMapping(
                            deviceKey = deviceKey,
                            deviceName = item.optString("deviceName", "Unknown keyboard"),
                            sourceScanCode = sourceScanCode,
                            sourceKeyCode = sourceKeyCode,
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
        val storedMappings = mappings.toList()
        val json = encode(storedMappings)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAPPINGS, json)
            .apply()
        cache.update(json, storedMappings)
    }
}
