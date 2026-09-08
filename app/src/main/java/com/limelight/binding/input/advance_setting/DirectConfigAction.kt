package com.limelight.binding.input.advance_setting

/** Stored in the existing element action value, never resolved by display name. */
object DirectConfigAction {
    const val PREFIX = "DCS:"

    @JvmStatic
    fun encode(configId: Long): String {
        require(configId >= 0)
        return PREFIX + configId
    }

    @JvmStatic
    fun parse(value: String?): Long? {
        if (value == null || !value.startsWith(PREFIX)) return null
        val id = value.substring(PREFIX.length)
        if (id.isEmpty() || id.any { it !in '0'..'9' }) return null
        return id.toLongOrNull()?.takeIf { it >= 0 }
    }
}

/** A switch is committed only after both the button and the stream touch sequence finish. */
class DirectConfigSwitchState {
    private val presses = mutableMapOf<Any, Long?>()
    private var pendingTarget: Long? = null

    fun begin(owner: Any) {
        if (!presses.containsKey(owner)) presses[owner] = null
    }

    fun request(owner: Any, target: Long) {
        if (presses.containsKey(owner) && presses[owner] == null) presses[owner] = target
    }

    fun finish(owner: Any, cancelled: Boolean) {
        val target = presses.remove(owner)
        if (!cancelled && pendingTarget == null) pendingTarget = target
    }

    fun takeCompletedTarget(): Long? {
        val target = pendingTarget
        reset()
        return target
    }

    fun reset() {
        presses.clear()
        pendingTarget = null
    }
}
