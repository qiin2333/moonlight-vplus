package com.limelight.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object ConfigurationSyncScheduler {
    private const val ACTION_RUN_CONFIG_SYNC = "com.limelight.action.RUN_CONFIG_SYNC"
    private const val REQUEST_CODE_CONFIG_SYNC = 178101
    private const val LIVE_SYNC_INTERVAL_MS = 30L * 1000L
    private const val LIVE_SYNC_DEBOUNCE_MS = 1000L
    private const val LOCAL_CHANGE_DEBOUNCE_MS = 1500L
    private const val LOCAL_CHANGE_FOLLOW_UP_MS = 2L * 60L * 1000L
    private const val CONVERGENCE_SYNC_INTERVAL_MS = 2L * 60L * 1000L
    private const val IDLE_SYNC_INTERVAL_MS = 15L * 60L * 1000L
    private val syncRunning = AtomicBoolean(false)
    private val syncPending = AtomicBoolean(false)
    private val liveHandler = Handler(Looper.getMainLooper())
    private var liveObserver: ContentObserver? = null
    private var liveContext: Context? = null
    private var liveObservedUriKey: String? = null
    private val localPreferenceListeners = mutableListOf<LocalPreferenceListenerRegistration>()
    private val liveRunnable = object : Runnable {
        override fun run() {
            val context = liveContext ?: return
            if (!ConfigurationSyncManager.isBackgroundSyncEnabled(context)) {
                stopLiveSync(context)
                return
            }

            runSync(context) {
                schedule(context)
                liveHandler.postDelayed(this, LIVE_SYNC_INTERVAL_MS)
            }
        }
    }

    private data class LocalPreferenceListenerRegistration(
        val preferences: SharedPreferences,
        val listener: SharedPreferences.OnSharedPreferenceChangeListener
    )

    fun runNow(context: Context) {
        val appContext = context.applicationContext
        if (!ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
            cancel(appContext)
            return
        }

        startLiveSync(appContext)
        runSync(appContext) {
            schedule(appContext)
        }
    }

    fun schedule(context: Context, delayMs: Long = nextSyncIntervalMs(context.applicationContext)) {
        val appContext = context.applicationContext
        if (!ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
            cancel(appContext)
            return
        }

        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        val pendingIntent = pendingIntent(appContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        stopLiveSync(appContext)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(appContext))
    }

    fun startLiveSync(context: Context) {
        val appContext = context.applicationContext
        if (!ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
            stopLiveSync(appContext)
            return
        }

        liveContext = appContext
        registerLiveObserver(appContext)
        registerLocalPreferenceListeners(appContext)
        liveHandler.removeCallbacks(liveRunnable)
        liveHandler.post(liveRunnable)
    }

    @JvmStatic
    fun requestSyncSoon(context: Context) {
        val appContext = context.applicationContext
        if (!ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
            cancel(appContext)
            return
        }

        liveContext = appContext
        registerLiveObserver(appContext)
        registerLocalPreferenceListeners(appContext)
        triggerLiveSyncSoon(LOCAL_CHANGE_DEBOUNCE_MS)
        schedule(appContext, LOCAL_CHANGE_FOLLOW_UP_MS)
    }

    private fun stopLiveSync(context: Context) {
        liveHandler.removeCallbacks(liveRunnable)
        liveObserver?.let {
            runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        }
        unregisterLocalPreferenceListeners()
        liveObserver = null
        liveObservedUriKey = null
        liveContext = null
    }

    private fun registerLiveObserver(context: Context) {
        val treeUri = ConfigurationSyncManager.externalSyncTreeUri(context) ?: return
        val observedUris = liveObservedUris(treeUri)
        val observedUriKey = observedUris.joinToString(separator = "\n") { it.toString() }
        if (liveObserver != null && liveObservedUriKey == observedUriKey) return

        liveObserver?.let {
            runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        }
        liveObserver = null
        liveObservedUriKey = null

        val observer = object : ContentObserver(liveHandler) {
            override fun onChange(selfChange: Boolean) {
                triggerLiveSyncSoon()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                triggerLiveSyncSoon()
            }

            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                triggerLiveSyncSoon()
            }
        }

        try {
            for (uri in observedUris) {
                context.contentResolver.registerContentObserver(uri, true, observer)
            }
            liveObserver = observer
            liveObservedUriKey = observedUriKey
        } catch (e: Exception) {
            runCatching { context.applicationContext.contentResolver.unregisterContentObserver(observer) }
            Log.w("ConfigSync", "Unable to register live sync observer: ${e.message}")
        }
    }

    private fun liveObservedUris(treeUri: Uri): List<Uri> {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        return listOf(
            treeUri,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId),
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        ).distinctBy { it.toString() }
    }

    private fun registerLocalPreferenceListeners(context: Context) {
        if (localPreferenceListeners.isNotEmpty()) return

        fun register(
            preferences: SharedPreferences,
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            preferences.registerOnSharedPreferenceChangeListener(listener)
            localPreferenceListeners += LocalPreferenceListenerRegistration(preferences, listener)
        }

        val defaultListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && ConfigurationSyncManager.isPortableDefaultPreferenceKey(key)) {
                triggerLiveSyncSoon(LOCAL_CHANGE_DEBOUNCE_MS)
            }
        }
        register(PreferenceManager.getDefaultSharedPreferences(context), defaultListener)

        for (preferencesName in ConfigurationSyncManager.portableSharedPreferenceNames()) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (ConfigurationSyncManager.isPortableSharedPreferenceKey(preferencesName, key)) {
                    triggerLiveSyncSoon(LOCAL_CHANGE_DEBOUNCE_MS)
                }
            }
            register(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE), listener)
        }
    }

    private fun unregisterLocalPreferenceListeners() {
        for ((preferences, listener) in localPreferenceListeners) {
            runCatching { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        localPreferenceListeners.clear()
    }

    private fun triggerLiveSyncSoon(delayMs: Long = LIVE_SYNC_DEBOUNCE_MS) {
        val context = liveContext ?: return
        if (!ConfigurationSyncManager.isBackgroundSyncEnabled(context)) return
        liveHandler.removeCallbacks(liveRunnable)
        liveHandler.postDelayed(liveRunnable, delayMs)
    }

    private fun nextSyncIntervalMs(context: Context): Long {
        val status = ConfigurationSyncManager(context.applicationContext).syncStatusInfo()
        if (!status.hasCompletedSync) return CONVERGENCE_SYNC_INTERVAL_MS
        return if (!status.success || status.appliedMergedPackage || status.wroteExternal) {
            CONVERGENCE_SYNC_INTERVAL_MS
        } else {
            IDLE_SYNC_INTERVAL_MS
        }
    }

    internal fun runSync(context: Context, onFinished: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        if (!syncRunning.compareAndSet(false, true)) {
            syncPending.set(true)
            onFinished?.invoke()
            return
        }

        thread(name = "ConfigSyncBackground") {
            try {
                val syncManager = ConfigurationSyncManager(appContext)
                val result = syncManager.trySynchronizeWithExternalSnapshot()
                if (result == null) {
                    Log.i("ConfigSync", "Background sync skipped because another process is running")
                    return@thread
                }
                syncManager.rememberAutoSyncResult(result)
                if (result.errorMessage != null) {
                    Log.w("ConfigSync", "Background sync failed: ${result.errorMessage}")
                } else if (result.enabled) {
                    Log.i(
                        "ConfigSync",
                        "Background sync complete: read=${result.readExternal}, " +
                                "applied=${result.appliedMergedPackage}, wrote=${result.wroteExternal}"
                    )
                }
            } catch (e: Exception) {
                ConfigurationSyncManager(appContext)
                    .rememberAutoSyncFailure(e.message ?: e.javaClass.simpleName)
                Log.e("ConfigSync", "Unexpected background sync failure", e)
            } finally {
                syncRunning.set(false)
                val runPending = syncPending.getAndSet(false) &&
                        ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)
                onFinished?.invoke()
                if (runPending) {
                    Log.i("ConfigSync", "Background sync pending request will run next")
                    runSync(appContext) {
                        schedule(appContext)
                    }
                }
            }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConfigurationSyncReceiver::class.java)
            .setAction(ACTION_RUN_CONFIG_SYNC)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE_CONFIG_SYNC, intent, flags)
    }
}

class ConfigurationSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.QUICKBOOT_POWERON",
            null -> ConfigurationSyncScheduler.schedule(appContext)
        }

        if (!ConfigurationSyncManager.isBackgroundSyncEnabled(appContext)) {
            ConfigurationSyncScheduler.cancel(appContext)
            return
        }

        val pendingResult = goAsync()
        ConfigurationSyncScheduler.runSync(appContext) {
            ConfigurationSyncScheduler.schedule(appContext)
            pendingResult.finish()
        }
    }
}
