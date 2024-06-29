package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;

public class SettingPreference {

    public static final String SETTING_TABLE_PREFIX = "setting_";

    private final SharedPreferences preferences;
    private final SharedPreferences.Editor editor;
    private final Context context;
    private final String settingPreferenceName;

    public SettingPreference(String configId, Context context){
        this.context = context;
        this.settingPreferenceName = getSettingLayoutName(configId);
        preferences = context.getSharedPreferences(settingPreferenceName,Context.MODE_PRIVATE);
        editor = preferences.edit();
    }

    private static String getSettingLayoutName(String configId){
        return SETTING_TABLE_PREFIX + configId;
    }

    public Map<String, String> getSettings(){
        return (Map<String, String>) preferences.getAll();
    }

    public void saveSetting(String name,String value){
        editor.putString(name,value);
        editor.apply();
    }

    public void delete(){
        editor.clear();
        editor.apply();
        new File(context.getFilesDir().getParent() + "/shared_prefs/" + settingPreferenceName + ".xml").delete();
    }

    public void importPreference(Map<String, String> elements){
        for (Map.Entry<String, String> entry: elements.entrySet()){
            editor.putString(entry.getKey(),entry.getValue());
            editor.apply();
        }
    }

    public Map<String, String> exportPreference(){
        return (Map<String, String>) preferences.getAll();
    }
}
