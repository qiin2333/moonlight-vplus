package com.limelight.utils

import android.app.Dialog
import android.content.Context
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.limelight.R

object AppDialogStyler {
    fun tintTitle(dialog: Dialog, context: Context) {
        val titleColor = ContextCompat.getColor(context, R.color.app_dialog_title_color)
        listOf(
            context.resources.getIdentifier("alertTitle", "id", context.packageName),
            context.resources.getIdentifier("alertTitle", "id", "android")
        ).filter { it != 0 }
            .distinct()
            .forEach { titleId ->
                dialog.findViewById<TextView>(titleId)?.setTextColor(titleColor)
            }
    }
}
