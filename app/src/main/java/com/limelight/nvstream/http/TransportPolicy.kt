package com.limelight.nvstream.http

import org.json.JSONObject

data class TransportAutomaticControl(val bitrate: Boolean, val fec: Boolean, val maximumKbps: Int,
                                     val activationEpoch: String = "0")
data class TransportFramePolicy(
    val revision: String, val controlEpoch: String, val source: String,
    val totalKbps: Int, val encoderKbps: Int,
    val fecBase: Int, val fecKey: Int, val fecRecovery: Int,
    val otherKbps: Int, val repairKbps: Int, val probeKbps: Int, val overheadKbps: Int,
    val automatic: TransportAutomaticControl?,
    val budgetBasis: String = "normalized", val encoderCeilingKbps: Int? = null
)
data class TransportPolicyReceipt(val policy: TransportFramePolicy, val encoderApplied: Boolean,
                                  val firstSentFrame: String?, val failure: String)
data class TransportNetworkStatistics(
    val connectionEpoch: String, val receiverClockEpoch: String, val sampleTimeUs: String?,
    val receivedPackets: Int, val missingPackets: Int, val unknownPackets: Int,
    val committedPackets: String, val committedIpBytes: String,
    val missingDeclarations: String, val lateCorrections: String, val unresolvedEvictions: String,
    val rawLossPercent: Double?, val coveragePercent: Double?,
    val fresh: Boolean, val historyTruncated: Boolean, val reason: String,
    val freshnessRemainingUs: Int? = null, val windowDurationMs: Int? = null
)
data class TransportPolicyStatus(
    val sessionId: String, val connectionEpoch: String, val controlEpoch: String,
    val accepted: TransportFramePolicy, val confirmed: TransportFramePolicy?,
    val encoderReady: Boolean, val pending: Boolean, val stopped: Boolean,
    val liveControlAvailable: Boolean, val receipts: List<TransportPolicyReceipt>,
    val networkStatistics: TransportNetworkStatistics? = null,
    val automaticFecAvailable: Boolean = false
)
data class TransportPolicySubmission(val requestId: String, val requestRevision: String,
                                     val status: TransportPolicyStatus)

