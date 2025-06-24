package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.util.AttributeSet;
import android.widget.Toast;

import com.limelight.R;

public class ResetPerfOverlayPositionPreference extends Preference {
    
    public ResetPerfOverlayPositionPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ResetPerfOverlayPositionPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ResetPerfOverlayPositionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ResetPerfOverlayPositionPreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        super.onClick();
        
        // 清除自定义位置设置
        SharedPreferences prefs = getContext().getSharedPreferences("performance_overlay", Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        
        Toast.makeText(getContext(), "性能统计位置已重置", Toast.LENGTH_SHORT).show();
    }
} 