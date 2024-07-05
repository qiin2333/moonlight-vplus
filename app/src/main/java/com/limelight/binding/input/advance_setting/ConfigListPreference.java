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
    final public static String DEFAULT_CONFIG_ID = "0";
    final public static String CURRENT_CONFIG_NAME_KEY = "current_config_name";
    final private Context context;

    private final SharedPreferences preference;
    private final SharedPreferences.Editor editor;
    private final SharedPreferences defaultPreferences;

    public ConfigListPreference(Context context){
        this.context = context;
        preference = context.getSharedPreferences(CONFIG_LIST_PREFERENCE, Activity.MODE_PRIVATE);
        editor = preference.edit();

        defaultPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        //初始化默认布局：每次创建该对象的时候，都会检查有没有默认布局
        if (!isContainedName(DEFAULT_CONFIG_NAME)){
            addConfiguration(DEFAULT_CONFIG_ID,DEFAULT_CONFIG_NAME);
        }


    }

    public int addConfiguration(String configurationId,String configurationName){
        editor.putString(configurationId, configurationName);
        editor.apply();

        //wg_debug
        System.out.println("wg_debug configurationsMap:" + preference.getAll());
        //新增布局成功
        return 0;
    }

    public int deleteConfig(String configurationId){
        editor.remove(configurationId);
        editor.apply();

        //wg_debug
        System.out.println("wg_debug configurationsMap:" + preference.getAll());
        return 0;
    }

    public int renameConfiguration(String configurationId, String configurationNewName){
        editor.putString(configurationId,configurationNewName);
        editor.apply();
        //重命名成功

        //wg_debug
        System.out.println("wg_debug configurationsMap:" + preference.getAll());
        return 0;
    }

    public Map<String,String> getSortedConfigurationMap(){
        Map<String, String> configurationMap = (Map<String, String>) preference.getAll();
        Map<Long, String> idNameMap = new HashMap<>();
        for (Map.Entry<String, String> entry: configurationMap.entrySet()){
            idNameMap.put(Long.parseLong(entry.getKey()),entry.getValue());
        }
        TreeMap<Long, String> sortedMap = new TreeMap<>(idNameMap);
        TreeMap<String, String> stringKeyMap = new TreeMap<>();

        // 遍历原始的 TreeMap 并将每个键值对插入到新的 TreeMap 中
        for (Map.Entry<Long, String> entry : sortedMap.entrySet()) {
            String key = String.valueOf(entry.getKey()); // 将 Long 转换为 String
            String value = entry.getValue();
            stringKeyMap.put(key, value);
        }
        return stringKeyMap;
    }


    public String getCurrentConfigId() {
        String currentConfigId = defaultPreferences.getString(CURRENT_CONFIG_NAME_KEY,DEFAULT_CONFIG_ID);
        return currentConfigId;
    }

    public void setCurrentConfigId(String currentConfigId) {
        System.out.println("wg_debug currentConfigId:" + currentConfigId);
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        editor.putString(CURRENT_CONFIG_NAME_KEY,currentConfigId);
        editor.apply();
    }

    public boolean isContainedName(String Name){
        return preference.getAll().containsValue(Name);
    }


}
