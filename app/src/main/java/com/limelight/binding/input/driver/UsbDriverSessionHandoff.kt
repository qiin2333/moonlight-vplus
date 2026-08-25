package com.limelight.binding.input.driver

/** Coordinates controller shutdown with the latest session that wants to start afterward. */
internal class UsbDriverSessionHandoff<T> {
    data class Completion<T>(
        val finished: Boolean,
        val pendingStart: T? = null
    )

    private var generation = 0L
    private val stoppingControllerIds = mutableSetOf<Int>()
    private var pendingStart: T? = null

    val isStopping: Boolean
        get() = stoppingControllerIds.isNotEmpty()

    fun queueStart(request: T): Boolean {
        if (!isStopping) return false
        pendingStart = request
        return true
    }

    fun setPendingStart(request: T) {
        pendingStart = request
    }

    fun cancelPendingStart() {
        pendingStart = null
    }

    fun takePendingStart(): T? = pendingStart.also { pendingStart = null }

    fun pendingStartMatches(predicate: (T) -> Boolean): Boolean =
        pendingStart?.let(predicate) == true

    fun beginStop(controllerIds: Collection<Int>): Long {
        check(!isStopping)
        generation++
        stoppingControllerIds.clear()
        stoppingControllerIds.addAll(controllerIds)
        return generation
    }

    fun completeController(stopGeneration: Long, controllerId: Int): Completion<T> {
        if (stopGeneration != generation || !stoppingControllerIds.remove(controllerId)) {
            return Completion(finished = false)
        }
        if (stoppingControllerIds.isNotEmpty()) return Completion(finished = false)
        return Completion(finished = true, pendingStart = takePendingStart())
    }

    fun isStoppingController(controllerId: Int): Boolean =
        stoppingControllerIds.contains(controllerId)
}
