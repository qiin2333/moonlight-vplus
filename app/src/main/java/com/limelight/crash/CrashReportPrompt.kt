package com.limelight.crash

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import androidx.core.content.FileProvider

import com.limelight.R
import com.limelight.preferences.BackgroundSource
import com.limelight.utils.AppDialogStyler

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

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
        val isTv = BackgroundSource.isTvDevice(activity)

        val builder = AlertDialog.Builder(activity, R.style.AppDialogStyle)
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

        if (isTv) {
            builder.setNeutralButton(R.string.crash_report_photo) { _, _ ->
                showPhotoSummary(activity)
            }
        } else {
            builder.setNeutralButton(R.string.crash_report_copy) { _, _ ->
                copyReport(activity)
                // 复制后保留报告文件，方便用户多次粘贴或最终分享
            }
        }

        val dialog = builder.show()
        AppDialogStyler.apply(dialog, activity)
    }

    private fun showPhotoSummary(activity: Activity) {
        val summary = buildPhotoSummary(activity) ?: return
        val density = activity.resources.displayMetrics.density
        val padding = (24f * density).roundToInt()

        val textView = TextView(activity).apply {
            text = summary
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextDirection(View.TEXT_DIRECTION_LTR)
            setLineSpacing(0f, 1.12f)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (BackgroundSource.isTvDevice(activity)) 16f else 14f
            )
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            addView(textView)
        }

        val dialog = AlertDialog.Builder(activity, R.style.AppDialogStyle)
            .setTitle(R.string.crash_report_photo_title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        AppDialogStyler.apply(dialog, activity)
    }

    private fun buildPhotoSummary(activity: Activity): String? {
        val reportText = CrashReporter.readReport(activity) ?: return null
        val lines = reportText.lineSequence().toList()

        fun field(prefix: String): String {
            return lines.firstOrNull { it.startsWith(prefix) }
                ?.substringAfter(prefix)
                ?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: activity.getString(R.string.crash_report_unknown)
        }

        val stackIndex = lines.indexOfFirst { it.startsWith("---- stack ----") }
        val stackLines = if (stackIndex >= 0) lines.drop(stackIndex + 1) else emptyList()
        val errorLine = stackLines.firstOrNull { it.isNotBlank() }
            ?.let { ellipsize(it.trim(), 110) }
            ?: activity.getString(R.string.crash_report_unknown)
        val frames = stackLines.asSequence()
            .map { it.trim() }
            .filter { it.startsWith("at ") }
            .take(3)
            .map { ellipsize(it.removePrefix("at ").trim(), 96) }
            .toList()

        return buildString {
            appendLine(activity.getString(R.string.crash_report_photo_hint))
            appendLine()
            appendLabelValue(activity, this, R.string.crash_report_photo_id, fingerprint(reportText))
            appendLabelValue(activity, this, R.string.crash_report_photo_time, field("time:"))
            appendLabelValue(activity, this, R.string.crash_report_photo_version, field("appVersion:"))
            appendLabelValue(activity, this, R.string.crash_report_photo_device, field("device:"))
            appendLabelValue(activity, this, R.string.crash_report_photo_android, field("android:"))
            appendLabelValue(activity, this, R.string.crash_report_photo_thread, field("thread:"))
            appendLabelValue(activity, this, R.string.crash_report_photo_error, errorLine)
            appendLine(activity.getString(R.string.crash_report_photo_frames) + ":")
            if (frames.isEmpty()) {
                append("1. ")
                appendLine(activity.getString(R.string.crash_report_unknown))
            } else {
                frames.forEachIndexed { index, frame ->
                    append(index + 1)
                    append(". ")
                    appendLine(frame)
                }
            }
        }.trimEnd()
    }

    private fun appendLabelValue(
        activity: Activity,
        builder: StringBuilder,
        labelRes: Int,
        value: String
    ) {
        builder.append(activity.getString(labelRes))
            .append(": ")
            .appendLine(value)
    }

    private fun fingerprint(reportText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(reportText.toByteArray())
            .take(4)
            .joinToString("") { "%02X".format(Locale.US, it) }
    }

    private fun ellipsize(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) text else text.take(maxLength - 1) + "…"
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

    private fun copyReport(activity: Activity) {
        val report = CrashReporter.pendingReportFile(activity) ?: return
        val text = try {
            report.readText()
        } catch (_: Exception) {
            return
        }
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(
            activity.getString(R.string.crash_report_subject), text))
        Toast.makeText(activity, R.string.crash_report_copied, Toast.LENGTH_SHORT).show()
    }
}
