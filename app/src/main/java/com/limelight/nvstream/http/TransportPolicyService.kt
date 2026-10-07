package com.limelight.nvstream.http

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal interface TransportPolicyTransport {
    fun query(sessionId: String, connectionEpoch: String?): TransportPolicyStatus
    fun submit(path: String, body: JSONObject): TransportPolicySubmission
}

data class TransportPolicyView(
    val status: TransportPolicyStatus? = null,
    val refreshing: Boolean = true,
    val submitting: Boolean = false,
    val requestRevision: String? = null,
    val requestError: String? = null,
    val error: String? = null,
    val readOnly: Boolean = false,
    val networkDeadlineNs: Long? = null
) {
    val canSubmit: Boolean get() = !readOnly && !refreshing && !submitting && error == null &&
        status?.let { !it.stopped && it.liveControlAvailable } == true
    // A 202 never means SDK application, and firstSentFrame never means remote delivery.
    val requestReceipt: TransportPolicyReceipt? get() = status?.receipts?.find { it.policy.revision == requestRevision }
    fun networkStatistics(nowNs: Long = System.nanoTime()): TransportNetworkStatistics? {
        val s = status?.takeUnless { it.stopped }?.networkStatistics ?: return null
        if (s.reason != "valid") return s
        // Subtraction remains valid across nanoTime wrap for this <=1s lifetime.
        return if (error != null || networkDeadlineNs == null || networkDeadlineNs - nowNs <= 0)
            s.copy(rawLossPercent = null, fresh = false,
                reason = if (networkDeadlineNs == null) "unavailable" else "feedback_stale") else s
    }
}

