package com.limelight.binding.input.advance_setting;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/*
* layout使用Map结构：
* key是用户自己命名的，用来唯一标识
* value是毫秒时间戳，作为layout的id，用这个值来索引layout表。
* */
public class ConfigListPreference {

    final private static String CONFIG_LIST_PREFERENCE = "configuration_list_preference";
    final public static String DEFAULT_CONFIG_NAME = "default";
    final public static String CURRENT_CONFIG_NAME_KEY = "current_config_name";
    final private Context context;
    private String currentConfigName = DEFAULT_CONFIG_NAME;

    private final SharedPreferences preference;
    private final SharedPreferences.Editor editor;

    public ConfigListPreference(Context context){
        this.context = context;
        preference = context.getSharedPreferences(CONFIG_LIST_PREFERENCE, Activity.MODE_PRIVATE);
        editor = preference.edit();

        SharedPreferences defaultPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        currentConfigName = defaultPreferences.getString(CURRENT_CONFIG_NAME_KEY,DEFAULT_CONFIG_NAME);

        //初始化默认布局：每次创建该对象的时候，都会检查有没有默认布局
        if (!isContainedName(DEFAULT_CONFIG_NAME)){
            addConfiguration(DEFAULT_CONFIG_NAME);
        }


    }

    public int addConfiguration(String configurationName){

        String configurationId = String.valueOf(System.currentTimeMillis());
        editor.putString(configurationName, configurationId);
        editor.apply();

        //wg_debug
        System.out.println("wg_debug configurationsMap:" + preference.getAll());
        //新增布局成功
        return 0;
    }

    public int deleteConfig(String configurationName){
        editor.remove(configurationName);
        editor.apply();

        //wg_debug
        System.out.println("wg_debug configurationsMap:" + preference.getAll());
        return 0;
    }

    public int renameConfiguration(String configurationOldName, String configurationNewName){
        String configurationId = ((Map<String, String>)preference.getAll()).get(configurationOldName);
        editor.remove(configurationOldName);
        editor.putString(configurationNewName,configurationId);
        editor.apply();
        //重命名成功

        //wg_debug
        System.out.println("wg_debug configurationsMap:" + preference.getAll());
        return 0;
    }

    public List<String> getSortedConfigurationNames(){
        Map<String, String> configurationMap = (Map<String, String>) preference.getAll();
        Map<Long, String> idNameMap = new HashMap<>();
        for (Map.Entry<String, String> entry: configurationMap.entrySet()){
            idNameMap.put(Long.parseLong(entry.getValue()),entry.getKey());
        }
        TreeMap<Long, String> sortedMap = new TreeMap<>(idNameMap);

        return new ArrayList<>(sortedMap.values());
    }

    public String getConfigId(String configurationName){
        return ((Map<String, String>) preference.getAll()).get(configurationName);
    }

    public String getCurrentConfigName() {
        System.out.println("wg_debug getCurrentConfigName:" + currentConfigName);
        return currentConfigName;
    }

    public void setCurrentConfigName(String currentConfigName) {
        System.out.println("wg_debug setCurrentConfigName:" + currentConfigName);
        this.currentConfigName = currentConfigName;
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.putString(CURRENT_CONFIG_NAME_KEY,currentConfigName);
        editor.apply();
    }

    public boolean isContainedName(String Name){
        return ((Map<String, String>) preference.getAll()).containsKey(Name);
    }


}
