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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    // Flow 版数据流。所有计算机状态更新从这里发射。
    private val _computerUpdates = MutableSharedFlow<ComputerDetails>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val computerUpdates: SharedFlow<ComputerDetails> = _computerUpdates.asSharedFlow()

    private val activePolls = AtomicInteger(0)
    @Volatile
    private var pollingActive = false
    private val defaultNetworkLock = ReentrantLock()

    // Service 生命周期作用域，用于所有后台协程（轮询、STUN 等）。onDestroy 时 cancel。
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        if (!newPc || details.state == ComputerDetails.State.ONLINE) {
            _computerUpdates.tryEmit(details)
        }

        releaseLocalDatabaseReference()
        return true
    }

    private fun startPollingInternal() {
        pollingActive = true
        discoveryBinder?.startDiscovery(MDNS_QUERY_PERIOD_MS)

        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (SystemClock.elapsedRealtime() - tuple.lastSuccessfulPollMs > POLL_DATA_TTL_MS) {
                    LimeLog.info("Timing out polled state for ${tuple.computer.name}")
                    tuple.computer.state = ComputerDetails.State.UNKNOWN
                }
                _computerUpdates.tryEmit(tuple.computer)
                if (tuple.job == null) {
                    tuple.job = createPollingJob(tuple)
                }
            }
        }
    }

    private fun createPollingJob(tuple: PollingTuple): Job = serviceScope.launch {
        var offlineCount = 0
        while (isActive && pollingActive && tuple.job === coroutineContext[Job]) {
            try {
                val polled = runInterruptible {
                    synchronized(tuple.networkLock) {
                        runPoll(tuple.computer, false, offlineCount)
                    }
                }
                if (!polled) {
                    LimeLog.warning("${tuple.computer.name} is offline (try $offlineCount)")
                    offlineCount++
                } else {
                    tuple.lastSuccessfulPollMs = SystemClock.elapsedRealtime()
                    offlineCount = 0
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
            try {
                delay(SERVERINFO_POLLING_PERIOD_MS.toLong())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }

    inner class ComputerManagerBinder : Binder() {
        /**
         * Flow 版的计算机更新流。调用 [startPolling] 后，所有轮询结果都会从这里发射。
         */
        val computerUpdates: SharedFlow<ComputerDetails>
            get() = this@ComputerManagerService.computerUpdates

        fun startPolling() {
            startPollingInternal()
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
                tuple.job?.cancel()
                tuple.job = null
            }
        }

        return false
    }

    private fun populateExternalAddress(details: ComputerDetails) {
        val prefConfig = PreferenceConfiguration.readPreferences(this)
        if (!prefConfig.enableStun) {
            return
        }
        serviceScope.launch { performStunRequestAsync(details) }
    }

    private suspend fun performStunRequestAsync(details: ComputerDetails) {
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
                        val stunResolvedAddress = performStunQuerySuspending("stun.moonlight-stream.org", 3478, stunTimeout.toLong())
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

    private suspend fun performStunQuerySuspending(stunHost: String, stunPort: Int, timeoutMs: Long): String? {
        return withTimeoutOrNull(timeoutMs) {
            runInterruptible(Dispatchers.IO) {
                try {
                    NvConnection.findExternalAddressForMdns(stunHost, stunPort)
                } catch (e: Exception) {
                    LimeLog.warning("STUN query exception: ${e.message}")
                    null
                }
            }
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
                    if (pollingActive && tuple.job == null) {
                        tuple.job = createPollingJob(tuple)
                    }
                    return
                }
            }

            val tuple = PollingTuple(details, null)
            if (pollingActive) {
                tuple.job = createPollingJob(tuple)
            }
            pollingTuples.add(tuple)
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
                    tuple.job?.cancel()
                    tuple.job = null
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

    @Throws(InterruptedException::class)
    private fun parallelPollPc(details: ComputerDetails): ComputerDetails? {
        val candidates = listOfNotNull(
            details.localAddress,
            details.manualAddress,
            details.remoteAddress,
            details.ipv6Address
        ).distinct()

        if (candidates.isEmpty()) return null

        val diagnostics = networkDiagnostics?.getLastDiagnostics()
        val skipLan = diagnostics?.networkType == NetworkDiagnostics.NetworkType.WAN ||
                diagnostics?.networkType == NetworkDiagnostics.NetworkType.MOBILE

        // 使用 runBlocking 在当前（已是后台）线程中构造结构化并发作用域；
        // 调用方要么在 Dispatchers.IO 协程的 runInterruptible 内，要么在 mDNS 发现线程，均非主线程。
        return runBlocking {
            val results = kotlinx.coroutines.channels.Channel<Pair<ComputerDetails.AddressTuple, ComputerDetails?>>(candidates.size)

            // 并发探测每个地址
            val probeJobs = candidates.map { addr ->
                launch(Dispatchers.IO) {
                    val polled = try {
                        @Suppress("DEPRECATION")
                        val isLan = NetworkDiagnostics.isLanAddress(addr.address)
                        if (isLan && skipLan) {
                            LimeLog.info("Skipping LAN address $addr on WAN/MOBILE network")
                            null
                        } else {
                            runInterruptible { tryPollIp(details, addr) }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LimeLog.warning("Poll exception for $addr: ${e.message}")
                        null
                    }
                    results.trySend(addr to polled)
                }
            }

            var result: ComputerDetails? = null
            var primaryAddress: ComputerDetails.AddressTuple? = null
            var firstResponseTime = 0L
            var completed = 0

            try {
                while (completed < candidates.size) {
                    val pair = if (result != null) {
                        val remaining = COLLECTION_TIMEOUT_MS - (SystemClock.elapsedRealtime() - firstResponseTime)
                        if (remaining <= 0) {
                            LimeLog.info("Fast poll: collection window elapsed (collected ${result?.availableAddresses?.size} addresses)")
                            break
                        }
                        withTimeoutOrNull(remaining) { results.receiveCatching().getOrNull() }
                            ?: run {
                                LimeLog.info("Fast poll: timed out waiting for more responses")
                                null
                            }
                    } else {
                        results.receiveCatching().getOrNull()
                    } ?: break

                    completed++
                    val (addr, polled) = pair

                    if (polled != null) {
                        if (result == null) {
                            result = polled
                            primaryAddress = addr
                            polled.activeAddress = addr
                            polled.addAvailableAddress(addr)
                            firstResponseTime = SystemClock.elapsedRealtime()
                            LimeLog.info("Fast poll: got first response from address $addr")
                        } else if (addr != primaryAddress &&
                            result!!.uuid != null && polled.uuid != null &&
                            result!!.uuid == polled.uuid &&
                            result!!.availableAddresses?.contains(addr) != true
                        ) {
                            result!!.addAvailableAddress(addr)
                            LimeLog.info("Fast poll: also got response from address $addr")
                        }
                    }
                }

                if (result == null) {
                    LimeLog.info("Fast poll: all addresses failed")
                }
            } finally {
                // 提前结束（拿够地址或超时）时取消未完成的探测
                probeJobs.forEach { it.cancel() }
                results.close()
            }

            result
        }
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
                            _computerUpdates.tryEmit(tuple.computer)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    LimeLog.info("Offlining PCs due to network loss")
                    networkDiagnostics?.diagnoseNetwork()
                    synchronized(pollingTuples) {
                        for (tuple in pollingTuples) {
                            tuple.computer.state = ComputerDetails.State.OFFLINE
                            _computerUpdates.tryEmit(tuple.computer)
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

        serviceScope.cancel()

        if (discoveryBinder != null) {
            unbindService(discoveryServiceConnection)
        }

        releaseLocalDatabaseReference()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class ApplistPoller(private val computer: ComputerDetails) {
        private var job: Job? = null
        private val pollSignal = kotlinx.coroutines.channels.Channel<Unit>(
            capacity = kotlinx.coroutines.channels.Channel.CONFLATED
        )
        @Volatile
        private var receivedAppList = false

        fun pollNow() {
            pollSignal.trySend(Unit)
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

        private suspend fun awaitNextPoll(): Boolean {
            val delayMs = if (receivedAppList) APPLIST_POLLING_PERIOD_MS else APPLIST_FAILED_POLLING_RETRY_MS
            // 等待信号或超时，任一触发都继续下一轮轮询
            val result = withTimeoutOrNull(delayMs.toLong()) { pollSignal.receiveCatching() }
            // Channel 被关闭时终止轮询
            return result?.isClosed != true
        }

        fun start() {
            job = serviceScope.launch {
                var emptyAppListResponses = 0
                do {
                    if (computer.state != ComputerDetails.State.ONLINE ||
                        computer.pairState != PairingManager.PairState.PAIRED
                    ) {
                        _computerUpdates.tryEmit(computer)
                        continue
                    }

                    if (computer.uuid == null) continue

                    val tuple = getPollingTuple(computer)

                    try {
                        runInterruptible {
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

                                _computerUpdates.tryEmit(computer)
                            } else if (appList.isEmpty()) {
                                LimeLog.warning("Null app list received from ${computer.uuid}")
                            }
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } catch (e: XmlPullParserException) {
                        e.printStackTrace()
                    } catch (e: InterruptedException) {
                        LimeLog.info("App list polling interrupted for ${computer.name}")
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                } while (awaitNextPoll())
            }
        }

        fun stop() {
            job?.cancel()
            job = null
            pollSignal.close()
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
    var job: kotlinx.coroutines.Job?
) {
    val networkLock = Any()
    var lastSuccessfulPollMs: Long = 0
}

class ReachabilityTuple(
    val computer: ComputerDetails,
    val reachableAddress: String
)