/** Latest native notice is a reconciliation hint, never a policy or a lease. */
data class TransportPolicyNotification(
    val sessionId: String, val connectionEpoch: String, val sequence: String,
    val controlEpoch: String, val acceptedRevision: String,
    val appliedRevision: String?, val firstSentRevision: String?, val firstSentFrame: String?,
    val source: String, val failure: String, val flags: Int
) {
    fun valid(): Boolean {
        fun id(v: String) = TransportPolicyCodec.isIdentity(v, nonzero = true)
        if (!TransportPolicyCodec.isIdentity(sessionId,"4294967295",nonzero = true) ||
            !id(connectionEpoch) || !id(sequence) || !id(controlEpoch) || !id(acceptedRevision) ||
            flags !in 0..127 || source !in setOf("legacy","manual","googcc","local") ||
            failure !in setOf("none","unsupported","backend_failure","superseded","stopped")) return false
        if ((flags and 1 != 0) != (appliedRevision != null) ||
            (flags and 2 != 0) != (firstSentRevision != null) ||
            (firstSentRevision != null) != (firstSentFrame != null)) return false
        if (appliedRevision != null && (!id(appliedRevision) ||
            TransportPolicyCodec.compareIdentity(appliedRevision,acceptedRevision) > 0)) return false
        if (firstSentRevision != null && (appliedRevision == null || !id(firstSentRevision) ||
            !TransportPolicyCodec.isIdentity(firstSentFrame!!) ||
            TransportPolicyCodec.compareIdentity(firstSentRevision,appliedRevision) > 0)) return false
        if (flags and 4 != 0 && (failure != "none" || appliedRevision == acceptedRevision)) return false
        return flags and 16 == 0 || flags and (4 or 8 or 32 or 64) == 0
    }
    fun advances(p: TransportPolicyNotification): Boolean {
        if (!valid() || sessionId != p.sessionId || connectionEpoch != p.connectionEpoch || p.flags and 16 != 0 ||
            TransportPolicyCodec.compareIdentity(sequence,p.sequence) <= 0 ||
            TransportPolicyCodec.compareIdentity(acceptedRevision,p.acceptedRevision) < 0 ||
            TransportPolicyCodec.compareIdentity(controlEpoch,p.controlEpoch) < 0 ||
            (controlEpoch == p.controlEpoch && source != p.source)) return false
        if (p.appliedRevision != null && (appliedRevision == null ||
            TransportPolicyCodec.compareIdentity(appliedRevision,p.appliedRevision) < 0)) return false
        if (p.firstSentRevision != null && (firstSentRevision == null ||
            TransportPolicyCodec.compareIdentity(firstSentRevision,p.firstSentRevision) < 0 ||
            (firstSentRevision == p.firstSentRevision && firstSentFrame != p.firstSentFrame))) return false
        // Readiness is a current gauge and may fall during encoder rebuild.
        return true
    }
    fun satisfiedBy(s: TransportPolicyStatus): Boolean {
        if (sessionId != s.sessionId || connectionEpoch != s.connectionEpoch ||
            TransportPolicyCodec.compareIdentity(s.accepted.revision,acceptedRevision) < 0 ||
            TransportPolicyCodec.compareIdentity(s.controlEpoch,controlEpoch) < 0 ||
            (s.controlEpoch == controlEpoch && s.accepted.source != source) ||
            (appliedRevision != null && (s.confirmed == null ||
                TransportPolicyCodec.compareIdentity(s.confirmed.revision,appliedRevision) < 0)) ||
            (flags and 16 != 0 && !s.stopped)) return false
        if (firstSentRevision != null) {
            val receipt = s.receipts.find { it.policy.revision == firstSentRevision }
            if (receipt != null && (!receipt.encoderApplied || receipt.firstSentFrame != firstSentFrame)) return false
            if (receipt == null && s.accepted.revision == firstSentRevision) return false
        }
        if (s.accepted.revision == acceptedRevision && failure != "none" && !s.stopped)
            return s.receipts.find { it.policy.revision == acceptedRevision }?.failure == failure
        return true
    }
    companion object {
        fun fromNative(n: com.limelight.nvstream.jni.TransportPolicyStatusNotice): TransportPolicyNotification? {
            val sources = arrayOf("legacy","manual","googcc","local")
            val failures = arrayOf("none","unsupported","backend_failure","superseded","stopped")
            if (n.source !in sources.indices || n.failure !in failures.indices) return null
            return TransportPolicyNotification(n.sessionId,n.connectionEpoch,n.sequence,n.controlEpoch,n.acceptedRevision,
                n.appliedRevision,n.firstSentRevision,n.firstSentFrame,sources[n.source],failures[n.failure],n.flags)
                .takeIf { it.valid() }
        }
    }
}

