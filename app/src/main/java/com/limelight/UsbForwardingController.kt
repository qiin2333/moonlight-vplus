package com.limelight

import android.app.Dialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.InputDevice
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.limelight.binding.input.driver.UsbDriverService
import com.limelight.binding.input.driver.wireless.hci.HciUsbDeviceProbe
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.UsbForwardingCapability
import java.io.FileNotFoundException
import com.limelight.usbip.UsbIpBackend
import com.limelight.usbip.UsbReverseTunnel
import com.limelight.utils.AppActionSheet
import com.limelight.utils.CompletionSignal
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors

/** One foreground stream owns one export per shared device, all served by the
 * backend's single exporter. Permission and UI state stay on the main thread;
 * blocking native cleanup is serialized behind export on the worker. */
internal class UsbForwardingController(
    private val game: Game,
    private val host: String,
    private val pinned: X509Certificate,
    private val crypto: LimelightCryptoProvider,
    hostId: String,
    private val autoShareSession: UsbAutoShareSession,
    private val httpProvider: () -> NvHTTP?
) : AutoCloseable {
    companion object {
        private val cleanupLock = Any()
        private var lastCleanup = CompletionSignal.completed()

        fun previousCleanup(): CompletionSignal = synchronized(cleanupLock) { lastCleanup }

        private fun publishCleanup(cleanup: CompletionSignal): CompletionSignal = synchronized(cleanupLock) {
            val previous = lastCleanup
            lastCleanup = cleanup
            previous
        }
    }

    /** Where one device is in its export journey. Row status and action button are
     * derived from it, and [claimed] is what keeps Android input suppressed for as
     * long as the device may still be the one the host is using. */
    private enum class Phase(val status: Int, val claimed: Boolean, val busy: Boolean) {
        Local(R.string.usb_forward_local, false, false),
        Authorizing(R.string.usb_forward_authorizing, true, true),
        Handoff(R.string.usb_forward_handoff, true, true),
        Connecting(R.string.usb_forward_connecting, true, true),
        Sharing(R.string.usb_forward_sharing, true, false),
        Releasing(R.string.usb_forward_releasing, true, true),

        /** Native cleanup failed: the device stays claimed until a release succeeds. */
        Failed(R.string.usb_forward_failed_short, true, false),
    }

    /** Per-device export state. [phase] is main-thread state, the handles are
     * written by the worker and read by whichever callback owns them. */
    private class Forwarding(val device: UsbDevice, val automatic: Boolean = false, remember: Boolean = false) {
        var remember by mutableStateOf(remember)
        @Volatile var generation = 0L
        var request = 0

        /** Released while its own permission dialog was on screen: the answer
         * that dialog eventually returns must be discarded. */
        var cancelled = false
        var phase by mutableStateOf(Phase.Local)
        @Volatile var export: UsbIpBackend.Export? = null
        @Volatile var tunnel: UsbReverseTunnel? = null
        @Volatile var reservation: UsbDriverService.Companion.ForwardingReservation? = null
    }

    private val manager = game.getSystemService(Context.USB_SERVICE) as UsbManager
    private val backend = UsbIpBackend(game)
    private val preferences = game.getSharedPreferences("usb_forwarding_hosts", Context.MODE_PRIVATE)
    private val preferenceKey = "enabled_$hostId"
    private val autoShareRules = UsbAutoShareRules(hostId,
        { key -> preferences.getStringSet(key, emptySet()).orEmpty() },
        { key, values -> preferences.edit().putStringSet(key, values).apply() })
    private var rules by mutableStateOf(autoShareRules.load())
    private val attempted = mutableSetOf<String>()
    private var foreground = false
    private var enabled by mutableStateOf(preferences.getBoolean(preferenceKey, false))
    private var capability by mutableStateOf<UsbForwardingCapability?>(null)
    private val worker = Executors.newSingleThreadExecutor()
    private val lifecycleLock = Any()
    private val predecessorCleanup = previousCleanup()
    private val closeCompletion = CompletionSignal()
    private val permissionAction = "${game.packageName}.USB_FORWARD_PERMISSION.${UUID.randomUUID()}"
    private var capabilityGeneration = 0L
    private var nextPermissionRequest = 1
    private var devices by mutableStateOf<List<UsbDevice>>(emptyList())
    private var busy by mutableStateOf(false)
    @Volatile private var closed = false
    private var sheet: Dialog? = null
    private var message by mutableIntStateOf(R.string.usb_forward_choose)
    private var forwarding by mutableStateOf<Map<String, Forwarding>>(emptyMap())
    /** One system permission dialog at a time; everything else waits in here. */
    private val permissionQueue = ArrayDeque<Forwarding>()
    private var pendingPermission: Forwarding? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == permissionAction) {
                val request = intent.getIntExtra("request", -1)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                LimeLog.info("USB permission result for request $request: granted=$granted")
                completePermission(request, granted)
                return
            }
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                UsbDriverService.forwardingDeviceDetached(device)
            }
            refreshDevices()
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                // The newcomer cannot be told apart from an identical shared device,
                // so Android input from it is suppressed as well.
                if (siblings(device).any { it.phase.claimed }) message = R.string.usb_forward_duplicate_device
                if (identicalCount(device) > 1) {
                    val automatic = siblings(device).filter { it.automatic }
                    for (state in automatic) {
                        attempted.remove(state.device.deviceName)
                        if (state.phase == Phase.Authorizing) discardAuthorization(state)
                    }
                    // An automatic share must not silently consume Android input from
                    // a newly attached, unselected device with the same VID/PID.
                    releaseGroup(automatic.filter { it.phase != Phase.Authorizing && it.phase != Phase.Releasing },
                        R.string.usb_forward_auto_ambiguous)
                }
                attempted.remove(device.deviceName)
                startAutomaticSharing(retryCapability = true)
                return
            }
            forwarding[device.deviceName]?.let { releaseGroup(listOf(it), R.string.usb_forward_detached) }
            startAutomaticSharing()
        }
    }

    init {
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(game, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun show(): Dialog? {
        if (closed) return null
        sheet?.dismiss()
        refreshDevices()
        if (forwarding.isEmpty() && !busy) refreshCapability()
        sheet = AppActionSheet.showCustom(game) {
            UsbDevicePanel(
                // Keep a detached device visible until its release finishes.
                devices = (devices + forwarding.values.map { it.device }).distinctBy { it.deviceName }.map {
                    val state = forwarding[it.deviceName]
                    val phase = state?.phase
                    val id = identity(it)
                    UsbPanelDevice(
                        path = it.deviceName,
                        name = it.productName ?: "USB %04x:%04x".format(it.vendorId, it.productId),
                        type = UsbDeviceType.from(it),
                        sharing = phase?.claimed == true,
                        busy = phase?.busy == true,
                        // Only a release in flight cannot be interrupted; every other
                        // transition can still be stopped from its own row.
                        locked = phase == Phase.Releasing,
                        status = phase?.status ?: R.string.usb_forward_local,
                        autoShare = state?.remember == true || rules.any { rule -> rule.sameType(id) &&
                            (rule.serial == null || !manager.hasPermission(it) || rule.serial == id.serial) },
                        typeRule = rules.any { rule -> rule.sameType(id) && rule.serial == null },
                    )
                },
                busy = busy,
                message = message,
                hostName = game.pcName ?: host,
                forwardingEnabled = enabled,
                canShare = enabled && capability?.available == true,
                onEnabledChange = ::changeEnabled,
                onRetry = { refreshCapability(resetAttempts = true) },
                onShare = { path -> manager.deviceList[path]?.let { device ->
                    autoShareSession.suppressedGroups.remove(identity(device).inputGroup)
                    request(device)
                } },
                onRelease = { path -> forwarding[path]?.let {
                    autoShareSession.suppressedGroups.add(identity(it.device).inputGroup)
                    releaseGroup(listOf(it), R.string.usb_forward_choose)
                } },
                onRefresh = { refreshDevices(); startAutomaticSharing() },
                onDismiss = { sheet?.dismiss() },
                onAutoShareChange = { path, value -> manager.deviceList[path]?.let { changeAutoShare(it, value) } },
            )
        }
        return sheet
    }

    private fun changeEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        preferences.edit().putBoolean(preferenceKey, value).apply()
        capability = null
        capabilityGeneration++
        busy = false
        if (value) {
            attempted.clear()
            refreshCapability()
        }
        else {
            message = R.string.usb_forward_disabled
            releaseGroup(forwarding.values.toList(), R.string.usb_forward_disabled)
        }
    }

    private fun refreshCapability(resetAttempts: Boolean = false) {
        if (closed || busy || forwarding.isNotEmpty()) return
        if (resetAttempts) attempted.clear()
        capability = null
        if (!enabled) { message = R.string.usb_forward_disabled; return }
        if (!UsbIpBackend.isSupported()) { message = R.string.usb_forward_unsupported; return }
        val operation = ++capabilityGeneration
        busy = true
        message = R.string.usb_forward_checking
        enqueue {
            val result = runCatching {
                (httpProvider() ?: error("No active connection")).getUsbForwardingCapability()
            }
            game.runOnUiThread {
                if (closed || capabilityGeneration != operation || !enabled) return@runOnUiThread
                busy = false
                capability = result.getOrNull()
                message = when {
                    result.exceptionOrNull() is FileNotFoundException -> R.string.usb_forward_host_update
                    result.isFailure -> R.string.usb_forward_host_error
                    capability?.available == true -> R.string.usb_forward_choose
                    capability?.reason == "disabled" -> R.string.usb_forward_host_disabled
                    else -> R.string.usb_forward_host_unavailable
                }
                startAutomaticSharing()
            }
        }
    }

    private fun refreshDevices() {
        devices = manager.deviceList.values.sortedBy { it.deviceName }
    }

    private fun identity(device: UsbDevice): UsbAutoShareIdentity =
        runCatching { device.autoShareIdentity(manager) }.getOrElse { device.autoShareIdentity(manager, false) }

    private fun saveRules() {
        autoShareRules.save(rules)
    }

    private fun changeAutoShare(device: UsbDevice, value: Boolean) {
        if (closed) return
        if (value && HciUsbDeviceProbe.probe(device) != null) {
            Toast.makeText(game, R.string.usb_forward_wireless_adapter, Toast.LENGTH_LONG).show()
            return
        }
        val id = identity(device)
        if (!value) {
            rules = rules.filterNot { it.sameType(id) &&
                (id.serial == null || it.serial == null || it.serial == id.serial) }.toSet()
            saveRules()
            // Do not release a manual share, but cancel an automatic permission request.
            forwarding[device.deviceName]?.let { state ->
                state.remember = false
                if (state.automatic && state.phase == Phase.Authorizing) discardAuthorization(state)
            }
        } else {
            autoShareSession.suppressedGroups.remove(id.inputGroup)
            attempted.remove(device.deviceName)
            val state = forwarding[device.deviceName]
            if (state != null && state.phase == Phase.Authorizing) state.remember = true
            else if (manager.hasPermission(device)) rememberDevice(device)
            else request(device, automatic = true, remember = true)
            startAutomaticSharing()
        }
    }

    private fun rememberDevice(device: UsbDevice): Boolean {
        val id = runCatching { device.autoShareIdentity(manager) }.getOrNull() ?: return false
        rules = rules.filterNot { it.sameType(id) && (it.serial == null || it.serial == id.serial) }.toSet() + id
        saveRules()
        return true
    }

    fun startForeground() {
        if (closed || foreground) return
        foreground = true
        refreshDevices()
        startAutomaticSharing(retryCapability = true)
    }

    private fun startAutomaticSharing(retryCapability: Boolean = false) {
        if (closed || !foreground || !game.connected || !enabled || rules.isEmpty()) return
        if (capability?.available != true) {
            if (retryCapability) refreshCapability()
            return
        }
        if (busy) return
        refreshDevices()
        for (device in devices) {
            if (device.deviceName in attempted || forwarding.containsKey(device.deviceName)) continue
            val id = identity(device)
            // A serial can only be checked after permission. Preliminary matching only
            // authorizes the system prompt, never driver handoff or native export.
            if (!rules.any { it.sameType(id) } || id.inputGroup in autoShareSession.suppressedGroups) continue
            if (identicalCount(device) != 1) { message = R.string.usb_forward_auto_ambiguous; continue }
            attempted.add(device.deviceName)
            request(device, automatic = true)
        }
    }

    private fun discardAuthorization(state: Forwarding) {
        cancelPermission(state)
        if (forwarding[state.device.deviceName] === state) forwarding = forwarding - state.device.deviceName
        if (foreground && enabled && !busy && forwarding.values.none { it.phase == Phase.Authorizing }) {
            message = if (forwarding.values.any { it.phase == Phase.Sharing })
                R.string.usb_forward_connected else R.string.usb_forward_choose
        }
    }

    /** Devices Android cannot tell apart, because it exposes no USB path on its
     * input devices: everything with the same vendor and product IDs. */
    private fun siblings(device: UsbDevice): List<Forwarding> = forwarding.values.filter {
        it.device.vendorId == device.vendorId && it.device.productId == device.productId
    }

    /** How many devices Android cannot tell apart from this one, connected or
     *  still forwarding: their input is suppressed together, so the warning has
     *  to count all of them, not just the ones already shared. */
    private fun identicalCount(device: UsbDevice): Int = maxOf(
        siblings(device).size,
        manager.deviceList.values.count {
            it.vendorId == device.vendorId && it.productId == device.productId
        },
    )

    private fun request(device: UsbDevice, automatic: Boolean = false, remember: Boolean = false) {
        if (closed || !foreground || busy || !game.connected || !enabled || capability?.available != true) return
        if (forwarding.containsKey(device.deviceName)) return
        LimeLog.info("USB forwarding selected ${device.deviceName} (${device.vendorId}:${device.productId})")
        // Wireless adapter handoff needs a separate whole-bridge lifecycle.
        val unavailableReason = when {
            HciUsbDeviceProbe.probe(device) != null -> R.string.usb_forward_wireless_adapter
            else -> null
        }
        if (unavailableReason != null) {
            Toast.makeText(game, unavailableReason, Toast.LENGTH_LONG).show()
            LimeLog.warning("USB forwarding unavailable: ${game.getString(unavailableReason)}")
            return
        }
        val state = Forwarding(device, automatic, remember)
        forwarding = forwarding + (device.deviceName to state)
        if (identicalCount(device) > 1) message = R.string.usb_forward_duplicate_device
        if (manager.hasPermission(device)) {
            authorized(state)
        } else {
            state.phase = Phase.Authorizing
            permissionQueue.addLast(state)
            pumpPermissionQueue()
        }
    }

    /** Serializes permission prompts: the system shows one dialog at a time, and a
     * queued device keeps its [Phase.Authorizing] row while it waits. */
    private fun pumpPermissionQueue() {
        if (closed || !foreground || pendingPermission != null) return
        while (true) {
            val next = permissionQueue.removeFirstOrNull() ?: return
            if (forwarding[next.device.deviceName] !== next) continue
            pendingPermission = next
            next.request = nextPermissionRequest++
            message = R.string.usb_forward_authorizing
            game.onUsbPermissionPromptStarting()
            try {
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                manager.requestPermission(next.device, PendingIntent.getBroadcast(game, next.request,
                    Intent(permissionAction).setPackage(game.packageName).putExtra("request", next.request), flags))
            } catch (_: Exception) {
                // No dialog was shown, so the bookkeeping closes here and the
                // queue can carry on.
                pendingPermission = null
                game.onUsbPermissionPromptCompleted()
                discardAuthorization(next)
                message = R.string.usb_forward_permission_denied
                pumpPermissionQueue()
            }
            return
        }
    }

    private fun completePermission(requestId: Int, granted: Boolean) {
        // One prompt is outstanding at a time and the queue only moves on once
        // its answer is in, so anything else is a stray broadcast.
        val pending = pendingPermission ?: return
        if (requestId != pending.request) return
        pendingPermission = null
        game.onUsbPermissionPromptCompleted()
        if (pending.cancelled) {
            // Released while its dialog was up: the answer no longer applies.
            pumpPermissionQueue()
            return
        }
        if (granted && manager.hasPermission(pending.device)) authorized(pending)
        else {
            discardAuthorization(pending)
            message = R.string.usb_forward_permission_denied
        }
        pumpPermissionQueue()
    }

    /** Drops a device that is waiting for permission. A dialog that is already on
     * screen keeps its record until it answers: the system shows one at a time,
     * and its completion still has to be accounted for. */
    private fun cancelPermission(state: Forwarding) {
        if (pendingPermission === state) {
            state.cancelled = true
            return
        }
        permissionQueue.remove(state)
    }

    private fun authorized(state: Forwarding) {
        val device = state.device
        val id = if (state.automatic || state.remember)
            runCatching { device.autoShareIdentity(manager) }.getOrNull() else identity(device)
        if (closed || !foreground || !enabled || !game.connected || state.cancelled ||
            forwarding[device.deviceName] !== state || !manager.deviceList.containsKey(device.deviceName) || id == null) {
            discardAuthorization(state)
            return
        }
        if (state.remember && !rememberDevice(device)) { discardAuthorization(state); return }
        if (state.automatic && !autoShareSession.allows(id, rules, identicalCount(device))) {
            discardAuthorization(state)
            return
        }
        export(state)
    }

    private fun export(state: Forwarding) {
        val credentials = capability ?: return
        val device = state.device
        val operation = ++state.generation
        state.phase = Phase.Handoff
        enqueue {
            try {
                // Activity recreation publishes its cleanup before a replacement
                // controller can enqueue native work.
                predecessorCleanup.await()
                if (closed || state.generation != operation) return@enqueue
                state.reservation = UsbDriverService.reserveForForwarding(device)
                state.reservation!!.ready.await(10_000)
                if (closed || state.generation != operation) return@enqueue
                val exported = backend.export(device).get()
                state.export = exported
                game.runOnUiThread {
                    // A release during setup owns the cleanup from here on.
                    if (closed || state.generation != operation) return@runOnUiThread
                    state.phase = Phase.Connecting
                    val transport = UsbReverseTunnel()
                    state.tunnel = transport
                    try {
                        transport.start(host, credentials.port, credentials.token, exported,
                            crypto.getClientCertificate(), crypto.getClientPrivateKey(), pinned)
                        transport.ready().whenComplete { error ->
                            game.runOnUiThread {
                                if (!closed && state.generation == operation) {
                                    if (error != null) {
                                        LimeLog.severe("USB forwarding tunnel failed: ${error.cause ?: error}")
                                        // What the user can do about it depends on the
                                        // host's reason; see UsbForwardingFailure.
                                        releaseGroup(listOf(state), UsbForwardingFailure.messageOf(
                                            UsbReverseTunnel.rejectionReason(error)))
                                    }
                                    else {
                                        state.phase = Phase.Sharing
                                        message = R.string.usb_forward_connected
                                    }
                                }
                            }
                        }
                        transport.completion().whenComplete {
                            game.runOnUiThread {
                                // Explicit release invalidates this operation before closing.
                                // EOF here therefore also includes a late host attach failure.
                                if (!closed && state.generation == operation) {
                                    releaseGroup(listOf(state), R.string.usb_forward_failed)
                                }
                            }
                        }
                    } catch (error: Exception) {
                        LimeLog.severe("USB forwarding tunnel setup failed: $error")
                        releaseGroup(listOf(state), R.string.usb_forward_failed)
                    }
                }
            } catch (error: Exception) {
                LimeLog.severe("USB forwarding export failed: ${error.cause ?: error}")
                game.runOnUiThread {
                    if (!closed && state.generation == operation) {
                        releaseGroup(listOf(state), R.string.usb_forward_failed)
                    }
                }
            }
        }
    }

    /** Android input must not duplicate the device's USB/IP reports. Identical
     * VID/PID pairs share one identity here because Android offers no USB path
     * mapping, so releasing one member releases the whole group. */
    fun consumes(input: InputDevice?): Boolean = input != null && forwarding.values.any {
        !(it.automatic && it.phase == Phase.Authorizing) &&
            it.device.vendorId == input.vendorId && it.device.productId == input.productId
    }

    fun release(resultMessage: Int = R.string.usb_forward_choose) {
        releaseGroup(forwarding.values.toList(), resultMessage)
    }

    private fun releaseGroup(states: List<Forwarding>, resultMessage: Int) {
        if (closed) return
        // Identical devices share one Android input identity, so they stop together —
        // except a request that is still queued for permission: nothing has been
        // handed over yet, so it can simply keep waiting for its own dialog.
        val requested = states.toSet()
        val group = states.flatMap { state -> siblings(state.device).ifEmpty { listOf(state) } }
            .distinct()
            .filter { it in requested || it.phase != Phase.Authorizing }
        if (group.isEmpty()) return
        for (state in group) {
            state.generation++
            cancelPermission(state)
            state.phase = Phase.Releasing
        }
        message = R.string.usb_forward_releasing
        enqueue {
            var released = true
            for (state in group) {
                val succeeded = runCatching {
                    // SSLSocket.close() may perform network I/O on Android. Keep it
                    // off the activity thread along with the native exporter cleanup.
                    val transport = state.tunnel
                    state.tunnel = null
                    transport?.close()
                    state.export?.let { backend.release(it).get() }
                    state.export = null
                    state.reservation?.let { reservation ->
                        // Native cleanup succeeded. Restore a clean handoff immediately,
                        // or after a pending stop, without wedging the UI on local failure.
                        reservation.restoreWhenReady {
                            LimeLog.warning("Unable to restore local USB driver; reconnect the USB device: $it")
                        }
                        state.reservation = null
                    }
                }.isSuccess
                if (!succeeded) released = false
                game.runOnUiThread {
                    if (closed) return@runOnUiThread
                    // Keep ownership blocked if native cleanup failed.
                    if (succeeded && forwarding[state.device.deviceName] === state) {
                        forwarding = forwarding - state.device.deviceName
                    } else if (!succeeded) {
                        state.phase = Phase.Failed
                    }
                }
            }
            game.runOnUiThread {
                if (!closed) {
                    message = if (released) resultMessage else R.string.usb_forward_failed
                    startAutomaticSharing(retryCapability = capability == null)
                }
            }
        }
    }

    fun stopForeground() {
        foreground = false
        capabilityGeneration++
        capability = null
        busy = false
        for (state in forwarding.values) {
            if (state.phase == Phase.Handoff || state.phase == Phase.Connecting || state.phase == Phase.Sharing) {
                attempted.remove(state.device.deviceName)
            }
        }
        sheet?.dismiss()
        sheet = null
        release()
    }

    override fun close() {
        val activeDevices: List<Forwarding>
        val cleanupPredecessor: CompletionSignal
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeDevices = forwarding.values.toList()
            for (state in activeDevices) state.generation++
            cleanupPredecessor = publishCleanup(closeCompletion)
            worker.execute {
                var failure: Throwable? = null
                fun cleanup(step: () -> Unit) {
                    try { step() } catch (error: Throwable) { if (failure == null) failure = error }
                }
                // Preserve the global cleanup order across consecutive Activity
                // instances, including overlapping close callbacks.
                cleanup { cleanupPredecessor.await() }
                // Every tunnel first: the exporter stops only once the host end
                // has been dropped.
                for (state in activeDevices) cleanup { state.tunnel?.close() }
                cleanup { backend.closeAsync().get() }
                for (state in activeDevices) state.export = null
                if (failure == null) {
                    // A local driver failure reserves only its USB path. Native cleanup
                    // succeeded, so unrelated devices may use the next exporter.
                    for (state in activeDevices) {
                        state.reservation?.restoreWhenReady {
                            LimeLog.warning("Unable to restore local USB driver: $it")
                        }
                        state.reservation = null
                    }
                }
                try {
                    if (failure == null) closeCompletion.complete()
                    else closeCompletion.completeExceptionally(failure!!)
                } finally {
                    worker.shutdown()
                }
            }
        }
        sheet?.dismiss()
        sheet = null
        // The system dialog can outlive this controller; only its bookkeeping can
        // be balanced here.
        pendingPermission?.let { pendingPermission = null; game.onUsbPermissionPromptCompleted() }
        permissionQueue.clear()
        game.unregisterReceiver(receiver)
    }

    fun cleanupCompletion(): CompletionSignal = closeCompletion

    private fun enqueue(action: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (closed) false else {
            worker.execute(action)
            true
        }
    }
}
