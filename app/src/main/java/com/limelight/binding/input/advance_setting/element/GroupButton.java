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
import android.widget.Button;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
public class GroupButton extends Element {

    /**
     * Listener interface to update registered observers.
     */
    public interface GroupButtonListener {

        /**
         * onClick event will be fired on button click.
         */
        void onClick();

        /**
         * onLongClick event will be fired on button long click.
         */
        void onLongClick();

        /**
         * onRelease event will be fired on button unpress.
         */
        void onRelease();
    }

    private interface ToastShower {
        void show();
    }

    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private PageDeviceController pageDeviceController;
    private GroupButton groupButton;
    private List<Element> childElementList = new ArrayList<>();
    private ElementController elementController;
    private ToastShower toastShower;

    private GroupButtonListener listener;
    private boolean childVisible = true;
    private String text;
    private String value;
    private int radius;
    private int layer;
    private int thick;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;

    private SuperPageLayout groupButtonPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;


    private float lastX;
    private float lastY;
    private long timerLongClickTimeout = 3000;
    private final Runnable longClickRunnable = new Runnable() {
        @Override
        public void run() {
            onLongClickCallback();
        }
    };
    private final Paint paintBorder = new Paint();
    private final Paint paintBackground = new Paint();
    private final Paint paintText = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();



