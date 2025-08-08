package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.DialogPreference;
import android.text.Editable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.limelight.R;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自定义分辨率常量类
 */
class CustomResolutionsConsts {
    public static final String CUSTOM_RESOLUTIONS_FILE = "custom_resolutions";
    public static final String CUSTOM_RESOLUTIONS_KEY = "custom_resolutions";
}

/**
 * 分辨率验证工具类
 */
class ResolutionValidator {
    private static final int MIN_WIDTH = 320;
    private static final int MAX_WIDTH = 7680;
    private static final int MIN_HEIGHT = 240;
    private static final int MAX_HEIGHT = 4320;

    private static final String RESOLUTION_REGEX = "^\\d{3,5}x\\d{3,5}$";
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile(RESOLUTION_REGEX);

    /**
     * 验证分辨率字符串格式
     */
    public static boolean isValidResolutionFormat(String resolution) {
        return RESOLUTION_PATTERN.matcher(resolution).matches();
    }

    /**
     * 验证宽度范围
     */
    public static boolean isValidWidth(int width) {
        return width >= MIN_WIDTH && width <= MAX_WIDTH;
    }

    /**
     * 验证高度范围
     */
    public static boolean isValidHeight(int height) {
        return height >= MIN_HEIGHT && height <= MAX_HEIGHT;
    }

    /**
     * 验证是否为偶数
     */
    public static boolean isEven(int value) {
        return value % 2 == 0;
    }

