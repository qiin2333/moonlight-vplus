package com.limelight.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.RemoteViews;

import com.limelight.AppSelectionActivity;
import com.limelight.R;
import com.limelight.ShortcutTrampoline;

public class GameListWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH_WIDGET = "com.limelight.widget.ACTION_REFRESH_WIDGET";
    public static final String EXTRA_COMPUTER_UUID = "com.limelight.widget.EXTRA_COMPUTER_UUID";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE);
        String computerName = prefs.getString("widget_" + appWidgetId + "_name", context.getString(R.string.widget_name));
        String computerUuid = prefs.getString("widget_" + appWidgetId + "_uuid", null);

        RemoteViews views;
        if (computerUuid == null) {
            // Not configured yet or invalid
            views = new RemoteViews(context.getPackageName(), R.layout.widget_initial_layout);
            
            // Click to configure
            Intent configIntent = new Intent(context, WidgetConfigurationActivity.class);
            configIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            configIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent configPendingIntent = PendingIntent.getActivity(context, appWidgetId, configIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.empty_view, configPendingIntent);
            
        } else {
            views = new RemoteViews(context.getPackageName(), R.layout.widget_grid_layout);
            views.setTextViewText(R.id.widget_title, computerName);

            // Set up the GridView adapter
            Intent serviceIntent = new Intent(context, GameListWidgetService.class);
            serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(R.id.widget_grid, serviceIntent);
            
            // Set Empty View
            views.setEmptyView(R.id.widget_grid, R.id.widget_empty_view);

            // Set up PendingIntent template for items
            // We use ShortcutTrampoline which handles the connection and launch
            Intent launchIntent = new Intent(context, ShortcutTrampoline.class);
            // We need to set action to something unique or standard to ensure extras are delivered?
            // Actually ShortcutTrampoline expects action MAIN or similar usually for standard shortcuts.
            // But here we just need to pass extras.
            // Let's check ShortcutTrampoline logic. It reads extras.
            
            PendingIntent launchPendingIntent = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            views.setPendingIntentTemplate(R.id.widget_grid, launchPendingIntent);

            // Header click to open AppSelectionActivity (View all apps / Connect)
            Intent headerIntent = new Intent(context, AppSelectionActivity.class);
            headerIntent.putExtra("UUID", computerUuid);
            headerIntent.putExtra("Name", computerName);
            PendingIntent headerPendingIntent = PendingIntent.getActivity(context, appWidgetId, headerIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_header, headerPendingIntent);
            

        }

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
    
    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        
        if (ACTION_REFRESH_WIDGET.equals(intent.getAction())) {
            String computerUuid = intent.getStringExtra(EXTRA_COMPUTER_UUID);
            int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            
            AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
            
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                refreshWidget(context, appWidgetManager, appWidgetId);
            } else if (computerUuid != null) {
                // Refresh all widgets bound to this computer
                int[] ids = appWidgetManager.getAppWidgetIds(new ComponentName(context, GameListWidgetProvider.class));
                SharedPreferences prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE);
                for (int id : ids) {
                    String widgetUuid = prefs.getString("widget_" + id + "_uuid", null);
                    if (computerUuid.equals(widgetUuid)) {
                        refreshWidget(context, appWidgetManager, id);
                    }
                }
            }
        }
    }

    private void refreshWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_grid);
        // Also update the full widget to refresh title/layout
        updateAppWidget(context, appWidgetManager, appWidgetId);
    }
    
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor editor = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit();
        for (int appWidgetId : appWidgetIds) {
            editor.remove("widget_" + appWidgetId + "_uuid");
            editor.remove("widget_" + appWidgetId + "_name");
        }
        editor.apply();
    }
}
