@file:Suppress("DEPRECATION")
package com.limelight.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.limelight.R
import com.limelight.computers.ComputerDatabaseManager
import com.limelight.nvstream.http.ComputerDetails

class WidgetConfigurationActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_configure)

        setResult(RESULT_CANCELED)

        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val recyclerView = findViewById<RecyclerView>(R.id.computer_list)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val dbManager = ComputerDatabaseManager(this)
        val computers = dbManager.getAllComputers()
        dbManager.close()

        recyclerView.adapter = ComputerAdapter(computers)
    }

    private inner class ComputerAdapter(
        private val computers: List<ComputerDetails>
    ) : RecyclerView.Adapter<ComputerAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_widget_computer, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val computer = computers[position]
            holder.nameText.text = computer.name
            holder.statusText.visibility = View.GONE
            holder.itemView.setOnClickListener { saveContext(computer) }
        }

        override fun getItemCount(): Int = computers.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameText: TextView = itemView.findViewById(R.id.computer_name)
            val statusText: TextView = itemView.findViewById(R.id.computer_status)
            val icon: View = itemView.findViewById(R.id.computer_icon)
        }
    }

    private fun saveContext(computer: ComputerDetails) {
        val context: Context = this@WidgetConfigurationActivity

        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit()
        prefs.putString("widget_${appWidgetId}_uuid", computer.uuid)
        prefs.putString("widget_${appWidgetId}_name", computer.name)
        prefs.apply()

        val appWidgetManager = AppWidgetManager.getInstance(context)
        GameListWidgetProvider.updateAppWidget(context, appWidgetManager, appWidgetId)

        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_grid)

        val resultValue = Intent()
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