    /**
     * 解析分辨率字符串为宽度和高度
     */
    public static Resolution parseResolution(String resolution) {
        try {
            String[] parts = resolution.split("x");
            if (parts.length != 2) {
                return null;
            }
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            return new Resolution(width, height);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 分辨率数据类
     */
    public static class Resolution {
        public final int width;
        public final int height;

        public Resolution(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }
}

/**
 * 自定义分辨率偏好设置类
 */
public class CustomResolutionsPreference extends DialogPreference {
    private final Context context;
    private final CustomResolutionsAdapter adapter;

    public CustomResolutionsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;
        this.adapter = new CustomResolutionsAdapter(context);
        adapter.setOnDataChangedListener(new UpdateStorageEventListener());
    }

    /**
     * 处理分辨率提交
     */
    void onSubmitResolution(EditText widthField, EditText heightField) {
        // 清除之前的错误
        clearErrors(widthField, heightField);

        // 获取输入值
        String widthText = widthField.getText().toString().trim();
        String heightText = heightField.getText().toString().trim();

        // 验证输入
        ResolutionValidationResult validation = validateInput(widthText, heightText);
        if (!validation.isValid()) {
            showValidationErrors(validation, widthField, heightField);
            return;
        }

        // 创建分辨率字符串
        String resolution = validation.getResolution().toString();

        // 检查是否已存在
        if (adapter.exists(resolution)) {
            Toast.makeText(context, context.getString(R.string.resolution_already_exists), Toast.LENGTH_SHORT).show();
            return;
        }

        // 添加分辨率
        addResolution(resolution, widthField, heightField);
    }

    /**
     * 清除输入框错误
     */
    private void clearErrors(EditText widthField, EditText heightField) {
        widthField.setError(null);
        heightField.setError(null);
    }

    /**
     * 验证输入
     */
    private ResolutionValidationResult validateInput(String widthText, String heightText) {
        // 检查空值
        if (widthText.isEmpty()) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.WIDTH_EMPTY);
        }
        if (heightText.isEmpty()) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.HEIGHT_EMPTY);
        }

        // 解析数字
        int width, height;
        try {
            width = Integer.parseInt(widthText);
            height = Integer.parseInt(heightText);
        } catch (NumberFormatException e) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.INVALID_FORMAT);
        }

        // 验证范围
        if (!ResolutionValidator.isValidWidth(width)) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.WIDTH_OUT_OF_RANGE);
        }
        if (!ResolutionValidator.isValidHeight(height)) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.HEIGHT_OUT_OF_RANGE);
        }

        // 验证偶数
        if (!ResolutionValidator.isEven(width)) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.WIDTH_ODD);
        }
        if (!ResolutionValidator.isEven(height)) {
            return ResolutionValidationResult.error(ResolutionValidationResult.ErrorType.HEIGHT_ODD);
        }

        return ResolutionValidationResult.success(new ResolutionValidator.Resolution(width, height));
    }

    /**
     * 显示验证错误
     */
    private void showValidationErrors(ResolutionValidationResult validation, EditText widthField, EditText heightField) {
        String errorMessage = getErrorMessage(validation.getErrorType());

        switch (validation.getErrorType()) {
            case WIDTH_EMPTY:
            case WIDTH_OUT_OF_RANGE:
            case WIDTH_ODD:
            case INVALID_FORMAT:
                widthField.setError(errorMessage);
                break;
            case HEIGHT_EMPTY:
            case HEIGHT_OUT_OF_RANGE:
            case HEIGHT_ODD:
                heightField.setError(errorMessage);
                break;
        }
    }

    /**
     * 获取错误消息
     */
    private String getErrorMessage(ResolutionValidationResult.ErrorType errorType) {
        switch (errorType) {
            case WIDTH_EMPTY:
            case HEIGHT_EMPTY:
                return context.getString(R.string.width_hint);
            case INVALID_FORMAT:
                return context.getString(R.string.invalid_resolution_format);
            case WIDTH_OUT_OF_RANGE:
                return "宽度应在320-7680之间";
            case HEIGHT_OUT_OF_RANGE:
                return "高度应在240-4320之间";
            case WIDTH_ODD:
                return "宽度不能为奇数";
            case HEIGHT_ODD:
                return "高度不能为奇数";
            default:
                return context.getString(R.string.invalid_resolution_format);
        }
    }

    /**
     * 添加分辨率
     */
    private void addResolution(String resolution, EditText widthField, EditText heightField) {
        adapter.addItem(resolution);
        Toast.makeText(context, context.getString(R.string.resolution_added_successfully), Toast.LENGTH_SHORT).show();

        // 清空输入框并设置焦点
        clearInputFields(widthField, heightField);
    }

    /**
     * 清空输入框
     */
    private void clearInputFields(EditText widthField, EditText heightField) {
        widthField.setText("");
        heightField.setText("");
        widthField.requestFocus();
    }

    @Override
    protected void onBindDialogView(View view) {
        loadStoredResolutions();
        super.onBindDialogView(view);
    }

    /**
     * 加载存储的分辨率
     */
    private void loadStoredResolutions() {
        SharedPreferences prefs = context.getSharedPreferences(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE, Context.MODE_PRIVATE);
        Set<String> stored = prefs.getStringSet(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY, null);

        if (stored == null) return;

        ArrayList<String> sortedList = sortResolutions(new ArrayList<>(stored));
        adapter.addAll(sortedList);
    }

    /**
     * 排序分辨率列表
     */
    private ArrayList<String> sortResolutions(ArrayList<String> resolutions) {
        Collections.sort(resolutions, new ResolutionComparator());
        return resolutions;
    }

    @Override
    protected View onCreateDialogView() {
        LinearLayout body = createMainLayout();
        ListView list = createListView();
        View inputRow = createInputRow();

        body.addView(list);
        body.addView(inputRow);

        return body;
    }

    /**
     * 创建主布局
     */
    private LinearLayout createMainLayout() {
        LinearLayout body = new LinearLayout(context);
        
        // 设置弹窗宽度为屏幕宽度的80%，最小宽度400dp
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int dialogWidth = Math.min((int) (screenWidth * 0.8), dpToPx(400));
        
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                dialogWidth,
                AbsListView.LayoutParams.WRAP_CONTENT
        );
        layoutParams.gravity = Gravity.CENTER;
        body.setLayoutParams(layoutParams);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        
        return body;
    }
    
    /**
     * 将dp转换为px
     */
    private int dpToPx(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    /**
     * 创建列表视图
     */
    private ListView createListView() {
        ListView list = new ListView(context);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        list.setLayoutParams(layoutParams);
        list.setAdapter(adapter);
        list.setDividerHeight(dpToPx(1));
        list.setDivider(context.getResources().getDrawable(android.R.color.darker_gray));
        return list;
    }

    /**
     * 创建输入行
     */
    private View createInputRow() {
        LayoutInflater inflater = LayoutInflater.from(context);
        View inputRow = inflater.inflate(R.layout.custom_resolutions_form, null);

        EditText widthField = inputRow.findViewById(R.id.custom_resolution_width_field);
        EditText heightField = inputRow.findViewById(R.id.custom_resolution_height_field);
        Button addButton = inputRow.findViewById(R.id.add_resolution_button);

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AbsListView.LayoutParams.WRAP_CONTENT
        );
        layoutParams.topMargin = dpToPx(16);
        inputRow.setLayoutParams(layoutParams);

        setupInputListeners(widthField, heightField, addButton);

        return inputRow;
    }

    /**
     * 设置输入监听器
     */
    private void setupInputListeners(EditText widthField, EditText heightField, Button addButton) {
        // 设置按钮点击事件
        addButton.setOnClickListener(view -> onSubmitResolution(widthField, heightField));

        // 设置回车键监听
        heightField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                onSubmitResolution(widthField, heightField);
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        StreamSettings settingsActivity = (StreamSettings) getContext();
        settingsActivity.reloadSettings();
    }

    /**
     * 存储更新事件监听器
     */
    private class UpdateStorageEventListener implements EventListener {
        @Override
        public void onTrigger() {
            saveResolutions();
        }
    }

    /**
     * 保存分辨率到存储
     */
    private void saveResolutions() {
        SharedPreferences prefs = context.getSharedPreferences(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Set<String> set = new HashSet<>(adapter.getAll());
        editor.putStringSet(CustomResolutionsConsts.CUSTOM_RESOLUTIONS_KEY, set).apply();
    }
}

