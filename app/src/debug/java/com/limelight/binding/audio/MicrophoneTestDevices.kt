package com.limelight.binding.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.limelight.LimeLog
import com.limelight.R
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong

internal data class MicrophoneTestInputDevice(
    val key: String,
    val id: Int,
    val type: Int,
    val name: String,
    val communication: Boolean = false,
    val address: String = ""
)

internal const val DEFAULT_MICROPHONE_INPUT = "default"

internal class MicrophoneTestDeviceRepository(context: Context) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun devices(): List<MicrophoneTestInputDevice> = inputDevices()

    fun choices(): List<MicrophoneTestInputDevice> =
        inputDevices().filterNot { isBluetoothType(it.type) } + communicationChoices()

    fun displayNames(devices: List<MicrophoneTestInputDevice>): Map<String, String> =
        devices.associate { it.key to displayName(it) }

    fun apply(record: AudioRecord, key: String): Boolean {
        if (Build.VERSION.SDK_INT < 23) return key == DEFAULT_MICROPHONE_INPUT
        if (key == DEFAULT_MICROPHONE_INPUT) return record.setPreferredDevice(null)
        val target = inputDevices().firstOrNull { it.key == key } ?: return false
        val raw = rawInputDevices().firstOrNull { it.id == target.id } ?: return false
        return try { record.setPreferredDevice(raw) } catch (_: RuntimeException) { false }
    }

    private fun inputDevices(): List<MicrophoneTestInputDevice> = rawInputDevices().map { raw ->
        MicrophoneTestInputDevice(
            key = "input:${raw.type}:${raw.id}",
            id = raw.id,
            type = raw.type,
            name = raw.productName?.toString().orEmpty(),
            address = deviceAddress(raw)
        )
    }

    private fun rawInputDevices(): List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 23) {
        try {
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        } catch (_: RuntimeException) {
            emptyList()
        }
    } else emptyList()

    private fun communicationChoices(): List<MicrophoneTestInputDevice> {
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return listOf(
                    MicrophoneTestInputDevice(
                        key = "communication:permission",
                        id = -1,
                        type = 7,
                        name = "",
                        communication = true
                    )
                )
            }
            return try {
                audio.availableCommunicationDevices
                    .filter { it.isSink && isBluetoothType(it.type) }
                    .map {
                        MicrophoneTestInputDevice(
                            key = "communication:${it.type}:${it.id}",
                            id = it.id,
                            type = it.type,
                            name = it.productName?.toString().orEmpty(),
                            communication = true,
                            address = deviceAddress(it)
                        )
                    }
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: RuntimeException) {
                emptyList()
            }
        }
        if (Build.VERSION.SDK_INT >= 23 && isLegacyBluetoothConnected()) {
            return listOf(MicrophoneTestInputDevice(
                key = "communication:7:legacy",
                id = -1,
                type = 7,
                name = "Bluetooth",
                communication = true
            ))
        }
        return emptyList()
    }

    private fun isLegacyBluetoothConnected(): Boolean = try {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(BluetoothProfile.HEADSET) ==
            BluetoothAdapter.STATE_CONNECTED && audio.isBluetoothScoAvailableOffCall
    } catch (_: SecurityException) { false }
    catch (_: RuntimeException) { false }

    private fun displayName(device: MicrophoneTestInputDevice): String {
        if (device.key == "communication:permission") {
            return context.getString(R.string.mic_input_bluetooth_permission)
        }
        val kind = when (device.type) {
            15 -> context.getString(R.string.mic_input_builtin)
            3 -> context.getString(R.string.mic_input_wired)
            7, 26 -> context.getString(R.string.mic_input_bluetooth)
            11, 12, 22 -> context.getString(R.string.mic_input_usb)
            else -> context.getString(R.string.mic_input_microphone)
        }
        return device.name.takeIf(String::isNotBlank)?.let {
            context.getString(R.string.mic_input_named_device, it, kind)
        } ?: kind
    }

    private fun deviceAddress(device: AudioDeviceInfo): String = if (Build.VERSION.SDK_INT >= 28) {
        try { device.address.orEmpty() } catch (_: SecurityException) { "" }
    } else ""
}

internal class MicrophoneTestBluetoothRouteController(context: Context) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val worker = HandlerThread("MicrophoneTestBluetoothRoute").apply { start() }
    private val handler = Handler(worker.looper)
    private val generation = AtomicLong()

    fun activate(
        device: MicrophoneTestInputDevice,
        isCurrent: () -> Boolean,
        onRouteLost: () -> Unit,
        complete: (Boolean) -> Unit
    ) {
        val token = generation.incrementAndGet()
        handler.post {
            val success = activateNow(device, token, isCurrent)
            if (generation.get() == token && isCurrent()) complete(success)
        }
    }

    fun release(complete: (Boolean) -> Unit = {}) {
        val token = generation.incrementAndGet()
        handler.post {
            if (generation.get() != token) return@post
            try {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
                audio.mode = AudioManager.MODE_NORMAL
                complete(true)
            } catch (e: RuntimeException) {
                LimeLog.warning("Microphone test Bluetooth route release failed: ${e.javaClass.simpleName}")
                complete(false)
            }
        }
    }

    private fun activateNow(
        device: MicrophoneTestInputDevice,
        token: Long,
        isCurrent: () -> Boolean
    ): Boolean {
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val target = audio.availableCommunicationDevices.firstOrNull {
                    it.isSink && isBluetoothType(it.type) &&
                        (it.id == device.id || device.id < 0)
                } ?: return false
                if (!audio.setCommunicationDevice(target)) return false
                return audio.communicationDevice?.id == target.id
            }
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            val deadline = SystemClock.elapsedRealtime() + 5000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (generation.get() != token || !isCurrent()) return false
                @Suppress("DEPRECATION")
                val sco = audio.isBluetoothScoOn
                if (sco) return true
                Thread.sleep(100L)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            LimeLog.warning("Microphone test Bluetooth route failed: ${e.javaClass.simpleName}")
        }
        return false
    }

    fun close() {
        release()
        worker.quitSafely()
    }
}

internal typealias MicrophoneInputDevice = MicrophoneTestInputDevice
internal typealias MicrophoneInputDeviceRepository = MicrophoneTestDeviceRepository
internal typealias BluetoothMicrophoneRouteController = MicrophoneTestBluetoothRouteController

internal fun isBluetoothMicrophone(type: Int): Boolean = isBluetoothType(type)

internal fun isBluetoothType(type: Int): Boolean = type == 7 || type == 26

internal fun microphoneCaptureSource(communicationInput: Boolean, voiceCommunicationConfigured: Boolean): Int =
    if (communicationInput || voiceCommunicationConfigured) 7 else 1

internal fun microphoneCaptureSampleRate(choice: MicrophoneInputDevice?): Int =
    if (choice?.communication == true && choice.type == 7) 16000 else MicrophoneConfig.SAMPLE_RATE

internal fun matchingBluetoothInput(
    target: MicrophoneInputDevice,
    inputs: List<MicrophoneInputDevice>
): MicrophoneInputDevice? {
    val candidates = inputs.filter { !it.communication && it.type == target.type }
    if (target.address.isNotBlank()) {
        val exact = candidates.filter { it.address.equals(target.address, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        if (candidates.any { it.address.isNotBlank() }) return null
    }
    return candidates.singleOrNull()
}
