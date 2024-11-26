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
import android.widget.SeekBar;

import com.limelight.Game;
import com.limelight.R;
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
    private DisplayMetrics displayMetrics = new DisplayMetrics();;

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
        this.simplifyPerformance = this;
        ((Game)context).getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
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
        setElementWidth(width);
        setElementHeight(height);
        if (radiusNumberSeekbar != null){
            radiusNumberSeekbar.setProgressMax(Math.min(getElementWidth(), getElementHeight()) / 2);
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
        rect.right = getElementWidth();
        rect.bottom = getElementHeight();
        // 绘制背景
        canvas.drawRoundRect(rect, radius, radius, paintBackground);
        // 绘制文字
        canvas.drawText(afterParseText,0,verticalPadding + textSize ,paintText);

        ElementController.Mode mode = elementController.getMode();
        if (mode == ElementController.Mode.Edit || mode == ElementController.Mode.Select){
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getElementWidth() - 2;
            rect.bottom = getElementHeight() - 2;
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

        textEditText.setText(preParseText);
        textEnsureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setElementPreParseText(textEditText.getText().toString());
                save();
            }
        });

        textResetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setElementPreParseText(SIMPLIFY_PERFORMANCE_TEXT_DEFAULT);
                save();
            }
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

        textColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",textColor));
        textColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new HexInputFilter()});
        textColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    setElementTextColor((int) Long.parseLong(text, 16));
                    save();
                }
            }
        });

        backgroundColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",backgroundColor));
        backgroundColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new HexInputFilter()});
        backgroundColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    setElementBackgroundColor((int) Long.parseLong(text, 16));
                    save();
                }
            }
        });

        copyButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_SIMPLIFY_PERFORMANCE);
                contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, preParseText);
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
                contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,Math.max(Math.min(getElementCentralX() + getElementWidth(),centralXMax),centralXMin));
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
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
    public void save() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_PRE_PARSE_TEXT, preParseText);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, getElementWidth());
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, getElementHeight());
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_SIZE,textSize);
        contentValues.put(COLUMN_INT_SIMPLIFY_PERFORMANCE_TEXT_COLOR,textColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
        elementController.updateElement(elementId,contentValues);

    }

    @Override
    protected void updatePage() {
        if (simplifyPerformancePage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }

    }

    public void setElementPreParseText(String preParseText) {
        if (preParseText.equals("")){
            this.preParseText = "   ";
        } else {
            this.preParseText = preParseText;
        }
        simplifyPerformance.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                radiusNumberSeekbar.setProgressMax(centralXNumberSeekbar.getHeight() / 2);
                simplifyPerformance.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });
    }

    public void setElementRadius(int radius) {
        this.radius = radius;
        invalidate();
    }

    public void setElementVerticalPadding(int verticalPadding) {
        this.verticalPadding = verticalPadding;
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


