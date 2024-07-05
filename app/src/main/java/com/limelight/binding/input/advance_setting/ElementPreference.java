package com.limelight.binding.input.advance_setting;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElementPreference {

    private final static String ELEMENT_TABLE_PREFIX = "element_";

    private final String elementPreferenceName;
    //private final Map<String, ElementBean> elements = new HashMap<>();
    private final Context context;
    private final Gson gson = new Gson();
    private final SharedPreferences preferences;
    private final SharedPreferences.Editor editor;

    public ElementPreference(String configId, Context context){
        this.context = context;
        this.elementPreferenceName = getElementLayoutName(configId);

        // 对象创建的时候读取按钮信息
        preferences = context.getSharedPreferences(elementPreferenceName,Activity.MODE_PRIVATE);
        editor = preferences.edit();
    }

    private static String getElementLayoutName(String configId){
        return ELEMENT_TABLE_PREFIX + configId;
    }

    public int addElement(ElementBean element){
        String elementName = element.getId();
        String elementString = JSONToString(element);
        editor.putString(elementName, elementString);
        editor.apply();
        //新增成功

        return 0;

    }

    public void deleteElement(String elementId){
        editor.remove(elementId);
        editor.apply();

    }
    public List<ElementBean> getElements() {
        List<ElementBean> list = new ArrayList<>();

        // 从SharePreference中获取Map<String, String>格式的Element信息
        Map<String, String> elementsString = (Map<String, String>) preferences.getAll();
        // 将Map<String, String>格式转换为Map<String, KeyboardBean>
        for (Map.Entry<String, String> entry: elementsString.entrySet()){
            ElementBean elementBean = stringToJSON(entry.getValue());
            // 转换完成后放到List<ElementBean>中
            list.add(elementBean);
            System.out.println(elementBean.toString());
        }

        //对element根据创建时间进行排序
        Collections.sort(list, new Comparator<ElementBean>() {
            @Override
            public int compare(ElementBean o1, ElementBean o2) {
                return Long.compare(o1.getCreateTime(), o2.getCreateTime());
            }
        });
        return list;
    }


    private ElementBean stringToJSON(String element){
        //这个elementBean的原因是因为Gson会报错，无法转换Map，但是新建一个elementBean就可以了，可能是创建之后，这个对象并没有被回收，于是就被gson使用了
        new ElementBean(
                null,
            null,
            "BUTTON",
            new HashMap<>(),
            0,
            0,
            0,
            0,
            100,
            0xF0888888,
            0xF00000FF,
            0,
            System.currentTimeMillis(),
            new HashMap<>());
        return gson.fromJson(element, ElementBean.class);
    }

    private String JSONToString(ElementBean element){
        return gson.toJson(element);
    }

    public void delete(){
        editor.clear();
        editor.apply();
        new File(context.getFilesDir().getParent() + "/shared_prefs/" + elementPreferenceName + ".xml").delete();
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
