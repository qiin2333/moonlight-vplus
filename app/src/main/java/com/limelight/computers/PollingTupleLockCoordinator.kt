package com.limelight.computers

/** Applies state changes only to the tuple that is current after its network lock is acquired. */
internal class PollingTupleLockCoordinator(
    private val pollingTuples: MutableList<PollingTuple>
) {
    fun withCurrent(uuid: String, action: (PollingTuple) -> Unit): Boolean {
        while (true) {
            val candidate = synchronized(pollingTuples) {
                pollingTuples.firstOrNull { uuid == it.computer.uuid }
            } ?: return false

            val applied = synchronized(candidate.networkLock) {
                synchronized(pollingTuples) {
                    val stillCurrent = pollingTuples.any {
                        it === candidate && uuid == it.computer.uuid
                    }
                    if (stillCurrent) {
                        action(candidate)
                    }
                    stillCurrent
                }
            }
            if (applied) return true
        }
    }
}
