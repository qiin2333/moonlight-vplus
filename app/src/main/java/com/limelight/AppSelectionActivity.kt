package com.limelight

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limelight.computers.ComputerManagerService
import com.limelight.grid.AppGridAdapter
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.AdapterRecyclerBridge
import com.limelight.utils.CacheHelper
import com.limelight.utils.ServerHelper
import com.limelight.utils.SpinnerDialog
import com.limelight.utils.UiHelper
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

class AppSelectionActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollingCollectJob: Job? = null

    private var pcName: String? = null
    private var pcUuid: String? = null
    private var computer: ComputerDetails? = null
    private var managerBinder: ComputerManagerService.ComputerManagerBinder? = null
    private lateinit var appGridAdapter: AppGridAdapter
    private lateinit var bridge: AdapterRecyclerBridge
    private lateinit var recyclerView: RecyclerView

    private var pendingAppLaunch: AppView.AppObject? = null
    private var connectingDialog: SpinnerDialog? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val localBinder = binder as ComputerManagerService.ComputerManagerBinder

            uiScope.launch {
                withContext(Dispatchers.IO) {
                    localBinder.waitForReady()
                }
                managerBinder = localBinder

                if (pcUuid != null) {
                    managerBinder?.invalidateStateForComputer(pcUuid!!)
                }

                if (!isFinishing && !isDestroyed) {
                    pollingCollectJob?.cancel()
                    pollingCollectJob = uiScope.launch {
                        localBinder.computerUpdates
                            .filter { it.uuid == pcUuid }
                            .collect { handleComputerUpdate(it) }
                    }
                    localBinder.startPolling()
                    loadApps()
                }
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            managerBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_app_selection)

        pcName = intent.getStringExtra(AppView.NAME_EXTRA)
        pcUuid = intent.getStringExtra(AppView.UUID_EXTRA)

        if (pcUuid == null) {
            finish()
            return
        }

        val pcNameView = findViewById<TextView>(R.id.pcName)
        pcNameView.text = pcName

        recyclerView = findViewById(R.id.appListGrid)
        findViewById<android.view.View>(R.id.closeButton).setOnClickListener { finish() }

        val serviceIntent = Intent(this, ComputerManagerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        if (managerBinder != null) {
            managerBinder?.stopPolling()
            unbindService(serviceConnection)
        }
        appGridAdapter?.cancelQueuedOperations()
        bridge?.cleanup()
        if (connectingDialog != null) {
            connectingDialog?.dismiss()
            connectingDialog = null
        }
    }

    private fun loadApps() {
        computer = managerBinder?.getComputer(pcUuid!!)
        if (computer == null) {
            Toast.makeText(this, R.string.scut_pc_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val apps = getAppListFromCache(pcUuid!!)
        if (apps.isNullOrEmpty()) {
            Toast.makeText(this, R.string.no_app_found_for_streaming, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val prefs = PreferenceConfiguration.readPreferences(this)
        val comp = computer!!
        appGridAdapter = AppGridAdapter(this, prefs, comp, (managerBinder?.getUniqueId() ?: ""), false)

        val appObjects = apps.map { AppView.AppObject(it) }
        appGridAdapter.rebuildAppList(appObjects)

        bridge = AdapterRecyclerBridge(this, appGridAdapter)
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = bridge

        bridge.setOnItemClickListener { _, item ->
            val obj = item as AppView.AppObject
            val comp = computer!!
            val binder = managerBinder!!

            if (comp.state == ComputerDetails.State.ONLINE && comp.activeAddress != null) {
                if (comp.runningGameId != 0 && comp.runningGameId != obj.app.appId) {
                    UiHelper.displayQuitConfirmationDialog(this, {
                        ServerHelper.doStart(this, obj.app, comp, binder)
                        finish()
                    }, null)
                } else {
                    ServerHelper.doStart(this, obj.app, comp, binder)
                    finish()
                }
            } else {
                pendingAppLaunch = obj
                connectingDialog = SpinnerDialog.displayDialog(
                    this,
                    getString(R.string.conn_establishing_title),
                    getString(R.string.applist_connect_msg), true
                )

                uiScope.launch {
                    withContext(Dispatchers.IO) {
                        binder.invalidateStateForComputer(pcUuid!!)

                        if (comp.state == ComputerDetails.State.OFFLINE) {
                            try {
                                WakeOnLanSender.sendWolPacket(comp)
                                binder.invalidateStateForComputer(pcUuid!!)
                            } catch (e: IOException) {
                                LimeLog.warning("Failed to send WoL packet:$e")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleComputerUpdate(details: ComputerDetails) {
        computer = details

        val pending = pendingAppLaunch ?: return
        if (details.state == ComputerDetails.State.ONLINE && details.activeAddress != null) {
            if (connectingDialog != null) {
                connectingDialog?.dismiss()
                connectingDialog = null
            }

            val comp = computer!!
            val binder = managerBinder ?: return
            if (comp.runningGameId != 0 && comp.runningGameId != pending.app.appId) {
                UiHelper.displayQuitConfirmationDialog(this@AppSelectionActivity, {
                    ServerHelper.doStart(this@AppSelectionActivity, pending.app, comp, binder)
                    finish()
                }, null)
            } else {
                ServerHelper.doStart(this@AppSelectionActivity, pending.app, comp, binder)
                finish()
            }
        }
    }

    private fun getAppListFromCache(uuid: String): List<NvApp>? {
        return try {
            val rawAppList = CacheHelper.readInputStreamToString(
                CacheHelper.openCacheFileForInput(cacheDir, "applist", uuid)
            )
            if (rawAppList.isEmpty()) null else NvHTTP.getAppListByReader(StringReader(rawAppList))
        } catch (e: IOException) {
            LimeLog.warning("Failed to read app list from cache: ${e.message}")
            null
        } catch (e: XmlPullParserException) {
            LimeLog.warning("Failed to read app list from cache: ${e.message}")
            null
        }
    }
}
