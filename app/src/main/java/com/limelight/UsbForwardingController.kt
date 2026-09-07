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
import com.limelight.binding.input.driver.UsbDriverService
import com.limelight.binding.input.driver.wireless.hci.HciUsbDeviceProbe
import com.limelight.nvstream.http.LimelightCryptoProvider
import com.limelight.usbip.UsbIpBackend
import com.limelight.usbip.UsbReverseTunnel
import com.limelight.utils.AppActionSheet
import java.security.cert.X509Certificate
import java.util.concurrent.Executors

/** One foreground stream owns one export. Permission and UI state stay on the main
 * thread; blocking native cleanup is serialized behind export on the worker. */
class UsbForwardingController(
    private val game: Game,
    private val host: String,
    private val pinned: X509Certificate,
    private val crypto: LimelightCryptoProvider,
    private val port: Int,
    private val token: String
) : AutoCloseable {
    private val manager = game.getSystemService(Context.USB_SERVICE) as UsbManager
    private val backend = UsbIpBackend(game)
    private val worker = Executors.newSingleThreadExecutor()
    private val permissionAction = "${game.packageName}.USB_FORWARD_PERMISSION"
    private var generation = 0L
    private var selected: UsbDevice? = null
    private var pendingPermission = false
    private var busy = false
    private var closed = false
    private var sheet: Dialog? = null
    private var message = R.string.usb_forward_choose
    @Volatile private var export: UsbIpBackend.Export? = null
    @Volatile private var tunnel: UsbReverseTunnel? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (closed || device != selected) return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                release()
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
        val filter = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) game.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else game.registerReceiver(receiver, filter)
    }

    fun show() {
        if (closed) return
        sheet?.dismiss()
        val devices = manager.deviceList.values.sortedBy { it.deviceName }
        val actions = if (selected != null) {
            listOf(AppActionSheet.Action(-1, game.getString(R.string.usb_forward_release)))
        } else if (busy) emptyList() else devices.mapIndexed { index, device ->
            AppActionSheet.Action(index, device.productName ?: "USB ${device.vendorId}:${device.productId}")
        }
        sheet = AppActionSheet.show(game, game.getString(R.string.usb_forward_title),
            subtitle = game.getString(message), actions = actions,
            onAction = { action ->
                if (action.id == -1) release() else devices.getOrNull(action.id)?.let(::request)
            })
    }

    private fun request(device: UsbDevice) {
        if (closed || busy || selected != null || !game.connected) return
        LimeLog.info("USB forwarding selected ${device.deviceName} (${device.vendorId}:${device.productId})")
        // A controller/HCI adapter can already own custom-driver threads. Their
        // per-device handoff is separate from the validated Android HID path.
        if (UsbDriverService.shouldClaimDevice(device, true) || HciUsbDeviceProbe.probe(device) != null ||
            manager.deviceList.values.count { it.vendorId == device.vendorId && it.productId == device.productId } != 1) {
            Toast.makeText(game, R.string.usb_forward_local_owner, Toast.LENGTH_LONG).show()
            LimeLog.warning("USB forwarding rejected because a local input driver owns the device")
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

    private fun export(device: UsbDevice, operation: Long) {
        worker.execute {
            try {
                val handle = backend.export(device).get()
                export = handle
                game.runOnUiThread {
                    if (closed || generation != operation) return@runOnUiThread
                    val transport = UsbReverseTunnel()
                    tunnel = transport
                    try {
                        transport.start(host, port, token, handle, crypto.getClientCertificate(),
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
                                        Toast.makeText(game, message, Toast.LENGTH_SHORT).show()
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
        tunnel?.close()
        tunnel = null
        worker.execute {
            val released = runCatching {
                export?.let { backend.release(it).get() }
                export = null
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
        if (closed) return
        stopForeground()
        closed = true
        game.unregisterReceiver(receiver)
        worker.execute { backend.close() }
        worker.shutdown()
    }
}
