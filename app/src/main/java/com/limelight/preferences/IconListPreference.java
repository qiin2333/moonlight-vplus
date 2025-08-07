package com.limelight.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.ListPreference;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.Game;

public class IconListPreference extends ListPreference {
    private int[] mEntryIcons;
    private String mOriginalSummary;

    public IconListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.IconListPreference);
        int iconsResId = a.getResourceId(R.styleable.IconListPreference_entryIcons, 0);
        if (iconsResId != 0) {
            TypedArray icons = context.getResources().obtainTypedArray(iconsResId);
            mEntryIcons = new int[icons.length()];
            for (int i = 0; i < icons.length(); i++) {
                mEntryIcons[i] = icons.getResourceId(i, 0);
            }
            icons.recycle();
        }
        a.recycle();
        
        // 保存原始summary用于以后显示
        mOriginalSummary = (String) getSummary();
        
        // 设置值变化监听器
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(android.preference.Preference preference, Object newValue) {
                // 当值变化时更新summary
                updateSummary(newValue.toString());
                return true; // 允许变化发生
            }
        });
        
        // 初始化summary显示当前值
        updateSummary(getValue());
    }

    @Override
    protected void onPrepareDialogBuilder(android.app.AlertDialog.Builder builder) {
        if (getEntries() == null || mEntryIcons == null) {
            super.onPrepareDialogBuilder(builder);
            return;
        }



        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(
                getContext(), R.layout.icon_list_item, R.id.text, getEntries()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ImageView iconView = view.findViewById(R.id.icon);
                if (position < mEntryIcons.length && mEntryIcons[position] != 0) {
                    iconView.setImageResource(mEntryIcons[position]);
                    iconView.setVisibility(View.VISIBLE);
                } else {
                    iconView.setVisibility(View.GONE);
                }
                return view;
            }
        };
        
        builder.setAdapter(adapter, this);
        super.onPrepareDialogBuilder(builder);
    }
    
    @Override
    public void setSummary(CharSequence summary) {
        // 如果不是我们程序化设置的summary，保存它作为原始summary
        if (mOriginalSummary == null || !summary.toString().contains(mOriginalSummary)) {
            mOriginalSummary = summary.toString();
        }
        super.setSummary(summary);
    }
    
    private void updateSummary(String value) {
        // 找到当前值对应的显示文本
        CharSequence[] entries = getEntries();
        CharSequence[] entryValues = getEntryValues();
        
        if (entries == null || entryValues == null) {
            return;
        }
        
        int index = findIndexOfValue(value);
        if (index >= 0) {
            // 组合原始summary和当前选择值
            String currentEntry = entries[index].toString();
            String summary = mOriginalSummary + " (当前：" + currentEntry + ")";
            super.setSummary(summary);
        } else {
            super.setSummary(mOriginalSummary);
        }
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        
        if (positiveResult) {
            // 当对话框关闭并确认后，更新summary
            updateSummary(getValue());
            
            // 如果当前正在游戏中，通知Activity刷新显示位置
            if (getContext() instanceof Game) {
                ((Game) getContext()).refreshDisplayPosition();
            }
        }
    }
    
    // 在旧版API中，需要覆盖这些方法来确保summary更新
    @Override
    protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
        super.onSetInitialValue(restoreValue, defaultValue);
        updateSummary(getValue());
    }
} 