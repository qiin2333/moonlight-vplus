package com.limelight.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Repair saved launcher targets after an update, before the first foreground launch. */
class LauncherEntryMigrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        LauncherEntry.migrateAsync(context) { pendingResult.finish() }
    }
}
