package com.limelight.services

/**
 * Preserves ownership of key sequences across interception pauses and resumes.
 *
 * Call [consumeDraining] first for every event, including after interception resumes.
 * If it returns false, call [shouldPassThrough] and pass the event when that returns true.
 * Otherwise, call [recordConsumed] or [recordPassed] according to the actual decision.
 * Call [pause] when interception stops; resuming needs no additional call. All calls must
 * be made on the same thread.
 */
internal class KeyboardInterceptionDrain {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }

    private data class Key(val deviceId: Int, val keyCode: Int, val scanCode: Int)

    private val active = mutableMapOf<Key, Long>()
    private val draining = mutableMapOf<Key, Long>()
    private val passed = mutableMapOf<Key, Long>()

    fun recordConsumed(deviceId: Int, keyCode: Int, scanCode: Int, downTime: Long, action: Int) {
        val key = Key(deviceId, keyCode, scanCode)
        when (action) {
            ACTION_DOWN -> {
                // A passed sequence remains owned by the system until its UP.
                if (passed[key] == downTime) return
                passed.remove(key)
                // A repeat already being drained must not start a second active sequence.
                if (draining[key] == downTime) return
                // A new sequence on the same key supersedes an interrupted drain.
                draining.remove(key)
                active[key] = downTime
            }
            ACTION_UP -> if (active[key] == downTime) {
                active.remove(key)
            }
        }
    }

    fun pause() {
        draining.putAll(active)
        active.clear()
    }

    fun recordPassed(deviceId: Int, keyCode: Int, scanCode: Int, downTime: Long, action: Int) {
        val key = Key(deviceId, keyCode, scanCode)
        when (action) {
            ACTION_DOWN -> {
                // Do not change ownership of a sequence already consumed by the service.
                if (active[key] == downTime || draining[key] == downTime) return
                // A new sequence supersedes an interrupted sequence for this physical key.
                active.remove(key)
                draining.remove(key)
                passed[key] = downTime
            }
            ACTION_UP -> if (passed[key] == downTime) {
                passed.remove(key)
            }
        }
    }

    fun shouldPassThrough(deviceId: Int, keyCode: Int, scanCode: Int, downTime: Long, action: Int): Boolean {
        val key = Key(deviceId, keyCode, scanCode)
        val passedDownTime = passed[key] ?: return false

        if (downTime != passedDownTime) {
            if (action == ACTION_DOWN) passed.remove(key)
            return false
        }

        return when (action) {
            ACTION_DOWN -> true
            ACTION_UP -> {
                passed.remove(key)
                true
            }
            else -> false
        }
    }

    fun consumeDraining(deviceId: Int, keyCode: Int, scanCode: Int, downTime: Long, action: Int): Boolean {
        val key = Key(deviceId, keyCode, scanCode)
        val drainingDownTime = draining[key] ?: return false

        if (downTime != drainingDownTime) {
            // A new DOWN means the old sequence was interrupted, not merely delayed.
            if (action == ACTION_DOWN) draining.remove(key)
            return false
        }

        return when (action) {
            ACTION_DOWN -> true
            ACTION_UP -> {
                draining.remove(key)
                true
            }
            else -> false
        }
    }
}
