package com.limelight.binding.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.annotation.SuppressLint
import android.os.Handler

/** Read-only HFP confirmation. All access is serialized on the route worker. */
@Suppress("DEPRECATION")
internal class LegacyBluetoothAudioState(private val handler: Handler) {
    private var adapter: BluetoothAdapter? = null
    private var headset: BluetoothHeadset? = null
    private var closed = false
    private var serviceAvailable = false
    private var serviceFailed = false

    fun open(context: Context) {
        if (closed) return
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                serviceFailed = true
                return
            }
            this.adapter = adapter

            val requested = try {
                adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        handler.post {
                            if (closed || profile != BluetoothProfile.HEADSET) closeProxy(adapter, proxy)
                            else {
                                headset = proxy as? BluetoothHeadset
                                serviceAvailable = true
                            }
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        handler.post { if (profile == BluetoothProfile.HEADSET) serviceAvailable = false }
                    }
                }, BluetoothProfile.HEADSET)
            } catch (e: RuntimeException) {
                // A denied/unavailable profile query must not block the communication route.
                false
            }
            if (!requested) serviceFailed = true
        } catch (_: RuntimeException) {
            serviceFailed = true
        }
    }

    /** True while the proxy has neither connected nor failed; success must not preempt it. */
    fun isServicePending(): Boolean = !closed && !serviceFailed && !serviceAvailable

    @SuppressLint("MissingPermission")
    fun isAudioConnected(targetAddress: String = ""): Boolean? {
        if (closed || !serviceAvailable) return null
        val proxy = headset ?: return null
        return try {
            val devices = proxy.connectedDevices
            val matchingDevices = if (targetAddress.isBlank()) devices else
                devices.filter { bluetoothAddressMatches(targetAddress, it.address) }
            if (targetAddress.isNotBlank() && matchingDevices.isEmpty()) false
            else matchingDevices.any { proxy.isAudioConnected(it) }
        }
        catch (_: SecurityException) { null }
        catch (_: RuntimeException) { null }
    }

    fun close() {
        closed = true
        serviceAvailable = false
        val proxy = headset
        val adapter = adapter
        headset = null
        this.adapter = null
        if (proxy != null && adapter != null) closeProxy(adapter, proxy)
    }

    private fun closeProxy(adapter: BluetoothAdapter, proxy: BluetoothProfile) {
        try { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) } catch (_: RuntimeException) { }
    }
}
