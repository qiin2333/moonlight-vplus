package com.limelight.services

import android.os.Build
import java.io.IOException

class ShellService : IKeyboardAccessibilityShell.Stub() {
    override fun enable(packageName: String, componentName: String) {
        clearRestriction(packageName)
        val current = shell("settings", "get", "secure", "enabled_accessibility_services")
        val services = current.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (!services.contains(componentName)) services += componentName
        shell("settings", "put", "secure", "enabled_accessibility_services", services.joinToString(":"))
        shell("settings", "put", "secure", "accessibility_enabled", "1")
    }

    private fun clearRestriction(packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        shell("appops", "set", packageName, "android:bind_accessibility_service", "allow")
        shell("appops", "set", packageName, "android:access_restricted_settings", "allow")
    }

    private fun shell(vararg command: String): String {
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        } catch (_: IOException) {
            ""
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ""
        }
    }
}
