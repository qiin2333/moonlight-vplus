package com.limelight.binding.input.touchpad

import android.content.Context
import android.os.Build
import android.view.InputDevice
import androidx.core.content.edit

/** Device-local opt-in. Descriptors survive reconnects and distinguish composite HID interfaces. */
object TouchpadCompatibilityDevices {
    private fun preferences(context: Context) =
        context.getSharedPreferences("touchpad_compatibility", Context.MODE_PRIVATE)

    fun selected(context: Context): Set<String> =
        preferences(context).getStringSet("devices", emptySet())!!.toSet()

    fun contains(context: Context, device: InputDevice?): Boolean =
        device != null && isPointer(device) && device.descriptor in selected(context)

    fun connected(): List<InputDevice> = InputDevice.getDeviceIds().asSequence()
        .mapNotNull(InputDevice::getDevice).filter(::isPointer).toList()

    fun names(context: Context): Map<String, String> {
        val prefs = preferences(context)
        return selected(context).associateWith { prefs.getString(it, it)!! } +
            connected().associate { it.descriptor to it.name }
    }

    fun save(context: Context, descriptors: Set<String>, names: Map<String, String>) {
        preferences(context).edit {
            clear()
            putStringSet("devices", descriptors.toSet())
            descriptors.forEach { putString(it, names.getValue(it)) }
        }
    }

    private fun isPointer(device: InputDevice): Boolean =
        !device.isVirtual && !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) &&
            !device.supportsSource(InputDevice.SOURCE_GAMEPAD) &&
            !device.supportsSource(InputDevice.SOURCE_JOYSTICK) &&
            (device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)) ||
                device.supportsSource(InputDevice.SOURCE_TOUCHPAD))
}
