package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.advance_setting.ControllerManager;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElementController {




    public interface SendEventHandler {
        void sendEvent(boolean down);
        void sendEvent(int analog1, int analog2);
    }


    public enum Mode{
        Normal,
        Edit
    }

    public static class GamepadInputContext {
        public short inputMap = 0x0000;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }


    private final Context context;
    private final Game game;
    private final Handler handler;

    private final ControllerManager controllerManager;
    private final ControllerHandler controllerHandler;
    private final PageDeviceController pageDeviceController;

    private GamepadInputContext gamepadInputContext = new GamepadInputContext();


    private final List<Element> elements = new ArrayList<>();
    private List<Long> elementIds;
    private Map<Short, Runnable> keyEventRunnableMap = new HashMap<>();
    private Map<Integer, Runnable> mouseEventRunnableMap = new HashMap<>();
    private FrameLayout elementsLayout;
    private Mode mode = Mode.Normal;
    private SuperPageLayout pageEdit;
    private SuperPageLayout lastElementSettingPage;



    public ElementController(ControllerManager controllerManager, FrameLayout layout, final Context context) {
        this.elementsLayout = layout;
        this.context = context;
        this.game = (Game) context;
        this.controllerManager = controllerManager;
        this.controllerHandler = game.getControllerHandler();
        this.pageDeviceController = controllerManager.getPageDeviceController();
        this.handler = new Handler(Looper.getMainLooper());
        this.pageEdit = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_edit,null);

        initEditPage();
    }

    private void initEditPage(){
        pageEdit.findViewById(R.id.page_edit_exit_edit_mode).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controllerManager.getPageSuperMenuController().exitElementEditMode();
                controllerManager.getTouchController().enableTouch(true);
                mode = Mode.Normal;
                for (Element element : elements){
                    element.invalidate();
                }
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_common_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalCommonButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_switch_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalSwitchButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_movable_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalMovableButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_pad).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalPad.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_analog_stick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = AnalogStick.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_stick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalStick.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_invisible_analog_stick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = InvisibleAnalogStick.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_invisible_digital_stick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = InvisibleDigitalStick.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_simplify_performance).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
                ContentValues contentValues = SimplifyPerformance.getInitialInfo();
                contentValues.put(Element.COLUMN_INT_ELEMENT_CENTRAL_X,displayMetrics.widthPixels / 2);
                contentValues.put(Element.COLUMN_INT_ELEMENT_CENTRAL_Y,30);
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_digital_combine_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = DigitalCombineButton.getInitialInfo();
                addElement(contentValues);
            }
        });
        pageEdit.findViewById(R.id.page_edit_add_group_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = GroupButton.getInitialInfo();
                addElement(contentValues);
            }
        });
    }


    protected Handler getHandler() {
        return handler;
    }

    protected SuperConfigDatabaseHelper getSuperConfigDatabaseHelper() {
        return controllerManager.getSuperConfigDatabaseHelper();
    }

    public void loadAllElement(Long configId){
        removeAllElementsOnScreen();
        elementIds = controllerManager.getSuperConfigDatabaseHelper().queryAllElementIds(configId);
        List<Long> groupButtonElementIdList = new ArrayList<>();
        for (Long elementId : elementIds){
            long elementType = (long) controllerManager.getSuperConfigDatabaseHelper().queryElementAttribute(elementId,Element.COLUMN_INT_ELEMENT_TYPE);
            if (elementType == Element.ELEMENT_TYPE_GROUP_BUTTON){
                groupButtonElementIdList.add(elementId);
            } else {
                loadElement(elementId);
            }

        }
        for (Long elementId : groupButtonElementIdList){
            loadElement(elementId);
        }
    }

    protected Long addElement(ContentValues contentValues){
        Long configId = controllerManager.getPageConfigController().getCurrentConfigId();
        Long elementId = System.currentTimeMillis();
        contentValues.put(Element.COLUMN_LONG_CONFIG_ID,configId);
        contentValues.put(Element.COLUMN_LONG_ELEMENT_ID,elementId);
        controllerManager.getSuperConfigDatabaseHelper().insertElement(contentValues);

        loadElement(elementId);
        return elementId;
    }

    protected void deleteElement(Element element){
        controllerManager.getSuperConfigDatabaseHelper().deleteElement(element.elementId);
        if (elements.contains(element)){
            elementsLayout.removeView(element);
            elements.remove(element);
        }
    }

    private void removeAllElementsOnScreen() {
        for (Element element : elements) {
            elementsLayout.removeView(element);
        }
        elements.clear();
    }

    private void loadElement(Long elementId){
        Map<String, Object> attributesMap =  controllerManager.getSuperConfigDatabaseHelper().queryAllElementAttributes(elementId);
        int type = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_TYPE)).intValue();
        Element element = null;
        switch (type){
            case Element.ELEMENT_TYPE_DIGITAL_COMMON_BUTTON:
                element = new DigitalCommonButton(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_SWITCH_BUTTON:
                element = new DigitalSwitchButton(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_MOVABLE_BUTTON:
                element = new DigitalMovableButton(attributesMap,
                        this,
                        controllerManager.getTouchController(),
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_GROUP_BUTTON:
                element = new GroupButton(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_PAD:
                element = new DigitalPad(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_ANALOG_STICK:
                element = new AnalogStick(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_STICK:
                element = new DigitalStick(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_INVISIBLE_ANALOG_STICK:
                element = new InvisibleAnalogStick(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_INVISIBLE_DIGITAL_STICK:
                element = new InvisibleDigitalStick(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
            case Element.ELEMENT_TYPE_SIMPLIFY_PERFORMANCE:
                element = new SimplifyPerformance(attributesMap,
                        this,
                        context);
                break;
            case Element.ELEMENT_TYPE_DIGITAL_COMBINE_BUTTON:
                element = new DigitalCombineButton(attributesMap,
                        this,
                        pageDeviceController,
                        context);
                break;
        }
        int elementWidth = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_WIDTH)).intValue();
        int elementHeight = ((Long) attributesMap.get(Element.COLUMN_INT_ELEMENT_HEIGHT)).intValue();
        int elementCentralX = ((Long) attributesMap.get( Element.COLUMN_INT_ELEMENT_CENTRAL_X)).intValue();
        int elementCentralY = ((Long) attributesMap.get( Element.COLUMN_INT_ELEMENT_CENTRAL_Y)).intValue();
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(elementWidth, elementHeight);
        layoutParams.leftMargin = elementCentralX - elementWidth / 2;
        layoutParams.topMargin = elementCentralY - elementHeight / 2;

        for (int i = 0;i <= elements.size();i ++){
            if (i == elements.size()){
                elements.add(i,element);
                elementsLayout.addView(element,i + 1,layoutParams);
                break;
            }
            Element elementExist = elements.get(i);
            if (elementExist.elementId  + ((long) elementExist.layer << 48 ) > element.elementId + ((long) element.layer << 48 )){
                elements.add(i,element);
                elementsLayout.addView(element,i + 1,layoutParams);
                break;
            }
        }
    }





    public void toggleInfoPage(SuperPageLayout elementSettingPage){
        if (controllerManager.getSuperPagesController().getLastPage() == lastElementSettingPage && lastElementSettingPage != null){
            controllerManager.getSuperPagesController().close();
            if (elementSettingPage != lastElementSettingPage){
                controllerManager.getSuperPagesController().open(elementSettingPage);
                lastElementSettingPage = elementSettingPage;
            }
        } else {
            controllerManager.getSuperPagesController().open(elementSettingPage);
            lastElementSettingPage = elementSettingPage;
        }
    }

    public SuperPageLayout getPageEdit() {
        return pageEdit;
    }

    public void entryEditMode(){

        controllerManager.getTouchController().enableTouch(false);
        mode = Mode.Edit;
        for (Element element : elements){
            element.invalidate();
        }

    }

    public Mode getMode() {
        return mode;
    }

    //其他辅助方法----------------------------------
    public List<Element> getElements() {
        return elements;
    }


    public SendEventHandler getSendEventHandler(String key){
        if (key.matches("k\\d+")){

            int keyCode = Integer.parseInt(key.substring(1));
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    sendKeyEvent(down,(short) keyCode);
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };

        } else if (key.matches("m\\d+")){
            int mouseCode = Integer.parseInt(key.substring(1));
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    sendMouseEvent(mouseCode,down);
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };

        } else if (key.matches("g\\d+")){
            int padCode = Integer.parseInt(key.substring(1));
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        gamepadInputContext.inputMap |= padCode;
                    } else {
                        gamepadInputContext.inputMap &= ~padCode;
                    }
                    sendGamepadEvent();
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };

        } else if (key.equals("LS")){
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {

                }

                @Override
                public void sendEvent(int analog1, int analog2) {
                    gamepadInputContext.leftStickX = (short) analog1;
                    gamepadInputContext.leftStickY = (short) analog2;
                    sendGamepadEvent();
                }
            };
        } else if (key.equals("RS")){
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {

                }

                @Override
                public void sendEvent(int analog1, int analog2) {
                    gamepadInputContext.rightStickX = (short) analog1;
                    gamepadInputContext.rightStickY = (short) analog2;
                    sendGamepadEvent();
                }
            };
        } else if (key.equals("lt")){
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        gamepadInputContext.leftTrigger = (byte) 0xFF;
                    } else {
                        gamepadInputContext.leftTrigger = (byte) 0;
                    }
                    sendGamepadEvent();
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals("rt")){
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down) {
                        gamepadInputContext.rightTrigger = (byte) 0xFF;
                    } else {
                        gamepadInputContext.rightTrigger = (byte) 0;
                    }
                    sendGamepadEvent();
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        } else if (key.equals("null")){
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                }

                @Override
                public void sendEvent(int analog1, int analog2) {

                }
            };
        }
        return null;
    }



    public void sendKeyEvent(boolean buttonDown, short keyCode) {
        game.keyboardEvent(buttonDown,keyCode);
        //如果map中有对应按键的runnable，则删除该按键的runnable。
        if (keyEventRunnableMap.containsKey(keyCode)){
            handler.removeCallbacks(keyEventRunnableMap.get(keyCode));
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                game.keyboardEvent(buttonDown,keyCode);
            }
        };
        //把这个按键的runnable放到map中，以便这个按键重新发送的时候，重置runnable。
        keyEventRunnableMap.put(keyCode,runnable);


        handler.postDelayed(runnable, 50);
        handler.postDelayed(runnable, 75);
    }
    public void sendMouseEvent(int mouseId, boolean down){
        game.mouseButtonEvent(mouseId, down);
        if (mouseEventRunnableMap.containsKey(mouseId)){
            handler.removeCallbacks(mouseEventRunnableMap.get(mouseId));
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                game.mouseButtonEvent(mouseId, down);
            }
        };
        //把这个按键的runnable放到map中，以便这个按键重新发送的时候，重置runnable。
        mouseEventRunnableMap.put(mouseId,runnable);

        handler.postDelayed(runnable, 50);
        handler.postDelayed(runnable, 75);
    }

    public void sendGamepadEvent(){
        controllerHandler.reportOscState(
                gamepadInputContext.inputMap,
                gamepadInputContext.leftStickX,
                gamepadInputContext.leftStickY,
                gamepadInputContext.rightStickX,
                gamepadInputContext.rightStickY,
                gamepadInputContext.leftTrigger,
                gamepadInputContext.rightTrigger
        );

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                controllerHandler.reportOscState(
                        gamepadInputContext.inputMap,
                        gamepadInputContext.leftStickX,
                        gamepadInputContext.leftStickY,
                        gamepadInputContext.rightStickX,
                        gamepadInputContext.rightStickY,
                        gamepadInputContext.leftTrigger,
                        gamepadInputContext.rightTrigger
                );
            }
        };
        handler.postDelayed(runnable, 50);
        handler.postDelayed(runnable, 75);


    }
}
