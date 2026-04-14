@file:Suppress("DEPRECATION")
package com.limelight.computers

import java.io.IOException
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.HashSet
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

import com.limelight.LimeLog
import com.limelight.binding.PlatformBinding
import com.limelight.discovery.DiscoveryService
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.mdns.MdnsComputer
import com.limelight.nvstream.mdns.MdnsDiscoveryListener
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.CacheHelper
import com.limelight.utils.NetHelper
import com.limelight.utils.ServerHelper

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock

import org.xmlpull.v1.XmlPullParserException

class ComputerManagerService : Service() {

    private val binder = ComputerManagerBinder()

    private lateinit var dbManager: ComputerDatabaseManager
    private val dbRefCount = AtomicInteger(0)

    private lateinit var idManager: IdentityManager
    private val pollingTuples = LinkedList<PollingTuple>()
    private var listener: ComputerManagerListener? = null
    private val activePolls = AtomicInteger(0)
    @Volatile
    private var pollingActive = false
    private val defaultNetworkLock = ReentrantLock()

    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // 网络诊断和动态超时管理
    private lateinit var networkDiagnostics: NetworkDiagnostics
    private var timeoutManager: DynamicTimeoutManager? = null

    private var discoveryBinder: DiscoveryService.DiscoveryBinder? = null
    private val discoveryServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            synchronized(this) {
                val privateBinder = binder as DiscoveryService.DiscoveryBinder
                privateBinder.setListener(createDiscoveryListener())
                discoveryBinder = privateBinder
                (this as Object).notifyAll()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            discoveryBinder = null
        }
    }

    // Returns true if the details object was modified
    @Throws(InterruptedException::class)
    private fun runPoll(details: ComputerDetails, newPc: Boolean, offlineCount: Int): Boolean {
        if (!getLocalDatabaseReference()) {
            return false
        }

        val pollTriesBeforeOffline = if (details.state == ComputerDetails.State.UNKNOWN)
            INITIAL_POLL_TRIES else OFFLINE_POLL_TRIES

        activePolls.incrementAndGet()

        try {
            if (!pollComputer(details)) {
                if (!newPc && offlineCount < pollTriesBeforeOffline) {
                    releaseLocalDatabaseReference()
                    return false
                }
                details.state = ComputerDetails.State.OFFLINE
            }
        } catch (e: InterruptedException) {
            releaseLocalDatabaseReference()
            throw e
        } finally {
            activePolls.decrementAndGet()
        }

        if (details.state == ComputerDetails.State.ONLINE) {
            val existingComputer = dbManager.getComputerByUUID(details.uuid!!)

            if (!newPc && existingComputer == null) {
                releaseLocalDatabaseReference()
                return false
            }

            if (existingComputer != null) {
                existingComputer.update(details)
                dbManager.updateComputer(existingComputer)
            } else {
                try {
                    if (details.remoteAddress == null) {
                        val addr = InetAddress.getByName(details.activeAddress?.address)
                        if (addr.isSiteLocalAddress) {
                            populateExternalAddress(details)
                        }
                    }
                } catch (_: UnknownHostException) {
                }
                dbManager.updateComputer(details)
            }
        }

        if ((!newPc || details.state == ComputerDetails.State.ONLINE) && listener != null) {
            listener?.notifyComputerUpdated(details)
        }

        releaseLocalDatabaseReference()
        return true
    }

    private fun createPollingThread(tuple: PollingTuple): Thread {
        val t = object : Thread() {
            override fun run() {
                var offlineCount = 0
                while (!isInterrupted && pollingActive && tuple.thread === this) {
                    try {
                        synchronized(tuple.networkLock) {
                            if (!runPoll(tuple.computer, false, offlineCount)) {
                                LimeLog.warning("${tuple.computer.name} is offline (try $offlineCount)")
                                offlineCount++
                            } else {
                                tuple.lastSuccessfulPollMs = SystemClock.elapsedRealtime()
                                offlineCount = 0
                            }
                        }
                        sleep(SERVERINFO_POLLING_PERIOD_MS.toLong())
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
        t.name = "Polling thread for ${tuple.computer.name}"
        return t
    }

    inner class ComputerManagerBinder : Binder() {
        fun startPolling(listener: ComputerManagerListener) {
            pollingActive = true
            this@ComputerManagerService.listener = listener
            discoveryBinder?.startDiscovery(MDNS_QUERY_PERIOD_MS)

            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (SystemClock.elapsedRealtime() - tuple.lastSuccessfulPollMs > POLL_DATA_TTL_MS) {
                        LimeLog.info("Timing out polled state for ${tuple.computer.name}")
                        tuple.computer.state = ComputerDetails.State.UNKNOWN
                    }
                    listener.notifyComputerUpdated(tuple.computer)
                    if (tuple.thread == null) {
                        tuple.thread = createPollingThread(tuple)
                        tuple.thread?.start()
                    }
                }
            }
        }

        fun waitForReady() {
            synchronized(discoveryServiceConnection) {
                try {
                    while (discoveryBinder == null) {
                        (discoveryServiceConnection as Object).wait(1000)
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
            }
        }

        fun waitForPollingStopped() {
            while (activePolls.get() != 0) {
                try {
                    Thread.sleep(250)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
            }
        }

        @Throws(InterruptedException::class)
        fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
            return this@ComputerManagerService.addComputerBlocking(fakeDetails)
        }

        fun removeComputer(computer: ComputerDetails) {
            this@ComputerManagerService.removeComputer(computer)
        }

        fun updateComputer(computer: ComputerDetails) {
            this@ComputerManagerService.updateComputer(computer)
        }

        fun stopPolling() {
            this@ComputerManagerService.onUnbind(null)
        }

        fun createAppListPoller(computer: ComputerDetails): ApplistPoller {
            return ApplistPoller(computer)
        }

        fun getUniqueId(): String {
            return idManager.uniqueId
        }

        fun getComputer(uuid: String): ComputerDetails? {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (uuid == tuple.computer.uuid) {
                        return tuple.computer
                    }
                }
            }
            return null
        }

        fun invalidateStateForComputer(uuid: String) {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (uuid == tuple.computer.uuid) {
                        synchronized(tuple.networkLock) {
                            tuple.computer.state = ComputerDetails.State.UNKNOWN
                        }
                    }
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        discoveryBinder?.stopDiscovery()

        pollingActive = false
        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (tuple.thread != null) {
                    tuple.thread?.interrupt()
                    tuple.thread = null
                }
            }
        }

        listener = null
        return false
    }

    private fun populateExternalAddress(details: ComputerDetails) {
        val prefConfig = PreferenceConfiguration.readPreferences(this)
        if (!prefConfig.enableStun) {
            return
        }
        Thread({ performStunRequestAsync(details) }, "STUN-Request-${details.name}").start()
    }

    private fun performStunRequestAsync(details: ComputerDetails) {
        try {
            var boundToNetwork = false
            val activeNetworkIsVpn = NetHelper.isActiveNetworkVpn(this)
            val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val stunTimeout = timeoutManager?.stunTimeout ?: 5000

            LimeLog.info("Starting async STUN request for ${details.name} with timeout: ${stunTimeout}ms")

            if (activeNetworkIsVpn) {
                defaultNetworkLock.lock()
                try {
                    val networks = connMgr.allNetworks
                    for (net in networks) {
                        val netCaps = connMgr.getNetworkCapabilities(net)
                        if (netCaps != null &&
                            !netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                            !netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        ) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                if (connMgr.bindProcessToNetwork(net)) {
                                    boundToNetwork = true
                                    break
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                if (ConnectivityManager.setProcessDefaultNetwork(net)) {
                                    boundToNetwork = true
                                    break
                                }
                            }
                        }
                    }

                    if (!activeNetworkIsVpn || boundToNetwork) {
                        val startTime = System.currentTimeMillis()
                        val stunResolvedAddress = performStunQueryWithTimeout("stun.moonlight-stream.org", 3478, stunTimeout)
                        val duration = System.currentTimeMillis() - startTime

                        if (stunResolvedAddress != null) {
                            details.remoteAddress = ComputerDetails.AddressTuple(stunResolvedAddress, details.guessExternalPort())
                            LimeLog.info("STUN success for ${details.name} in ${duration}ms: $stunResolvedAddress")
                            timeoutManager?.recordSuccess("STUN-${details.name}", duration)
                        } else {
                            LimeLog.warning("STUN failed for ${details.name} after ${duration}ms, timeout: ${stunTimeout}ms")
                            timeoutManager?.recordFailure("STUN-${details.name}")
                        }
                    }
                } finally {
                    if (boundToNetwork) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connMgr.bindProcessToNetwork(null)
                        } else {
                            @Suppress("DEPRECATION")
                            ConnectivityManager.setProcessDefaultNetwork(null)
                        }
                    }
                    defaultNetworkLock.unlock()
                }
            }
        } catch (e: Exception) {
            LimeLog.warning("Async STUN request failed: ${e.message}")
        }
    }

    private fun performStunQueryWithTimeout(stunHost: String, stunPort: Int, timeoutMs: Int): String? {
        var address: String? = null
        val stunThread = Thread({
            try {
                address = NvConnection.findExternalAddressForMdns(stunHost, stunPort)
            } catch (e: Exception) {
                LimeLog.warning("STUN query exception: ${e.message}")
            }
        }, "STUN-Query")

        stunThread.start()

        return try {
            stunThread.join(timeoutMs.toLong())
            if (stunThread.isAlive) {
                LimeLog.warning("STUN query timeout after ${timeoutMs}ms")
                stunThread.interrupt()
                stunThread.join(500)
                null
            } else {
                address
            }
        } catch (e: InterruptedException) {
            stunThread.interrupt()
            null
        }
    }

    private fun createDiscoveryListener(): MdnsDiscoveryListener {
        return object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) {
                val details = ComputerDetails()

                if (computer.getLocalAddress() != null) {
                    details.localAddress = ComputerDetails.AddressTuple(computer.getLocalAddress()!!.hostAddress!!, computer.getPort())

                    if (computer.getLocalAddress() is Inet4Address) {
                        populateExternalAddress(details)
                    }
                }
                if (computer.getIpv6Address() != null) {
                    details.ipv6Address = ComputerDetails.AddressTuple(computer.getIpv6Address()!!.hostAddress!!, computer.getPort())
                }

                try {
                    if (!addComputerBlocking(details)) {
                        LimeLog.warning("Auto-discovered PC failed to respond: $details")
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                LimeLog.severe("mDNS discovery failed")
                e.printStackTrace()
            }
        }
    }

    private fun addTuple(details: ComputerDetails) {
        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (tuple.computer.uuid == details.uuid) {
                    tuple.computer.update(details)
                    if (pollingActive && tuple.thread == null) {
                        tuple.thread = createPollingThread(tuple)
                        tuple.thread?.start()
                    }
                    return
                }
            }

            val tuple = PollingTuple(details, null)
            if (pollingActive) {
                tuple.thread = createPollingThread(tuple)
            }
            pollingTuples.add(tuple)
            tuple.thread?.start()
        }
    }

    @Throws(InterruptedException::class)
    fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
        if (pollComputer(fakeDetails)) {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (tuple.computer.uuid == fakeDetails.uuid) {
                        fakeDetails.serverCert = tuple.computer.serverCert
                        break
                    }
                }
            }
            runPoll(fakeDetails, true, 0)
        }

        if (fakeDetails.state == ComputerDetails.State.ONLINE) {
            LimeLog.info("New PC (${fakeDetails.name}) is UUID ${fakeDetails.uuid}")
            addTuple(fakeDetails)
            return true
        }
        return false
    }

    fun removeComputer(computer: ComputerDetails) {
        if (!getLocalDatabaseReference()) return

        dbManager.deleteComputer(computer)

        synchronized(pollingTuples) {
            val iterator = pollingTuples.iterator()
            while (iterator.hasNext()) {
                val tuple = iterator.next()
                if (tuple.computer.uuid == computer.uuid) {
                    tuple.thread?.interrupt()
                    tuple.thread = null
                    iterator.remove()
                    break
                }
            }
        }

        releaseLocalDatabaseReference()
    }

    fun updateComputer(computer: ComputerDetails) {
        if (!getLocalDatabaseReference()) return

        dbManager.updateComputer(computer)

        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (tuple.computer.uuid == computer.uuid) {
                    tuple.computer.update(computer)
                    break
                }
            }
        }

        releaseLocalDatabaseReference()
    }

    private fun getLocalDatabaseReference(): Boolean {
        if (dbRefCount.get() == 0) return false
        dbRefCount.incrementAndGet()
        return true
    }

    private fun releaseLocalDatabaseReference() {
        if (dbRefCount.decrementAndGet() == 0) {
            dbManager.close()
        }
    }

    private fun tryPollIp(details: ComputerDetails, address: ComputerDetails.AddressTuple): ComputerDetails? {
        val startTime = System.currentTimeMillis()
        try {
            val portMatchesActiveAddress = details.state == ComputerDetails.State.ONLINE &&
                    details.activeAddress != null && address.port == details.activeAddress?.port

            val http = NvHTTP(
                address,
                if (portMatchesActiveAddress) details.httpsPort else 0,
                idManager.uniqueId, "", details.serverCert,
                PlatformBinding.getCryptoProvider(this)
            )

            val isLikelyOnline = details.state == ComputerDetails.State.ONLINE && address == details.activeAddress

            val timeoutConfig = timeoutManager?.getDynamicTimeoutConfig(address.address, isLikelyOnline)
            if (timeoutConfig != null) {
                LimeLog.info("Polling $address with timeout config: $timeoutConfig")
            }

            val newDetails = http.getComputerDetails(isLikelyOnline)

            if (newDetails.uuid == null) {
                LimeLog.severe("Polling returned no UUID!")
                timeoutManager?.recordFailure(address.address)
                return null
            }
            if (details.uuid != null && details.uuid != newDetails.uuid) {
                LimeLog.info("Polling returned the wrong PC!")
                timeoutManager?.recordFailure(address.address)
                return null
            }

            val responseTime = System.currentTimeMillis() - startTime
            timeoutManager?.recordSuccess(address.address, responseTime)
            LimeLog.info("Poll success for $address in ${responseTime}ms")

            return newDetails
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            timeoutManager?.recordFailure(address.address)
            return null
        } catch (e: IOException) {
            timeoutManager?.recordFailure(address.address)
            return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            timeoutManager?.recordFailure(address.address)
            return null
        }
    }

    private class ParallelPollTuple(
        val address: ComputerDetails.AddressTuple?,
        val existingDetails: ComputerDetails
    ) {
        @Volatile
        var complete = false
        var pollingThread: Thread? = null

        @Volatile
        var returnedDetails: ComputerDetails? = null

        fun interrupt() {
            pollingThread?.interrupt()
        }
    }

    @Throws(InterruptedException::class)
    private fun parallelPollPc(details: ComputerDetails): ComputerDetails? {
        val tuples = arrayOf(
            ParallelPollTuple(details.localAddress, details),
            ParallelPollTuple(details.manualAddress, details),
            ParallelPollTuple(details.remoteAddress, details),
            ParallelPollTuple(details.ipv6Address, details)
        )

        val sharedLock = Object()

        val uniqueAddresses = HashSet<ComputerDetails.AddressTuple>()
        for (tuple in tuples) {
            startParallelPollThreadFast(tuple, uniqueAddresses, sharedLock)
        }

        var result: ComputerDetails? = null
        var primaryAddress: ComputerDetails.AddressTuple? = null
        var firstResponseTime: Long = 0

        try {
            synchronized(sharedLock) {
                while (true) {
                    if (result == null) {
                        for (tuple in tuples) {
                            if (tuple.complete && tuple.returnedDetails != null) {
                                result = tuple.returnedDetails
                                primaryAddress = tuple.address
                                result?.activeAddress = primaryAddress
                                result?.addAvailableAddress(primaryAddress)
                                firstResponseTime = SystemClock.elapsedRealtime()
                                LimeLog.info("Fast poll: got first response from address ${tuple.address}")
                                break
                            }
                        }
                    }

                    if (result != null && primaryAddress != null) {
                        for (tuple in tuples) {
                            if (tuple.complete && tuple.returnedDetails != null &&
                                tuple.address != null && tuple.address != primaryAddress &&
                                result?.uuid != null && tuple.returnedDetails?.uuid != null &&
                                result?.uuid == tuple.returnedDetails?.uuid
                            ) {
                                if (result?.availableAddresses?.contains(tuple.address) != true) {
                                    result?.addAvailableAddress(tuple.address)
                                    LimeLog.info("Fast poll: also got response from address ${tuple.address}")
                                }
                            }
                        }

                        val elapsed = SystemClock.elapsedRealtime() - firstResponseTime
                        val allComplete = areAllComplete(tuples)

                        if (elapsed >= COLLECTION_TIMEOUT_MS || allComplete) {
                            LimeLog.info("Fast poll: collected ${result?.availableAddresses?.size} available addresses (timeout: ${elapsed >= COLLECTION_TIMEOUT_MS}, all complete: $allComplete)")
                            break
                        }
                    }

                    if (result == null && areAllComplete(tuples)) {
                        LimeLog.info("Fast poll: all addresses failed")
                        break
                    }

                    (sharedLock as Object).wait()
                }
            }
        } finally {
            for (tuple in tuples) {
                tuple.interrupt()
            }
        }

        return result
    }

    private fun areAllComplete(tuples: Array<ParallelPollTuple>): Boolean {
        for (tuple in tuples) {
            if (!tuple.complete) return false
        }
        return true
    }

    private fun startParallelPollThreadFast(
        tuple: ParallelPollTuple,
        uniqueAddresses: HashSet<ComputerDetails.AddressTuple>,
        sharedLock: Any
    ) {
        if (tuple.address == null || !uniqueAddresses.add(tuple.address)) {
            tuple.complete = true
            tuple.returnedDetails = null
            synchronized(sharedLock) {
                (sharedLock as Object).notifyAll()
            }
            return
        }

        @Suppress("DEPRECATION")
        val isLanAddress = NetworkDiagnostics.isLanAddress(tuple.address.address)
        val diagnostics = networkDiagnostics?.getLastDiagnostics()

        LimeLog.info("Starting poll thread for ${tuple.address} (LAN: $isLanAddress, Network: ${diagnostics?.networkType ?: "UNKNOWN"})")

        tuple.pollingThread = object : Thread() {
            override fun run() {
                val startTime = System.currentTimeMillis()
                var details: ComputerDetails? = null

                try {
                    if (isLanAddress && diagnostics != null &&
                        (diagnostics.networkType == NetworkDiagnostics.NetworkType.WAN ||
                                diagnostics.networkType == NetworkDiagnostics.NetworkType.MOBILE)
                    ) {
                        LimeLog.info("Skipping LAN address ${tuple.address} on WAN/MOBILE network")
                        details = null
                    } else {
                        details = tryPollIp(tuple.existingDetails, tuple.address)
                    }

                    val duration = System.currentTimeMillis() - startTime
                    if (details == null && duration < 1000) {
                        LimeLog.warning("Poll failed quickly for ${tuple.address} (${duration}ms)")
                    }
                } catch (e: Exception) {
                    LimeLog.warning("Poll thread exception for ${tuple.address}: ${e.message}")
                }

                synchronized(tuple) {
                    tuple.complete = true
                    tuple.returnedDetails = details
                    (tuple as Object).notify()
                }

                synchronized(sharedLock) {
                    (sharedLock as Object).notifyAll()
                }
            }
        }
        tuple.pollingThread?.name = "Parallel Poll - ${tuple.address} - ${tuple.existingDetails.name}"
        tuple.pollingThread?.start()
    }

    @Throws(InterruptedException::class)
    private fun pollComputer(details: ComputerDetails): Boolean {
        LimeLog.info("Starting parallel poll for ${details.name} (${details.localAddress}, ${details.remoteAddress}, ${details.manualAddress}, ${details.ipv6Address})")
        val polledDetails = parallelPollPc(details)
        LimeLog.info("Parallel poll for ${details.name} returned address: ${details.activeAddress}")

        if (polledDetails != null) {
            details.update(polledDetails)
            return true
        }
        return false
    }

    override fun onCreate() {
        networkDiagnostics = NetworkDiagnostics(this)
        timeoutManager = DynamicTimeoutManager(networkDiagnostics)

        networkDiagnostics.diagnoseNetwork()

        bindService(
            Intent(this, DiscoveryService::class.java),
            discoveryServiceConnection, Service.BIND_AUTO_CREATE
        )

        idManager = IdentityManager(this)

        dbManager = ComputerDatabaseManager(this)
        dbRefCount.set(1)

        if (!getLocalDatabaseReference()) return

        for (computer in dbManager.getAllComputers()) {
            addTuple(computer)
        }

        releaseLocalDatabaseReference()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    LimeLog.info("Resetting PC state for new available network")
                    networkDiagnostics?.diagnoseNetwork()
                    LimeLog.info("Network diagnostics after available: ${networkDiagnostics?.getLastDiagnostics()}")
                    synchronized(pollingTuples) {
                        for (tuple in pollingTuples) {
                            tuple.computer.state = ComputerDetails.State.UNKNOWN
                            listener?.notifyComputerUpdated(tuple.computer)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    LimeLog.info("Offlining PCs due to network loss")
                    networkDiagnostics?.diagnoseNetwork()
                    synchronized(pollingTuples) {
                        for (tuple in pollingTuples) {
                            tuple.computer.state = ComputerDetails.State.OFFLINE
                            listener?.notifyComputerUpdated(tuple.computer)
                        }
                    }
                }
            }

            val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connMgr.registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connMgr.unregisterNetworkCallback(networkCallback)
        }

        if (discoveryBinder != null) {
            unbindService(discoveryServiceConnection)
        }

        releaseLocalDatabaseReference()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class ApplistPoller(private val computer: ComputerDetails) {
        private var thread: Thread? = null
        private val pollEvent = Object()
        private var receivedAppList = false

        fun pollNow() {
            synchronized(pollEvent) {
                (pollEvent as Object).notify()
            }
        }

        private fun waitPollingDelay(): Boolean {
            try {
                synchronized(pollEvent) {
                    if (receivedAppList) {
                        (pollEvent as Object).wait(APPLIST_POLLING_PERIOD_MS.toLong())
                    } else {
                        (pollEvent as Object).wait(APPLIST_FAILED_POLLING_RETRY_MS.toLong())
                    }
                }
            } catch (e: InterruptedException) {
                return false
            }
            return thread?.isInterrupted == false
        }

        private fun getPollingTuple(details: ComputerDetails): PollingTuple? {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (details.uuid == tuple.computer.uuid) {
                        return tuple
                    }
                }
            }
            return null
        }

        fun start() {
            thread = object : Thread() {
                override fun run() {
                    var emptyAppListResponses = 0
                    do {
                        if (computer.state != ComputerDetails.State.ONLINE ||
                            computer.pairState != PairingManager.PairState.PAIRED
                        ) {
                            listener?.notifyComputerUpdated(computer)
                            continue
                        }

                        if (computer.uuid == null) continue

                        val tuple = getPollingTuple(computer)

                        try {
                            val http = NvHTTP(
                                ServerHelper.getCurrentAddressFromComputer(computer),
                                computer.httpsPort, idManager.uniqueId, "",
                                computer.serverCert, PlatformBinding.getCryptoProvider(this@ComputerManagerService)
                            )

                            val appList: String = if (tuple != null) {
                                synchronized(tuple.networkLock) {
                                    http.getAppListRaw()
                                }
                            } else {
                                http.getAppListRaw()
                            }

                            val list: List<NvApp> = NvHTTP.getAppListByReader(StringReader(appList))
                            if (list.isEmpty()) {
                                LimeLog.warning("Empty app list received from ${computer.uuid}")
                                emptyAppListResponses++
                            }
                            if (appList.isNotEmpty() &&
                                (list.isNotEmpty() || emptyAppListResponses >= EMPTY_LIST_THRESHOLD)
                            ) {
                                try {
                                CacheHelper.openCacheFileForOutput(cacheDir, "applist", computer.uuid!!).use { cacheOut ->
                                        CacheHelper.writeStringToOutputStream(cacheOut, appList)
                                    }
                                } catch (e: IOException) {
                                    e.printStackTrace()
                                }

                                // Trigger widget refresh
                                val refreshIntent = Intent(com.limelight.widget.GameListWidgetProvider.ACTION_REFRESH_WIDGET)
                                refreshIntent.component = ComponentName(this@ComputerManagerService, com.limelight.widget.GameListWidgetProvider::class.java)
                                refreshIntent.putExtra(com.limelight.widget.GameListWidgetProvider.EXTRA_COMPUTER_UUID, computer.uuid!!)
                                sendBroadcast(refreshIntent)

                                if (list.isNotEmpty()) {
                                    emptyAppListResponses = 0
                                }

                                computer.rawAppList = appList
                                receivedAppList = true

                                if (listener != null && thread != null) {
                                    listener?.notifyComputerUpdated(computer)
                                }
                            } else if (appList.isEmpty()) {
                                LimeLog.warning("Null app list received from ${computer.uuid}")
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } catch (e: XmlPullParserException) {
                            e.printStackTrace()
                        } catch (e: InterruptedException) {
                            LimeLog.info("App list polling thread interrupted for ${computer.name}")
                            currentThread().interrupt()
                            break
                        }
                    } while (waitPollingDelay())
                }
            }
            thread?.name = "App list polling thread for ${computer.name}"
            thread?.start()
        }

        fun stop() {
            thread?.interrupt()
            thread = null
        }
    }

    companion object {
        private const val SERVERINFO_POLLING_PERIOD_MS = 1500
        private const val APPLIST_POLLING_PERIOD_MS = 30000
        private const val APPLIST_FAILED_POLLING_RETRY_MS = 2000
        private const val MDNS_QUERY_PERIOD_MS = 1000
        private const val OFFLINE_POLL_TRIES = 3
        private const val INITIAL_POLL_TRIES = 2
        private const val EMPTY_LIST_THRESHOLD = 3
        private const val POLL_DATA_TTL_MS = 30000
        private const val COLLECTION_TIMEOUT_MS: Long = 2000
    }
}

class PollingTuple(
    val computer: ComputerDetails,
    var thread: Thread?
) {
    val networkLock = Any()
    var lastSuccessfulPollMs: Long = 0
}

class ReachabilityTuple(
    val computer: ComputerDetails,
    val reachableAddress: String
)
