package com.limelight.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

import com.limelight.AppSelectionActivity
import com.limelight.R
import com.limelight.ShortcutTrampoline

class GameListWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (ACTION_REFRESH_WIDGET == intent.action) {
            val computerUuid = intent.getStringExtra(EXTRA_COMPUTER_UUID)
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

            val appWidgetManager = AppWidgetManager.getInstance(context)

            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                refreshWidget(context, appWidgetManager, appWidgetId)
            } else if (computerUuid != null) {
                // Refresh all widgets bound to this computer
                val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, GameListWidgetProvider::class.java))
                val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                for (id in ids) {
                    val widgetUuid = prefs.getString("widget_${id}_uuid", null)
                    if (computerUuid == widgetUuid) {
                        refreshWidget(context, appWidgetManager, id)
                    }
                }
            }
        }
    }

    private fun refreshWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_grid)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit()
        for (appWidgetId in appWidgetIds) {
            editor.remove("widget_${appWidgetId}_uuid")
            editor.remove("widget_${appWidgetId}_name")
        }
        editor.apply()
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.limelight.widget.ACTION_REFRESH_WIDGET"
        const val EXTRA_COMPUTER_UUID = "com.limelight.widget.EXTRA_COMPUTER_UUID"

        @JvmStatic
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val computerName = prefs.getString("widget_${appWidgetId}_name", context.getString(R.string.widget_name))
            val computerUuid = prefs.getString("widget_${appWidgetId}_uuid", null)

            val views: RemoteViews
            if (computerUuid == null) {
                // Not configured yet or invalid
                views = RemoteViews(context.packageName, R.layout.widget_initial_layout)

                val configIntent = Intent(context, WidgetConfigurationActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val configPendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, configIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.empty_view, configPendingIntent)
            } else {
                views = RemoteViews(context.packageName, R.layout.widget_grid_layout)
                views.setTextViewText(R.id.widget_title, computerName)

                // Set up the GridView adapter
                val serviceIntent = Intent(context, GameListWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                serviceIntent.data = Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME))
                views.setRemoteAdapter(R.id.widget_grid, serviceIntent)

                // Set Empty View
                views.setEmptyView(R.id.widget_grid, R.id.widget_empty_view)

                // Set up PendingIntent template for items
                val launchIntent = Intent(context, ShortcutTrampoline::class.java)
                val launchPendingIntent = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.widget_grid, launchPendingIntent)

                // Header click to open AppSelectionActivity
                val headerIntent = Intent(context, AppSelectionActivity::class.java).apply {
                    putExtra("UUID", computerUuid)
                    putExtra("Name", computerName)
                }
                val headerPendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, headerIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_header, headerPendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
