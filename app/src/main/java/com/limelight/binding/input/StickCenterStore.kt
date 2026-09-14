package com.limelight.binding.input

import android.content.Context
import android.view.InputDevice

/** Separate, local preferences: calibration belongs to hardware, not synced stream settings. */
internal class StickCenterStore(context: Context) {
    private val preferences = context.getSharedPreferences("stick_centers", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, Float>()

    private fun key(device: InputDevice, axis: Int) = "${device.descriptor}:$axis"

    fun center(device: InputDevice, axis: Int): Float {
        val key = key(device, axis)
        return cache.getOrPut(key) { preferences.getFloat(key, 0f) }
    }

    fun correct(device: InputDevice, axis: Int, value: Float) =
        recenterStickAxis(value, center(device, axis))

    fun save(device: InputDevice, values: Map<Int, Float>) {
        require(values.values.all { it.isFinite() && it in -0.9f..0.9f })
        val editor = preferences.edit()
        values.forEach { (axis, value) -> editor.putFloat(key(device, axis), value) }
        editor.apply()
        cache.clear()
    }

    fun reset(device: InputDevice) {
        val prefix = "${device.descriptor}:"
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
        cache.clear()
    }
}
