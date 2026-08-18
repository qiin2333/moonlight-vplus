package com.limelight

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.driver.UsbDriverService

internal object UsbDriverExitCoordinator {
    fun exit(
        isFinishing: Boolean,
        releaseUsb: () -> Unit,
        finishActivity: () -> Unit
    ) {
        if (isFinishing) return
        releaseUsb()
        finishActivity()
    }
}

/**
 * 管理 USB 驱动服务的绑定和生命周期。
 */
class UsbDriverServiceManager(
    private val context: Context,
    private val stateListener: UsbDriverService.UsbDriverStateListener,
) {
    var controllerHandler: ControllerHandler? = null

    private var connected = false
    private var bound = false
    private var stopRequested = false
    private var binder: UsbDriverService.UsbDriverBinder? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val usbBinder = service as UsbDriverService.UsbDriverBinder
            if (!bound || stopRequested) {
                runCatching { usbBinder.stop() }
                runCatching { usbBinder.setListener(null) }
                runCatching { usbBinder.setStateListener(null) }
                return
            }
            binder = usbBinder
            controllerHandler?.let { usbBinder.setListener(it) }
            usbBinder.setStateListener(stateListener)
            connected = true
            usbBinder.start()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            connected = false
            binder = null
        }
    }

    fun bind() {
        if (bound) return
        stopRequested = false
        bound = context.bindService(
            Intent(context, UsbDriverService::class.java),
            serviceConnection,
            Service.BIND_AUTO_CREATE
        )
    }

    fun stopAndUnbind() {
        stopRequested = true
        val currentBinder = binder
        try { currentBinder?.stop() } catch (_: Exception) {}
        try { currentBinder?.setListener(null) } catch (_: Exception) {}
        try { currentBinder?.setStateListener(null) } catch (_: Exception) {}
        if (bound) {
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
        }
        bound = false
        connected = false
        binder = null
    }

    /**
     * 更新 controllerHandler 引用后重新绑定监听器。
     */
    fun refreshListener() {
        if (connected) {
            controllerHandler?.let { binder?.setListener(it) }
        }
    }

    val isConnected get() = connected
}
