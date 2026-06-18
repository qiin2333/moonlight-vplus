package com.limelight.binding.input.capture

import android.content.Context
import android.view.InputDevice
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class TouchpadCompatibilityDevice(
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val name: String
) {
    fun matches(device: InputDevice): Boolean {
        if (descriptor.isNotBlank() && descriptor == device.descriptor) return true
        return vendorId == device.vendorId &&
            productId == device.productId &&
            name == device.name
    }
}

object TouchpadCompatibilityStore {
    private const val PREFS_NAME = "touchpad_compatibility_devices"
    private const val KEY_DEVICES = "devices_v1"

    fun from(device: InputDevice): TouchpadCompatibilityDevice {
        return TouchpadCompatibilityDevice(
            descriptor = device.descriptor.orEmpty(),
            vendorId = device.vendorId,
            productId = device.productId,
            name = device.name
        )
    }

    fun load(context: Context): List<TouchpadCompatibilityDevice> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEVICES, null) ?: return emptyList()
        return decode(json)
    }

    fun save(context: Context, device: TouchpadCompatibilityDevice) {
        val devices = load(context).filterNot {
            it.descriptor.isNotBlank() && it.descriptor == device.descriptor ||
                it.vendorId == device.vendorId &&
                it.productId == device.productId &&
                it.name == device.name
        } + device
        write(context, devices)
    }

    fun remove(context: Context, device: TouchpadCompatibilityDevice) {
        write(context, load(context).filterNot { it == device })
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_DEVICES) }
    }

    fun shouldSkipPointerCapture(context: Context, devices: Iterable<InputDevice>): Boolean {
        val configured = load(context)
        return configured.isNotEmpty() && devices.any { inputDevice ->
            configured.any { it.matches(inputDevice) }
        }
    }

    fun isConfigured(context: Context, device: InputDevice): Boolean {
        return load(context).any { it.matches(device) }
    }

    internal fun encode(devices: List<TouchpadCompatibilityDevice>): String {
        val array = JSONArray()
        devices.forEach { device ->
            array.put(JSONObject().apply {
                put("descriptor", device.descriptor)
                put("vendorId", device.vendorId)
                put("productId", device.productId)
                put("name", device.name)
            })
        }
        return array.toString()
    }

    internal fun decode(json: String): List<TouchpadCompatibilityDevice> {
        return try {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    if (name.isBlank()) continue
                    add(
                        TouchpadCompatibilityDevice(
                            descriptor = item.optString("descriptor"),
                            vendorId = item.optInt("vendorId"),
                            productId = item.optInt("productId"),
                            name = name
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, devices: List<TouchpadCompatibilityDevice>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_DEVICES, encode(devices)) }
    }
}
