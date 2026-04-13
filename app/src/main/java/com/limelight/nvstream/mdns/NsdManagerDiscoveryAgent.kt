package com.limelight.nvstream.mdns

import android.annotation.TargetApi
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

import com.limelight.LimeLog

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class NsdManagerDiscoveryAgent(
    context: Context,
    listener: MdnsDiscoveryListener
) : MdnsDiscoveryAgent(listener) {

    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val listenerLock = Any()
    private var pendingListener: NsdManager.DiscoveryListener? = null
    private var activeListener: NsdManager.DiscoveryListener? = null
    private val serviceCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue())

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LimeLog.severe("NSD: Service discovery start failed: $errorCode")

                synchronized(listenerLock) {
                    if (pendingListener !== this) return
                    pendingListener = null
                }

                listener.notifyDiscoveryFailure(RuntimeException("onStartDiscoveryFailed(): $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                LimeLog.severe("NSD: Service discovery stop failed: $errorCode")

                synchronized(listenerLock) {
                    if (activeListener !== this) return
                    activeListener = null
                }
            }

            override fun onDiscoveryStarted(serviceType: String) {
                LimeLog.info("NSD: Service discovery started")

                synchronized(listenerLock) {
                    if (pendingListener !== this) {
                        nsdManager.stopServiceDiscovery(this)
                        return
                    }

                    pendingListener = null
                    activeListener = this
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                LimeLog.info("NSD: Service discovery stopped")

                synchronized(listenerLock) {
                    if (activeListener !== this) return
                    activeListener = null
                }
            }

            override fun onServiceFound(nsdServiceInfo: NsdServiceInfo) {
                synchronized(listenerLock) {
                    if (activeListener !== this) return

                    LimeLog.info("NSD: Machine appeared: ${nsdServiceInfo.serviceName}")

                    val serviceInfoCallback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            LimeLog.severe("NSD: Service info callback registration failed: $errorCode")
                            listener.notifyDiscoveryFailure(RuntimeException("onServiceInfoCallbackRegistrationFailed(): $errorCode"))
                        }

                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            LimeLog.info("NSD: Machine resolved: ${info.serviceName}")
                            reportNewComputer(
                                info.serviceName,
                                info.port,
                                getV4Addrs(info.hostAddresses),
                                getV6Addrs(info.hostAddresses)
                            )
                        }

                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }

                    nsdManager.registerServiceInfoCallback(nsdServiceInfo, executor, serviceInfoCallback)
                    serviceCallbacks[nsdServiceInfo.serviceName] = serviceInfoCallback
                }
            }

            override fun onServiceLost(nsdServiceInfo: NsdServiceInfo) {
                synchronized(listenerLock) {
                    if (activeListener !== this) return

                    LimeLog.info("NSD: Machine lost: ${nsdServiceInfo.serviceName}")

                    serviceCallbacks.remove(nsdServiceInfo.serviceName)?.let {
                        nsdManager.unregisterServiceInfoCallback(it)
                    }
                }
            }
        }
    }

    override fun startDiscovery(discoveryIntervalMs: Int) {
        synchronized(listenerLock) {
            if (pendingListener == null && activeListener == null) {
                pendingListener = createDiscoveryListener()
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, pendingListener)
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(listenerLock) {
            pendingListener = null

            activeListener?.let {
                nsdManager.stopServiceDiscovery(it)
                activeListener = null
            }

            for (callback in serviceCallbacks.values) {
                nsdManager.unregisterServiceInfoCallback(callback)
            }
            serviceCallbacks.clear()
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_nvstream._tcp"

        private fun getV4Addrs(addrs: List<InetAddress>): Array<Inet4Address> {
            return addrs.filterIsInstance<Inet4Address>().toTypedArray()
        }

        private fun getV6Addrs(addrs: List<InetAddress>): Array<Inet6Address> {
            return addrs.filterIsInstance<Inet6Address>().toTypedArray()
        }
    }
}
