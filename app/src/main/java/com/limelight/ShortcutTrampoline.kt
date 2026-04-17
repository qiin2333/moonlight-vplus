package com.limelight

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder

import com.limelight.computers.ComputerDatabaseManager
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.utils.CacheHelper
import com.limelight.utils.Dialog
import com.limelight.utils.ServerHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.UiHelper
import com.limelight.utils.AppCacheManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.xmlpull.v1.XmlPullParserException

import java.io.IOException
import java.io.StringReader
import java.util.UUID

class ShortcutTrampoline : Activity() {
    private var uuidString: String? = null
    private var app: NvApp? = null
    private val intentStack = ArrayList<Intent>()

    private var wakeHostTries = 10
    private var computer: ComputerDetails? = null
    private var blockingLoadSpinner: SpinnerDialog? = null

    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingCollectJob: Job? = null

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder

            uiScope.launch {
                withContext(Dispatchers.IO) {
                    localBinder.waitForReady()
                }
                managerBinder = localBinder

                computer = managerBinder?.getComputer(uuidString!!)

                if (computer == null) {
                    Dialog.displayDialog(this@ShortcutTrampoline,
                            resources.getString(R.string.conn_error_title),
                            resources.getString(R.string.scut_pc_not_found),
                            true)

                    blockingLoadSpinner?.dismiss()
                    blockingLoadSpinner = null

                    if (managerBinder != null) {
                        unbindService(serviceConnection)
                        managerBinder = null
                    }
                    return@launch
                }

                managerBinder?.invalidateStateForComputer(computer?.uuid!!)

                pollingCollectJob?.cancel()
                pollingCollectJob = uiScope.launch {
                    localBinder.computerUpdates
                        .filter { it.uuid.equals(uuidString, ignoreCase = true) }
                        .collect { details -> handleDetails(details) }
                }
                localBinder.startPolling()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    private fun handleDetails(details: ComputerDetails) {
        if (details.state == ComputerDetails.State.OFFLINE && details.macAddress != null && --wakeHostTries >= 0) {
            try {
                val comp = computer ?: return
                WakeOnLanSender.sendWolPacket(comp)
                managerBinder?.invalidateStateForComputer(comp.uuid!!)
                return
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        if (details.state == ComputerDetails.State.UNKNOWN) return

        blockingLoadSpinner?.dismiss()
        blockingLoadSpinner = null

        if (managerBinder == null) {
            finish()
            return
        }

        if (details.state == ComputerDetails.State.ONLINE && details.pairState == PairingManager.PairState.PAIRED) {
            if (app != null) {
                if (details.runningGameId == 0 || details.runningGameId == app?.appId) {
                    intentStack.add(ServerHelper.createStartIntent(this@ShortcutTrampoline, app!!, details, managerBinder!!))
                    finish()
                    startActivities(intentStack.toTypedArray())
                } else {
                    val startIntent = ServerHelper.createStartIntent(this@ShortcutTrampoline, app!!, details, managerBinder!!)

                    UiHelper.displayQuitConfirmationDialog(this@ShortcutTrampoline, {
                        intentStack.add(startIntent)
                        finish()
                        startActivities(intentStack.toTypedArray())
                    }, {
                        finish()
                    })
                }
            } else {
                finish()

                var i = Intent(this@ShortcutTrampoline, PcView::class.java)
                i.action = Intent.ACTION_MAIN
                i.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                intentStack.add(i)

                i = Intent(intent)
                i.setClass(this@ShortcutTrampoline, AppView::class.java)
                intentStack.add(i)

                if (details.runningGameId != 0) {
                    val actualApp = getNvAppById(details.runningGameId, uuidString!!)
                    if (actualApp != null) {
                        intentStack.add(ServerHelper.createStartIntent(this@ShortcutTrampoline, actualApp, details, managerBinder!!))
                    } else {
                        intentStack.add(ServerHelper.createStartIntent(this@ShortcutTrampoline,
                                NvApp("", details.runningGameId, false), details, managerBinder!!))
                    }
                }

                startActivities(intentStack.toTypedArray())
            }
        } else if (details.state == ComputerDetails.State.OFFLINE) {
            Dialog.displayDialog(this@ShortcutTrampoline,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.error_pc_offline),
                    true)
        } else if (details.pairState != PairingManager.PairState.PAIRED) {
            Dialog.displayDialog(this@ShortcutTrampoline,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_not_paired),
                    true)
        }

        if (managerBinder != null) {
            pollingCollectJob?.cancel()
            pollingCollectJob = null
            managerBinder?.stopPolling()
            unbindService(serviceConnection)
            managerBinder = null
        }
    }

    protected fun validateInput(uuidString: String?, appIdString: String?, nameString: String?): Boolean {
        if (uuidString == null && nameString == null) {
            Dialog.displayDialog(this@ShortcutTrampoline,
                    resources.getString(R.string.conn_error_title),
                    resources.getString(R.string.scut_invalid_uuid),
                    true)
            return false
        }

        if (uuidString != null && uuidString.isNotEmpty()) {
            try {
                UUID.fromString(uuidString)
            } catch (ex: IllegalArgumentException) {
                Dialog.displayDialog(this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_invalid_uuid),
                        true)
                return false
            }
        } else {
            if (nameString == null || nameString.isEmpty()) {
                Dialog.displayDialog(this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_invalid_uuid),
                        true)
                return false
            }
        }

