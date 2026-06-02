package com.limelight.binding.input.advance_setting.element;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

final class CrownAutoColorApplier {
    private interface ColorSetter {
        void set(int color);
    }

    private CrownAutoColorApplier() {
    }

    static boolean supports(Element element) {
        return element instanceof AnalogStick
                || element instanceof DigitalCombineButton
                || element instanceof DigitalCommonButton
                || element instanceof DigitalMovableButton
                || element instanceof DigitalPad
                || element instanceof DigitalStick
                || element instanceof DigitalSwitchButton
                || element instanceof GroupButton
                || element instanceof InvisibleAnalogStick
                || element instanceof InvisibleDigitalStick
                || element instanceof SimplifyPerformance
                || element instanceof WheelPad;
    }

    static boolean apply(Element element, CrownAutoColorPalette palette, SuperPageLayout page) {
        if (element instanceof AnalogStick) {
            AnalogStick analogStick = (AnalogStick) element;
            applyShape(palette,
                    analogStick::setElementNormalColor,
                    analogStick::setElementPressedColor,
                    analogStick::setElementBackgroundColor);
        } else if (element instanceof DigitalStick) {
            DigitalStick digitalStick = (DigitalStick) element;
            applyShape(palette,
                    digitalStick::setElementNormalColor,
                    digitalStick::setElementPressedColor,
                    digitalStick::setElementBackgroundColor);
        } else if (element instanceof DigitalPad) {
            DigitalPad digitalPad = (DigitalPad) element;
            applyShape(palette,
                    digitalPad::setElementNormalColor,
                    digitalPad::setElementPressedColor,
                    digitalPad::setElementBackgroundColor);
        } else if (element instanceof InvisibleAnalogStick) {
            InvisibleAnalogStick analogStick = (InvisibleAnalogStick) element;
            applyShape(palette,
                    analogStick::setElementNormalColor,
                    analogStick::setElementPressedColor,
                    analogStick::setElementBackgroundColor);
        } else if (element instanceof InvisibleDigitalStick) {
            InvisibleDigitalStick digitalStick = (InvisibleDigitalStick) element;
            applyShape(palette,
                    digitalStick::setElementNormalColor,
                    digitalStick::setElementPressedColor,
                    digitalStick::setElementBackgroundColor);
        } else if (element instanceof DigitalCommonButton) {
            DigitalCommonButton button = (DigitalCommonButton) element;
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor);
        } else if (element instanceof DigitalSwitchButton) {
            DigitalSwitchButton button = (DigitalSwitchButton) element;
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor);
        } else if (element instanceof DigitalCombineButton) {
            DigitalCombineButton button = (DigitalCombineButton) element;
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor);
        } else if (element instanceof DigitalMovableButton) {
            DigitalMovableButton button = (DigitalMovableButton) element;
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor);
        } else if (element instanceof GroupButton) {
            GroupButton button = (GroupButton) element;
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor);
        } else if (element instanceof SimplifyPerformance) {
            SimplifyPerformance performance = (SimplifyPerformance) element;
            performance.setElementTextColor(palette.normalTextColor);
            performance.setElementBackgroundColor(palette.backgroundColor);
        } else if (element instanceof WheelPad) {
            WheelPad wheelPad = (WheelPad) element;
            wheelPad.setElementNormalColor(palette.normalColor);
            wheelPad.setElementPressedColor(palette.pressedColor);
            wheelPad.setElementBackgroundColor(palette.backgroundColor);
            wheelPad.setElementNormalTextColor(palette.normalTextColor);
            wheelPad.setElementPressedTextColor(palette.pressedTextColor);
            wheelPad.setElementCenterTextColor(palette.normalTextColor);
        } else {
            return false;
        }

        refreshColorFields(page, palette);
        element.invalidate();
        element.updatePage();
        element.save();
        return true;
    }

    private static void applyShape(CrownAutoColorPalette palette,
                                   ColorSetter normalColor,
                                   ColorSetter pressedColor,
                                   ColorSetter backgroundColor) {
        normalColor.set(palette.normalColor);
        pressedColor.set(palette.pressedColor);
        backgroundColor.set(palette.backgroundColor);
    }

    private static void applyTextButton(CrownAutoColorPalette palette,
                                        ColorSetter normalColor,
                                        ColorSetter pressedColor,
                                        ColorSetter backgroundColor,
                                        ColorSetter normalTextColor,
                                        ColorSetter pressedTextColor) {
        applyShape(palette, normalColor, pressedColor, backgroundColor);
        normalTextColor.set(palette.normalTextColor);
        pressedTextColor.set(palette.pressedTextColor);
    }

    private static void refreshColorFields(View view, CrownAutoColorPalette palette) {
        if (view == null) {
            return;
        }
        if (view instanceof ElementEditText && view.getId() != View.NO_ID) {
            String idName;
            try {
                idName = view.getResources().getResourceEntryName(view.getId());
            } catch (Resources.NotFoundException e) {
                idName = "";
            }

            int color = 0;
            boolean matched = true;
            if (idName.endsWith("_normal_color")) {
                color = palette.normalColor;
            } else if (idName.endsWith("_pressed_color")) {
                color = palette.pressedColor;
            } else if (idName.endsWith("_background_color")) {
                color = palette.backgroundColor;
            } else if (idName.endsWith("_pressed_text_color")) {
                color = palette.pressedTextColor;
            } else if (idName.endsWith("_center_text_color")) {
                color = palette.normalTextColor;
            } else if (idName.endsWith("_normal_text_color") || idName.endsWith("_text_color")) {
                color = palette.normalTextColor;
            } else {
                matched = false;
            }

            if (matched) {
                CrownColorPickerBinder.updateColorDisplay((ElementEditText) view, color);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                refreshColorFields(viewGroup.getChildAt(i), palette);
            }
        }
    }
}
