package com.limelight.binding.input

internal class StartWheelOwnerGate {
    private var owner: Any? = null

    @Synchronized
    fun tryClaim(candidate: Any): Boolean {
        if (owner != null && owner !== candidate) return false
        owner = candidate
        return true
    }

    @Synchronized
    fun isOwner(candidate: Any): Boolean = owner === candidate

    @Synchronized
    fun release(candidate: Any): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }

    @Synchronized
    fun clear(): Boolean {
        val hadOwner = owner != null
        owner = null
        return hadOwner
    }
}
