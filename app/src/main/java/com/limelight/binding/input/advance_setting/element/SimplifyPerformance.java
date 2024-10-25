package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SimplifyPerformance extends Element {

    private static final String SIMPLIFY_PERFORMANCE_TEXT_DEFAULT = "  带宽: ##band_width##    主机/网络/解码: ##host_latency## / ##net_latency## / ##decode_time##    帧率: ##fps##    丢帧: ##lost_frame##  ";
    private static final String COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE = COLUMN_INT_ELEMENT_THICK;
    private static final String COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR = COLUMN_INT_ELEMENT_NORMAL_COLOR;
    private static final String COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT = COLUMN_STRING_ELEMENT_TEXT;

    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private SimplifyPerformance simplifyPerformance;
    private Pattern pattern = Pattern.compile("##(.*?)##");
    private DisplayMetrics displayMetrics;

    private String afterParseText = "null";

    private String preParseText;
    private int radius;
    private int verticalPadding;
    private int textColor;
    private int textSize;
    private int layer;
    private int backgroundColor;

    private SuperPageLayout simplifyPerformancePage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;
    private NumberSeekbar radiusNumberSeekbar;

    private final Paint paintBackground = new Paint();
    private final Paint paintText = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();

    public SimplifyPerformance(Map<String,Object> attributesMap,
                               ElementController controller,
                               Context context) {
        super(attributesMap,controller,context);
        this.superConfigDatabaseHelper = controller.getSuperConfigDatabaseHelper();
        this.simplifyPerformance = this;

        displayMetrics = context.getResources().getDisplayMetrics();
        super.centralXMax  = displayMetrics.widthPixels;
        super.centralXMin  = 0;
        super.centralYMax  = displayMetrics.heightPixels;
        super.centralYMin  = 0;
        super.widthMax  = 100000;
        super.widthMin  = 0;
        super.heightMax  = 100000;
        super.heightMin  = 0;

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

        ((Game)context).addPerformanceInfoDisplay(new Game.PerformanceInfoDisplay() {
            @Override
            public void display(Map<String, String> performanceAttrs) {
                Matcher matcher = pattern.matcher(preParseText);
                StringBuffer sb = new StringBuffer();
                try {
                    while (matcher.find()) {
                        String key = matcher.group(1); // 获取 ##中间的内容##
                        String replacement = performanceAttrs.getOrDefault(key, ""); // 从 perfAttrs 中获取相应的值
                        matcher.appendReplacement(sb, replacement);
                    }
                    matcher.appendTail(sb);
                    afterParseText = sb.toString();
                } catch (Exception e){
                    afterParseText = "error";
                }
                changeSize();
            }
        });
    }

    private void changeSize(){
        paintText.setTextSize(textSize);
        int textWidth = (int) paintText.measureText(afterParseText);
        Paint.FontMetrics fontMetrics = paintText.getFontMetrics();
        int textHeight = (int) (fontMetrics.bottom - fontMetrics.top);
        int width = textWidth;
        int height = textHeight + verticalPadding + verticalPadding;
        setParamWidth(width);
        setParamHeight(height);
        if (radiusNumberSeekbar != null){
            radiusNumberSeekbar.setProgressMax(Math.min(getParamWidth(),getParamHeight()) / 2);
        }


    }

    @Override
    protected void onElementDraw(Canvas canvas) {

        // 文字
        paintText.setColor(textColor);
        // 背景颜色
        paintBackground.setColor(backgroundColor);
        // 绘画范围
        rect.left = rect.top = 0;
        rect.right = getParamWidth();
        rect.bottom = getParamHeight();
        // 绘制背景
        canvas.drawRoundRect(rect, radius, radius, paintBackground);
        // 绘制文字
        canvas.drawText(afterParseText,0,verticalPadding + textSize ,paintText);

        if (elementController.getMode() == ElementController.Mode.Edit){
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getParamWidth() - 2;
            rect.bottom = getParamHeight() - 2;
            // 边框
            paintEdit.setColor(editColor);
            canvas.drawRect(rect,paintEdit);

        }
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (simplifyPerformancePage == null){
            simplifyPerformancePage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_simplify_performance,null);
            centralXNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_central_x);
            centralYNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_central_y);

        }


        radiusNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_radius);
        EditText textEditText = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text);
        Button textEnsureButton = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text_ensure);
        Button textResetButton = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text_reset);
        NumberSeekbar textSizeNumberSeekbar = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text_size);
        ElementEditText textColorElementEditText = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_text_color);
        ElementEditText backgroundColorElementEditText = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_background_color);
        Button copyButton = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_copy);
        Button deleteButton = simplifyPerformancePage.findViewById(R.id.page_simplify_performance_delete);

        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getParamCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                setParamCentralX(progress);
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,getParamCentralX());
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });
        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getParamCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                setParamCentralY(progress);
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,getParamCentralY());
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });

        radiusNumberSeekbar.setProgressMax(Math.min(getParamWidth(),getParamHeight()) / 2);
        radiusNumberSeekbar.setValueWithNoCallBack(radius);
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                radius = progress;
                invalidate();
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });

        textEditText.setText(preParseText);
        textEnsureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newText = textEditText.getText().toString();
                if (newText.equals("")){
                    preParseText = "   ";
                } else {
                    preParseText = newText;
                }
                simplifyPerformance.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        radiusNumberSeekbar.setProgressMax(centralXNumberSeekbar.getHeight() / 2);
                        simplifyPerformance.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                });
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, preParseText);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });

        textResetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, preParseText);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });

        textSizeNumberSeekbar.setProgressMin(10);
        textSizeNumberSeekbar.setProgressMax(50);
        textSizeNumberSeekbar.setValueWithNoCallBack(textSize);
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                textSize = progress;
                changeSize();
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE, lastProgress);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });

        textColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",textColor));
        textColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new HexInputFilter()});
        textColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    textColor = (int) Long.parseLong(text, 16);
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR, textColor);
                    superConfigDatabaseHelper.updateElement(elementId,contentValues);
                }
            }
        });

        backgroundColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",backgroundColor));
        backgroundColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new HexInputFilter()});
        backgroundColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    backgroundColor = (int) Long.parseLong(text, 16);
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor);
                    superConfigDatabaseHelper.updateElement(elementId,contentValues);
                }
            }
        });

        copyButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_SIMPLIFY_PERFORMANCE);
                contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, preParseText);
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH,getParamWidth());
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,getParamHeight());
                contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,Math.max(Math.min(getParamCentralX() + getParamWidth(),centralXMax),centralXMin));
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,getParamCentralY());
                contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
                contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE,textSize);
                contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR,textColor);
                contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
                elementController.addElement(contentValues);
            }
        });

        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                elementController.toggleInfoPage(simplifyPerformancePage);
                elementController.deleteElement(simplifyPerformance);
            }
        });

        return simplifyPerformancePage;
    }

    @Override
    public void updateDataBase() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,getParamCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,getParamCentralY());
        superConfigDatabaseHelper.updateElement(elementId,contentValues);

    }

    @Override
    protected void updatePageInfo() {
        if (simplifyPerformancePage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getParamCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getParamCentralY());
        }

    }
    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        return true;
    }

    public static ContentValues getInitialInfo(){
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_SIMPLIFY_PERFORMANCE);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT,SIMPLIFY_PERFORMANCE_TEXT_DEFAULT);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 100);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,20);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,100);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,100);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,19);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE,30);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR,0xB3FFFFFF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,0xF0555555);
        return contentValues;


    }
}


