package com.limelight.binding.input.driver

import android.annotation.SuppressLint
import java.util.concurrent.CompletableFuture

/** One service stop operation; callers serialize updates with the session lock. */
@SuppressLint("NewApi") // CompletableFuture is supplied by core library desugaring on API 22/23.
internal class UsbDriverStopResult {
    val completion = CompletableFuture<Void>()
    private var failure: Throwable? = null

    fun failed(error: Throwable) {
        if (failure == null) failure = error
    }

    fun finish() {
        val error = failure
        if (error == null) completion.complete(null)
        else completion.completeExceptionally(error)
    }
}
