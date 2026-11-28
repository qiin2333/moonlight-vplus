package com.limelight.nvstream

/** Asks the host to rotate its display, e.g. [NvConnection]. */
fun interface DisplayRotator {
    fun rotateDisplay(angle: Int, callback: NvConnection.DisplayRotationCallback?)
}