    public GroupButton(Map<String,Object> attributesMap,
                       ElementController controller,
                       PageDeviceController pageDeviceController, Context context) {
        super(attributesMap,controller,context);
        this.superConfigDatabaseHelper = controller.getSuperConfigDatabaseHelper();
        this.pageDeviceController = pageDeviceController;
        this.groupButton = this;
        this.elementController = controller;
        toastShower = new ToastShower() {
            @Override
            public void show() {
                Toast.makeText(context,"按键添加成功,处于隐藏状态",Toast.LENGTH_SHORT).show();
            }
        };


        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        super.centralXMax  = displayMetrics.widthPixels;
        super.centralXMin  = 0;
        super.centralYMax  = displayMetrics.heightPixels;
        super.centralYMin  = 0;
        super.widthMax  = displayMetrics.widthPixels / 2;
        super.widthMin  = 50;
        super.heightMax  = displayMetrics.heightPixels / 2;
        super.heightMin  = 50;

        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));
        paintText.setTextAlign(Paint.Align.CENTER);
        paintBorder.setStyle(Paint.Style.STROKE);
        paintBackground.setStyle(Paint.Style.FILL);


        text = (String) attributesMap.get(COLUMN_STRING_ELEMENT_TEXT);
        radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
        layer = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_LAYER)).intValue();
        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        value = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);

        String[] childElementIds = value.split(",");
        List<Element> allElements = elementController.getElements();
        StringBuilder newValue = new StringBuilder("-1");
        for (String childElementIdString : childElementIds){
            Long childElementId = Long.parseLong(childElementIdString);
            for (Element element : allElements){
                if (element.elementId.equals(childElementId)){
                    childElementList.add(element);
                    newValue.append(",").append(childElementId);
                }
            }
        }

        value = newValue.toString();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE,value);
        superConfigDatabaseHelper.updateElement(elementId,contentValues);
        displayChild(false);
        System.out.println("value = " + value);

        listener = new GroupButtonListener() {
            @Override
            public void onClick() {
                displayChild(!childVisible);
            }

            @Override
            public void onLongClick() {

            }

            @Override
            public void onRelease() {

            }
        };
    }

    private void displayChild(boolean display){
        if (!display){
            childVisible = false;
            for (Element element : childElementList){
                element.setVisibility(INVISIBLE);
            }
        } else {
            childVisible = true;
            for (Element element : childElementList){
                element.setVisibility(VISIBLE);
            }
        }
    }

    @Override
    protected void onElementDraw(Canvas canvas) {

        // 文字
        paintText.setTextSize(getPercent(getParamWidth(), 25));
        paintText.setColor(isPressed() ? pressedColor : normalColor);
        // 边框
        paintBorder.setStrokeWidth(thick);
        paintBorder.setColor(isPressed() ? pressedColor : normalColor);
        // 背景颜色
        paintBackground.setColor(backgroundColor);
        // 绘画范围
        rect.left = rect.top = (float) thick / 2;
        rect.right = getParamWidth() - rect.left;
        rect.bottom = getHeight() - rect.top;
        // 绘制背景
        canvas.drawRoundRect(rect, radius, radius, paintBackground);
        // 绘制边框
        canvas.drawRoundRect(rect, radius, radius, paintBorder);
        // 绘制文字
        canvas.drawText(text, getPercent(getParamWidth(), 50), getPercent(getParamHeight(), 63), paintText);

        if (elementController.getMode() == ElementController.Mode.Edit){
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(editColor);
            canvas.drawRect(rect,paintEdit);

        }
    }

    private void onClickCallback() {
        // notify listenersbuttonListener.onClick();
        System.out.println("onClickCallback");
        listener.onClick();
        elementController.getHandler().removeCallbacks(longClickRunnable);
        elementController.getHandler().postDelayed(longClickRunnable, timerLongClickTimeout);

    }

    private void onLongClickCallback() {
        // notify listeners
        listener.onLongClick();
    }

    private void onReleaseCallback() {
        // notify listeners
        System.out.println("onReleaseCallback");
        listener.onRelease();

        // We may be called for a release without a prior click
        elementController.getHandler().removeCallbacks(longClickRunnable);
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        // get masked (not specific to a pointer) action
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                lastX = event.getX();
                lastY = event.getY();
                setPressed(true);
                onClickCallback();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                onReleaseCallback();
                invalidate();
                return true;
            }
            default: {
            }
        }
        return true;
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
        if (groupButtonPage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getParamCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getParamCentralY());
        }

    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (groupButtonPage == null){
            groupButtonPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_group_button,null);
            centralXNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_central_x);
            centralYNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_central_y);

        }

        NumberSeekbar widthNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_width);
        NumberSeekbar heightNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_height);
        NumberSeekbar radiusNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_radius);
        ElementEditText textElementEditText = groupButtonPage.findViewById(R.id.page_group_button_text);
        NumberSeekbar thickNumberSeekbar = groupButtonPage.findViewById(R.id.page_group_button_thick);
        ElementEditText normalColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_normal_color);
        ElementEditText pressedColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_pressed_color);
        ElementEditText backgroundColorElementEditText = groupButtonPage.findViewById(R.id.page_group_button_background_color);
        Button deleteButton = groupButtonPage.findViewById(R.id.page_group_button_delete);

        textElementEditText.setTextWithNoTextChangedCallBack(text);
        textElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                groupButton.text = text;
                groupButton.invalidate();
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_STRING_ELEMENT_TEXT,text);
                superConfigDatabaseHelper.updateElement(groupButton.elementId,contentValues);
            }
        });


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


        widthNumberSeekbar.setProgressMax(widthMax);
        widthNumberSeekbar.setProgressMin(widthMin);
        widthNumberSeekbar.setValueWithNoCallBack(getParamWidth());
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                setParamWidth(progress);
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                radiusNumberSeekbar.setProgressMax(Math.min(getParamWidth(),getParamHeight()) / 2);
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH,getParamWidth());
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });

        heightNumberSeekbar.setProgressMax(heightMax);
        heightNumberSeekbar.setProgressMin(heightMin);
        heightNumberSeekbar.setValueWithNoCallBack(getParamHeight());
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                setParamHeight(progress);
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                radiusNumberSeekbar.setProgressMax(Math.min(getParamWidth(),getParamHeight()) / 2);
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,getParamHeight());
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });



        radiusNumberSeekbar.setProgressMax(Math.min(getParamWidth(),getParamHeight()) / 2);
        radiusNumberSeekbar.setValueWithNoCallBack(radius);
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                radius = progress;
                groupButton.invalidate();
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });


        thickNumberSeekbar.setValueWithNoCallBack(thick);
        thickNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                thick = progress;
                groupButton.invalidate();
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_THICK,thick);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });


        normalColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",normalColor));
        normalColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        normalColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    normalColor = (int) Long.parseLong(text, 16);
                    groupButton.invalidate();
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,normalColor);
                    superConfigDatabaseHelper.updateElement(elementId,contentValues);
                }
            }
        });


        pressedColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",pressedColor));
        pressedColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        pressedColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    pressedColor = (int) Long.parseLong(text, 16);
                    groupButton.invalidate();
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,pressedColor);
                    superConfigDatabaseHelper.updateElement(elementId,contentValues);
                }
            }
        });


        backgroundColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",backgroundColor));
        backgroundColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        backgroundColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    backgroundColor = (int) Long.parseLong(text, 16);
                    groupButton.invalidate();
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
                    superConfigDatabaseHelper.updateElement(elementId,contentValues);
                }
            }
        });

        groupButtonPage.findViewById(R.id.page_group_button_add_digital_common_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Long elementId = elementController.addElement(DigitalCommonButton.getInitialInfo());
                value = value + "," + elementId.toString();
                List<Element> allElements = elementController.getElements();
                for (Element element : allElements){
                    if (element.elementId.equals(elementId)){
                        childElementList.add(element);
                        if (!childVisible){
                            element.setVisibility(INVISIBLE);
                        }
                    }
                }

                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE,value);
                superConfigDatabaseHelper.updateElement(groupButton.elementId,contentValues);
                if (!childVisible){
                    toastShower.show();
                }
            }
        });


        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                elementController.toggleInfoPage(groupButtonPage);
                elementController.deleteElement(groupButton);
            }
        });



        return groupButtonPage;
    }

    public static ContentValues getInitialInfo(){
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_GROUP_BUTTON);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT,"GROUP");
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE,"-1");
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH,100);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,100);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,50);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,100);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,100);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,0);
        contentValues.put(COLUMN_INT_ELEMENT_THICK,5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,0x00FFFFFF);
        return contentValues;


    }
}
