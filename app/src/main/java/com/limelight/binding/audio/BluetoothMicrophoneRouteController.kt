package com.limelight.binding.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.limelight.LimeLog
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only explicitly requested microphone communication routes, never device priority. */
internal class BluetoothMicrophoneRouteController(context: Context) {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val generation = AtomicLong()
    private val used = AtomicBoolean()
    private val ownerToken = Any()
    private var unregister: (() -> Unit)? = null
    private var timeout: Runnable? = null
    private var drainTask: Runnable? = null
    private var savedMode: Int? = null
    private var requested = false
    private var legacyConnected = false
    private var active = false

    @Volatile var input: MicrophoneInputDevice? = null
        private set

    fun activate(device: MicrophoneInputDevice, isCurrent: () -> Boolean,
                 onRouteLost: () -> Unit, complete: (Boolean) -> Unit) {
        used.set(true)
        val token = generation.incrementAndGet()
        worker.post {
            if (generation.get() != token || !isCurrent()) return@post
            if (!clearOwnedRoute()) {
                complete(false)
                return@post
            }
            awaitReleased({ generation.get() == token && isCurrent() }) { released ->
                if (released) activateOnWorker(device, token, isCurrent, onRouteLost, complete)
                else complete(false)
            }
        }
    }

    private fun activateOnWorker(device: MicrophoneInputDevice, token: Long, isCurrent: () -> Boolean,
                                 onRouteLost: () -> Unit, complete: (Boolean) -> Unit) {
        if (owner != null && owner !== ownerToken) {
            complete(false)
            return
        }
        val start = SystemClock.elapsedRealtime()
        var completed = false
        fun finish(success: Boolean) {
            if (completed || generation.get() != token) return
            completed = true
            timeout?.let(worker::removeCallbacks)
            timeout = null
            active = success
            if (!success) clearOwnedRoute()
            LimeLog.info("Bluetooth microphone route: connected=$success, elapsed=${SystemClock.elapsedRealtime() - start}")
            complete(success)
        }
        fun changed() {
            if (generation.get() != token) return
            if (!isCurrent()) {
                completed = true
                clearOwnedRoute()
                return
            }
            try {
                if (Build.VERSION.SDK_INT < 31 && legacyConnected && requested) audio.isBluetoothScoOn = true
                val connected = audio.mode == AudioManager.MODE_IN_COMMUNICATION &&
                    if (Build.VERSION.SDK_INT >= 31) Api31.isSelected(audio, device.id) else legacyConnected
                if (connected) {
                    input = matchingBluetoothInput(device, MicrophoneInputDeviceRepository.rawDevices(context))
                    if (!completed && input != null) finish(true)
                    else if (active && input == null) {
                        clearOwnedRoute()
                        onRouteLost()
                    }
                } else if (active) {
                    clearOwnedRoute()
                    onRouteLost()
                }
            } catch (_: RuntimeException) {
                if (!completed) finish(false) else if (active) {
                    clearOwnedRoute()
                    onRouteLost()
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT < 23 || !device.communication) {
                finish(false)
                return
            }
            owner = ownerToken
            savedMode = audio.mode
            // Do not compete with a phone call or another communication owner.
            if (savedMode != AudioManager.MODE_NORMAL) {
                finish(false)
                return
            }
            unregister = if (Build.VERSION.SDK_INT >= 31) {
                Api31.observe(audio, ::changed)
            } else {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (generation.get() != token || !isCurrent()) return
                        legacyConnected = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ==
                            AudioManager.SCO_AUDIO_STATE_CONNECTED
                        changed()
                    }
                }
                ContextCompat.registerReceiver(this.context, receiver,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), null, worker,
                    ContextCompat.RECEIVER_EXPORTED)
                val removeReceiver: () -> Unit = { this.context.unregisterReceiver(receiver) }
                removeReceiver
            }
            requested = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val accepted = if (Build.VERSION.SDK_INT >= 31) Api31.select(audio, device.id)
                else {
                    @Suppress("DEPRECATION")
                    audio.startBluetoothSco()
                    true
                }
            if (!accepted) {
                finish(false)
                return
            }
            val poll = object : Runnable {
                override fun run() {
                    if (completed || generation.get() != token) return
                    changed()
                    if (!completed) {
                        if (SystemClock.elapsedRealtime() - start >= 5000L) finish(false)
                        else worker.postDelayed(this, 100L)
                    }
                }
            }
            timeout = poll
            worker.post(poll)
        } catch (e: RuntimeException) {
            LimeLog.warning("Bluetooth microphone setup failed: ${e.javaClass.simpleName}")
            finish(false)
        }
    }

    fun release(complete: (Boolean) -> Unit = {}) {
        if (!used.get()) {
            complete(true)
            return
        }
        val token = generation.incrementAndGet()
        worker.post {
            if (generation.get() != token) return@post
            if (clearOwnedRoute()) awaitReleased({ generation.get() == token }, complete)
            else complete(false)
        }
    }

    private fun awaitReleased(isCurrent: () -> Boolean, complete: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (isCurrent()) complete(true)
            return
        }
        val poll = object : Runnable {
            override fun run() {
                if (!isCurrent()) return
                val disconnected = try {
                    context.registerReceiver(null, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                        ?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                } catch (_: RuntimeException) { false }
                val result = legacyDrain.observe(SystemClock.elapsedRealtime(), disconnected)
                if (result == null) worker.postDelayed(this, 100L)
                else {
                    drainTask = null
                    complete(result)
                }
            }
        }
        drainTask = poll
        worker.post(poll)
    }

    private fun clearOwnedRoute(): Boolean {
        var success = true
        drainTask?.let(worker::removeCallbacks)
        drainTask = null
        timeout?.let(worker::removeCallbacks)
        timeout = null
        try { unregister?.invoke() } catch (_: RuntimeException) { }
        unregister = null
        active = false
        input = null
        if (owner === ownerToken) {
            try {
                if (requested) {
                    if (Build.VERSION.SDK_INT >= 31) Api31.clear(audio)
                    else {
                        legacyDrain.begin(SystemClock.elapsedRealtime())
                        @Suppress("DEPRECATION")
                        audio.stopBluetoothSco()
                        audio.isBluetoothScoOn = false
                    }
                }
            } catch (e: RuntimeException) {
                success = false
                LimeLog.warning("Bluetooth microphone release failed: ${e.javaClass.simpleName}")
            } finally {
                try {
                    if (requested && audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
                        savedMode?.let { audio.mode = it }
                    }
                } catch (_: RuntimeException) { success = false }
                if (success) owner = null
            }
        }
        if (success) requested = false
        legacyConnected = false
        if (success) savedMode = null
        return success
    }

    companion object {
        // Serialize teardown across managers, including an immediate reconnect to another stream.
        private val worker by lazy { Handler(HandlerThread("MicrophoneRoute").apply { start() }.looper) }
        private var owner: Any? = null
        private val legacyDrain = MicrophoneRouteDrain()

        fun choices(context: Context): List<MicrophoneInputDevice> = try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when {
                Build.VERSION.SDK_INT >= 31 -> Api31.choices(audio)
                Build.VERSION.SDK_INT >= 23 -> {
                    @Suppress("DEPRECATION")
                    val connected = BluetoothAdapter.getDefaultAdapter()
                        ?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
                    if (connected) listOf(MicrophoneInputDevice("bluetooth:system", -1, 7, "", null, true))
                    else emptyList()
                }
                else -> emptyList()
            }
        } catch (_: SecurityException) { emptyList() }
        catch (_: RuntimeException) { emptyList() }
    }

    @RequiresApi(31)
    private object Api31 {
        fun choices(audio: AudioManager): List<MicrophoneInputDevice> = communicationMicrophoneChoices(
            audio.availableCommunicationDevices.filter { it.isSink }.map {
                MicrophoneInputDescriptor(it.id, it.type, it.productName?.toString().orEmpty(), it.address.orEmpty())
            })

        fun select(audio: AudioManager, id: Int): Boolean {
            val target = audio.availableCommunicationDevices.firstOrNull {
                it.id == id && isBluetoothMicrophone(it.type)
            } ?: return false
            return audio.setCommunicationDevice(target)
        }

        fun isSelected(audio: AudioManager, id: Int) = audio.communicationDevice?.id == id
        fun clear(audio: AudioManager) = audio.clearCommunicationDevice()

        fun observe(audio: AudioManager, changed: () -> Unit): () -> Unit {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { changed() }
            val modeListener = AudioManager.OnModeChangedListener { changed() }
            val devices = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
            }
            val executor = Executor { worker.post(it) }
            val remove = {
                try { audio.removeOnCommunicationDeviceChangedListener(listener) } catch (_: RuntimeException) { }
                try { audio.removeOnModeChangedListener(modeListener) } catch (_: RuntimeException) { }
                try { audio.unregisterAudioDeviceCallback(devices) } catch (_: RuntimeException) { }
            }
            try {
                audio.addOnCommunicationDeviceChangedListener(executor, listener)
                audio.addOnModeChangedListener(executor, modeListener)
                audio.registerAudioDeviceCallback(devices, worker)
            } catch (e: RuntimeException) {
                remove()
                throw e
            }
            return remove
        }
    }
}
