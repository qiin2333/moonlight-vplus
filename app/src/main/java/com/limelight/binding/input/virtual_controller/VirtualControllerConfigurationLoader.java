/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

public class VirtualControllerConfigurationLoader {
    public static final String OSC_PREFERENCE = "OSC";

    private static int getPercent(
            int percent,
            int total) {
        return (int) (((float) total / (float) 100) * (float) percent);
    }

    // The default controls are specified using a grid of 128*72 cells at 16:9
    private static int screenScale(int units, int height) {
        return Math.round(((float) height / (float) 72) * (float) units);
    }
    private static int screenScaleReverse(int pixel, int height) {
        return Math.round((float) pixel / ((float) height / (float) 72));
    }

    private static DigitalPad createDigitalPad(
            final VirtualController controller,
            final Context context) {

        DigitalPad digitalPad = new DigitalPad(controller, context);
        digitalPad.addDigitalPadListener(new DigitalPad.DigitalPadListener() {
            @Override
            public void onDirectionChange(int direction) {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();

                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    inputContext.inputMap |= ControllerPacket.LEFT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.LEFT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    inputContext.inputMap |= ControllerPacket.RIGHT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.RIGHT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_UP) != 0) {
                    inputContext.inputMap |= ControllerPacket.UP_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.UP_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    inputContext.inputMap |= ControllerPacket.DOWN_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.DOWN_FLAG;
                }

                controller.sendControllerInputContext();
            }
        });

        return digitalPad;
    }

    private static DigitalButton createDigitalButton(
            final int elementId,
            final int keyShort,
            final int keyLong,
            final int layer,
            final String text,
            final int icon,
            final VirtualController controller,
            final Context context) {
        DigitalButton button = new DigitalButton(controller, elementId, layer, context);
        button.setText(text);
        button.setIcon(icon);

        button.addDigitalButtonListener(new DigitalButton.DigitalButtonListener() {
            @Override
            public void onClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyShort;

                controller.sendControllerInputContext();
            }

            @Override
            public void onLongClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyLong;

                controller.sendControllerInputContext();
            }

            @Override
            public void onRelease() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap &= ~keyShort;
                inputContext.inputMap &= ~keyLong;

                controller.sendControllerInputContext();
            }
        });

        return button;
    }

    private static DigitalButton createLeftTrigger(
            final int layer,
            final String text,
            final int icon,
            final VirtualController controller,
            final Context context) {
        LeftTrigger button = new LeftTrigger(controller, layer, context);
        button.setText(text);
        button.setIcon(icon);
        return button;
    }

    private static DigitalButton createRightTrigger(
            final int layer,
            final String text,
            final int icon,
            final VirtualController controller,
            final Context context) {
        RightTrigger button = new RightTrigger(controller, layer, context);
        button.setText(text);
        button.setIcon(icon);
        return button;
    }

    private static AnalogStick createLeftStick(
            final VirtualController controller,
            final Context context) {
        return new LeftAnalogStick(controller, context);
    }

    private static AnalogStick createRightStick(
            final VirtualController controller,
            final Context context) {
        return new RightAnalogStick(controller, context);
    }


    private static final int TRIGGER_L_BASE_X = 1;
    private static final int TRIGGER_R_BASE_X = 92;
    private static final int TRIGGER_DISTANCE = 23;
    private static final int TRIGGER_BASE_Y = 31;
    private static final int TRIGGER_WIDTH = 12;
    private static final int TRIGGER_HEIGHT = 9;

    // Face buttons are defined based on the Y button (button number 9)
    private static final int BUTTON_BASE_X = 106;
    private static final int BUTTON_BASE_Y = 1;
    private static final int BUTTON_SIZE = 10;

    private static final int DPAD_BASE_X = 4;
    private static final int DPAD_BASE_Y = 41;
    private static final int DPAD_SIZE = 30;

    private static final int ANALOG_L_BASE_X = 6;
    private static final int ANALOG_L_BASE_Y = 4;
    private static final int ANALOG_R_BASE_X = 98;
    private static final int ANALOG_R_BASE_Y = 42;
    private static final int ANALOG_SIZE = 26;

    private static final int L3_R3_BASE_Y = 60;

    private static final int START_X = 83;
    private static final int BACK_X = 34;
    private static final int START_BACK_Y = 64;
    private static final int START_BACK_WIDTH = 12;
    private static final int START_BACK_HEIGHT = 7;

    // Make the Guide Menu be in the center of START and BACK menu
    private static final int GUIDE_X = START_X-BACK_X;
    private static final int GUIDE_Y = START_BACK_Y;

    private static int screenHeight = 1080;
    private static int screenWidth = 1920;
    private static int baseYUnit = 0;

    public static void createDefaultLayout(final VirtualController controller, final Context context) {

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);

        screenHeight = screen.heightPixels;
        baseYUnit = 0;
        if (config.halfHeightOscPortrait && context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            screenHeight /= 2;
            baseYUnit = 72;
        }

        // Displace controls on the right by this amount of pixels to account for different aspect ratios
        screenWidth = screen.widthPixels;
        int rightDisplacement = screenWidth - screenHeight * 16 / 9;

        // NOTE: Some of these getPercent() expressions seem like they can be combined
        // into a single call. Due to floating point rounding, this isn't actually possible.

        if (!config.onlyL3R3)
        {
            controller.addElement(createDigitalPad(controller, context),
                    screenScale(DPAD_BASE_X, screenHeight),
                    screenScale(DPAD_BASE_Y + baseYUnit, screenHeight),
                    screenScale(DPAD_SIZE, screenHeight),
                    screenScale(DPAD_SIZE, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_A,
                    !config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                    !config.flipFaceButtons ? "A" : "B", -1, controller, context),
                    screenScale(BUTTON_BASE_X, screenHeight) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + 2 * BUTTON_SIZE + baseYUnit, screenHeight),
                    screenScale(BUTTON_SIZE, screenHeight),
                    screenScale(BUTTON_SIZE, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_B,
                    config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                    config.flipFaceButtons ? "A" : "B", -1, controller, context),
                    screenScale(BUTTON_BASE_X + BUTTON_SIZE, screenHeight) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + BUTTON_SIZE + baseYUnit, screenHeight),
                    screenScale(BUTTON_SIZE, screenHeight),
                    screenScale(BUTTON_SIZE, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_X,
                    !config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                    !config.flipFaceButtons ? "X" : "Y", -1, controller, context),
                    screenScale(BUTTON_BASE_X - BUTTON_SIZE, screenHeight) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + BUTTON_SIZE + baseYUnit, screenHeight),
                    screenScale(BUTTON_SIZE, screenHeight),
                    screenScale(BUTTON_SIZE, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_Y,
                    config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                    config.flipFaceButtons ? "X" : "Y", -1, controller, context),
                    screenScale(BUTTON_BASE_X, screenHeight) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + baseYUnit, screenHeight),
                    screenScale(BUTTON_SIZE, screenHeight),
                    screenScale(BUTTON_SIZE, screenHeight)
            );

            controller.addElement(createLeftTrigger(
                    1, "LT", -1, controller, context),
                    screenScale(TRIGGER_L_BASE_X, screenHeight),
                    screenScale(TRIGGER_BASE_Y + baseYUnit, screenHeight),
                    screenScale(TRIGGER_WIDTH, screenHeight),
                    screenScale(TRIGGER_HEIGHT, screenHeight)
            );

            controller.addElement(createRightTrigger(
                    1, "RT", -1, controller, context),
                    screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, screenHeight) + rightDisplacement,
                    screenScale(TRIGGER_BASE_Y + baseYUnit, screenHeight),
                    screenScale(TRIGGER_WIDTH, screenHeight),
                    screenScale(TRIGGER_HEIGHT, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_LB,
                    ControllerPacket.LB_FLAG, 0, 1, "LB", -1, controller, context),
                    screenScale(TRIGGER_L_BASE_X + TRIGGER_DISTANCE, screenHeight),
                    screenScale(TRIGGER_BASE_Y + baseYUnit, screenHeight),
                    screenScale(TRIGGER_WIDTH, screenHeight),
                    screenScale(TRIGGER_HEIGHT, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_RB,
                    ControllerPacket.RB_FLAG, 0, 1, "RB", -1, controller, context),
                    screenScale(TRIGGER_R_BASE_X, screenHeight) + rightDisplacement,
                    screenScale(TRIGGER_BASE_Y + baseYUnit, screenHeight),
                    screenScale(TRIGGER_WIDTH, screenHeight),
                    screenScale(TRIGGER_HEIGHT, screenHeight)
            );

            controller.addElement(createLeftStick(controller, context),
                    screenScale(ANALOG_L_BASE_X, screenHeight),
                    screenScale(ANALOG_L_BASE_Y + baseYUnit, screenHeight),
                    screenScale(ANALOG_SIZE, screenHeight),
                    screenScale(ANALOG_SIZE, screenHeight)
            );

            controller.addElement(createRightStick(controller, context),
                    screenScale(ANALOG_R_BASE_X, screenHeight) + rightDisplacement,
                    screenScale(ANALOG_R_BASE_Y + baseYUnit, screenHeight),
                    screenScale(ANALOG_SIZE, screenHeight),
                    screenScale(ANALOG_SIZE, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_BACK,
                    ControllerPacket.BACK_FLAG, 0, 2, "BACK", -1, controller, context),
                    screenScale(BACK_X, screenHeight),
                    screenScale(START_BACK_Y + baseYUnit, screenHeight),
                    screenScale(START_BACK_WIDTH, screenHeight),
                    screenScale(START_BACK_HEIGHT, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_START,
                    ControllerPacket.PLAY_FLAG, 0, 3, "START", -1, controller, context),
                    screenScale(START_X, screenHeight) + rightDisplacement,
                    screenScale(START_BACK_Y + baseYUnit, screenHeight),
                    screenScale(START_BACK_WIDTH, screenHeight),
                    screenScale(START_BACK_HEIGHT, screenHeight)
            );
        }
        else {
            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_LSB,
                    ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", -1, controller, context),
                    screenScale(TRIGGER_L_BASE_X, screenHeight),
                    screenScale(L3_R3_BASE_Y + baseYUnit, screenHeight),
                    screenScale(TRIGGER_WIDTH, screenHeight),
                    screenScale(TRIGGER_HEIGHT, screenHeight)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_RSB,
                    ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", -1, controller, context),
                    screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, screenHeight) + rightDisplacement,
                    screenScale(L3_R3_BASE_Y + baseYUnit, screenHeight),
                    screenScale(TRIGGER_WIDTH, screenHeight),
                    screenScale(TRIGGER_HEIGHT, screenHeight)
            );
        }

        if(config.showGuideButton){
            controller.addElement(createDigitalButton(VirtualControllerElement.EID_GDB,
                            ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 1, "GUIDE", -1, controller, context),
                    screenScale(GUIDE_X, screenHeight)+ rightDisplacement,
                    screenScale(GUIDE_Y + baseYUnit, screenHeight),
                    screenScale(START_BACK_WIDTH, screenHeight),
                    screenScale(START_BACK_HEIGHT, screenHeight)
            );
        }

        controller.setOpacity(config.oscOpacity);
    }

    public static JSONObject prepareSave(JSONObject config) throws JSONException {
        // 靠右侧的右对齐
        int dPosX = config.optInt("LEFT") * 2 + config.optInt("WIDTH");
        if (dPosX < screenWidth) {
            config.put("R_SIDE", false);
            config.put("LEFT", screenScaleReverse(config.optInt("LEFT"), screenHeight));
        } else {
            config.put("R_SIDE", true);
            config.put("LEFT", screenScaleReverse(screenWidth - config.optInt("LEFT"), screenHeight));
        }
        config.put("TOP", screenScaleReverse(config.optInt("TOP"), screenHeight) - baseYUnit);
        config.put("WIDTH", screenScaleReverse(config.optInt("WIDTH"), screenHeight));
        config.put("HEIGHT", screenScaleReverse(config.optInt("HEIGHT"), screenHeight));
        return config;
    }

    public static JSONObject prepareLoad(JSONObject config) throws JSONException {
        if (config.optBoolean("R_SIDE")) {
            config.put("LEFT", screenWidth - screenScale(config.optInt("LEFT"), screenHeight));
        } else {
            config.put("LEFT", screenScale(config.optInt("LEFT"), screenHeight));
        }
        config.put("TOP", screenScale(config.optInt("TOP") + baseYUnit, screenHeight));
        config.put("WIDTH", screenScale(config.optInt("WIDTH"), screenHeight));
        config.put("HEIGHT", screenScale(config.optInt("HEIGHT"), screenHeight));
        return config;
    }

    public static void saveProfile(final VirtualController controller,
                                   final Context context) {
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(OSC_PREFERENCE, Activity.MODE_PRIVATE).edit();

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;
            try {
                prefEditor.putString(prefKey, prepareSave(element.getConfiguration()).toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        prefEditor.apply();
    }

    public static void loadFromPreferences(final VirtualController controller, final Context context) {
        SharedPreferences pref = context.getSharedPreferences(OSC_PREFERENCE, Activity.MODE_PRIVATE);

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;

            String jsonConfig = pref.getString(prefKey, null);
            if (jsonConfig != null) {
                try {
                    element.loadConfiguration(prepareLoad(new JSONObject(jsonConfig)));
                } catch (JSONException e) {
                    e.printStackTrace();

                    // Remove the corrupt element from the preferences
                    pref.edit().remove(prefKey).apply();
                }
            }
        }
    }
}