/** One worker owns HTTP/state. A stopped/replaced connection cannot publish delayed results. */
class TransportPolicyService internal constructor(
    private val sessionId: String,
    private val factory: () -> TransportPolicyTransport,
    private val executor: ScheduledExecutorService,
    private val readOnly: Boolean = false,
    expectedEpoch: String? = null,
    private val nanoClock: () -> Long = System::nanoTime,
    private val notificationProvider: (() -> TransportPolicyNotification?)? = null
) {
    constructor(sessionId: String, httpFactory: () -> NvHTTP, readOnly: Boolean = false, expectedEpoch: String? = null,
                notificationProvider: (() -> TransportPolicyNotification?)? = null) : this(sessionId, {
        val http = httpFactory()
        object : TransportPolicyTransport {
            override fun query(sessionId: String, connectionEpoch: String?) = http.getTransportPolicy(sessionId, connectionEpoch)
            override fun submit(path: String, body: JSONObject) = http.postTransportPolicy(path, body)
        }
    }, Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "TransportPolicy").apply { isDaemon = true } },
        readOnly, expectedEpoch, notificationProvider = notificationProvider)

    @Volatile var view = TransportPolicyView(readOnly = readOnly)
        private set
    @Volatile var listener: ((TransportPolicyView) -> Unit)? = null
    @Volatile private var stopped = false
    private var started = false
    private var submitting = false
    private var transport: TransportPolicyTransport? = null
    private var connectionEpoch: String? = expectedEpoch
    private var reconnectBudgetKbps: Int? = null
    private var notification: TransportPolicyNotification? = null
    private var notificationRefreshPending = false
    private var lastQueryBeginNs: Long? = null
    private val knownPolicies = linkedMapOf<String, TransportFramePolicy>()
    private val knownReceipts = linkedMapOf<String, TransportPolicyReceipt>()

    @Synchronized fun start() {
        if (started || stopped) return
        started = true
        executor.scheduleWithFixedDelay({ refresh() }, 0, 1, TimeUnit.SECONDS)
        if (notificationProvider != null)
            executor.scheduleWithFixedDelay({ pollNotification() }, 0, 100, TimeUnit.MILLISECONDS)
    }
    fun stop() { stopAndGetReconnectBudget() }
    /** Atomically freeze the last authoritative budget and revoke this connection's view. */
    @Synchronized fun stopAndGetReconnectBudget(): Int? {
        if (!stopped) {
            stopped = true
            listener = null
            view = TransportPolicyView(refreshing = true, error = "Connection stopped", readOnly = readOnly)
            knownPolicies.clear()
            knownReceipts.clear()
            notification = null
            notificationRefreshPending = false
            executor.shutdownNow()
        }
        return reconnectBudgetKbps
    }
    private fun pollNotification() {
        if (stopped) return
        runCatching { notificationProvider?.invoke()?.let { receiveNotification(it) } }
    }
    @Synchronized fun receiveNotification(next: TransportPolicyNotification): Boolean {
        if (stopped || !started || !next.valid() || next.sessionId != sessionId ||
            connectionEpoch == null || next.connectionEpoch != connectionEpoch ||
            notification?.let { !next.advances(it) } == true) return false
        notification = next
        if (view.status?.let { next.satisfiedBy(it) } != true)
            publish(view.copy(refreshing = true, error = "State reconciliation pending"))
        if (stopped) return false
        if (!notificationRefreshPending) {
            notificationRefreshPending = true
            val delayNs = lastQueryBeginNs?.let { (it + 250_000_000L - nanoClock()).coerceAtLeast(0) } ?: 0L
            executor.schedule({
                synchronized(this) { notificationRefreshPending = false }
                refresh()
            }, delayNs, TimeUnit.NANOSECONDS)
        }
        return true
    }
    private fun publish(next: TransportPolicyView) {
        synchronized(this) {
            if (stopped) return
            view = if (notification != null && next.status?.let { notification!!.satisfiedBy(it) } != true)
                next.copy(refreshing = true, error = "State reconciliation pending") else next
            // A UI callback failure must not cancel the periodic reconciliation task.
            runCatching { listener?.invoke(view) }
        }
    }
    @Synchronized private fun accept(next: TransportPolicyStatus, networkDeadlineNs: Long? = null) {
        if (stopped) return
        require(next.sessionId == sessionId) { "Session changed" }
        val previous = view.status
        require(connectionEpoch == null || connectionEpoch == next.connectionEpoch) { "Connection changed; reconnect required" }
        require(notification?.let { it.satisfiedBy(next) } != false) { "Query is behind authenticated notification" }
        require(previous == null || (TransportPolicyCodec.compareIdentity(next.accepted.revision, previous.accepted.revision) >= 0 &&
            TransportPolicyCodec.compareIdentity(next.controlEpoch, previous.controlEpoch) >= 0)) { "Stale policy response" }
        require(previous?.confirmed == null || next.confirmed == null ||
            TransportPolicyCodec.compareIdentity(next.confirmed.revision, previous.confirmed.revision) >= 0) { "Stale applied response" }
        val policies = listOf(next.accepted) + listOfNotNull(next.confirmed) + next.receipts.map { it.policy }
        for (policy in policies) {
            require(knownPolicies[policy.revision]?.let { it == policy } != false) { "Policy revision was mutated" }
        }
        for (receipt in next.receipts) {
            knownReceipts[receipt.policy.revision]?.let { prior ->
                require(!prior.encoderApplied || receipt.encoderApplied) { "Encoder receipt regressed" }
                require(prior.firstSentFrame == null || prior.firstSentFrame == receipt.firstSentFrame) { "Send receipt regressed or changed" }
            }
        }
        // Validate everything before updating either the mirror or its bounded history.
        policies.forEach { knownPolicies[it.revision] = it }
        next.receipts.forEach { knownReceipts[it.policy.revision] = it }
        while (knownPolicies.size > 64) knownPolicies.remove(knownPolicies.keys.first())
        while (knownReceipts.size > 64) knownReceipts.remove(knownReceipts.keys.first())
        connectionEpoch = next.connectionEpoch
        // An accepted request can fail application. Retain the last applied
        // budget through pending requests and temporary encoder rebuilds.
        if (!readOnly && next.confirmed != null) reconnectBudgetKbps = next.confirmed.totalKbps
        publish(view.copy(status = next, refreshing = false, error = null, networkDeadlineNs = networkDeadlineNs))
    }
    private fun http(): TransportPolicyTransport = transport ?: factory().also { transport = it }
    private fun query(): TransportPolicyStatus {
        val beginNs = nanoClock()
        synchronized(this) { lastQueryBeginNs = beginNs }
        val next = http().query(sessionId, connectionEpoch)
        // Read the native waterline again after a blocking HTTP call, before
        // publishing its response. No new native/UI callback is retained.
        pollNotification()
        val deadline = next.networkStatistics?.freshnessRemainingUs?.let { beginNs + it.toLong() * 1000 }
        accept(next, deadline)
        return next
    }
    private fun refresh() {
        if (stopped) return
        try { query() }
        catch (e: Exception) { publish(view.copy(refreshing = true, error = e.message ?: "Query failed")) }
    }
    @Synchronized fun setModes(bitrate: Boolean, fec: Boolean, maximumKbps: Int): Boolean {
        if (fec && view.status?.automaticFecAvailable != true) return false
        return submit { s, id ->
            "api/v2/transport-control" to TransportPolicyCodec.control(s, id, bitrate, fec, maximumKbps)
        }
    }
    fun setManualBudget(totalKbps: Int) = submit { s, id ->
        "api/v2/transport-policy" to TransportPolicyCodec.manual(s, id, totalKbps)
    }
    @Synchronized private fun submit(body: (TransportPolicyStatus, String) -> Pair<String, JSONObject>): Boolean {
        if (readOnly || stopped || submitting || !view.canSubmit) return false
        submitting = true
        publish(view.copy(submitting = true, error = null, requestError = null))
        if (stopped) return false
        executor.execute {
            try {
                if (stopped) return@execute
                // Re-read immediately before each explicit request; never silently retry a conflict/write.
                query()
                if (stopped) return@execute
                val current = synchronized(this) {
                    require(!stopped && !readOnly && !view.refreshing && view.error == null &&
                        view.status?.let { !it.stopped && it.liveControlAvailable } == true) { "Reconcile state before submitting" }
                    requireNotNull(view.status)
                }
                val id = "android-${UUID.randomUUID()}"
                val (path, json) = body(current, id)
                val reply = http().submit(path, json)
                require(reply.requestId == id) { "Request identity mismatch" }
                accept(reply.status)
                publish(view.copy(requestRevision = reply.requestRevision, error = null))
            } catch (e: Exception) {
                // Outcome may be unknown. The next poll reconciles state; it cannot identify an unacknowledged write.
                publish(view.copy(refreshing = true, requestRevision = null,
                    requestError = e.message ?: "Request failed; query state before submitting again"))
            } finally {
                synchronized(this) { submitting = false; publish(view.copy(submitting = false)) }
            }
        }
        return true
    }
}
