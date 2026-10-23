package com.limelight.binding.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler

/** Read-only HFP confirmation for API 23-30. All access is serialized on the route worker. */
@Suppress("DEPRECATION")
internal class LegacyBluetoothAudioState(private val handler: Handler) {
    private var adapter: BluetoothAdapter? = null
    private var headset: BluetoothHeadset? = null
    private var closed = false
    private var serviceAvailable = false

    fun open(context: Context) {
        if (Build.VERSION.SDK_INT >= 31 || closed) return
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            this.adapter = adapter
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
        } catch (_: RuntimeException) {
            // A denied/unavailable profile query must not replace the SCO broadcast path.
        }
    }

    fun isAudioConnected(): Boolean? {
        if (Build.VERSION.SDK_INT >= 31 || closed || !serviceAvailable) return null
        val proxy = headset ?: return null
        return try { proxy.connectedDevices.any { proxy.isAudioConnected(it) } }
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
        if (Build.VERSION.SDK_INT >= 31) return
        try { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) } catch (_: RuntimeException) { }
    }
}
