package com.limelight.preferences

import android.app.Dialog
import android.content.Context

/** Frame rates reuse the resolution dialog. The protected 60 FPS entry uses height zero. */
object CustomFrameRatesDialog {
    private const val PROTECTED_FRAME_RATE = 60

    fun show(
        context: Context,
        onClosed: () -> Unit,
        requestInitialFocus: Boolean = false
    ): Dialog {
        val initial = CustomFrameRatesStore.load(context)
        return CustomResolutionsDialog.show(
            context = context,
            initial = initial.map { Resolution(it, 0) },
            frameRateMode = true,
            requestInitialFocus = requestInitialFocus,
            onCommit = { resolutions ->
                CustomFrameRatesStore.save(
                    context,
                    resolutions.map { it.width }.filter { it > 0 }.distinct().sorted()
                )
            },
            onClosed = onClosed
        )
    }
}
