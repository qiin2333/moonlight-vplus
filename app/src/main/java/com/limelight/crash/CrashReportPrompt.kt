package com.limelight.crash

import android.app.Activity
import android.content.Intent
import android.net.Uri

import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider

import com.limelight.R

/**
 * Surfaces a previously captured crash report to the user and lets them ship
 * it back to the developer through whatever messenger / mail app they prefer.
 *
 * The dialog is intentionally non-blocking: even if the user picks "ignore"
 * the report is dropped (we don't want it nagging forever), and the dialog
 * is fully dismissable so it can never become a dead end.
 */
object CrashReportPrompt {

    /**
     * Show the prompt iff a report exists. Safe to call from any Activity's
     * onResume / completeOnCreate; idempotent when no report is pending.
     */
    fun maybeShow(activity: Activity) {
        val report = CrashReporter.pendingReportFile(activity) ?: return
        val preview = report.readText().lineSequence().take(20).joinToString("\n")
            .let { if (it.length > 600) it.substring(0, 600) + "…" else it }

        AlertDialog.Builder(activity)
            .setTitle(R.string.crash_report_dialog_title)
            .setMessage(activity.getString(R.string.crash_report_dialog_message, preview))
            .setPositiveButton(R.string.crash_report_share) { _, _ ->
                shareReport(activity)
                CrashReporter.clear(activity)
            }
            .setNegativeButton(R.string.crash_report_dismiss) { _, _ ->
                CrashReporter.clear(activity)
            }
            .setOnCancelListener {
                // Cancel just dismisses; user can still see it next launch.
            }
            .show()
    }

    private fun shareReport(activity: Activity) {
        val report = CrashReporter.pendingReportFile(activity) ?: return
        val authority = "${activity.packageName}.update_fileprovider"
        val uri: Uri = try {
            FileProvider.getUriForFile(activity, authority, report)
        } catch (e: IllegalArgumentException) {
            // FileProvider misconfigured (path entry missing) — fall back to plain text.
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.crash_report_subject))
                putExtra(Intent.EXTRA_TEXT, report.readText())
            }
            activity.startActivity(Intent.createChooser(send, null))
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.crash_report_subject))
            putExtra(Intent.EXTRA_STREAM, uri)
            // Inline the contents too, so messengers that ignore attachments
            // still surface the stack trace as the message body.
            putExtra(Intent.EXTRA_TEXT, report.readText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, null))
    }
}
