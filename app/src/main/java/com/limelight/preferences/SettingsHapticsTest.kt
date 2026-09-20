package com.limelight.preferences

import android.app.AlertDialog
import android.content.*
import android.hardware.usb.UsbManager
import android.os.*
import androidx.preference.Preference
import com.limelight.R
import com.limelight.binding.input.driver.*
import com.limelight.binding.input.haptics.*

/** Uses the existing USB service's permission, ownership and cleanup paths. */
internal class SettingsHapticsTest(private val context: Context, private val preference: Preference) {
    private val handler = Handler(Looper.getMainLooper())
    private var binder: UsbDriverService.UsbDriverBinder? = null
    private var bound = false
    private var closed = false
    private var ownsDiagnostics = false
    private var selectedPath: String? = null
    private var activeSink: WaveformHapticsSink? = null
    private var picker: AlertDialog? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 26) { finish(R.string.haptics_test_unavailable); return }
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.filter { device ->
            HapticBackendRegistry().discover(UsbWaveformBackends.identity(device)).any {
                it.layoutMatches && it.capability.backendId == KishiSensaHapticProfile.id
            }
        }
        if (devices.isEmpty()) { finish(R.string.haptics_test_unavailable); return }
        preference.isEnabled = false
        if (devices.size == 1) connect(devices.single().deviceName)
        else {
            picker = AlertDialog.Builder(context).setTitle(R.string.title_test_experimental_haptics)
                .setItems(devices.map { "${it.productName ?: "USB"} (${it.deviceId})" }.toTypedArray()) { _, index ->
                    connect(devices[index].deviceName)
                }.setOnCancelListener { finish(R.string.summary_test_experimental_haptics) }.show()
        }
    }

    private fun connect(path: String) {
        selectedPath = path
        preference.setSummary(R.string.haptics_test_connecting)
        bound = context.bindService(Intent(context, UsbDriverService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish(R.string.haptics_test_failed)
        else handler.postDelayed({ if (!closed) finish(R.string.haptics_test_failed) }, 15000)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed) return
            val usb = service as UsbDriverService.UsbDriverBinder
            binder = usb
            if (usb.hasActiveStreamSession()) { finish(R.string.haptics_test_busy); return }
            ownsDiagnostics = true
            usb.setListener(listener)
            usb.startForDiagnostics()
        }
        override fun onServiceDisconnected(name: ComponentName) { finish(R.string.haptics_test_failed) }
    }

    private val listener = object : UsbDriverListener {
        override fun onSystemWaveformSinkAvailable(route: HapticRouteSnapshot, sink: WaveformHapticsSink,
                                                   onAssociationLost: () -> Unit) {
            handler.post {
                if (closed || route.device.instance != selectedPath || activeSink != null) return@post
                activeSink = sink
                Thread({
                    val started = runCatching { sink.start() }.getOrDefault(false)
                    handler.post {
                        if (closed) return@post
                        if (!started || sink.channelTest?.canTest != true) { finish(R.string.haptics_test_failed); return@post }
                        preference.setSummary(R.string.haptics_test_running)
                        sink.channelTest?.testChannels()
                        handler.postDelayed(object : Runnable {
                            override fun run() {
                                if (closed) return
                                if (!sink.isOperational) finish(R.string.haptics_test_failed)
                                else if (sink.channelTest?.isTesting == true) handler.postDelayed(this, 100)
                                else finish(R.string.haptics_test_finished)
                            }
                        }, 100)
                    }
                }, "SettingsHapticsTest").start()
            }
        }
        override fun onWaveformRouteChanged(route: HapticRouteSnapshot) {
            handler.post {
                if (closed || route.device.instance != selectedPath) return@post
                when (route.capability.availability) {
                    HapticAvailability.BUSY -> finish(R.string.haptics_test_busy)
                    // The service publishes NEEDS_ASSOCIATION before handing the companion
                    // sink to this listener. A local test does not need a streamed player slot.
                    HapticAvailability.FAILED, HapticAvailability.UNSUPPORTED_PATH -> finish(R.string.haptics_test_failed)
                    else -> Unit
                }
            }
        }
        override fun onSystemWaveformSinkGone(routeId: Int) {
            handler.post { if (!closed && activeSink != null) finish(R.string.haptics_test_failed) }
        }
        override fun reportControllerState(controllerId: Int, buttonFlags: Int, leftStickX: Float, leftStickY: Float,
                                           rightStickX: Float, rightStickY: Float, leftTrigger: Float, rightTrigger: Float) = Unit
        override fun deviceRemoved(controller: AbstractController) = Unit
        override fun deviceAdded(controller: AbstractController) = Unit
        override fun reportControllerMotion(controllerId: Int, motionType: Byte, x: Float, y: Float, z: Float) = Unit
    }

    private fun finish(message: Int) {
        if (closed) return
        preference.setSummary(message)
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        picker?.dismiss()
        handler.removeCallbacksAndMessages(null)
        activeSink?.channelTest?.cancelTest()
        val usb = binder
        fun released() {
            if (bound) { context.unbindService(connection); bound = false }
            preference.isEnabled = SensaStrengthPreferences.enabled(context)
        }
        if (ownsDiagnostics && usb != null) usb.stop {
            handler.post { usb.setListener(null); released() }
        } else released()
    }
}
