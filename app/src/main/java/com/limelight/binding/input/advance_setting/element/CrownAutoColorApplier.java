package com.limelight.binding.input.advance_setting.element;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

final class CrownAutoColorApplier {
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
            applyShape((AnalogStick) element, palette);
        } else if (element instanceof DigitalStick) {
            applyShape((DigitalStick) element, palette);
        } else if (element instanceof DigitalPad) {
            applyShape((DigitalPad) element, palette);
        } else if (element instanceof InvisibleAnalogStick) {
            applyShape((InvisibleAnalogStick) element, palette);
        } else if (element instanceof InvisibleDigitalStick) {
            applyShape((InvisibleDigitalStick) element, palette);
        } else if (element instanceof DigitalCommonButton) {
            applyTextButton((DigitalCommonButton) element, palette);
        } else if (element instanceof DigitalSwitchButton) {
            applyTextButton((DigitalSwitchButton) element, palette);
        } else if (element instanceof DigitalCombineButton) {
            applyTextButton((DigitalCombineButton) element, palette);
        } else if (element instanceof DigitalMovableButton) {
            applyTextButton((DigitalMovableButton) element, palette);
        } else if (element instanceof GroupButton) {
            applyTextButton((GroupButton) element, palette);
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

    private static void applyShape(AnalogStick element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
    }

    private static void applyShape(DigitalStick element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
    }

    private static void applyShape(DigitalPad element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
    }

    private static void applyShape(InvisibleAnalogStick element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
    }

    private static void applyShape(InvisibleDigitalStick element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
    }

    private static void applyTextButton(DigitalCommonButton element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
        element.setElementNormalTextColor(palette.normalTextColor);
        element.setElementPressedTextColor(palette.pressedTextColor);
    }

    private static void applyTextButton(DigitalSwitchButton element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
        element.setElementNormalTextColor(palette.normalTextColor);
        element.setElementPressedTextColor(palette.pressedTextColor);
    }

    private static void applyTextButton(DigitalCombineButton element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
        element.setElementNormalTextColor(palette.normalTextColor);
        element.setElementPressedTextColor(palette.pressedTextColor);
    }

    private static void applyTextButton(DigitalMovableButton element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
        element.setElementNormalTextColor(palette.normalTextColor);
        element.setElementPressedTextColor(palette.pressedTextColor);
    }

    private static void applyTextButton(GroupButton element, CrownAutoColorPalette palette) {
        element.setElementNormalColor(palette.normalColor);
        element.setElementPressedColor(palette.pressedColor);
        element.setElementBackgroundColor(palette.backgroundColor);
        element.setElementNormalTextColor(palette.normalTextColor);
        element.setElementPressedTextColor(palette.pressedTextColor);
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
