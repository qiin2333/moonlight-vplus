//串流信息
package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;
import com.limelight.utils.ColorPickerDialog;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimplifyPerformance extends Element {
    //总延迟 ≈ (网络延迟 + 排队延迟) + 解码延迟 + 渲染延迟
    private static final String SIMPLIFY_PERFORMANCE_TEXT_DEFAULT = "  带宽: ##带宽##    主机/网络/解码: ##主机延时## / ##网络延时## / ##解码时间##    帧率: ##帧率##    丢帧: ##丢帧率##    渲染:##渲染延迟##    时间:HH:MM:SS";
    private static final String COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE = COLUMN_INT_ELEMENT_THICK;
    private static final String COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR = COLUMN_INT_ELEMENT_NORMAL_COLOR;
    private static final String COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT = COLUMN_STRING_ELEMENT_TEXT;

    private final SimplifyPerformance simplifyPerformance;
    private final Pattern pattern = Pattern.compile("##(.*?)##");
    private final DisplayMetrics displayMetrics = new DisplayMetrics();

    private String afterParseText = "null";

    private String preParseText;
    private int radius;
    private int verticalPadding = 10; // 给一个默认的垂直内边距，使文本看起来更舒适
    private int horizontalPadding = 10; // 增加一个水平内边距
    private int textColor;
    private int textSize;
    private int layer;
    private int backgroundColor;

    // 添加时钟相关字段
    private boolean showClock = false;
    private SimpleDateFormat hourFormat = new SimpleDateFormat("HH", Locale.getDefault());
    private SimpleDateFormat minuteFormat = new SimpleDateFormat("mm", Locale.getDefault());
    private SimpleDateFormat secondFormat = new SimpleDateFormat("ss", Locale.getDefault());

    private SuperPageLayout simplifyPerformancePage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;
    private NumberSeekbar radiusNumberSeekbar;

    private final Paint paintBackground = new Paint();
    private final Paint paintText = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();

    // --- 自驱动心跳机制 ---
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;
    private boolean isHeartbeatActive = false;
    private static final long HEARTBEAT_INTERVAL_MS = 1000; // 1秒心跳间隔
    private final Game.PerformanceInfoDisplay performanceInfoDisplayListener;

    public SimplifyPerformance(Map<String, Object> attributesMap,
                               ElementController controller,
                               Context context) {
        super(attributesMap, controller, context);
        this.simplifyPerformance = this;
        ((Game) context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        super.centralXMax = displayMetrics.widthPixels;
        super.centralXMin = 0;
        super.centralYMax = displayMetrics.heightPixels;
        super.centralYMin = 0;
        super.widthMax = 100000;
        super.widthMin = 0;
        super.heightMax = 100000;
        super.heightMin = 0;

        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));
        paintText.setTextAlign(Paint.Align.LEFT);
        paintBackground.setStyle(Paint.Style.FILL);


        preParseText = (String) attributesMap.get(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT);
        radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
        layer = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_LAYER)).intValue();
        textSize = ((Long) attributesMap.get(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE)).intValue();
        textColor = ((Long) attributesMap.get(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();

        // 检查是否包含时钟占位符
        checkForClockPlaceholder();

        // 1. 创建监听器并持有其引用
        performanceInfoDisplayListener = performanceAttrs -> {
            // 先复制原始文本
            String tempText = preParseText;

            // 处理性能数据占位符
            Matcher matcher = pattern.matcher(tempText);
            StringBuffer sb = new StringBuffer();
            try {
                while (matcher.find()) {
                    String key = matcher.group(1);
                    String replacement = performanceAttrs.getOrDefault(key, "N/A");
                    matcher.appendReplacement(sb, replacement);
                }
                matcher.appendTail(sb);
                tempText = sb.toString();
            } catch (Exception e) {
                // 如果处理出错，保留原始文本
                tempText = preParseText;
            }

            // 处理时钟占位符
            if (showClock) {
                Calendar calendar = Calendar.getInstance();
                String hour = hourFormat.format(calendar.getTime());
                String minute = minuteFormat.format(calendar.getTime());
                String second = secondFormat.format(calendar.getTime());

                tempText = tempText.replace("HH", hour);
                tempText = tempText.replace("MM", minute);
                tempText = tempText.replace("SS", second);
            }

            afterParseText = tempText;

            // 数据更新后，立即重算尺寸
            changeSize();
        };
        ((Game) context).addPerformanceInfoDisplay(performanceInfoDisplayListener);

        // 2. 初始化并启动心跳
        initializeHeartbeat();
        startHeartbeat();
    }

    // --- 心跳和生命周期管理 ---

    private void initializeHeartbeat() {
        heartbeatRunnable = () -> {
            if (isHeartbeatActive) {// 如果显示时钟，则每秒更新一次
                if (showClock) {
                    // 更新时间显示
                    if (!"null".equals(afterParseText) && !afterParseText.isEmpty()) {
                        Calendar calendar = Calendar.getInstance();
                        String hour = hourFormat.format(calendar.getTime());
                        String minute = minuteFormat.format(calendar.getTime());
                        String second = secondFormat.format(calendar.getTime());

                        // 先复制当前文本
                        String tempText = afterParseText;

                        // 更新时间占位符
                        tempText = tempText.replace("HH", hour);
                        tempText = tempText.replace("MM", minute);
                        tempText = tempText.replace("SS", second);

                        afterParseText = tempText;
                    }
                    invalidate();
                } else {
                    invalidate(); // 周期性地请求重绘
                }
                heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
            }
        };
    }

    private void startHeartbeat() {
        if (!isHeartbeatActive) {
            isHeartbeatActive = true;
            heartbeatHandler.post(heartbeatRunnable);
        }
    }

    private void stopHeartbeat() {
        isHeartbeatActive = false;
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
    }

    /**
     * 元素销毁时调用的清理方法
     */
    public void destroy() {
        stopHeartbeat();
        if (getContext() instanceof Game && performanceInfoDisplayListener != null) {
            ((Game) getContext()).removePerformanceInfoDisplay(performanceInfoDisplayListener);
        }
    }

    // --- 尺寸和绘制 ---

    private void changeSize() {
        paintText.setTextSize(textSize);

        // 按换行符分割文本
        String[] lines = afterParseText.split("\n");
        if (lines.length == 0) { // 处理空文本的情况
            setElementWidth(horizontalPadding * 2);
            setElementHeight(verticalPadding * 2);
            return;
        }

        // 计算最长一行的宽度
        float maxWidth = 0;
        for (String line : lines) {
            float lineWidth = paintText.measureText(line);
            if (lineWidth > maxWidth) {
                maxWidth = lineWidth;
            }
        }

        // 计算总高度
        Paint.FontMetrics fontMetrics = paintText.getFontMetrics();
        float singleLineHeight = fontMetrics.bottom - fontMetrics.top;
        int totalTextHeight = (int) (singleLineHeight * lines.length);

        int width = (int) maxWidth + horizontalPadding * 2;
        int height = totalTextHeight + verticalPadding * 2;
        setElementWidth(width);
        setElementHeight(height);

        // 更新半径滑块的最大值
        if (radiusNumberSeekbar != null) {
            radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
        }
        invalidate();
    }


    @Override
    protected void onElementDraw(Canvas canvas) {
        // 设置画笔颜色
        paintText.setColor(textColor);
        paintBackground.setColor(backgroundColor);

        // 定义背景绘制范围
        rect.left = rect.top = 0;
        rect.right = getElementWidth();
        rect.bottom = getElementHeight();

        // 绘制圆角背景
        canvas.drawRoundRect(rect, radius, radius, paintBackground);

        // [修改] 逐行绘制文本以支持换行
        String[] lines = afterParseText.split("\n");
        Paint.FontMetrics fontMetrics = paintText.getFontMetrics();
        float lineHeight = fontMetrics.bottom - fontMetrics.top;

        // 计算第一行文本的基线 (baseline) Y坐标
        // y坐标在 drawText 中是基线的位置，而不是文本的顶部。
        // -fontMetrics.top 可以得到从顶部到基线的距离
        float startY = verticalPadding - fontMetrics.top;

        for (int i = 0; i < lines.length; i++) {
            // 计算当前行的Y坐标
            float currentY = startY + (i * lineHeight);
            // 从水平内边距开始绘制
            canvas.drawText(lines[i], horizontalPadding, currentY, paintText);
        }

        // 绘制编辑模式下的边框
        ElementController.Mode mode = elementController.getMode();
        if (mode == ElementController.Mode.Edit || mode == ElementController.Mode.Select) {
            rect.left = rect.top = 2;
            rect.right = getElementWidth() - 2;
            rect.bottom = getElementHeight() - 2;
            paintEdit.setColor(editColor);
            canvas.drawRect(rect, paintEdit);
        }
    }

    // --- UI 和页面逻辑 ---

    @Override
    protected SuperPageLayout getInfoPage() {
        if (simplifyPerformancePage == null) {
            simplifyPerformancePage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_simplify_performance, null);
            centralXNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_central_x);
            centralYNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_central_y);

        }


        radiusNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_radius);
        EditText textEditText = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text);
        Button textEnsureButton = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text_ensure);
        Button textResetButton = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text_reset);
        NumberSeekbar textSizeNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text_size);
        NumberSeekbar layerNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_layer);
        ElementEditText textColorElementEditText = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text_color);
        ElementEditText backgroundColorElementEditText = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_background_color);
        Button copyButton = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_copy);
        Button deleteButton = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_delete);

        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementCentralX(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });
        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementCentralY(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
        radiusNumberSeekbar.setValueWithNoCallBack(radius);
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementRadius(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        // 使 EditText 支持多行输入
        textEditText.setSingleLine(false);
        textEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textEditText.setGravity(Gravity.TOP | Gravity.START);
        textEditText.setText(preParseText.replace("\\n", "\n"));
        textEnsureButton.setOnClickListener(v -> {
            setElementPreParseText(textEditText.getText().toString());
            save();
        });

        textResetButton.setOnClickListener(v -> {
            setElementPreParseText(SIMPLIFY_PERFORMANCE_TEXT_DEFAULT);
            textEditText.setText(SIMPLIFY_PERFORMANCE_TEXT_DEFAULT);
            save();
        });

        textSizeNumberSeekbar.setProgressMin(10);
        textSizeNumberSeekbar.setProgressMax(50);
        textSizeNumberSeekbar.setValueWithNoCallBack(textSize);
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setElementTextSize(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        layerNumberSeekbar.setValueWithNoCallBack(layer);
        layerNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setElementLayer(seekBar.getProgress());
                save();
            }
        });

        setupColorPickerButton(textColorElementEditText, () -> this.textColor, this::setElementTextColor);
        setupColorPickerButton(backgroundColorElementEditText, () -> this.backgroundColor, this::setElementBackgroundColor);

        copyButton.setOnClickListener(v -> {
            ContentValues contentValues = getSaveContentValues();
            contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + getElementWidth(), centralXMax), centralXMin));
            elementController.addElement(contentValues);
        });

        deleteButton.setOnClickListener(v -> {
            destroy(); // 在删除前清理资源
            elementController.toggleInfoPage(simplifyPerformancePage);
            elementController.deleteElement(simplifyPerformance);
        });

        return simplifyPerformancePage;
    }

    // --- 数据持久化和状态更新 ---

    private ContentValues getSaveContentValues() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_SIMPLIFY_PERFORMANCE);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, preParseText);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, radius);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE, textSize);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR, textColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor);
        return contentValues;
    }

    @Override
    public void save() {
        elementController.updateElement(elementId, getSaveContentValues());
    }

    @Override
    protected void updatePage() {
        if (simplifyPerformancePage != null) {
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }
    }

    // --- Setters ---

    public void setElementPreParseText(String preParseText) {
        this.preParseText = preParseText.isEmpty() ? " " : preParseText;
        checkForClockPlaceholder(); // 检查是否包含时钟占位符
    }

    public void setElementRadius(int radius) {
        this.radius = radius;
        invalidate();
    }



    public void setElementTextColor(int textColor) {
        this.textColor = textColor;
        invalidate();
    }

    public void setElementTextSize(int textSize) {
        this.textSize = textSize;
        changeSize();
    }

    public void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidate();
    }

    // --- 其他 ---

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        return false;
    }

    public static ContentValues getInitialInfo() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_SIMPLIFY_PERFORMANCE);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, SIMPLIFY_PERFORMANCE_TEXT_DEFAULT);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 100);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 20);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, 50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 100);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 100);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS, 19);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE, 30);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR, 0xB3FFFFFF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0xF0555555);
        return contentValues;


    }

    // 添加检查时钟占位符的方法
    private void checkForClockPlaceholder() {
        showClock = preParseText.contains("HH") || preParseText.contains("MM") || preParseText.contains("SS");
    }

    private interface IntSupplier {
        int get();
    }

    private interface IntConsumer {
        void accept(int value);
    }

    /**
     * 更新颜色显示按钮的外观（文本、背景色、文本颜色）。
     */
    private void updateColorDisplay(ElementEditText colorDisplay, int color) {
        // 显示十六进制颜色码
        colorDisplay.setTextWithNoTextChangedCallBack(String.format("%08X", color));
        // 将背景设置为当前颜色
        colorDisplay.setBackgroundColor(color);

        // 根据背景色的亮度自动设置文本颜色为黑色或白色，以确保可读性
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        colorDisplay.setTextColor(luminance > 0.5 ? Color.BLACK : Color.WHITE);
        colorDisplay.setGravity(Gravity.CENTER);
    }

    /**
     * 配置一个 ElementEditText 控件，使其作为颜色选择器按钮使用。
     *
     * @param colorDisplay        用于作为按钮的 ElementEditText 视图。
     * @param initialColorFetcher 一个用于获取当前颜色值的 Lambda 表达式。
     * @param colorUpdater        一个用于设置新颜色值的 Lambda 表达式。
     */
    private void setupColorPickerButton(ElementEditText colorDisplay, IntSupplier initialColorFetcher, IntConsumer colorUpdater) {
        // 禁输入，让 EditText 表现得像一个按钮
        colorDisplay.setFocusable(false);
        colorDisplay.setCursorVisible(false);
        colorDisplay.setKeyListener(null);

        // 使用传入的 Lambda 获取初始颜色并设置外观
        updateColorDisplay(colorDisplay, initialColorFetcher.get());

        // 设置点击监听器，打开颜色选择器
        colorDisplay.setOnClickListener(v -> {
            // 再次获取当前颜色，确保打开时颜色是最新的
            new ColorPickerDialog(
                    getContext(),
                    initialColorFetcher.get(),
                    true, // true 表示显示 Alpha 透明度滑块
                    newColor -> {
                        colorUpdater.accept(newColor); // 使用传入的 Lambda 更新颜色属性
                        save();                      // 保存更改
                        updateColorDisplay(colorDisplay, newColor); // 更新UI显示
                    }
            ).show();
        });
    }
}


