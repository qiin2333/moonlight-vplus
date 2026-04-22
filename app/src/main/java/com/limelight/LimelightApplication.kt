package com.limelight

import android.app.Application

import com.limelight.crash.CrashReporter

/**
 * Custom Application that wires up crash diagnostics as early as possible.
 *
 * Firebase Crashlytics auto-initialises through its own ContentProvider when
 * `google-services.json` is present, so all we have to do here is install our
 * local fallback handler — that handler chains to whatever the system (or
 * Crashlytics) had previously installed, so both paths get the exception.
 *
 * Without a Crashlytics build (no google-services.json), the local file +
 * "share log on next launch" flow is the only diagnostic, which is exactly the
 * point of having both.
 */
class LimelightApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
