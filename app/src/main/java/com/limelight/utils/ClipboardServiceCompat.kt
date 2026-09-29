package com.limelight.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.limelight.LimeLog

object ClipboardServiceCompat {
    fun get(context: Context): ClipboardManager? {
        return try {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (_: RuntimeException) {
            null
        }
    }

    fun setPrimaryClip(context: Context, clip: ClipData): Boolean {
        val clipboard = get(context) ?: return false
        return try {
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: RuntimeException) {
            LimeLog.warning("Clipboard write failed: ${e.message}")
            false
        }
    }
}
