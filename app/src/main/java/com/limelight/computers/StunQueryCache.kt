package com.limelight.computers

import java.util.concurrent.atomic.AtomicReference

internal class StunQueryCache<K> {
    class Attempt<K> internal constructor(val key: K, internal val generation: Long)

    private data class State<K>(
        val generation: Long = 0,
        val active: Attempt<K>? = null,
        val lastKey: K? = null,
        val completedAt: Long = 0,
        val address: String? = null
    )
    private val state = AtomicReference(State<K>())

    fun cached(key: K, now: Long): String? {
        val current = state.get()
        return current.address?.takeIf { key == current.lastKey && now - current.completedAt < SUCCESS_TTL_MS }
    }

    fun begin(key: K, now: Long): Attempt<K>? {
        while (true) {
            val current = state.get()
            if (current.active != null) return null
            if (current.lastKey == key && now - current.completedAt <
                (if (current.address != null) SUCCESS_TTL_MS else FAILURE_RETRY_MS)) return null
            val attempt = Attempt(key, current.generation)
            if (state.compareAndSet(current, current.copy(active = attempt))) return attempt
        }
    }

    fun isCurrent(attempt: Attempt<K>): Boolean {
        val current = state.get()
        return current.active === attempt && attempt.generation == current.generation
    }

    fun finish(attempt: Attempt<K>, result: String?, now: Long): Boolean {
        while (true) {
            val current = state.get()
            if (current.active !== attempt) return false
            val valid = attempt.generation == current.generation
            val next = if (valid) current.copy(active = null, lastKey = attempt.key, address = result, completedAt = now)
                else current.copy(active = null)
            if (state.compareAndSet(current, next)) return valid
        }
    }

    fun invalidate() {
        // Keep the active slot until its worker exits, including blocked platform DNS.
        while (true) {
            val current = state.get()
            if (state.compareAndSet(current, current.copy(generation = current.generation + 1, lastKey = null, address = null))) return
        }
    }

    companion object {
        const val SUCCESS_TTL_MS = 5 * 60_000L
        const val FAILURE_RETRY_MS = 60_000L
    }
}