/**
 * 事件监听器接口
 */
interface EventListener {
    void onTrigger();
}

/**
 * 分辨率验证结果类
 */
class ResolutionValidationResult {
    private final boolean valid;
    private final ResolutionValidator.Resolution resolution;
    private final ErrorType errorType;

    private ResolutionValidationResult(boolean valid, ResolutionValidator.Resolution resolution, ErrorType errorType) {
        this.valid = valid;
        this.resolution = resolution;
        this.errorType = errorType;
    }

    public static ResolutionValidationResult success(ResolutionValidator.Resolution resolution) {
        return new ResolutionValidationResult(true, resolution, null);
    }

    public static ResolutionValidationResult error(ErrorType errorType) {
        return new ResolutionValidationResult(false, null, errorType);
    }

    public boolean isValid() {
        return valid;
    }

    public ResolutionValidator.Resolution getResolution() {
        return resolution;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public enum ErrorType {
        WIDTH_EMPTY,
        HEIGHT_EMPTY,
        INVALID_FORMAT,
        WIDTH_OUT_OF_RANGE,
        HEIGHT_OUT_OF_RANGE,
        WIDTH_ODD,
        HEIGHT_ODD
    }
}

/**
 * 分辨率比较器
 */
class ResolutionComparator implements Comparator<String> {
    @Override
    public int compare(String s1, String s2) {
        ResolutionValidator.Resolution res1 = ResolutionValidator.parseResolution(s1);
        ResolutionValidator.Resolution res2 = ResolutionValidator.parseResolution(s2);

        if (res1 == null || res2 == null) {
            return s1.compareTo(s2);
        }

        if (res1.width == res2.width) {
            return Integer.compare(res1.height, res2.height);
        }
        return Integer.compare(res1.width, res2.width);
    }
}

/**
 * 自定义分辨率适配器
 */
class CustomResolutionsAdapter extends BaseAdapter {
    private final ArrayList<String> resolutions = new ArrayList<>();
    private final Context context;
    private EventListener listener;

    public CustomResolutionsAdapter(Context context) {
        this.context = context;
    }

    public void setOnDataChangedListener(EventListener listener) {
        this.listener = listener;
    }

    @Override
    public void notifyDataSetChanged() {
        if (listener != null) {
            listener.onTrigger();
        }
        super.notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = createListItemView();
        }

        setupListItemView(convertView, position);
        return convertView;
    }

    /**
     * 创建列表项视图
     */
    private View createListItemView() {
        LinearLayout row = new LinearLayout(context);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        row.setLayoutParams(layoutParams);
        row.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView listItemText = new TextView(context);
        ImageButton deleteButton = new ImageButton(context);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.weight = 1;
        textParams.gravity = Gravity.CENTER_VERTICAL;
        textParams.leftMargin = dpToPx(8);
        listItemText.setLayoutParams(textParams);
        listItemText.setTextSize(16);

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48));
        buttonParams.gravity = Gravity.CENTER_VERTICAL;
        deleteButton.setLayoutParams(buttonParams);
        deleteButton.setImageResource(R.drawable.ic_delete);
        deleteButton.setBackgroundResource(android.R.color.transparent);
        deleteButton.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

        row.addView(listItemText);
        row.addView(deleteButton);

        return row;
    }

    /**
     * 设置列表项视图
     */
    private void setupListItemView(View convertView, int position) {
        LinearLayout row = (LinearLayout) convertView;
        TextView listItemText = (TextView) row.getChildAt(0);
        ImageButton deleteButton = (ImageButton) row.getChildAt(1);

        listItemText.setText(resolutions.get(position));

        // 设置删除按钮点击事件
        deleteButton.setOnClickListener(view -> {
            resolutions.remove(position);
            notifyDataSetChanged();
        });
    }

    @Override
    public int getCount() {
        return resolutions.size();
    }

    @Override
    public Object getItem(int position) {
        return resolutions.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void addItem(String value) {
        if (!resolutions.contains(value)) {
            resolutions.add(value);
            notifyDataSetChanged();
        }
    }

    public ArrayList<String> getAll() {
        return new ArrayList<>(resolutions);
    }

    public void addAll(ArrayList<String> list) {
        this.resolutions.addAll(list);
        notifyDataSetChanged();
    }

    public boolean exists(String item) {
        return resolutions.contains(item);
    }

    /**
     * 将dp转换为px
     */
    private int dpToPx(int value) {
        float density = this.context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}