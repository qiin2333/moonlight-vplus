package com.limelight.binding.input.driver

/** USB claims belong to interface numbers, not to individual alternate settings. */
internal class UsbInterfaceClaims<T>(private val interfaceId: (T) -> Int) {
    private val claimed = linkedMapOf<Int, T>()

    @Synchronized
    fun claim(iface: T, acquire: (T) -> Boolean): Boolean {
        val id = interfaceId(iface)
        if (claimed.containsKey(id)) return true
        if (!acquire(iface)) return false
        claimed[id] = iface
        return true
    }

    /** Best effort: closing the owning connection is the final release boundary. */
    @Synchronized
    fun releaseAll(release: (T) -> Boolean, onWarning: (T, Exception?) -> Unit) {
        for (iface in claimed.values.toList().asReversed()) {
            try {
                if (!release(iface)) onWarning(iface, null)
            } catch (error: Exception) {
                onWarning(iface, error)
            }
        }
        claimed.clear()
    }
}