        if (appIdString != null && appIdString.isNotEmpty()) {
            try {
                appIdString.toInt()
            } catch (ex: NumberFormatException) {
                Dialog.displayDialog(this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_invalid_app_id),
                        true)
                return false
            }
        }

        return true
    }

    private fun getNvAppById(appId: Int, uuidString: String): NvApp? {
        try {
            val rawAppList = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString))
            if (rawAppList.isEmpty()) {
                return getLastNvAppFromPreferences(appId, uuidString)
            }

            val applist = NvHTTP.getAppListByReader(StringReader(rawAppList))
            for (nvApp in applist) {
                if (nvApp.appId == appId) {
                    val cacheManager = AppCacheManager(this)
                    cacheManager.saveAppInfo(uuidString, nvApp)
                    return nvApp
                }
            }

            return getLastNvAppFromPreferences(appId, uuidString)
        } catch (e: IOException) {
            e.printStackTrace()
            return getLastNvAppFromPreferences(appId, uuidString)
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            return getLastNvAppFromPreferences(appId, uuidString)
        }
    }

    private fun getLastNvAppFromPreferences(appId: Int, uuidString: String): NvApp? {
        return try {
            val cacheManager = AppCacheManager(this)
            cacheManager.getAppInfo(uuidString, appId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UiHelper.notifyNewRootView(this)
        val dbManager = ComputerDatabaseManager(this)

        uuidString = intent.getStringExtra(AppView.UUID_EXTRA)
        val nameString = intent.getStringExtra(AppView.NAME_EXTRA)

        val appIdString = intent.getStringExtra(Game.EXTRA_APP_ID)
        val appNameString = intent.getStringExtra(Game.EXTRA_APP_NAME)

        if (!validateInput(uuidString, appIdString, nameString)) {
            return
        }

        if (uuidString == null || uuidString?.isEmpty() == true) {
            val foundComputer = dbManager.getComputerByName(nameString!!)

            if (foundComputer == null) {
                Dialog.displayDialog(this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_pc_not_found),
                        true)
                return
            }

            uuidString = foundComputer.uuid
            setIntent(Intent(intent).putExtra(AppView.UUID_EXTRA, uuidString))
        }

        if (appIdString != null && appIdString.isNotEmpty()) {
            app = NvApp(intent.getStringExtra(Game.EXTRA_APP_NAME) ?: "",
                    appIdString.toInt(),
                    intent.getBooleanExtra(Game.EXTRA_APP_HDR, false))

            val cachedApp = getLastNvAppFromPreferences((app?.appId ?: 0), uuidString!!)
            if (cachedApp?.cmdList != null) {
                app?.setCmdList(cachedApp.cmdList.toString())
            }
        } else if (appNameString != null && appNameString.isNotEmpty()) {
            try {
                var appId = -1
                val rawAppList = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(cacheDir, "applist", uuidString!!))

                if (rawAppList.isEmpty()) {
                    Dialog.displayDialog(this@ShortcutTrampoline,
                            resources.getString(R.string.conn_error_title),
                            resources.getString(R.string.scut_invalid_app_id),
                            true)
                    return
                }
                val applist = NvHTTP.getAppListByReader(StringReader(rawAppList))

                for (nvApp in applist) {
                    if (nvApp.appName == appNameString) {
                        appId = nvApp.appId
                        break
                    }
                }
                if (appId < 0) {
                    Dialog.displayDialog(this@ShortcutTrampoline,
                            resources.getString(R.string.conn_error_title),
                            resources.getString(R.string.scut_invalid_app_id),
                            true)
                    return
                }
                setIntent(Intent(intent).putExtra(Game.EXTRA_APP_ID, appId))
                app = NvApp(
                        appNameString,
                        appId,
                        intent.getBooleanExtra(Game.EXTRA_APP_HDR, false))

                val cachedApp = getLastNvAppFromPreferences(appId, uuidString!!)
                if (cachedApp?.cmdList != null) {
                    app?.setCmdList(cachedApp.cmdList.toString())
                }
            } catch (e: IOException) {
                Dialog.displayDialog(this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_invalid_app_id),
                        true)
                return
            } catch (e: XmlPullParserException) {
                Dialog.displayDialog(this@ShortcutTrampoline,
                        resources.getString(R.string.conn_error_title),
                        resources.getString(R.string.scut_invalid_app_id),
                        true)
                return
            }
        }

        bindService(Intent(this, ComputerManagerService::class.java), serviceConnection,
                Service.BIND_AUTO_CREATE)

        blockingLoadSpinner = SpinnerDialog.displayDialog(this, resources.getString(R.string.conn_establishing_title),
                resources.getString(R.string.applist_connect_msg), true)
    }

    override fun onStop() {
        super.onStop()

        uiScope.cancel()
        blockingLoadSpinner?.dismiss()
        blockingLoadSpinner = null

        Dialog.closeDialogs()

        if (managerBinder != null) {
            managerBinder?.stopPolling()
            unbindService(serviceConnection)
            managerBinder = null
        }

        finish()
    }
}
