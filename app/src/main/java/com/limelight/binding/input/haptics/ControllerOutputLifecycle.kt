package com.limelight.binding.input.haptics

/** Reject output queued before retirement, including after the same slot is reused. */
internal class ControllerOutputLifecycle {
    private val generations = LongArray(16)
    private val retired = BooleanArray(16)

    @Synchronized fun capture(number: Short): Long? {
        val index = number.toInt()
        return if (index in generations.indices && !retired[index]) generations[index] else null
    }

    @Synchronized fun isCurrent(number: Short, generation: Long): Boolean =
        capture(number) == generation

    @Synchronized fun retire(number: Short) {
        val index = number.toInt()
        if (index !in generations.indices) return
        generations[index]++
        retired[index] = true
    }

    @Synchronized fun arrive(number: Short) {
        val index = number.toInt()
        if (index in generations.indices) retired[index] = false
    }
}