/** Identities stay decimal strings, including uint64 values beyond Java's signed Long. */
object TransportPolicyCodec {
    private const val U64_MAX = "18446744073709551615"
    fun compareIdentity(a: String, b: String): Int =
        if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)

    fun isIdentity(value: String, maximum: String = U64_MAX, nonzero: Boolean = false): Boolean =
        value.isNotEmpty() && value.length <= maximum.length &&
            value.all { it in '0'..'9' } && (value.length == 1 || value[0] != '0') &&
            (!nonzero || value != "0") && compareIdentity(value, maximum) <= 0

    private fun id(j: JSONObject, key: String, nonzero: Boolean = true): String {
        val v = j.get(key)
        require(v is String && isIdentity(v, nonzero = nonzero)) { "Invalid $key" }
        return v
    }
    private fun int(j: JSONObject, key: String, minimum: Int = 0, maximum: Int = 800_000): Int {
        val v = j.get(key)
        require((v is Int || v is Long) && (v as Number).toLong() in minimum.toLong()..maximum.toLong()) { "Invalid $key" }
        return (v as Number).toInt()
    }
    private fun bool(j: JSONObject, key: String): Boolean {
        val v = j.get(key)
        require(v is Boolean) { "Invalid $key" }
        return v
    }
    fun networkStatistics(j: JSONObject, expectedEpoch: String): TransportNetworkStatistics {
        val version = int(j,"version",1,2)
        val connection = id(j,"connectionEpoch")
        require(connection == expectedEpoch) { "Network connection changed" }
        val clock = id(j,"receiverClockEpoch",nonzero = false)
        fun time(key: String): String? {
            require(j.has(key)) { "Missing network time" }
            return if (j.isNull(key)) null else id(j,key,nonzero = false)
        }
        val sampleTime = time("sampleTimeUs")
        val begin = time("windowBeginUs")
        val end = time("windowEndUs")
        require((begin == null) == (end == null)) { "Partial network window" }
        if (begin != null) require(sampleTime != null && compareIdentity(begin,end!!) <= 0 &&
            compareIdentity(end,sampleTime) <= 0) { "Invalid network window" }
        fun count(key: String): Int = id(j,key,nonzero = false).let {
            require(isIdentity(it,if (version == 1) "4096" else "32768")) { "Unbounded network sample" }
            it.toInt()
        }
        val received = count("receivedPackets")
        val missing = count("missingPackets")
        val unknown = count("unknownPackets")
        val sampled = count("sampledPackets")
        require(sampled == received + missing + unknown) { "Inconsistent network sample count" }
        val committed = id(j,"committedPackets",nonzero = false)
        val bytes = id(j,"committedIpBytes",nonzero = false)
        val declarations = id(j,"missingDeclarations",nonzero = false)
        val corrections = id(j,"lateCorrections",nonzero = false)
        val evictions = id(j,"unresolvedEvictions",nonzero = false)
        val fresh = bool(j,"fresh")
        require(version == 1 || j.has("freshnessRemainingUs")) { "Missing statistics lifetime" }
        val remaining = if (j.has("freshnessRemainingUs")) id(j,"freshnessRemainingUs",nonzero = false).let {
            require(isIdentity(it,"1000000",nonzero = false)) { "Invalid statistics lifetime" }
            it.toInt().also { value -> require(fresh || value == 0) { "Stale statistics cannot retain a lifetime" } }
        } else null
        val durationMs = if (begin != null) (java.math.BigInteger(end!!) - java.math.BigInteger(begin)).let {
            require(it <= java.math.BigInteger.valueOf(5000000)) { "Unbounded network window" }
            it.divide(java.math.BigInteger.valueOf(1000)).toInt()
        } else null
        val truncated = bool(j,"historyTruncated")
        val reason = j.get("reason")
        require(reason is String && reason in setOf("valid","not_negotiated","unavailable","no_samples",
            "feedback_stale","history_truncated","coverage_incomplete")) { "Unknown network availability" }
        fun percent(key: String): Double? {
            require(j.has(key)) { "Missing network percentage" }
            if (j.isNull(key)) return null
            val value = j.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..100.0) { "Invalid network percentage" }
            return value.toDouble()
        }
        val loss = percent("rawLossPercent")
        val coverage = percent("coveragePercent")
        if (reason == "valid") require(begin != null && fresh && !truncated && unknown == 0 && sampled > 0 &&
            clock != "0" && loss != null && kotlin.math.abs(loss - missing * 100.0 / sampled) < 1e-8) {
            "Loss rate lacks complete fresh coverage"
        } else require(loss == null) { "Unavailable loss rate must be null" }
        if (begin != null && sampled > 0 && !truncated) require(coverage != null &&
            kotlin.math.abs(coverage - (received + missing) * 100.0 / sampled) < 1e-8) { "Inconsistent network coverage" }
        else require(coverage == null) { "Unknown coverage must be null" }
        return TransportNetworkStatistics(connection,clock,sampleTime,received,missing,unknown,committed,bytes,
            declarations,corrections,evictions,loss,coverage,fresh,truncated,reason,remaining,durationMs)
    }
    private fun policy(j: JSONObject): TransportFramePolicy {
        val f = j.getJSONObject("fec")
        val r = j.getJSONObject("reservesKbps")
        require(j.has("automaticControl") && j.has("encoderCeilingKbps")) { "Missing policy state" }
        val basis = j.getString("budgetBasis")
        require(basis in setOf("legacy", "normalized")) { "Invalid budget basis" }
        val ceiling = if (j.isNull("encoderCeilingKbps")) null else int(j, "encoderCeilingKbps", 1)
        val a = if (j.isNull("automaticControl")) null else j.getJSONObject("automaticControl").let {
            TransportAutomaticControl(bool(it, "automaticBitrate"), bool(it, "automaticFec"), int(it, "maximumTotalKbps", 1),
                id(it, "activationEpoch", nonzero = false))
        }
        val source = j.getString("controlSource")
        require(source in setOf("legacy", "manual", "googcc", "local"))
        return TransportFramePolicy(id(j, "revision"), id(j, "controlEpoch"), source,
            int(j, "wireBudgetKbps", 1), int(j, "encoderKbps", 1),
            int(f, "base", maximum = 100), int(f, "key", maximum = 100), int(f, "recovery", maximum = 100),
            int(r, "otherTraffic"), int(r, "repair"), int(r, "probe"), int(r, "videoOverhead"), a, basis, ceiling)
    }
    fun status(j: JSONObject): TransportPolicyStatus {
        int(j, "version", 2, 2)
        val session = id(j, "sessionId")
        require(isIdentity(session, "4294967295", nonzero = true))
        val epoch = id(j, "controlEpoch")
        val accepted = policy(j.getJSONObject("accepted"))
        require(accepted.revision == id(j, "acceptedRevision") && accepted.controlEpoch == epoch)
        require(j.getString("controlSource") == accepted.source)
        require(j.has("confirmed") && j.has("encoderAppliedRevision")) { "Missing encoder state" }
        val confirmed = if (j.isNull("confirmed")) null else policy(j.getJSONObject("confirmed"))
        val applied = if (j.isNull("encoderAppliedRevision")) null else id(j, "encoderAppliedRevision")
        require(confirmed?.revision == applied)
        require(confirmed == null || compareIdentity(confirmed.revision, accepted.revision) <= 0)
        val array = j.getJSONArray("receipts")
        require(array.length() <= 32)
        val receipts = (0 until array.length()).map { index ->
            val r = array.getJSONObject(index)
            val p = policy(r)
            require(compareIdentity(p.revision, accepted.revision) <= 0)
            val failure = r.getString("failure")
            require(failure in setOf("none", "unsupported", "backend_failure", "superseded", "stopped"))
            require(r.has("firstSentFrame")) { "Missing send receipt" }
            TransportPolicyReceipt(p, bool(r, "encoderApplied"),
                if (r.isNull("firstSentFrame")) null else id(r, "firstSentFrame", nonzero = false), failure)
        }
        require(receipts.map { it.policy.revision }.distinct().size == receipts.size)
        val policies = listOf(accepted) + listOfNotNull(confirmed) + receipts.map { it.policy }
        require(policies.groupBy { it.revision }.values.all { versions -> versions.all { it == versions.first() } }) {
            "Policy revision was mutated within response"
        }
        require(confirmed == null || receipts.any { it.policy == confirmed && it.encoderApplied }) {
            "Confirmed encoder policy has no application receipt"
        }
        return TransportPolicyStatus(session, id(j, "connectionEpoch"), epoch, accepted, confirmed,
            bool(j, "encoderReady"), bool(j, "pending"), bool(j, "stopped"),
            bool(j, "experimentalLiveControlAvailable"), receipts,
            j.optJSONObject("networkStatistics")?.let { value ->
                // Optional statistics degrade independently of valid control receipts.
                runCatching { networkStatistics(value,id(j,"connectionEpoch")) }.getOrNull()
            }, j.opt("experimentalAutomaticFecAvailable") == true)
    }
    fun submission(j: JSONObject): TransportPolicySubmission {
        val requestId = j.getString("requestId")
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val revision = id(j, "requestRevision")
        val status = status(j)
        require(compareIdentity(revision, status.accepted.revision) <= 0)
        return TransportPolicySubmission(requestId, revision, status)
    }
    private fun identity(s: TransportPolicyStatus, requestId: String): JSONObject {
        require(!s.stopped && s.liveControlAvailable)
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return JSONObject().put("version", 2).put("sessionId", s.sessionId)
            .put("connectionEpoch", s.connectionEpoch).put("controlEpoch", s.controlEpoch)
            .put("expectedRevision", s.accepted.revision).put("requestId", requestId)
    }
    fun control(s: TransportPolicyStatus, requestId: String, bitrate: Boolean, fec: Boolean, maximumKbps: Int): JSONObject {
        require(maximumKbps in 1..800_000)
        require(!fec || s.automaticFecAvailable) { "Automatic FEC unavailable" }
        return identity(s, requestId).put("automaticBitrate", bitrate).put("automaticFec", fec).put("maximumTotalKbps", maximumKbps)
    }
    fun manual(s: TransportPolicyStatus, requestId: String, totalKbps: Int): JSONObject {
        require(totalKbps in 1..800_000)
        val p = s.accepted
        return identity(s, requestId).put("budget", JSONObject().put("totalKbps", totalKbps)
            .put("otherTrafficKbps", p.otherKbps).put("repairReserveKbps", p.repairKbps)
            .put("probeReserveKbps", p.probeKbps).put("videoOverheadKbps", p.overheadKbps))
            .put("fec", JSONObject().put("base", p.fecBase).put("key", p.fecKey).put("recovery", p.fecRecovery))
    }
}
