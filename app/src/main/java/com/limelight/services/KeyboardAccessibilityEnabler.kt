package com.limelight.services

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import com.limelight.BuildConfig
import com.limelight.LimeLog
import com.limelight.preferences.PreferenceConfiguration
import rikka.shizuku.Shizuku

object KeyboardAccessibilityEnabler {
    private const val REQUEST_PERMISSION = 26012
    private val attemptLock = Any()
    private var attemptStarted = false
    private var activeBinding: Binding? = null

    private class Binding(
        val args: Shizuku.UserServiceArgs,
        val connection: ServiceConnection
    )

    enum class AuthorizationState {
        NOT_INSTALLED,
        NOT_RUNNING,
        AUTHORIZATION_REQUIRED,
        AUTHORIZED,
    }

    fun authorizationState(): AuthorizationState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return AuthorizationState.NOT_INSTALLED
        return try {
            if (!Shizuku.pingBinder()) return AuthorizationState.NOT_RUNNING
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                AuthorizationState.AUTHORIZED
            } else {
                AuthorizationState.AUTHORIZATION_REQUIRED
            }
        } catch (_: RuntimeException) {
            AuthorizationState.NOT_INSTALLED
        }
    }

    fun requestAuthorization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (authorizationState() != AuthorizationState.AUTHORIZATION_REQUIRED) return
        Shizuku.requestPermission(REQUEST_PERMISSION)
    }

    fun addAuthorizationListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removeAuthorizationListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun requestEnableForCurrentForegroundCycle(context: Context) {
        if (!shouldStartEnableAttempt(
                enabled = PreferenceConfiguration.readPreferences(context).enableShizukuAccessibility,
                serviceRunning = KeyboardAccessibilityService.instance != null,
                authorized = authorizationState() == AuthorizationState.AUTHORIZED,
                attemptStarted = attemptStarted
            )
        ) return
        synchronized(attemptLock) {
            attemptStarted = true
        }
        enableInShell(context.applicationContext)
    }

    internal fun shouldStartEnableAttempt(
        enabled: Boolean,
        serviceRunning: Boolean,
        authorized: Boolean,
        attemptStarted: Boolean
    ): Boolean = enabled && !serviceRunning && authorized && !attemptStarted

    fun resetForegroundAttempt() {
        val binding = synchronized(attemptLock) {
            attemptStarted = false
            activeBinding.also { activeBinding = null }
        }
        if (binding != null) unbind(binding)
    }

    private fun enableInShell(context: Context) {
        val component = ComponentName(context, KeyboardAccessibilityService::class.java).flattenToString()
        val packageName = context.packageName
        val args = Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("keyboard_accessibility")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val stillActive = synchronized(attemptLock) { activeBinding?.connection === connection }
                if (!stillActive) return
                try {
                    IKeyboardAccessibilityShell.Stub.asInterface(binder).enable(packageName, component)
                } catch (e: RemoteException) {
                    LimeLog.warning("Shizuku could not enable keyboard accessibility: ${e.message}")
                } catch (e: RuntimeException) {
                    LimeLog.warning("Shizuku could not enable keyboard accessibility: ${e.message}")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val binding = Binding(args, connection)
        synchronized(attemptLock) {
            activeBinding = binding
        }
        try {
            Shizuku.bindUserService(args, binding.connection)
        } catch (e: RuntimeException) {
            synchronized(attemptLock) {
                if (activeBinding === binding) activeBinding = null
            }
            LimeLog.warning("Shizuku could not start keyboard accessibility service: ${e.message}")
        }
    }

    private fun unbind(binding: Binding) {
        try {
            Shizuku.unbindUserService(binding.args, binding.connection, true)
        } catch (e: RuntimeException) {
            LimeLog.warning("Shizuku could not stop keyboard accessibility service: ${e.message}")
        }
    }
}
