package com.limelight.binding.audio

/** A preferred-device request is not a route confirmation. Silence is valid PCM data. */
internal class MicrophoneRouteVerification(
    private val expectedId: Int?,
    private val startedAtMs: Long,
    private val requiredBytes: Int = MicrophoneConfig.BYTES_PER_FRAME * 5
) {
    enum class Result { WAITING, READY, FAILED }

    private var observedId: Int? = null
    private var receivedBytes = 0

    fun observe(nowMs: Long, routedId: Int?, bytesRead: Int): Result {
        if (bytesRead < 0 || nowMs - startedAtMs >= TIMEOUT_MS) return Result.FAILED
        if (expectedId != null && routedId != expectedId) {
            observedId = null
            receivedBytes = 0
            return Result.WAITING
        }
        if (observedId != routedId) receivedBytes = 0
        observedId = routedId
        receivedBytes += bytesRead.coerceAtLeast(0).coerceAtMost(requiredBytes)
        return if (receivedBytes >= requiredBytes) Result.READY else Result.WAITING
    }

    companion object {
        const val TIMEOUT_MS = 2000L
    }
}
