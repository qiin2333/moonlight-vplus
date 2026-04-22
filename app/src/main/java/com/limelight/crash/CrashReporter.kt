package com.limelight.crash

import android.app.Application
import android.content.Context
import android.os.Build

import com.limelight.BuildConfig

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight on-device crash reporter.
 *
 * Complements Firebase Crashlytics for users on builds that ship without
 * `google-services.json` (forks, sideloads, F-Droid-style distributions).
 * The collected text file is meant to be shared by the user manually next
 * time they launch the app — see [pendingReportFile] / [shareIntentFor].
 *
 * Design choices:
 * - Chains to the previously-installed [Thread.UncaughtExceptionHandler] so
 *   Crashlytics still fires when both are active.
 * - Writes synchronously: the process is dying, so a queued task may never run.
 * - Catches and swallows any failure during write — the user-visible crash
 *   dialog must not be replaced by a nested crash from our reporter.
 * - Single rolling file (`crash/last.txt`); we don't need history.
 */
object CrashReporter {

    private const val DIR_NAME = "crash"
    private const val FILE_NAME = "last.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(app, thread, throwable)
            } catch (_: Throwable) {
                // Swallow — never let the reporter replace the original crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** @return the saved crash file if one is pending, else null. */
    fun pendingReportFile(ctx: Context): File? {
        val f = reportFile(ctx)
        return if (f.exists() && f.length() > 0) f else null
    }

    /** Best-effort delete of the saved report. Safe to call even if absent. */
    fun clear(ctx: Context) {
        runCatching { reportFile(ctx).delete() }
    }

    /** Read the report's contents, or null on any I/O failure. */
    fun readReport(ctx: Context): String? = runCatching {
        reportFile(ctx).readText()
    }.getOrNull()

    private fun reportFile(ctx: Context): File =
        File(File(ctx.filesDir, DIR_NAME), FILE_NAME)

    private fun writeReport(ctx: Context, thread: Thread, throwable: Throwable) {
        val dir = File(ctx.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            pw.println("=== Moonlight crash report ===")
            pw.println("time:        $time")
            pw.println("thread:      ${thread.name}")
            pw.println("appVersion:  ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            pw.println("flavor:      ${BuildConfig.FLAVOR}")
            pw.println("build:       ${BuildConfig.BUILD_TYPE}")
            pw.println("device:      ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            pw.println("android:     ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("abi:         ${Build.SUPPORTED_ABIS.joinToString()}")
            pw.println("---- stack ----")
            throwable.printStackTrace(pw)
        }
        reportFile(ctx).writeText(sw.toString())
    }
}
