package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.MultiSelectListPreference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import com.limelight.R;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PerfOverlayDisplayItemsPreference extends MultiSelectListPreference {
    
    private static final String DEFAULT_ITEMS = "resolution,decoder,render_fps,network_latency,decode_latency,host_latency,packet_loss,battery";
    
    public PerfOverlayDisplayItemsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize();
    }

    public PerfOverlayDisplayItemsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public PerfOverlayDisplayItemsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public PerfOverlayDisplayItemsPreference(Context context) {
        super(context);
        initialize();
    }

    private void initialize() {
        setEntries(R.array.perf_overlay_display_items_names);
        setEntryValues(R.array.perf_overlay_display_items_values);
        
        // 设置默认值（所有项目都默认选中）
        String[] defaultValues = DEFAULT_ITEMS.split(",");
        Set<String> defaultSet = new HashSet<>(Arrays.asList(defaultValues));
        setDefaultValue(defaultSet);
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        
        // 如果没有保存的值，设置默认值
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (!prefs.contains(getKey())) {
            String[] defaultValues = DEFAULT_ITEMS.split(",");
            Set<String> defaultSet = new HashSet<>(Arrays.asList(defaultValues));
            setValues(defaultSet);
        }
    }
    
    /**
     * 获取默认的显示项目
     */
    public static Set<String> getDefaultDisplayItems() {
        String[] defaultValues = DEFAULT_ITEMS.split(",");
        return new HashSet<>(Arrays.asList(defaultValues));
    }
    
    /**
     * 检查特定项目是否被选中显示
     */
    public static boolean isItemEnabled(Context context, String itemKey) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> selectedItems = prefs.getStringSet("perf_overlay_display_items", getDefaultDisplayItems());
        return selectedItems.contains(itemKey);
    }
    
    /**
     * 测试用：获取当前选中的所有显示项目
     */
    public static Set<String> getSelectedItems(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getStringSet("perf_overlay_display_items", getDefaultDisplayItems());
    }
    
    /**
     * 测试用：手动设置显示项目
     */
    public static void setDisplayItems(Context context, Set<String> items) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putStringSet("perf_overlay_display_items", items).apply();
    }
} 
