package com.limelight.computers

internal class StunQueryCache<K> {
    class Attempt<K> internal constructor(val key: K, internal val generation: Long)

    private var generation = 0L
    private var active: Attempt<K>? = null
    private var lastKey: K? = null
    private var completedAt = 0L
    private var address: String? = null

    @Synchronized
    fun cached(key: K, now: Long): String? =
        address?.takeIf { key == lastKey && now - completedAt < SUCCESS_TTL_MS }

    @Synchronized
    fun begin(key: K, now: Long): Attempt<K>? {
        if (active != null || cached(key, now) != null) return null
        if (lastKey == key && address == null && now - completedAt < FAILURE_RETRY_MS) return null
        return Attempt(key, generation).also { active = it }
    }

    @Synchronized
    fun isCurrent(attempt: Attempt<K>): Boolean = active === attempt && attempt.generation == generation

    @Synchronized
    fun finish(attempt: Attempt<K>, result: String?, now: Long): Boolean {
        if (active !== attempt) return false
        active = null
        if (attempt.generation != generation) return false
        lastKey = attempt.key
        address = result
        completedAt = now
        return true
    }

    @Synchronized
    fun invalidate() {
        generation++
        lastKey = null
        address = null
        // Keep the active slot until its worker exits, including blocked platform DNS.
    }

    companion object {
        const val SUCCESS_TTL_MS = 5 * 60_000L
        const val FAILURE_RETRY_MS = 60_000L
    }
}
