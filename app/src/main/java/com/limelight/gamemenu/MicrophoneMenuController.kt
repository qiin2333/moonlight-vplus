package com.limelight.gamemenu

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.limelight.Game
import com.limelight.R
import com.limelight.binding.audio.DEFAULT_MICROPHONE_INPUT
import com.limelight.binding.audio.MicrophoneInputDevice
import com.limelight.binding.audio.MicrophoneManager
import com.limelight.binding.audio.resolvedMicrophoneInputKey

internal data class MicrophoneMenuChoice(val key: String, val name: String)

internal data class MicrophoneMenuState(
    val choices: List<MicrophoneMenuChoice> = emptyList(),
    val selectedKey: String = DEFAULT_MICROPHONE_INPUT,
    val status: String = "",
    val bluetoothWarning: String? = null
)

internal fun microphoneRecordingStatusResource(sdkInt: Int, capturing: Boolean, hasRoutedDevice: Boolean): Int = when {
    !capturing -> R.string.mic_disabled
    sdkInt < 23 || hasRoutedDevice -> R.string.mic_input_active
    else -> R.string.mic_input_route_pending
}

/** Enumeration/listeners exist only while the user is on this page. Never starts recording. */
internal class MicrophoneMenuController(private val game: Game) {
    private val handler = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null
    private var manager: MicrophoneManager? = null
    private var listener: ((MicrophoneMenuState) -> Unit)? = null
    private var devices = emptyList<MicrophoneInputDevice>()
    private val refresh = object : Runnable {
        override fun run() {
            if (listener == null) return
            publish()
            handler.postDelayed(this, 1000L)
        }
    }

    fun start(onChanged: (MicrophoneMenuState) -> Unit) {
        stop()
        manager = game.microphoneManager
        listener = onChanged
        refreshDevices()
        unsubscribe = manager?.inputDevices?.observe(handler, ::refreshDevices)
        handler.postDelayed(refresh, 1000L)
    }

    fun select(key: String): Boolean {
        val selected = manager?.selectInputDevice(key) == true
        refreshDevices()
        return selected
    }

    private fun refreshDevices() {
        if (listener == null) return
        devices = manager?.inputDevices?.devices().orEmpty()
        publish()
    }

    private fun publish() {
        val manager = manager ?: return
        val repository = manager.inputDevices
        val selectable = repository.choices()
        val names = repository.displayNames(selectable)
        val routedId = manager.routedInputId()
        val current = devices.firstOrNull { it.id == routedId }
        val resolvedKey = resolvedMicrophoneInputKey(repository.selectedKey, selectable)
        val unavailable = repository.selectedKey != DEFAULT_MICROPHONE_INPUT &&
            resolvedKey == DEFAULT_MICROPHONE_INPUT
        val recordingStatus = if (manager.isInputSwitchPending()) R.string.mic_input_route_pending else microphoneRecordingStatusResource(
            sdkInt = Build.VERSION.SDK_INT,
            capturing = manager.isMicrophoneActive(),
            hasRoutedDevice = current != null
        )
        val recording = if (recordingStatus == R.string.mic_input_active) {
            val inputName = current?.let { names[it.key] ?: repository.displayName(it) }
                ?: game.getString(R.string.mic_input_default)
            game.getString(recordingStatus, inputName)
        } else {
            game.getString(recordingStatus)
        }
        val status = if (unavailable) game.getString(R.string.mic_input_unavailable) + "\n" + recording else recording
        listener?.invoke(MicrophoneMenuState(
            choices = listOf(MicrophoneMenuChoice(DEFAULT_MICROPHONE_INPUT, game.getString(R.string.mic_input_default))) +
                selectable.map { MicrophoneMenuChoice(it.key, names.getValue(it.key)) },
            selectedKey = if (unavailable) repository.selectedKey else resolvedKey,
            status = status,
            bluetoothWarning = game.getString(R.string.mic_input_bluetooth_quality).takeIf {
                selectable.any { it.communication }
            }
        ))
    }

    fun stop() {
        listener = null
        handler.removeCallbacks(refresh)
        unsubscribe?.invoke()
        unsubscribe = null
        manager = null
        devices = emptyList()
    }
}
