package com.limelight.utils

import android.graphics.Bitmap

/** Holds one stream frame for the next reconnect in memory. */
internal object StreamReconnectBackdrop {
    private var pending: Bitmap? = null

    fun stage(bitmap: Bitmap) {
        pending?.recycle()
        pending = bitmap
    }

    fun consume(): Bitmap? {
        val bitmap = pending
        pending = null
        return bitmap
    }
}
