package com.limelight

import android.app.Dialog
import android.app.PendingIntent
import android.annotation.SuppressLint
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
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One foreground stream owns one export. Permission and UI state stay on the main
 * thread; blocking native cleanup is serialized behind export on the worker. */
@SuppressLint("NewApi") // CompletableFuture is supplied on API 22/23 by desugaring.
class UsbForwardingController(
    private val game: Game,
    private val host: String,
    private val pinned: X509Certificate,
    private val crypto: LimelightCryptoProvider,
    hostId: String,
    private val httpProvider: () -> NvHTTP?
) : AutoCloseable {
    companion object {
        private val cleanupLock = Any()
        private var lastCleanup = CompletableFuture.completedFuture<Void>(null)

        fun previousCleanup(): CompletableFuture<Void> = synchronized(cleanupLock) { lastCleanup }

        private fun publishCleanup(cleanup: CompletableFuture<Void>): CompletableFuture<Void> = synchronized(cleanupLock) {
            val previous = lastCleanup
            lastCleanup = cleanup
            previous
        }
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
    private val closeCompletion = CompletableFuture<Void>()
    private val permissionAction = "${game.packageName}.USB_FORWARD_PERMISSION.${UUID.randomUUID()}"
    private var generation = 0L
    private var selected by mutableStateOf<UsbDevice?>(null)
    private var devices by mutableStateOf<List<UsbDevice>>(emptyList())
    private var pendingPermission = false
    private var busy by mutableStateOf(false)
    @Volatile private var closed = false
    private var sheet: Dialog? = null
    private var message by mutableIntStateOf(R.string.usb_forward_choose)
    @Volatile private var export: UsbIpBackend.Export? = null
    private var localReservation: UsbDriverService.Companion.ForwardingReservation? = null
    @Volatile private var tunnel: UsbReverseTunnel? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            refreshDevices()
            val active = selected
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && active != null &&
                !hasUniqueIdentity(active)) {
                release(R.string.usb_forward_duplicate_device)
                return
            }
            if (device != selected) return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                release(R.string.usb_forward_detached)
            } else if (intent.action == permissionAction && pendingPermission &&
                intent.getLongExtra("generation", -1) == generation) {
                completePermission()
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                    manager.hasPermission(device)) export(device, generation)
                else release(R.string.usb_forward_permission_denied)
            }
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
        if (selected == null && !busy) refreshCapability()
        sheet = AppActionSheet.showCustom(game) {
            UsbDevicePanel(
                devices = (devices + listOfNotNull(selected)).distinctBy { it.deviceName }.map {
                    UsbPanelDevice(it.deviceName,
                        it.productName ?: "USB %04x:%04x".format(it.vendorId, it.productId),
                        UsbDeviceType.from(it))
                },
                selected = selected?.deviceName,
                busy = busy,
                message = message,
                hostName = game.pcName ?: host,
                forwardingEnabled = enabled,
                canShare = enabled && capability?.available == true,
                onEnabledChange = ::changeEnabled,
                onRetry = ::refreshCapability,
                onShare = { path -> manager.deviceList[path]?.let(::request) },
                onRelease = { release() },
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
        if (value) refreshCapability() else release(R.string.usb_forward_disabled)
    }

    private fun refreshCapability() {
        if (closed || busy || selected != null) return
        capability = null
        if (!enabled) { message = R.string.usb_forward_disabled; return }
        if (!UsbIpBackend.isSupported()) { message = R.string.usb_forward_unsupported; return }
        val operation = ++generation
        busy = true
        message = R.string.usb_forward_checking
        enqueue {
            val result = runCatching {
                (httpProvider() ?: error("No active connection")).getUsbForwardingCapability()
            }
            game.runOnUiThread {
                if (closed || generation != operation || !enabled) return@runOnUiThread
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

    private fun hasUniqueIdentity(device: UsbDevice): Boolean =
        manager.deviceList.values.count {
            it.vendorId == device.vendorId && it.productId == device.productId
        } == 1

    private fun request(device: UsbDevice) {
        if (closed || busy || selected != null || !game.connected ||
            !enabled || capability?.available != true) return
        LimeLog.info("USB forwarding selected ${device.deviceName} (${device.vendorId}:${device.productId})")
        // Wireless adapter handoff needs a separate whole-bridge lifecycle.
        val unavailableReason = when {
            HciUsbDeviceProbe.probe(device) != null -> R.string.usb_forward_wireless_adapter
            !hasUniqueIdentity(device) -> R.string.usb_forward_duplicate_device
            else -> null
        }
        if (unavailableReason != null) {
            Toast.makeText(game, unavailableReason, Toast.LENGTH_LONG).show()
            LimeLog.warning("USB forwarding unavailable: ${game.getString(unavailableReason)}")
            return
        }
        selected = device
        busy = true
        generation++
        message = R.string.usb_forward_connecting
        if (manager.hasPermission(device)) {
            export(device, generation)
            return
        }
        pendingPermission = true
        message = R.string.usb_forward_authorizing
        game.onUsbPermissionPromptStarting()
        try {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            manager.requestPermission(device, PendingIntent.getBroadcast(game, generation.toInt(),
                Intent(permissionAction).setPackage(game.packageName).putExtra("generation", generation), flags))
        } catch (_: Exception) {
            release(R.string.usb_forward_permission_denied)
        }
    }

    // The app enables core library desugaring, which provides CompletableFuture
    // callbacks on API 22 and 23 even though the framework added them in API 24.
    private fun export(device: UsbDevice, operation: Long) {
        val credentials = capability ?: return
        enqueue {
            try {
                // Activity recreation publishes its cleanup before a replacement
                // controller can enqueue native work.
                predecessorCleanup.get()
                if (closed) return@enqueue
                game.runOnUiThread {
                    if (!closed && generation == operation) message = R.string.usb_forward_handoff
                }
                localReservation = UsbDriverService.reserveForForwarding(device)
                localReservation!!.ready.get(10, TimeUnit.SECONDS)
                if (closed) return@enqueue
                check(hasUniqueIdentity(device)) { "USB identity changed during local driver handoff" }
                val handle = backend.export(device).get()
                export = handle
                game.runOnUiThread {
                    if (closed || generation != operation) return@runOnUiThread
                    val transport = UsbReverseTunnel()
                    tunnel = transport
                    try {
                        transport.start(host, credentials.port, credentials.token, handle, crypto.getClientCertificate(),
                            crypto.getClientPrivateKey(), pinned).whenComplete { _, error ->
                            game.runOnUiThread {
                                if (!closed && generation == operation) {
                                    if (error != null) {
                                        LimeLog.severe("USB forwarding tunnel failed: ${error.cause ?: error}")
                                        release(R.string.usb_forward_failed)
                                    }
                                    else {
                                        busy = false
                                        message = R.string.usb_forward_connected
                                    }
                                }
                            }
                        }
                        transport.completion().whenComplete { _, _ ->
                            game.runOnUiThread {
                                // Explicit release invalidates this operation before closing.
                                // EOF here therefore also includes a late host attach failure.
                                if (!closed && generation == operation) release(R.string.usb_forward_failed)
                            }
                        }
                    } catch (error: Exception) {
                        LimeLog.severe("USB forwarding tunnel setup failed: $error")
                        release(R.string.usb_forward_failed)
                    }
                }
            } catch (error: Exception) {
                LimeLog.severe("USB forwarding export failed: ${error.cause ?: error}")
                game.runOnUiThread {
                    if (!closed && generation == operation) release(R.string.usb_forward_failed)
                }
            }
        }
    }

    /** Android input must not duplicate the device's USB/IP reports. Identical
     * VID/PID pairs are rejected above because Android offers no USB path mapping. */
    fun consumes(input: InputDevice?): Boolean {
        val device = selected ?: return false
        return input != null && input.vendorId == device.vendorId && input.productId == device.productId
    }

    private fun completePermission() {
        if (pendingPermission) {
            pendingPermission = false
            game.onUsbPermissionPromptCompleted()
        }
    }

    fun release(resultMessage: Int = R.string.usb_forward_choose) {
        if (closed) return
        val operation = ++generation
        completePermission()
        busy = true
        message = R.string.usb_forward_releasing
        val activeTunnel = tunnel
        tunnel = null
        enqueue {
            val released = runCatching {
                // SSLSocket.close() may perform network I/O on Android. Keep it
                // off the activity thread along with the native exporter cleanup.
                activeTunnel?.close()
                export?.let { backend.release(it).get() }
                export = null
                localReservation?.let { reservation ->
                    val stopped = try {
                        reservation.ready.get(10, TimeUnit.SECONDS)
                        true
                    } catch (_: TimeoutException) {
                        // Native cleanup already succeeded. A slow local stop keeps
                        // only this path reserved until its completion callback restores it.
                        reservation.restoreWhenReady {
                            LimeLog.warning("Unable to restore local USB driver: $it")
                        }
                        false
                    }
                    if (stopped) reservation.restore()
                    localReservation = null
                }
            }.isSuccess
            game.runOnUiThread {
                if (!closed && generation == operation) {
                    // Keep ownership blocked if native cleanup failed.
                    if (released) selected = null
                    busy = !released
                    message = if (released) resultMessage else R.string.usb_forward_failed
                }
            }
        }
    }

    fun stopForeground() {
        sheet?.dismiss()
        sheet = null
        release()
    }

    override fun close() {
        val activeTunnel: UsbReverseTunnel?
        val cleanupPredecessor: CompletableFuture<Void>
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            ++generation
            activeTunnel = tunnel
            tunnel = null
            cleanupPredecessor = publishCleanup(closeCompletion)
            worker.execute {
                var failure: Throwable? = null
                fun cleanup(step: () -> Unit) {
                    try { step() } catch (error: Throwable) { if (failure == null) failure = error }
                }
                // Preserve the global native-owner order across consecutive
                // Activity instances, including overlapping close callbacks.
                cleanup { cleanupPredecessor.get() }
                cleanup { activeTunnel?.close() }
                cleanup { backend.closeAsync().get() }
                export = null
                if (failure == null) {
                    // A local driver failure reserves only its USB path. Native cleanup
                    // succeeded, so unrelated devices may use the next exporter.
                    localReservation?.restoreWhenReady {
                        LimeLog.warning("Unable to restore local USB driver: $it")
                    }
                    localReservation = null
                }
                try {
                    if (failure == null) closeCompletion.complete(null)
                    else closeCompletion.completeExceptionally(failure!!)
                } finally {
                    worker.shutdown()
                }
            }
        }
        sheet?.dismiss()
        sheet = null
        completePermission()
        game.unregisterReceiver(receiver)
    }

    fun cleanupCompletion(): CompletableFuture<Void> = closeCompletion

    private fun enqueue(action: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (closed) false else {
            worker.execute(action)
            true
        }
    }
}
