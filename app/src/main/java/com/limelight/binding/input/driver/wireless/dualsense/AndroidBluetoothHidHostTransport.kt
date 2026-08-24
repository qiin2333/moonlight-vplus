package com.limelight.binding.input.driver.wireless.dualsense

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.limelight.LimeLog
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.Closeable
import java.lang.reflect.Method

/**
 * Output-only access to Android's hidden Bluetooth HID Host profile.
 *
 * Android remains responsible for pairing and for exposing the controller as an InputDevice.
 * This transport only writes DualSense output reports to the already connected HID device.
 */
internal class AndroidBluetoothHidHostTransport(
    context: Context,
    private val onReady: () -> Unit = {}
) : Closeable {
    private enum class SendKind { SEND_DATA_RAW, SEND_DATA_HEX, SET_REPORT_RAW, SET_REPORT_HEX }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val bluetoothAdapter =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var profileProxy: BluetoothProfile? = null
    private var proxyRequested = false
    private var closed = false
    private var sendDataRaw: Method? = null
    private var sendDataHex: Method? = null
    private var setReportRaw: Method? = null
    private var setReportHex: Method? = null
    private var preferredSendKind: SendKind? = null
    private val deviceSelections = mutableMapOf<String, DeviceSelection>()

    private data class DeviceSelection(
        val device: BluetoothDevice?,
        val checkedAtMs: Long
    )

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != HID_HOST_PROFILE) return
            synchronized(lock) {
                if (closed) {
                    bluetoothAdapter?.closeProfileProxy(HID_HOST_PROFILE, proxy)
                    return
                }
                profileProxy = proxy
                proxyRequested = false
                deviceSelections.clear()
                resolveMethods(proxy)
            }
            LimeLog.info("Direct DualSense Bluetooth HID host is ready")
            runCatching(onReady)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != HID_HOST_PROFILE) return
            synchronized(lock) {
                profileProxy = null
                proxyRequested = false
                preferredSendKind = null
                deviceSelections.clear()
            }
            LimeLog.warning("Direct DualSense Bluetooth HID host disconnected")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !hasPermission()) return false
        val adapter = bluetoothAdapter ?: return false
        synchronized(lock) {
            if (closed) return false
            if (profileProxy != null || proxyRequested) return true
            val bypassReady = runCatching {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/bluetooth/BluetoothHidHost;",
                    "Landroid/bluetooth/BluetoothAdapter;",
                    "Landroid/bluetooth/BluetoothDevice;"
                )
            }.getOrElse {
                LimeLog.warning("Direct DualSense hidden API setup failed: ${it.message}")
                false
            }
            if (!bypassReady) return false
            proxyRequested = runCatching {
                adapter.getProfileProxy(appContext, serviceListener, HID_HOST_PROFILE)
            }.getOrElse {
                LimeLog.warning("Direct DualSense HID host binding failed: ${it.message}")
                false
            }
            return proxyRequested
        }
    }

    @SuppressLint("MissingPermission")
    fun isReady(preferredAddress: String?): Boolean = synchronized(lock) {
        val proxy = profileProxy ?: return@synchronized false
        hasOutputMethod() && findDualSense(proxy, preferredAddress) != null
    }

    fun send(preferredAddress: String?, report: ByteArray): Boolean {
        if (report.size != DualSenseBluetoothOutputEncoder.REPORT_SIZE) return false
        if (!hasPermission()) return false
        synchronized(lock) {
            if (closed) return false
            val proxy = profileProxy ?: run {
                start()
                return false
            }
            val device = findDualSense(proxy, preferredAddress) ?: return false
            val order = buildList {
                preferredSendKind?.let(::add)
                DEFAULT_SEND_ORDER.forEach { if (it !in this) add(it) }
            }
            for (kind in order) {
                if (invoke(kind, proxy, device, report)) {
                    preferredSendKind = kind
                    return true
                }
            }
            preferredSendKind = null
            deviceSelections.remove(selectionKey(preferredAddress))
            return false
        }
    }

    override fun close() {
        val proxy = synchronized(lock) {
            if (closed) return
            closed = true
            profileProxy.also {
                profileProxy = null
                proxyRequested = false
                preferredSendKind = null
                deviceSelections.clear()
            }
        }
        if (proxy != null) {
            runCatching { bluetoothAdapter?.closeProfileProxy(HID_HOST_PROFILE, proxy) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findDualSense(proxy: BluetoothProfile, preferredAddress: String?): BluetoothDevice? {
        val key = selectionKey(preferredAddress)
        val nowMs = SystemClock.elapsedRealtime()
        deviceSelections[key]?.let { selection ->
            if (nowMs - selection.checkedAtMs < DEVICE_REFRESH_INTERVAL_MS) {
                return selection.device
            }
        }
        val devices = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
        val selected = if (preferredAddress != null) {
            // Never redirect an addressed controller's output to a different HID device.
            devices.firstOrNull {
                runCatching { it.address.equals(preferredAddress, ignoreCase = true) }.getOrDefault(false)
            }
        } else {
            val likely = devices.filter { device ->
                val name = runCatching { device.name.orEmpty() }.getOrDefault("")
                name.contains("DualSense", ignoreCase = true) ||
                    name.equals("Wireless Controller", ignoreCase = true)
            }
            likely.singleOrNull() ?: devices.singleOrNull()
        }
        deviceSelections[key] = DeviceSelection(selected, nowMs)
        return selected
    }

    private fun selectionKey(preferredAddress: String?): String =
        preferredAddress?.uppercase() ?: FALLBACK_SELECTION_KEY

    private fun resolveMethods(proxy: BluetoothProfile) {
        val classes = linkedSetOf<Class<*>>(proxy.javaClass)
        runCatching { Class.forName("android.bluetooth.BluetoothHidHost") }
            .getOrNull()?.let(classes::add)
        sendDataRaw = findMethod(classes, "sendData", ByteArray::class.java)
        sendDataHex = findMethod(classes, "sendData", String::class.java)
        setReportRaw = findSetReportMethod(classes, ByteArray::class.java)
        setReportHex = findSetReportMethod(classes, String::class.java)
        LimeLog.info(
            "Direct DualSense HID methods: sendData(raw)=${sendDataRaw != null}, " +
                "sendData(hex)=${sendDataHex != null}, setReport(raw)=${setReportRaw != null}, " +
                "setReport(hex)=${setReportHex != null}"
        )
    }

    private fun findMethod(classes: Iterable<Class<*>>, name: String, payload: Class<*>): Method? =
        findCompatibleMethod(classes) { method ->
            val parameters = method.parameterTypes
            method.name == name && parameters.size == 2 &&
                BluetoothDevice::class.java.isAssignableFrom(parameters[0]) &&
                parameters[1] == payload
        }

    private fun findSetReportMethod(classes: Iterable<Class<*>>, payload: Class<*>): Method? =
        findCompatibleMethod(classes) { method ->
            val parameters = method.parameterTypes
            method.name == "setReport" && parameters.size == 3 &&
                BluetoothDevice::class.java.isAssignableFrom(parameters[0]) &&
                isReportType(parameters[1]) && parameters[2] == payload
        }

    private fun findCompatibleMethod(
        classes: Iterable<Class<*>>,
        predicate: (Method) -> Boolean
    ): Method? {
        for (candidate in classes) {
            var current: Class<*>? = candidate
            while (current != null) {
                val currentClass = current
                val match = runCatching {
                    currentClass.declaredMethods.firstOrNull(predicate)?.apply {
                        isAccessible = true
                    }
                }.getOrNull()
                if (match != null) return match
                current = currentClass.superclass
            }
        }
        return null
    }

    private fun invoke(
        kind: SendKind,
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        report: ByteArray
    ): Boolean = runCatching {
        when (kind) {
            SendKind.SEND_DATA_RAW -> sendDataRaw?.invoke(proxy, device, report) as? Boolean
            SendKind.SEND_DATA_HEX -> sendDataHex?.invoke(proxy, device, report.toHex()) as? Boolean
            SendKind.SET_REPORT_RAW -> setReportRaw?.let {
                it.invoke(proxy, device, reportTypeArgument(it), report) as? Boolean
            }
            SendKind.SET_REPORT_HEX -> setReportHex?.let {
                it.invoke(proxy, device, reportTypeArgument(it), report.toHex()) as? Boolean
            }
        } ?: false
    }.getOrElse {
        LimeLog.warning("Direct DualSense ${kind.name} failed: ${it.cause?.message ?: it.message}")
        false
    }

    private fun hasOutputMethod(): Boolean =
        sendDataRaw != null || sendDataHex != null || setReportRaw != null || setReportHex != null

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun reportTypeArgument(method: Method): Any =
        if (method.parameterTypes[1] == Byte::class.javaPrimitiveType ||
            method.parameterTypes[1] == Byte::class.javaObjectType
        ) REPORT_TYPE_OUTPUT.toByte() else REPORT_TYPE_OUTPUT

    private fun isReportType(type: Class<*>): Boolean =
        type == Byte::class.javaPrimitiveType || type == Byte::class.javaObjectType ||
            type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType

    private fun ByteArray.toHex(): String = CharArray(size * 2).also { chars ->
        forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xFF
            chars[index * 2] = HEX_DIGITS[unsigned ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0F]
        }
    }.concatToString()

    companion object {
        fun isAvailable(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        private const val HID_HOST_PROFILE = 4
        private const val REPORT_TYPE_OUTPUT = 2
        private const val DEVICE_REFRESH_INTERVAL_MS = 500L
        private const val FALLBACK_SELECTION_KEY = "<single-dualsense>"
        private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()
        private val DEFAULT_SEND_ORDER = listOf(
            SendKind.SEND_DATA_RAW,
            SendKind.SET_REPORT_RAW,
            SendKind.SEND_DATA_HEX,
            SendKind.SET_REPORT_HEX
        )
    }
}
