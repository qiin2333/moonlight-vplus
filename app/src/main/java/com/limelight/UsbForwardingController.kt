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
import android.os.Handler
import android.os.Looper
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
class UsbForwardingController(
    private val game: Game,
    private val host: String,
    private val pinned: X509Certificate,
    private val crypto: LimelightCryptoProvider,
    hostId: String,
    private val httpProvider: () -> NvHTTP?
) : AutoCloseable {
    companion object {
        /** Covers the result broadcast the dialog sends as it finishes, so an
         *  answered prompt is never mistaken for a dismissed one. */
        private const val PROMPT_DISMISS_GRACE_MS = 400L

        /** Backstop for a prompt whose dialog never hands focus back, which is
         *  the only thing left to tell a dismissal from an open dialog by. */
        private const val PROMPT_TIMEOUT_MS = 30_000L

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
    private class Forwarding(val device: UsbDevice) {
        var generation = 0L
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
    private val promptHandler = Handler(Looper.getMainLooper())
    private var promptTimeout: Runnable? = null
    /** Whether the stream activity holds window focus, i.e. no system dialog
     *  is on top of it; the stream starts focused. */
    private var hasFocus = true

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
                return
            }
            forwarding[device.deviceName]?.let { releaseGroup(listOf(it), R.string.usb_forward_detached) }
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
                    val phase = forwarding[it.deviceName]?.phase
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
                    )
                },
                busy = busy,
                message = message,
                hostName = game.pcName ?: host,
                forwardingEnabled = enabled,
                canShare = enabled && capability?.available == true,
                onEnabledChange = ::changeEnabled,
                onRetry = ::refreshCapability,
                onShare = { path -> manager.deviceList[path]?.let(::request) },
                onRelease = { path -> forwarding[path]?.let { releaseGroup(listOf(it), R.string.usb_forward_choose) } },
                onRefresh = { refreshDevices() },
                onDismiss = { sheet?.dismiss() }
            )
        }
        return sheet
    }

    private fun changeEnabled(value: Boolean) {
        if (closed || enabled == value) return
        enabled = value
        preferences.edit().putBoolean(preferenceKey, value).apply()
        capability = null
        if (value) refreshCapability()
        else releaseGroup(forwarding.values.toList(), R.string.usb_forward_disabled)
    }

    private fun refreshCapability() {
        if (closed || busy || forwarding.isNotEmpty()) return
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
            }
        }
    }

    private fun refreshDevices() {
        devices = manager.deviceList.values.sortedBy { it.deviceName }
    }

    /** Devices Android cannot tell apart, because it exposes no USB path on its
     * input devices: everything with the same vendor and product IDs. */
    private fun siblings(device: UsbDevice): List<Forwarding> = forwarding.values.filter {
        it.device.vendorId == device.vendorId && it.device.productId == device.productId
    }

    private fun request(device: UsbDevice) {
        if (closed || busy || !game.connected || !enabled || capability?.available != true) return
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
        val state = Forwarding(device)
        forwarding = forwarding + (device.deviceName to state)
        if (siblings(device).size > 1) message = R.string.usb_forward_duplicate_device
        if (manager.hasPermission(device)) {
            export(state)
        } else {
            state.phase = Phase.Authorizing
            permissionQueue.addLast(state)
            pumpPermissionQueue()
        }
    }

    /** Serializes permission prompts: the system shows one dialog at a time, and a
     * queued device keeps its [Phase.Authorizing] row while it waits. */
    private fun pumpPermissionQueue() {
        if (closed || pendingPermission != null) return
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
                schedulePromptTimeout(next.request)
            } catch (_: Exception) {
                // No dialog was shown, so the bookkeeping closes here and the
                // queue can carry on.
                pendingPermission = null
                game.onUsbPermissionPromptCompleted()
                releaseGroup(listOf(next), R.string.usb_forward_permission_denied)
                pumpPermissionQueue()
            }
            return
        }
    }

    private fun completePermission(request: Int, granted: Boolean) {
        val pending = pendingPermission ?: return
        if (request != pending.request) return
        clearPromptTimeout()
        pendingPermission = null
        game.onUsbPermissionPromptCompleted()
        if (pending.cancelled) {
            // Released while its dialog was up: the answer no longer applies.
            pumpPermissionQueue()
            return
        }
        if (granted && manager.hasPermission(pending.device)) export(pending)
        else releaseGroup(listOf(pending), R.string.usb_forward_permission_denied)
        pumpPermissionQueue()
    }

    /** Called when the stream regains window focus, which means any system
     *  dialog on top of it is gone. A prompt with no answer by then was
     *  dismissed rather than decided: settle it as not granted, or it would
     *  block every later request until the app restarted. The grace covers the
     *  result broadcast, which the dialog sends as it finishes, and focus is
     *  re-checked when it expires: losing it again means the dialog is only
     *  late, not dismissed - our own sheet closing hands focus back before the
     *  system dialog has taken it. */
    fun onFocusChanged(focused: Boolean) {
        hasFocus = focused
        if (closed || !focused) return
        val request = pendingPermission?.request ?: return
        promptHandler.postDelayed({
            if (closed || !hasFocus || pendingPermission?.request != request) return@postDelayed
            LimeLog.warning("USB permission prompt $request was dismissed; treating it as not granted")
            completePermission(request, false)
        }, PROMPT_DISMISS_GRACE_MS)
    }

    /** Backstop for a prompt whose dialog never hands focus back at all. */
    private fun schedulePromptTimeout(request: Int) {
        clearPromptTimeout()
        val timeout = Runnable {
            promptTimeout = null
            if (closed) return@Runnable
            if (pendingPermission?.request != request) return@Runnable
            LimeLog.warning("USB permission prompt $request was never answered; treating it as not granted")
            completePermission(request, false)
        }
        promptTimeout = timeout
        promptHandler.postDelayed(timeout, PROMPT_TIMEOUT_MS)
    }

    private fun clearPromptTimeout() {
        promptTimeout?.let { promptHandler.removeCallbacks(it) }
        promptTimeout = null
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
                if (closed) return@enqueue
                state.reservation = UsbDriverService.reserveForForwarding(device)
                state.reservation!!.ready.await(10_000)
                if (closed) return@enqueue
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
                if (!closed) message = if (released) resultMessage else R.string.usb_forward_failed
            }
        }
    }

    fun stopForeground() {
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
        clearPromptTimeout()
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
