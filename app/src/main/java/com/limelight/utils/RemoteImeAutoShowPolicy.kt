package com.limelight.utils

/** Platform gate for the automatic remote-text keyboard path. */
internal object RemoteImeAutoShowPolicy {
    const val MIN_SUPPORTED_API = 30

    fun isSupported(apiLevel: Int): Boolean = apiLevel >= MIN_SUPPORTED_API
}
