package com.limelight.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;

import java.util.List;

public class WidgetConfigurationActivity extends Activity {

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_widget_configure);

        // Set the result to CANCELED.  This will cause the widget host to cancel
        // out of the widget placement if the user presses the back button.
        setResult(RESULT_CANCELED);

        // Find the widget id from the intent.
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        // If this activity was started with an intent without an app widget ID, finish with an error.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        RecyclerView recyclerView = findViewById(R.id.computer_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Get computers
        ComputerDatabaseManager dbManager = new ComputerDatabaseManager(this);
        List<ComputerDetails> computers = dbManager.getAllComputers();
        dbManager.close();

        recyclerView.setAdapter(new ComputerAdapter(computers));
        
        // If no computers, maybe show a message or open main app? 
        // For now, let's just let the list be empty or show a placeholder in the layout.
    }

    private class ComputerAdapter extends RecyclerView.Adapter<ComputerAdapter.ViewHolder> {
        private final List<ComputerDetails> computers;

        ComputerAdapter(List<ComputerDetails> computers) {
            this.computers = computers;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_widget_computer, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ComputerDetails computer = computers.get(position);
            holder.nameText.setText(computer.name);
            
            // Set status text if possible, though ComputerDetails might not have up-to-date status here without polling.
            // For now, we can just show the UUID or IP if available, or keep it simple.
            // Let's hide the status view to keep it clean unless we have useful info.
            holder.statusText.setVisibility(View.GONE);
            
            holder.itemView.setOnClickListener(v -> saveContext(computer));
        }

        @Override
        public int getItemCount() {
            return computers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView nameText;
            TextView statusText;
            View icon;

            ViewHolder(View itemView) {
                super(itemView);
                nameText = itemView.findViewById(R.id.computer_name);
                statusText = itemView.findViewById(R.id.computer_status);
                icon = itemView.findViewById(R.id.computer_icon);
            }
        }
    }

    private void saveContext(ComputerDetails computer) {
        Context context = WidgetConfigurationActivity.this;

        // Save the computer UUID and Name to preferences
        SharedPreferences.Editor prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE).edit();
        prefs.putString("widget_" + appWidgetId + "_uuid", computer.uuid);
        prefs.putString("widget_" + appWidgetId + "_name", computer.name);
        prefs.apply();

        // Push widget update to surface with newly set config
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        GameListWidgetProvider.updateAppWidget(context, appWidgetManager, appWidgetId);
        
        // Notify data changed
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_grid);

        // Make sure we pass back the original appWidgetId
        Intent resultValue = new Intent();
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, resultValue);
        finish();
    }
}
