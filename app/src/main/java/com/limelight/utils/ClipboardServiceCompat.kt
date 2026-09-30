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

    fun getAvailable(context: Context): ClipboardManager? {
        val clipboard = get(context) ?: return null
        return try {
            // Some TV firmware returns a manager whose backing binder is unavailable.
            // A read-only probe distinguishes that state from a merely non-null wrapper.
            clipboard.hasPrimaryClip()
            clipboard
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
