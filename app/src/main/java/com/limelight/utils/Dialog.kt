package com.limelight.utils

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.limelight.R

class Dialog private constructor(
    private val activity: Activity,
    private val title: String,
    private val message: String,
    private val runOnDismiss: Runnable,
    private val isDetailsDialog: Boolean = false
) : Runnable {

    private lateinit var alert: AlertDialog

    override fun run() {
        if (activity.isFinishing) return

        if (isDetailsDialog) {
            createDetailsDialog()
        } else {
            createStandardDialog()
        }
    }

    private fun createStandardDialog() {
        alert = AlertDialog.Builder(activity, R.style.AppDialogStyle).create()

        alert.setTitle(title)
        alert.setMessage(message)
        alert.setCancelable(false)
        alert.setCanceledOnTouchOutside(false)

        alert.setButton(AlertDialog.BUTTON_POSITIVE, activity.resources.getText(android.R.string.ok)) { dialog, _ ->
            synchronized(rundownDialogs) {
                rundownDialogs.remove(this)
                alert.dismiss()
            }
            runOnDismiss.run()
        }
        alert.setButton(AlertDialog.BUTTON_NEUTRAL, activity.resources.getText(R.string.help)) { dialog, _ ->
            synchronized(rundownDialogs) {
                rundownDialogs.remove(this)
                alert.dismiss()
            }
            runOnDismiss.run()
            HelpLauncher.launchTroubleshooting(activity)
        }
        alert.setOnShowListener {
            val button = alert.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isFocusable = true
            button.isFocusableInTouchMode = true
            button.requestFocus()
        }

        synchronized(rundownDialogs) {
            rundownDialogs.add(this)
            alert.show()
        }

        alert.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.alpha = 0.8f
            window.attributes = layoutParams
        }
    }

    private fun createDetailsDialog() {
        val builder = AlertDialog.Builder(activity, R.style.AppDialogStyle)

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.details_dialog, null)

        val titleView = dialogView.findViewById<TextView>(R.id.detailsTitle)
        val contentView = dialogView.findViewById<TextView>(R.id.detailsContent)
        val copyButton = dialogView.findViewById<ImageButton>(R.id.copyButton)

        titleView.text = title
        contentView.text = formatDetailsMessage(message)

        copyButton.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(
                activity.getString(R.string.copy_details),
                contentView.text.toString()
            )
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, activity.getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
        }

        copyButton.isFocusable = true
        copyButton.isFocusableInTouchMode = true
        contentView.isFocusable = true
        contentView.isFocusableInTouchMode = true

        copyButton.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        copyButton.performClick()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        contentView.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        titleView.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        alert.dismiss()
                        true
                    }
                    else -> false
                }
            } else false
        }

        contentView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        copyButton.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        alert.dismiss()
                        true
                    }
                    else -> false
                }
            } else false
        }

        titleView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        copyButton.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        alert.dismiss()
                        true
                    }
                    else -> false
                }
            } else false
        }

        builder.setView(dialogView)
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            synchronized(rundownDialogs) {
                rundownDialogs.remove(this)
                alert.dismiss()
            }
            runOnDismiss.run()
        }

        alert = builder.create()
        alert.setCancelable(false)
        alert.setCanceledOnTouchOutside(false)

        synchronized(rundownDialogs) {
            rundownDialogs.add(this)
            alert.show()
        }

        alert.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.alpha = 0.8f
            window.attributes = layoutParams
        }

        alert.setOnShowListener {
            copyButton.requestFocus()
        }

        alert.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        alert.dismiss()
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun formatDetailsMessage(message: String): String {
        return message.split("\n").joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                ""
            } else if (trimmed.contains(": ")) {
                val parts = trimmed.split(": ", limit = 2)
                if (parts.size == 2) {
                    val label = parts[0].trim()
                    val value = parts[1].trim()
                    val icon = getIconForLabel(label)
                    "$icon $label: $value"
                } else {
                    line
                }
            } else {
                line
            }
        }
    }

    private fun getIconForLabel(label: String): String = when (label.lowercase()) {
        "name" -> "📱"
        "state" -> "🔄"
        "uuid" -> "🔑"
        "id" -> "🆔"
        "address", "local address", "remote address", "ipv6 address", "manual address", "active address" -> "🌐"
        "mac address" -> "📡"
        "pair state" -> "🔗"
        "running game id" -> "🎮"
        "https port" -> "🔒"
        "hdr supported" -> "🎨"
        "super cmds" -> "⚡"
        else -> "🔹"
    }

    companion object {
        private val rundownDialogs = ArrayList<Dialog>()

        fun closeDialogs() {
            synchronized(rundownDialogs) {
                for (d in rundownDialogs) {
                    if (d.alert.isShowing) {
                        d.alert.dismiss()
                    }
                }
                rundownDialogs.clear()
            }
        }

        fun displayDialog(activity: Activity, title: String, message: String, endAfterDismiss: Boolean) {
            activity.runOnUiThread(Dialog(activity, title, message, Runnable {
                if (endAfterDismiss) activity.finish()
            }))
        }

        fun displayDetailsDialog(activity: Activity, title: String, message: String, endAfterDismiss: Boolean) {
            activity.runOnUiThread(Dialog(activity, title, message, Runnable {
                if (endAfterDismiss) activity.finish()
            }, isDetailsDialog = true))
        }

        fun displayDialog(activity: Activity, title: String, message: String, runOnDismiss: Runnable) {
            activity.runOnUiThread(Dialog(activity, title, message, runOnDismiss))
        }
    }
}
