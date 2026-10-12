package com.limelight.services

import android.os.Build
import android.os.Parcel
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShellService : IKeyboardAccessibilityShell.Stub() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun enable(packageName: String, componentName: String) {
        executor.execute { enableNow(packageName, componentName) }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != DESTROY_TRANSACTION) return super.onTransact(code, data, reply, flags)
        executor.shutdownNow()
        System.exit(0)
        return true
    }

    private fun enableNow(packageName: String, componentName: String) {
        clearRestriction(packageName)
        val current = shell("settings", "get", "secure", "enabled_accessibility_services") ?: return
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

    private fun shell(vararg command: String): String? {
        val process = try {
            ProcessBuilder(*command).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return null
        }
        return try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().readText().trim()
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            null
        }
    }

    private companion object {
        const val DESTROY_TRANSACTION = 16777114
        const val COMMAND_TIMEOUT_SECONDS = 3L
    }
}
