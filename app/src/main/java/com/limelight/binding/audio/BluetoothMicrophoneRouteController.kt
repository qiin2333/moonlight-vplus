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
    private var activeLease: MicrophoneRouteOwnership.Lease? = null
    private var legacyConnected = false
    private var legacyAudioState: LegacyBluetoothAudioState? = null
    private var scoNudged = false
    private var active = false

    @Volatile private var activeKey: String? = null

    fun isReady(device: MicrophoneInputDevice): Boolean = activeKey == device.key

    fun activate(device: MicrophoneInputDevice, isCurrent: () -> Boolean,
                 onRouteLost: () -> Unit, complete: (Boolean) -> Unit) {
        used.set(true)
        val token = generation.incrementAndGet()
        worker.post {
            if (generation.get() != token || !isCurrent()) return@post
            if (!clearOwnedRoute() || !ownership.retryPendingCleanup()) {
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
        val lease = createLease()
        if (!ownership.acquire(lease)) {
            complete(false)
            return
        }
        activeLease = lease
        val start = SystemClock.elapsedRealtime()
        var completed = false
        fun finish(success: Boolean) {
            if (completed || generation.get() != token) return
            completed = true
            timeout?.let(worker::removeCallbacks)
            timeout = null
            val diagnostics = routeDiagnostics(device.address)
            active = success
            activeKey = device.key.takeIf { success }
            if (!success) clearOwnedRoute()
            LimeLog.info("Bluetooth microphone route: connected=$success, " +
                "elapsed=${SystemClock.elapsedRealtime() - start}, $diagnostics")
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
                if (activeLease?.routeRequested == true) {
                    if (Build.VERSION.SDK_INT < 31 && legacyConnected) {
                        audio.isBluetoothScoOn = true
                    } else if (Build.VERSION.SDK_INT >= 31 &&
                        scoNudged && Api31.isSelected(audio, device.id)) {
                        audio.isBluetoothScoOn = true
                    }
                }
                val connected = audio.mode == AudioManager.MODE_IN_COMMUNICATION &&
                    (if (Build.VERSION.SDK_INT >= 31)
                        Api31.isSelected(audio, device.id)
                    else legacyConnected)
                if (connected) {
                    // On API 31+, the actual AudioRecord input is the authoritative HFP
                    // confirmation. Some OEMs update the communication route before their
                    // Bluetooth profile proxy reports SCO audio connected.
                    if (!completed) finish(true)
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
            // Do not compete with a phone call or another communication owner.
            if (audio.mode != AudioManager.MODE_NORMAL) {
                finish(false)
                return
            }
            if (Build.VERSION.SDK_INT < 31 && !audio.isBluetoothScoAvailableOffCall) {
                finish(false)
                return
            }
            // HFP is required only for classic Bluetooth SCO. LE Audio uses the
            // communication route directly and has no HFP proxy to query.
            if (device.type == 7) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    finish(false)
                    return
                }
                legacyAudioState = LegacyBluetoothAudioState(worker).also { it.open(context) }
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
            lease.modeRequested = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            lease.routeRequested = true
            val accepted = if (Build.VERSION.SDK_INT >= 31) {
                val selected = Api31.select(audio, device.id)
                if (selected && device.type == 7) requestLegacyScoCompatibility()
                selected
            } else {
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
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val expired = elapsed >= 5000L
                    if (Build.VERSION.SDK_INT < 31 && (!legacyConnected || expired)) {
                        // Some systems establish SCO without delivering its connected broadcast.
                        legacyConnected = confirmedLegacyScoState(
                            legacyConnected,
                            legacyAudioState?.isAudioConnected(device.address)
                        )
                    } else if (Build.VERSION.SDK_INT >= 31 && !expired && !scoNudged &&
                        elapsed >= SCO_NUDGE_DELAY_MS && activeLease?.routeRequested == true &&
                        !scoAudioConfirmed(device.type, device.address)) {
                        // Several OEM stacks accept setCommunicationDevice without opening the
                        // SCO link; the legacy request is still what actually starts the audio.
                        scoNudged = true
                        try {
                            @Suppress("DEPRECATION")
                            audio.startBluetoothSco()
                            audio.isBluetoothScoOn = true
                        } catch (_: RuntimeException) { }
                    }
                    changed()
                    if (!completed) {
                        if (expired) finish(false)
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
        legacyDrain.restartIfExpired(SystemClock.elapsedRealtime())
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
        drainTask?.let(worker::removeCallbacks)
        drainTask = null
        timeout?.let(worker::removeCallbacks)
        timeout = null
        try { unregister?.invoke() } catch (_: RuntimeException) { }
        unregister = null
        legacyAudioState?.close()
        legacyAudioState = null
        active = false
        activeKey = null
        activeLease = null
        legacyConnected = false
        return ownership.release(ownerToken)
    }

    /** The proxy answer is authoritative once known; a pending proxy must delay success. */
    private fun scoAudioConfirmed(type: Int, address: String): Boolean {
        if (type != 7) return true
        val state = legacyAudioState ?: return false
        if (state.isServicePending()) return false
        return bluetoothScoAudioConfirmed(state.isAudioConnected(address), type)
    }

    /** A snapshot for logs; never a substitute for the HFP confirmation itself. */
    private fun routeDiagnostics(targetAddress: String = ""): String {
        val communication = if (Build.VERSION.SDK_INT >= 31) {
            try { audio.communicationDevice?.let { "${it.id}/${it.type}" } }
            catch (_: RuntimeException) { null }
        } else null
        val scoOn = try { audio.isBluetoothScoOn } catch (_: RuntimeException) { null }
        return "sdk=${Build.VERSION.SDK_INT}, mode=${audio.mode}, " +
            "communicationDevice=$communication, scoOn=$scoOn, legacyScoRequested=$scoNudged, " +
            "hfpAudio=${legacyAudioState?.isAudioConnected(targetAddress)}"
    }

    /** Some API 31+ OEM stacks expose a selected communication device but only
     * switch the SCO input HAL after the legacy request is also issued. */
    private fun requestLegacyScoCompatibility() {
        if (scoNudged) return
        scoNudged = true
        try {
            @Suppress("DEPRECATION")
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            LimeLog.info("Bluetooth microphone: requested legacy SCO compatibility")
        } catch (e: RuntimeException) {
            LimeLog.warning("Bluetooth microphone: legacy SCO compatibility request failed: " +
                e.javaClass.simpleName)
        }
    }

    private fun createLease(): MicrophoneRouteOwnership.Lease {

        // Pending cleanup retains only the application AudioManager, never this controller's callbacks.
        val manager = audio
        return MicrophoneRouteOwnership.Lease(ownerToken,
            clearRoute = {
                if (Build.VERSION.SDK_INT >= 31) {
                    Api31.clear(manager)
                    if (scoNudged) {
                        scoNudged = false
                        try {
                            @Suppress("DEPRECATION")
                            manager.stopBluetoothSco()
                            manager.isBluetoothScoOn = false
                        } catch (_: RuntimeException) { }
                    }
                } else {
                    legacyDrain.begin(SystemClock.elapsedRealtime())
                    @Suppress("DEPRECATION")
                    manager.stopBluetoothSco()
                    manager.isBluetoothScoOn = false
                }
            },
            clearMode = { manager.mode = AudioManager.MODE_NORMAL },
            onFailure = { operation, error ->
                LimeLog.warning("Bluetooth microphone $operation cleanup failed: ${error.javaClass.simpleName}")
            })
    }

    companion object {
        // Serialize teardown across managers, including an immediate reconnect to another stream.
        private val worker by lazy { Handler(HandlerThread("MicrophoneRoute").apply { start() }.looper) }
        private val ownership = MicrophoneRouteOwnership()
        private val legacyDrain = MicrophoneRouteDrain()

        // setCommunicationDevice responds immediately, long before the SCO link carries audio.
        private const val SCO_NUDGE_DELAY_MS = 1200L

        fun choices(context: Context): List<MicrophoneInputDevice> = try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when {
                Build.VERSION.SDK_INT >= 31 -> Api31.choices(audio)
                Build.VERSION.SDK_INT >= 23 -> {
                    @Suppress("DEPRECATION")
                    val connected = BluetoothAdapter.getDefaultAdapter()
                        ?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
                    if (connected && audio.isBluetoothScoAvailableOffCall)
                        listOf(MicrophoneInputDevice("bluetooth:system", -1, 7, "", null, true))
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
                it.isSink && it.id == id && isBluetoothMicrophone(it.type)
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
