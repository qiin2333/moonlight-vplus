package com.limelight.gamemenu

/** Serializes host changes while keeping only the latest target selected during a request. */
internal class BitrateAdjustmentQueue {
    class Request(val bitrateKbps: Int)

    data class Completion(
        val nextRequest: Request?,
        val settleSelection: Boolean,
        val reportResult: Boolean
    )

    private var activeRequest: Request? = null
    private var pendingBitrateKbps: Int? = null

    val isBusy: Boolean
        get() = activeRequest != null

    val latestTargetKbps: Int?
        get() = pendingBitrateKbps ?: activeRequest?.bitrateKbps

    /** Forget an obsolete connection; an HTTP call already sent cannot be recalled here. */
    fun discard() {
        activeRequest = null
        pendingBitrateKbps = null
    }

    // All methods run on the UI thread; the network callback must return there first.
    fun submit(bitrateKbps: Int): Request? {
        if (activeRequest != null) {
            pendingBitrateKbps = bitrateKbps
            return null
        }
        // An explicit apply may restore a value changed by ABR while the user was dragging.
        return start(bitrateKbps)
    }

    fun complete(
        request: Request,
        confirmedBitrateKbps: Int?,
        userTracking: Boolean
    ): Completion? {
        if (activeRequest !== request) return null
        activeRequest = null
        val pending = pendingBitrateKbps
        pendingBitrateKbps = null
        val next = pending?.takeIf { it != confirmedBitrateKbps }?.let(::start)
        return Completion(
            nextRequest = next,
            settleSelection = !userTracking && next == null,
            reportResult = !userTracking && next == null &&
                (pending == null || pending == request.bitrateKbps)
        )
    }

    private fun start(bitrateKbps: Int): Request =
        Request(bitrateKbps).also { activeRequest = it }
}
