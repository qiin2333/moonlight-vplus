package com.limelight.binding.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.limelight.LimeLog
import com.limelight.R

/** Device-local routing preference, deliberately separate from portable configuration. */
internal class MicrophoneInputDeviceRepository(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("microphone_input_device", Context.MODE_PRIVATE)
    var selectedKey: String = preferences.getString("selected_input_device_key", DEFAULT_MICROPHONE_INPUT)
        ?.takeIf { it == DEFAULT_MICROPHONE_INPUT || it.matches(Regex("device:[a-f0-9]{64}")) }
        ?: DEFAULT_MICROPHONE_INPUT
        private set

    fun devices(): List<MicrophoneInputDevice> = if (Build.VERSION.SDK_INT >= 23) {
        try { Api23.devices(context) } catch (_: RuntimeException) {
            LimeLog.warning("Microphone input enumeration failed")
            emptyList()
        }
    } else emptyList()

    fun saveSelection(key: String, choice: MicrophoneInputDevice?) {
        selectedKey = key
        preferences.edit { putString("selected_input_device_key", persistedMicrophoneInputKey(choice)) }
    }

    fun applySaved(record: AudioRecord): Boolean {
        if (selectedKey == DEFAULT_MICROPHONE_INPUT) return true
        // Reconnects resolve against fresh IDs. An absent device does not erase the preference.
        return apply(record, resolvedMicrophoneInputKey(selectedKey, devices()))
    }

    fun apply(record: AudioRecord, key: String): Boolean {
        if (Build.VERSION.SDK_INT < 23) return key == DEFAULT_MICROPHONE_INPUT
        return try { Api23.apply(context, record, key) } catch (_: RuntimeException) {
            LimeLog.warning("Microphone preferred input request failed")
            false
        }
    }

    fun observe(handler: Handler, onChanged: () -> Unit): () -> Unit {
        if (Build.VERSION.SDK_INT < 23) return {}
        return try { Api23.observe(context, handler, onChanged) } catch (_: RuntimeException) { {} }
    }

    fun displayName(device: MicrophoneInputDevice): String {
        // AudioDeviceInfo type protocol values keep newer device types safe on API 22.
        val typeName = context.getString(when (device.type) {
            15 -> R.string.mic_input_builtin
            3 -> R.string.mic_input_wired
            7, 26 -> R.string.mic_input_bluetooth
            11, 12, 22 -> R.string.mic_input_usb
            else -> R.string.mic_button
        })
        return device.name.takeIf(String::isNotBlank)?.let {
            context.getString(R.string.mic_input_named_device, it, typeName)
        } ?: typeName
    }

    @RequiresApi(23)
    private object Api23 {
        private fun manager(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private fun inputs(context: Context): List<AudioDeviceInfo> =
            manager(context).getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }

        fun devices(context: Context): List<MicrophoneInputDevice> = choices(inputs(context))

        private fun choices(inputs: List<AudioDeviceInfo>) = microphoneInputChoices(inputs.map { input ->
            val address = if (Build.VERSION.SDK_INT >= 28) {
                try { input.address.orEmpty() } catch (_: SecurityException) { "" }
            } else ""
            MicrophoneInputDescriptor(input.id, input.type, input.productName?.toString().orEmpty(), address)
        })

        fun apply(context: Context, record: AudioRecord, key: String): Boolean {
            if (key == DEFAULT_MICROPHONE_INPUT) return record.setPreferredDevice(null)
            val inputs = inputs(context)
            val choice = choices(inputs).firstOrNull { it.key == key } ?: return false
            val device = inputs.firstOrNull { it.id == choice.id } ?: return false
            return record.setPreferredDevice(device)
        }

        fun observe(context: Context, handler: Handler, onChanged: () -> Unit): () -> Unit {
            val manager = manager(context)
            var listener: (() -> Unit)? = onChanged
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { listener?.invoke() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { listener?.invoke() }
            }
            manager.registerAudioDeviceCallback(callback, handler)
            return {
                listener = null
                try { manager.unregisterAudioDeviceCallback(callback) } catch (_: RuntimeException) { }
            }
        }
    }
}
