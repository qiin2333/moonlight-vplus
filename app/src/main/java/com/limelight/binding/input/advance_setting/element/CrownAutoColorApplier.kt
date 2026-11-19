package com.limelight.binding.input.advance_setting.element

import android.content.res.Resources
import android.view.View
import android.view.ViewGroup

import com.limelight.binding.input.advance_setting.superpage.ElementEditText
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

object CrownAutoColorApplier {
    private fun interface ColorSetter {
        fun set(color: Int)
    }

    @JvmStatic
    fun supports(element: Element): Boolean {
        return element is AnalogStick
                || element is DigitalCombineButton
                || element is DigitalCommonButton
                || element is DigitalMovableButton
                || element is DigitalPad
                || element is DigitalStick
                || element is DigitalSwitchButton
                || element is GroupButton
                || element is InvisibleAnalogStick
                || element is InvisibleDigitalStick
                || element is SimplifyPerformance
                || element is WheelPad
    }

    @JvmStatic
    fun apply(element: Element, palette: CrownAutoColorPalette, page: SuperPageLayout?): Boolean {
        if (element is AnalogStick) {
            val analogStick = element as AnalogStick
            applyShape(palette,
                    analogStick::setElementNormalColor,
                    analogStick::setElementPressedColor,
                    analogStick::setElementBackgroundColor)
        } else if (element is DigitalStick) {
            val digitalStick = element as DigitalStick
            applyShape(palette,
                    digitalStick::setElementNormalColor,
                    digitalStick::setElementPressedColor,
                    digitalStick::setElementBackgroundColor)
        } else if (element is DigitalPad) {
            val digitalPad = element as DigitalPad
            applyShape(palette,
                    digitalPad::setElementNormalColor,
                    digitalPad::setElementPressedColor,
                    digitalPad::setElementBackgroundColor)
        } else if (element is InvisibleAnalogStick) {
            val analogStick = element as InvisibleAnalogStick
            applyShape(palette,
                    analogStick::setElementNormalColor,
                    analogStick::setElementPressedColor,
                    analogStick::setElementBackgroundColor)
        } else if (element is InvisibleDigitalStick) {
            val digitalStick = element as InvisibleDigitalStick
            applyShape(palette,
                    digitalStick::setElementNormalColor,
                    digitalStick::setElementPressedColor,
                    digitalStick::setElementBackgroundColor)
        } else if (element is DigitalCommonButton) {
            val button = element as DigitalCommonButton
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor)
        } else if (element is DigitalSwitchButton) {
            val button = element as DigitalSwitchButton
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor)
        } else if (element is DigitalCombineButton) {
            val button = element as DigitalCombineButton
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor)
        } else if (element is DigitalMovableButton) {
            val button = element as DigitalMovableButton
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor)
        } else if (element is GroupButton) {
            val button = element as GroupButton
            applyTextButton(palette,
                    button::setElementNormalColor,
                    button::setElementPressedColor,
                    button::setElementBackgroundColor,
                    button::setElementNormalTextColor,
                    button::setElementPressedTextColor)
        } else if (element is SimplifyPerformance) {
            val performance = element as SimplifyPerformance
            performance.setElementTextColor(palette.normalTextColor)
            performance.setElementBackgroundColor(palette.backgroundColor)
        } else if (element is WheelPad) {
            val wheelPad = element as WheelPad
            wheelPad.setElementNormalColor(palette.normalColor)
            wheelPad.setElementPressedColor(palette.pressedColor)
            wheelPad.setElementBackgroundColor(palette.backgroundColor)
            wheelPad.setElementNormalTextColor(palette.normalTextColor)
            wheelPad.setElementPressedTextColor(palette.pressedTextColor)
            wheelPad.setElementCenterTextColor(palette.normalTextColor)
        } else {
            return false
        }

        refreshColorFields(page, palette)
        element.invalidate()
        element.updatePage()
        element.save()
        return true
    }

    private fun applyShape(palette: CrownAutoColorPalette,
                           normalColor: ColorSetter,
                           pressedColor: ColorSetter,
                           backgroundColor: ColorSetter) {
        normalColor.set(palette.normalColor)
        pressedColor.set(palette.pressedColor)
        backgroundColor.set(palette.backgroundColor)
    }

    private fun applyTextButton(palette: CrownAutoColorPalette,
                                normalColor: ColorSetter,
                                pressedColor: ColorSetter,
                                backgroundColor: ColorSetter,
                                normalTextColor: ColorSetter,
                                pressedTextColor: ColorSetter) {
        applyShape(palette, normalColor, pressedColor, backgroundColor)
        normalTextColor.set(palette.normalTextColor)
        pressedTextColor.set(palette.pressedTextColor)
    }

    private fun refreshColorFields(view: View?, palette: CrownAutoColorPalette) {
        if (view == null) {
            return
        }
        if (view is ElementEditText && view.id != View.NO_ID) {
            val idName = try {
                view.resources.getResourceEntryName(view.id)
            } catch (e: Resources.NotFoundException) {
                ""
            }

            var color = 0
            var matched = true
            if (idName.endsWith("_normal_color")) {
                color = palette.normalColor
            } else if (idName.endsWith("_pressed_color")) {
                color = palette.pressedColor
            } else if (idName.endsWith("_background_color")) {
                color = palette.backgroundColor
            } else if (idName.endsWith("_pressed_text_color")) {
                color = palette.pressedTextColor
            } else if (idName.endsWith("_center_text_color")) {
                color = palette.normalTextColor
            } else if (idName.endsWith("_normal_text_color") || idName.endsWith("_text_color")) {
                color = palette.normalTextColor
            } else {
                matched = false
            }

            if (matched) {
                CrownColorPickerBinder.updateColorDisplay(view, color)
            }
        }

        if (view is ViewGroup) {
            val viewGroup = view as ViewGroup
            for (i in 0 until viewGroup.childCount) {
                refreshColorFields(viewGroup.getChildAt(i), palette)
            }
        }
    }
}
