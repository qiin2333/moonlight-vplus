package com.limelight.preferences;

import android.content.Context;
import android.preference.ListPreference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

public class PerfOverlayOrientationPreference extends ListPreference {
    
    public PerfOverlayOrientationPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public PerfOverlayOrientationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public PerfOverlayOrientationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PerfOverlayOrientationPreference(Context context) {
        super(context);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        
        if (positiveResult) {
            // 当方向改变时，通知位置Preference更新选项
            updatePositionPreference();
        }
    }
    
    private void updatePositionPreference() {
        PreferenceManager preferenceManager = getPreferenceManager();
        if (preferenceManager != null) {
            DynamicPerfOverlayPositionPreference positionPref = 
                (DynamicPerfOverlayPositionPreference) preferenceManager.findPreference("list_perf_overlay_position");
            if (positionPref != null) {
                positionPref.refreshEntries();
            }
        }
    }
    

} 