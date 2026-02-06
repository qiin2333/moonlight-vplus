package com.limelight;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.ui.AdapterRecyclerBridge;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.ServerHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class AppSelectionActivity extends Activity implements ComputerManagerListener {

    private String pcName;
    private String pcUuid;
    private ComputerDetails computer;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private AppGridAdapter appGridAdapter;
    private AdapterRecyclerBridge bridge;
    private RecyclerView recyclerView;
    
    private AppView.AppObject pendingAppLaunch;
    private SpinnerDialog connectingDialog;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder = 
                    (ComputerManagerService.ComputerManagerBinder) binder;

            new Thread(() -> {
                // Wait for the binder to be ready (initializes DiscoveryService)
                localBinder.waitForReady();

                managerBinder = localBinder;
                
                // Start polling (thread-safe)
                managerBinder.startPolling(AppSelectionActivity.this);
                
                // Force a refresh immediately
                if (pcUuid != null) {
                    managerBinder.invalidateStateForComputer(pcUuid);
                }

                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        loadApps();
                    }
                });
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_app_selection);

        pcName = getIntent().getStringExtra(AppView.NAME_EXTRA);
        pcUuid = getIntent().getStringExtra(AppView.UUID_EXTRA);

        if (pcUuid == null) {
            finish();
            return;
        }

        TextView pcNameView = findViewById(R.id.pcName);
        pcNameView.setText(pcName);

        recyclerView = findViewById(R.id.appListGrid);
        findViewById(R.id.closeButton).setOnClickListener(v -> finish());

        Intent serviceIntent = new Intent(this, ComputerManagerService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (managerBinder != null) {
            managerBinder.stopPolling();
            unbindService(serviceConnection);
        }
        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
        if (bridge != null) {
            bridge.cleanup();
        }
        if (connectingDialog != null) {
            connectingDialog.dismiss();
            connectingDialog = null;
        }
    }

    private void loadApps() {
        computer = managerBinder.getComputer(pcUuid);
        if (computer == null) {
            Toast.makeText(this, R.string.scut_pc_not_found, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        List<NvApp> apps = getAppListFromCache(pcUuid);
        if (apps == null || apps.isEmpty()) {
            Toast.makeText(this, R.string.no_app_found_for_streaming, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
        appGridAdapter = new AppGridAdapter(this, prefs, computer, managerBinder.getUniqueId(), false);
        
        List<AppView.AppObject> appObjects = new ArrayList<>();
        for (NvApp app : apps) {
            appObjects.add(new AppView.AppObject(app));
        }
        appGridAdapter.rebuildAppList(appObjects);

        bridge = new AdapterRecyclerBridge(this, appGridAdapter);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerView.setAdapter(bridge);

        bridge.setOnItemClickListener((position, item) -> {
            AppView.AppObject obj = (AppView.AppObject) item;
            
            // Check if computer is ready to launch
            if (computer.state == ComputerDetails.State.ONLINE && computer.activeAddress != null) {
                if (computer.runningGameId != 0 && computer.runningGameId != obj.app.getAppId()) {
                    // Another game is running, confirm quit
                    UiHelper.displayQuitConfirmationDialog(this, () -> {
                        ServerHelper.doStart(this, obj.app, computer, managerBinder);
                        finish();
                    }, null);
                } else {
                    ServerHelper.doStart(this, obj.app, computer, managerBinder);
                    finish();
                }
            } else {
                // Not ready, show spinner and wait for callback
                pendingAppLaunch = obj;
                connectingDialog = SpinnerDialog.displayDialog(this, 
                        getString(R.string.conn_establishing_title), 
                        getString(R.string.applist_connect_msg), true);
                
                // Trigger refresh and WoL in background to avoid blocking UI
                new Thread(() -> {
                    // invalidateStateForComputer can block if a poll is in progress
                    // because it needs to acquire the network lock
                    managerBinder.invalidateStateForComputer(pcUuid);
                    
                    if (computer.state == ComputerDetails.State.OFFLINE) {
                        try {
                            WakeOnLanSender.sendWolPacket(computer);
                            // Invalidate again to force a re-poll after WoL
                            managerBinder.invalidateStateForComputer(pcUuid);
                        } catch (IOException e) {
                            LimeLog.warning("Failed to send WoL packet:" + e);
                        }
                    }
                }).start();
            }
        });
    }

    @Override
    public void notifyComputerUpdated(ComputerDetails details) {
        if (!details.uuid.equals(pcUuid)) return;

        this.computer = details;

        if (pendingAppLaunch != null) {
            if (details.state == ComputerDetails.State.ONLINE && details.activeAddress != null) {
                runOnUiThread(() -> {
                    if (connectingDialog != null) {
                        connectingDialog.dismiss();
                        connectingDialog = null;
                    }
                    
                    if (computer.runningGameId != 0 && computer.runningGameId != pendingAppLaunch.app.getAppId()) {
                         // Another game is running, confirm quit
                        UiHelper.displayQuitConfirmationDialog(AppSelectionActivity.this, () -> {
                            ServerHelper.doStart(AppSelectionActivity.this, pendingAppLaunch.app, computer, managerBinder);
                            finish();
                        }, null);
                    } else {
                        ServerHelper.doStart(AppSelectionActivity.this, pendingAppLaunch.app, computer, managerBinder);
                        finish();
                    }
                });
            }
        }
    }

    private List<NvApp> getAppListFromCache(String uuid) {
        try {
            String rawAppList = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuid));
            return rawAppList.isEmpty() ? null : NvHTTP.getAppListByReader(new StringReader(rawAppList));
        } catch (IOException | XmlPullParserException e) {
            LimeLog.warning("Failed to read app list from cache: " + e.getMessage());
            return null;
        }
    }
}
