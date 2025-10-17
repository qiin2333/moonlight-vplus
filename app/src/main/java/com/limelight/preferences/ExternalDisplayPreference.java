package com.limelight.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.preference.CheckBoxPreference;
import android.util.AttributeSet;
import android.view.Display;

import com.limelight.ExternalDisplayManager;

/**
 * 外接显示器状态偏好设置
 */
public class ExternalDisplayPreference extends CheckBoxPreference {

    public ExternalDisplayPreference(Context context) {
        super(context);
        init(context);
    }

    public ExternalDisplayPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ExternalDisplayPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        updateSummary();
    }

    @Override
    protected void onAttachedToActivity() {
        super.onAttachedToActivity();
        updateSummary();
    }

    private void updateSummary() {
        try {
            if (ExternalDisplayManager.hasExternalDisplay(getContext())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    DisplayManager displayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
                    if (displayManager != null) {
                        Display[] displays = displayManager.getDisplays();
                        for (Display display : displays) {
                            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                                setSummary("检测到外接显示器: " + display.getName() + " (ID: " + display.getDisplayId() + ")");
                                setEnabled(true);
                                return;
                            }
                        }
                    }
                }
            } else {
                setSummary("未检测到外接显示器");
                setEnabled(false);
                setChecked(false);
            }
        } catch (Exception e) {
            setSummary("检测外接显示器失败: " + e);
            setEnabled(false);
            setChecked(false);
        }
    }
} 