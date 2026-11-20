package com.limelight.services

/** Owns physical key sequences until UP. Android downTime is not a per-key identifier. */
internal class KeyboardInterceptionState {
    data class Key(val deviceId: Int, val keyCode: Int, val scanCode: Int)
    enum class Route { PASS, FORWARD, DRAIN }
    data class Decision(val route: Route, val keyCode: Int)

    private val held = mutableMapOf<Key, Decision>()

    fun dispatch(key: Key, down: Boolean, repeat: Int, mappedKey: Int?): Decision {
        if (down && repeat == 0) {
            // A new DOWN also recovers from a missing UP (unplug/reconnect, for example).
            return Decision(if (mappedKey == null) Route.PASS else Route.FORWARD,
                mappedKey ?: key.keyCode).also { held[key] = it }
        }
        val decision = if (down) held[key] else held.remove(key)
        // Never start intercepting with a repeat or an unmatched UP.
        return decision ?: Decision(Route.PASS, key.keyCode)
    }

    fun pause() {
        held.entries.forEach { entry ->
            if (entry.value.route == Route.FORWARD) entry.setValue(entry.value.copy(route = Route.DRAIN))
        }
    }

    fun clear() = held.clear()
}
