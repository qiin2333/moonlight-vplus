package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.virtual_controller.VirtualController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElementController {

    public abstract class SendEventHandler {

        public abstract void sendEvent(boolean down);
        public abstract void sendEvent(int analog1, int analog2);

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
    private ElementPreference elementPreference;

    private final ControllerManager controllerManager;
    private final ControllerHandler controllerHandler;

    private GamepadInputContext gamepadInputContext = new GamepadInputContext();


    private final List<Element> elements = new ArrayList<>();
    private Map<Integer, Runnable> keyEventRunnableMap = new HashMap<>();
    private Map<Integer, Runnable> mouseEventRunnableMap = new HashMap<>();
    private FrameLayout elementsLayout;
    public ElementController(ControllerManager controllerManager, FrameLayout layout, final Context context) {
        this.elementsLayout = layout;
        this.context = context;
        this.game = (Game) context;
        this.controllerManager = controllerManager;
        this.controllerHandler = game.getControllerHandler();


        handler = new Handler(Looper.getMainLooper());
    }

    Handler getHandler() {
        return handler;
    }




    public void loadElementConfig(String configId){
        removeElementsFromScreen();
        elementPreference = new ElementPreference(configId,context);
        for (ElementBean elementBean : elementPreference.getElements()){
            addElementToScreen(elementBean);
        }
    }


    public void deleteElement(Element element){
        elementsLayout.removeView(element);
        elements.remove(element);
        elementPreference.deleteElement(element.getElementId());
    }

    public void saveElement(Element element){
        elementPreference.addElement(element.getElementBean());
    }


    public Element addElement(ElementBean elementBean){
        Element element = addElementToScreen(elementBean);
        elementPreference.addElement(elementBean);
        return element;
    }

    private Element addElementToScreen(ElementBean elementBean){
        Element element = null;
        switch (elementBean.getType()){
            case ElementBean.TYPE_BUTTON:
                element = new DigitalButton(this,elementBean,context);
                break;

            case ElementBean.TYPE_SWITCH:
                element = new DigitalSwitch(this,elementBean,context);
                break;

            case ElementBean.TYPE_K_PAD:
            case ElementBean.TYPE_G_PAD:
            case ElementBean.TYPE_PAD:
                element = new DigitalPad(this,elementBean,context);
                break;

            case ElementBean.TYPE_M_BUTTON:
                element = addMButton(elementBean);
                break;

            case ElementBean.TYPE_K_STICK:
                element = new AnalogKStick(this,elementBean,context);
                break;

            case ElementBean.TYPE_K_ISTICK:
                element = addKIStick(elementBean);
                break;

            case ElementBean.TYPE_G_STICK:
                element = new AnalogGStick(this,elementBean,context);
                break;

            case ElementBean.TYPE_G_ISTICK:
                element = addGIStick(elementBean);
                break;


        }
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(elementBean.getWidth(), elementBean.getHeight());
        layoutParams.setMargins(elementBean.getPositionX() - elementBean.getWidth()/2, elementBean.getPositionY() - elementBean.getHeight()/2, 0, 0);

        elementsLayout.addView(element, layoutParams);
        return element;
    }

    private Element addMButton(ElementBean elementBean){
        return null;
    }
    private Element addKStick(ElementBean elementBean){
        return null;
    }
    private Element addKIStick(ElementBean elementBean){
        return null;
    }
    private Element addGStick(ElementBean elementBean){
        return null;
    }
    private Element addGIStick(ElementBean elementBean){
        return null;
    }


    //其他辅助方法----------------------------------
    public List<Element> getElements() {
        return elements;
    }
    public void removeElementsFromScreen() {
        for (Element element : elements) {
            elementsLayout.removeView(element);
        }
        elements.clear();
    }

    //倒序选择，不然会先选择下面的按钮
    public Element selectElement(float x, float y){
        for (int i = elements.size() - 1;i > -1;i --){
            Element element = elements.get(i);
            if (element.inRange(x,y)){
                return element;
            }
        }
        return null;
    }

    public void setOpacity(int opacity) {
        for (Element element : elements) {
            element.setOpacity(opacity);
        }
    }

    public SendEventHandler getSendEventHandler(String key){
        if (key.matches("k\\d+")){

            int keyCode = Integer.parseInt(key.substring(1));
            return new SendEventHandler() {
                @Override
                public void sendEvent(boolean down) {
                    if (down){
                        sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,keyCode));
                    } else {
                        sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,keyCode));
                    }
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
                    gamepadInputContext.rightStickX = (short) (analog1 * 0x7FFE);
                    gamepadInputContext.rightStickY = (short) (analog2 * 0x7FFE);
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
        }
        return null;
    }



    public void sendKeyEvent(KeyEvent keyEvent) {
        game.onKey(null,keyEvent.getKeyCode(),keyEvent);
        //如果map中有对应按键的runnable，则删除该按键的runnable。
        if (keyEventRunnableMap.containsKey(keyEvent.getKeyCode())){
            handler.removeCallbacks(keyEventRunnableMap.get(keyEvent.getKeyCode()));
        }
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                game.onKey(null,keyEvent.getKeyCode(),keyEvent);
            }
        };
        //把这个按键的runnable放到map中，以便这个按键重新发送的时候，重置runnable。
        keyEventRunnableMap.put(keyEvent.getKeyCode(),runnable);


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


