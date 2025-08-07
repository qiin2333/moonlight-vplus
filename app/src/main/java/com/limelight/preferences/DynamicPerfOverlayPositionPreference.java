package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.ListPreference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import com.limelight.R;

public class DynamicPerfOverlayPositionPreference extends ListPreference {
    
    public DynamicPerfOverlayPositionPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        updateEntries();
    }

    public DynamicPerfOverlayPositionPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateEntries();
    }

    public DynamicPerfOverlayPositionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateEntries();
    }

    public DynamicPerfOverlayPositionPreference(Context context) {
        super(context);
        updateEntries();
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        updateEntries();
    }

    private void updateEntries() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        String orientation = prefs.getString("list_perf_overlay_orientation", "horizontal");
        
        if ("vertical".equals(orientation)) {
            // 垂直方向：显示四个角选项
            setEntries(R.array.perf_overlay_position_vertical_names);
            setEntryValues(R.array.perf_overlay_position_vertical_values);
            
            // 如果当前值不在垂直选项中，重置为默认值
            String currentValue = getValue();
            if (currentValue != null && !isValidVerticalValue(currentValue)) {
                setValue("top_left");
            }
        } else {
            // 水平方向：显示顶部和底部选项
            setEntries(R.array.perf_overlay_position_horizontal_names);
            setEntryValues(R.array.perf_overlay_position_horizontal_values);
            
            // 如果当前值不在水平选项中，重置为默认值
            String currentValue = getValue();
            if (currentValue != null && !isValidHorizontalValue(currentValue)) {
                setValue("top");
            }
        }
    }
    
    private boolean isValidVerticalValue(String value) {
        return "top_left".equals(value) || "top_right".equals(value) || 
               "bottom_left".equals(value) || "bottom_right".equals(value);
    }
    
    private boolean isValidHorizontalValue(String value) {
        return "top".equals(value) || "bottom".equals(value);
    }
    
    public void refreshEntries() {
        updateEntries();
    }
    
    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        
        if (positiveResult) {
            // 当位置改变时，清除自定义拖动位置
            clearCustomPosition();
        }
    }
    
    private void clearCustomPosition() {
        SharedPreferences prefs = getContext().getSharedPreferences("performance_overlay", Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }
    

} 