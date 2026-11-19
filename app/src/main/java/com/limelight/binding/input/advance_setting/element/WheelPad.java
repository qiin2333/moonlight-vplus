//轮盘按键
package com.limelight.binding.input.advance_setting.element;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.limelight.Game;
import com.limelight.R;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;
import com.limelight.utils.AppDialogStyler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WheelPad extends Element {

    private static final String EXTRA_SEGMENT_ALIGNMENT = "segmentAlignment";
    private static final String SEGMENT_ALIGNMENT_CENTER = "center";
    private static final String SEGMENT_ALIGNMENT_EDGE = "edge";
    private static final String EXTRA_WHEEL_DIAMETER = "wheelDiameter";
    private static final String EXTRA_TRIGGER_DIAMETER = "triggerDiameter";

    private static final int WHEEL_MIN_DIAMETER = 150;
    private static final int TRIGGER_MIN_DIAMETER = 60;
    private static final int TRIGGER_MAX_DIAMETER = 300;
    private static final float GLASS_SEGMENT_GAP_DEG = 1.6f;

    private final PageDeviceController pageDeviceController;
    private final WheelPad wheelPad;

    private int diameter;         // 轮盘自身直径
    private int triggerDiameter;  // 屏幕中心弹出模式下，触发按钮的独立直径（也作为 View 尺寸）
    private int currentViewSize = -1;
    private int segmentCount;
    private int innerRadiusPercent;
    private boolean isPopupMode;
    private String centerText;
    private List<String> segmentValues;
    private List<String> segmentNames;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;
    private int thick;
    private int normalTextColor;
    private int pressedTextColor;
    private int centerTextColor;
    private int textSizePercent;
    private int centerTextSizePercent;
    private int triggerTextSizePercent;

    private final int screenWidth;
    private final int screenHeight;

    private final Paint paintBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintEdit = new Paint();
    private final Paint paintCenterText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGlassFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGlassStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintAccentArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintPointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textMeasurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect = new RectF();
    private final RectF tempOuterRect = new RectF();
    private final RectF tempInnerRect = new RectF();
    private final Path segmentPath = new Path();
    private final Path pointerPath = new Path();

    // 渐变着色器缓存，避免频繁 GC
    private Shader cachedNormalShader;
    private Shader cachedPressedShader;
    private float lastShaderCx, lastShaderCy, lastShaderInnerR, lastShaderOuterR;
    private int lastShaderBgColor, lastShaderPressedColor;

    private Shader cachedTriggerShader;
    private float lastTrigCx, lastTrigCy, lastTrigR;
    private int lastTrigBgColor;

    // 动画过渡状态
    private float popupAnimProgress = 1.0f;
    private float[] segPopProgress = new float[16];
    private float lastPointerAngleDeg = -90f;
    private long lastAnimTimestamp = 0L;

    private int activeIndex = -1;
    private int lastActiveIndex = -1;
    private boolean isWheelActive = false;
    private boolean popupAtScreenCenter;
    private boolean previewGroupChildren;
    private boolean alignSegmentEdge;
    private GroupButton hoveredGroupButton = null;

    private SuperPageLayout wheelPadPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;
    private LinearLayout valuesContainer;

    public WheelPad(Map<String, Object> attributesMap,
                    ElementController controller,
                    PageDeviceController pageDeviceController, Context context) {
        super(attributesMap, controller, context);
        this.pageDeviceController = pageDeviceController;
        this.wheelPad = this;

        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((Game) context).getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
        this.screenWidth = displayMetrics.widthPixels;
        this.screenHeight = displayMetrics.heightPixels;

        super.centralXMax = displayMetrics.widthPixels;
        super.centralXMin = 0;
        super.centralYMax = displayMetrics.heightPixels;
        super.centralYMin = 0;
        super.widthMax = displayMetrics.widthPixels;
        super.widthMin = TRIGGER_MIN_DIAMETER;
        super.heightMax = displayMetrics.heightPixels;
        super.heightMin = TRIGGER_MIN_DIAMETER;

        paintBackground.setStyle(Paint.Style.FILL);
        paintText.setColor(0xFFFFFFFF);
        paintText.setTextSize(30);
        paintText.setTextAlign(Paint.Align.CENTER);
        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));
        paintCenterText.setColor(0xFFFFFFFF);
        paintCenterText.setTextSize(60);
        paintCenterText.setTextAlign(Paint.Align.CENTER);
        paintCenterText.setFakeBoldText(true);

        paintGlassFill.setStyle(Paint.Style.FILL);
        paintGlassStroke.setStyle(Paint.Style.STROKE);
        paintAccentArc.setStyle(Paint.Style.STROKE);
        paintAccentArc.setStrokeCap(Paint.Cap.ROUND);
        paintPointer.setStyle(Paint.Style.FILL);

        // 历史数据里 width 就是轮盘直径；新数据在中心弹出模式下 width 只是触发按钮直径，
        // 真正的轮盘直径由 extra_attributes.wheelDiameter 保存。
        int savedViewWidth = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_WIDTH)).intValue();
        this.currentViewSize = savedViewWidth;
        this.diameter = clampWheelDiameter(savedViewWidth);

        Object textObj = attributesMap.get(COLUMN_STRING_ELEMENT_TEXT);
        this.centerText = (textObj != null) ? (String) textObj : "";
        this.isPopupMode = !this.centerText.isEmpty();

        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        segmentCount = Math.max(2, ((Long) attributesMap.get(COLUMN_INT_ELEMENT_MODE)).intValue());
        innerRadiusPercent = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_SENSE)).intValue();

        Object popupFlagObj = attributesMap.get(COLUMN_INT_ELEMENT_FLAG1);
        this.popupAtScreenCenter = (popupFlagObj == null) || ((Long) popupFlagObj).intValue() == 1;

        this.triggerDiameter = 0;

        if (attributesMap.containsKey("extra_attributes")) {
            String extraAttrsJsonString = (String) attributesMap.get("extra_attributes");
            if (extraAttrsJsonString != null && !extraAttrsJsonString.isEmpty()) {
                try {
                    JsonObject extraAttrs = new Gson().fromJson(extraAttrsJsonString, JsonObject.class);

                    this.normalTextColor = extraAttrs.has("normalTextColor")
                            ? extraAttrs.get("normalTextColor").getAsInt() : 0xFFFFFFFF;
                    this.centerTextColor = extraAttrs.has("centerTextColor")
                            ? extraAttrs.get("centerTextColor").getAsInt() : 0xFFFFFFFF;
                    this.pressedTextColor = extraAttrs.has("pressedTextColor")
                            ? extraAttrs.get("pressedTextColor").getAsInt() : 0xFFFFFFFF;
                    this.textSizePercent = extraAttrs.has("textSizePercent")
                            ? extraAttrs.get("textSizePercent").getAsInt() : 35;
                    this.centerTextSizePercent = extraAttrs.has("centerTextSizePercent")
                            ? extraAttrs.get("centerTextSizePercent").getAsInt() : 60;
                    this.triggerTextSizePercent = extraAttrs.has("triggerTextSizePercent")
                            ? extraAttrs.get("triggerTextSizePercent").getAsInt() : 40;
                    this.previewGroupChildren = !extraAttrs.has("previewGroupChildren")
                            || extraAttrs.get("previewGroupChildren").getAsBoolean();
                    this.alignSegmentEdge = extraAttrs.has(EXTRA_SEGMENT_ALIGNMENT)
                            && SEGMENT_ALIGNMENT_EDGE.equals(extraAttrs.get(EXTRA_SEGMENT_ALIGNMENT).getAsString());

                    if (extraAttrs.has(EXTRA_WHEEL_DIAMETER)) {
                        this.diameter = clampWheelDiameter(extraAttrs.get(EXTRA_WHEEL_DIAMETER).getAsInt());
                    }
                    if (extraAttrs.has(EXTRA_TRIGGER_DIAMETER)) {
                        this.triggerDiameter = clampTriggerDiameter(extraAttrs.get(EXTRA_TRIGGER_DIAMETER).getAsInt());
                    }
                } catch (Exception e) {
                    initializeDefaultFontAttributes();
                }
            } else {
                initializeDefaultFontAttributes();
            }
        } else {
            initializeDefaultFontAttributes();
        }

        if (this.triggerDiameter <= 0) {
            this.triggerDiameter = computeDefaultTriggerDiameter();
        }

        String valuesString = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);
        segmentValues = new ArrayList<>();
        segmentNames = new ArrayList<>();
        if (valuesString != null && !valuesString.isEmpty()) {
            String[] segments = valuesString.split(",");
            for (String segment : segments) {
                if (segment.isEmpty()) continue;
                String[] parts = segment.split("\\|", 2);
                segmentValues.add(parts[0]);
                segmentNames.add(parts.length > 1 ? parts[1] : "");
            }
        }
        while (segmentValues.size() < segmentCount) {
            segmentValues.add("null");
            segmentNames.add("");
        }

        ensureSegPopCapacity(segmentCount);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 构造阶段还没有 LayoutParams，尺寸同步必须等 View 真正挂载后再做。
        syncViewSizeForCurrentMode();
    }

    private void initializeDefaultFontAttributes() {
        this.normalTextColor = 0xFFFFFFFF;
        this.pressedTextColor = 0xFFFFFFFF;
        this.centerTextColor = 0xFFFFFFFF;
        this.textSizePercent = 35;
        this.centerTextSizePercent = 60;
        this.triggerTextSizePercent = 40;
        this.previewGroupChildren = true;
        this.alignSegmentEdge = false;
    }

    private int clampWheelDiameter(int size) {
        int max = Math.min(widthMax, heightMax);
        return Math.max(WHEEL_MIN_DIAMETER, Math.min(max, size));
    }

    private int clampTriggerDiameter(int size) {
        int max = Math.min(TRIGGER_MAX_DIAMETER, Math.min(widthMax, heightMax));
        return Math.max(TRIGGER_MIN_DIAMETER, Math.min(max, size));
    }

    private int computeDefaultTriggerDiameter() {
        float outerR = Math.max(20f, (this.diameter / 2.0f) - this.thick);
        float innerR = outerR * (this.innerRadiusPercent / 100.0f);
        float visualRadius = innerR + Math.max(5f, this.thick);
        return clampTriggerDiameter(Math.round(visualRadius * 2.0f));
    }

    private int getExpectedViewSize() {
        if (isPopupMode && popupAtScreenCenter) {
            return clampTriggerDiameter(this.triggerDiameter);
        }
        return clampWheelDiameter(this.diameter);
    }

    private void syncViewSizeForCurrentMode() {
        if (getLayoutParams() == null) {
            return;
        }
        int targetSize = getExpectedViewSize();
        if (targetSize != currentViewSize) {
            currentViewSize = targetSize;
            setElementWidth(targetSize);
            setElementHeight(targetSize);
        }
    }

    private float getWheelFillOuterRadius() {
        float wheelRadius = this.diameter / 2.0f;
        float popReserve = Math.min(12f, wheelRadius * 0.055f);
        return Math.max(0f, wheelRadius - popReserve - thick);
    }

    private float getWheelInnerRadiusPx() {
        return getWheelFillOuterRadius() * (innerRadiusPercent / 100.0f);
    }

    private float getTriggerRadius() {
        if (isPopupMode && popupAtScreenCenter) {
            float inset = Math.max(4f, thick * 0.4f);
            float viewSize = getWidth() > 0 ? getWidth() : getExpectedViewSize();
            return Math.max(10f, (viewSize / 2.0f) - inset);
        }
        return Math.max(10f, getWheelInnerRadiusPx());
    }

    private void ensureSegPopCapacity(int count) {
        if (segPopProgress.length < count) {
            segPopProgress = Arrays.copyOf(segPopProgress, Math.max(count, segPopProgress.length * 2));
        }
    }

    private void invalidateWheel() {
        invalidate();
        // 中心弹出模式下轮盘绘制在 View 边界之外，只重绘自身不会刷新屏幕中心区域。
        if (getParent() instanceof View) {
            ((View) getParent()).invalidate();
        }
    }

    public boolean isBeingEdited() {
        if (elementController != null) {
            SuperPageLayout currentPage = elementController.getCurrentEditingPage();
            return this.wheelPadPage != null && currentPage == this.wheelPadPage;
        }
        return false;
    }

    private List<ElementController.SendEventHandler> getHandlersForValueNow(String value) {
        List<ElementController.SendEventHandler> handlers = new ArrayList<>();
        if (value == null || value.isEmpty() || value.equals("null")) {
            return handlers;
        }

        String[] keyValues = value.split("\\+");
        for (String singleKeyValue : keyValues) {
            ElementController.SendEventHandler handler = elementController.getSendEventHandler(singleKeyValue.trim());
            if (handler != null) {
                handlers.add(handler);
            }
        }
        return handlers;
    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        ElementController.Mode currentMode = elementController.getMode();
        boolean isTheOneBeingEdited = isBeingEdited();

        if (isPopupMode && popupAtScreenCenter) {
            drawInactivePopupCenter(canvas);
            boolean shouldDrawCentralPreview =
                    (currentMode == ElementController.Mode.Normal && isWheelActive) ||
                            (currentMode == ElementController.Mode.Edit && isTheOneBeingEdited);

            if (shouldDrawCentralPreview) {
                canvas.save();
                float translateX = (screenWidth / 2.0f) - getElementCentralX();
                float translateY = (screenHeight / 2.0f) - getElementCentralY();
                canvas.translate(translateX, translateY);
                drawFullWheel(canvas);

                if (previewGroupChildren) {
                    drawHoveredGroupButtonChildren(canvas, translateX, translateY);
                }
                canvas.restore();
            }
        } else {
            boolean shouldDrawTriggerInsteadOfFullWheel = isPopupMode && !popupAtScreenCenter &&
                    currentMode == ElementController.Mode.Normal && !isWheelActive;
            if (shouldDrawTriggerInsteadOfFullWheel) {
                drawInactivePopupCenter(canvas);
            } else {
                drawFullWheel(canvas);
                boolean isActiveOnSitePopup = isPopupMode && !popupAtScreenCenter &&
                        currentMode == ElementController.Mode.Normal && isWheelActive;
                if (previewGroupChildren && isActiveOnSitePopup) {
                    drawHoveredGroupButtonChildren(canvas, 0, 0);
                }
            }
        }

        if (currentMode == ElementController.Mode.Edit || currentMode == ElementController.Mode.Select) {
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            paintEdit.setColor(editColor);
            canvas.drawRect(rect, paintEdit);
        }
    }

    private void drawHoveredGroupButtonChildren(Canvas canvas, float wheelTranslateX, float wheelTranslateY) {
        if (hoveredGroupButton == null) {
            return;
        }
        List<Long> childIds = hoveredGroupButton.getChildIds();
        if (childIds == null || childIds.isEmpty()) {
            return;
        }

        for (Long childId : childIds) {
            Element child = elementController.findElementById(childId);
            if (child != null) {
                canvas.save();
                float childDrawX = child.getLeft() - (this.getLeft() + wheelTranslateX);
                float childDrawY = child.getTop() - (this.getTop() + wheelTranslateY);
                canvas.translate(childDrawX, childDrawY);
                child.onElementDraw(canvas);
                canvas.restore();
            }
        }
    }

    private void drawInactivePopupCenter(Canvas canvas) {
        float centerX = getWidth() / 2.0f;
        float centerY = getHeight() / 2.0f;
        float triggerRadius = getTriggerRadius();

        int bgAlpha = Math.max(45, Color.alpha(backgroundColor));
        int innerGlass = Color.argb(Math.round(bgAlpha * 0.65f),
                Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor));
        int outerGlass = Color.argb(Math.round(bgAlpha * 0.92f),
                Color.red(backgroundColor) / 2, Color.green(backgroundColor) / 2, Color.blue(backgroundColor) / 2);

        if (cachedTriggerShader == null || lastTrigCx != centerX || lastTrigCy != centerY
                || lastTrigR != triggerRadius || lastTrigBgColor != backgroundColor) {
            cachedTriggerShader = new RadialGradient(
                    centerX, centerY - triggerRadius * 0.25f, Math.max(1f, triggerRadius * 1.2f),
                    innerGlass, outerGlass, Shader.TileMode.CLAMP);
            lastTrigCx = centerX;
            lastTrigCy = centerY;
            lastTrigR = triggerRadius;
            lastTrigBgColor = backgroundColor;
        }

        paintGlassFill.setShader(cachedTriggerShader);
        paintGlassFill.setShadowLayer(Math.max(4f, thick * 1.2f), 0, 2f, 0x66000000);
        canvas.drawCircle(centerX, centerY, triggerRadius, paintGlassFill);
        paintGlassFill.setShadowLayer(0, 0, 0, 0);
        paintGlassFill.setShader(null);

        // 微透外边
        float glassStroke = Math.max(1.5f, thick * 0.45f);
        paintGlassStroke.setStrokeWidth(glassStroke);
        paintGlassStroke.setColor(Color.argb(150,
                Color.red(normalColor), Color.green(normalColor), Color.blue(normalColor)));
        canvas.drawCircle(centerX, centerY, Math.max(1f, triggerRadius - glassStroke / 2f), paintGlassStroke);

        // 内沿微弱白色高光环（玻璃质感）
        paintGlassStroke.setStrokeWidth(1.2f);
        paintGlassStroke.setColor(0x2EFFFFFF);
        canvas.drawCircle(centerX, centerY, Math.max(1f, triggerRadius - glassStroke - 2.5f), paintGlassStroke);

        paintCenterText.setColor(this.centerTextColor);
        float triggerTextSize = (triggerRadius * 2f) * (this.triggerTextSizePercent / 100.0f);
        paintCenterText.setTextSize(triggerTextSize);
        paintCenterText.setShadowLayer(Math.max(2f, thick * 0.35f), 0, 1.5f, 0x99000000);

        float textY = centerY - ((paintCenterText.descent() + paintCenterText.ascent()) / 2f);
        canvas.drawText(centerText, centerX, textY, paintCenterText);
    }

    private void drawFullWheel(Canvas canvas) {
        ElementController.Mode currentMode = elementController.getMode();
        float centerX = getWidth() / 2.0f;
        float centerY = getHeight() / 2.0f;

        // 轮盘尺寸始终由 this.diameter 决定（与 View 触发尺寸彻底解耦）
        float wheelRadius = this.diameter / 2.0f;
        float popOutMax = Math.min(12f, wheelRadius * 0.055f);
        float fillOuterRadius = Math.max(0f, wheelRadius - popOutMax - thick);
        float fillInnerRadius = fillOuterRadius * (innerRadiusPercent / 100.0f);

        // 平滑过渡动画
        long now = SystemClock.uptimeMillis();
        float dt = lastAnimTimestamp == 0 ? 16f : Math.min(32f, Math.max(1f, now - lastAnimTimestamp));
        lastAnimTimestamp = now;
        float step = Math.min(1f, dt * 0.022f);

        boolean needNextFrame = false;
        boolean isEditPreview = (currentMode == ElementController.Mode.Edit && isBeingEdited());
        float targetPopup = (isEditPreview || !isPopupMode || isWheelActive) ? 1f : 0f;
        if (isEditPreview) {
            popupAnimProgress = 1f;
        } else if (Math.abs(targetPopup - popupAnimProgress) > 0.01f) {
            popupAnimProgress += (targetPopup - popupAnimProgress) * Math.min(1f, step * 1.4f);
            needNextFrame = true;
        } else {
            popupAnimProgress = targetPopup;
        }

        ensureSegPopCapacity(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            float targetPop = (i == activeIndex) ? 1f : 0f;
            if (Math.abs(targetPop - segPopProgress[i]) > 0.01f) {
                segPopProgress[i] += (targetPop - segPopProgress[i]) * Math.min(1f, step * 1.6f);
                needNextFrame = true;
            } else {
                segPopProgress[i] = targetPop;
            }
        }

        float maxOuterForShader = fillOuterRadius + popOutMax;
        if (cachedNormalShader == null || cachedPressedShader == null
                || lastShaderCx != centerX || lastShaderCy != centerY
                || lastShaderInnerR != fillInnerRadius || lastShaderOuterR != maxOuterForShader
                || lastShaderBgColor != backgroundColor || lastShaderPressedColor != pressedColor) {

            int bgA = Color.alpha(backgroundColor);
            int bgInner = Color.argb(Math.max(20, Math.round(bgA * 0.55f)),
                    Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor));
            int bgOuter = Color.argb(Math.max(30, Math.round(bgA * 0.88f)),
                    Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor));
            cachedNormalShader = new RadialGradient(centerX, centerY, Math.max(1f, maxOuterForShader),
                    new int[]{bgInner, bgOuter},
                    new float[]{Math.min(0.95f, fillInnerRadius / Math.max(1f, maxOuterForShader)), 1f},
                    Shader.TileMode.CLAMP);

            int prA = Color.alpha(pressedColor);
            int prInner = Color.argb(Math.max(35, Math.round(prA * 0.28f)),
                    Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor));
            int prOuter = Color.argb(Math.max(60, Math.round(prA * 0.68f)),
                    Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor));
            cachedPressedShader = new RadialGradient(centerX, centerY, Math.max(1f, maxOuterForShader),
                    new int[]{prInner, prOuter},
                    new float[]{Math.min(0.95f, fillInnerRadius / Math.max(1f, maxOuterForShader)), 1f},
                    Shader.TileMode.CLAMP);

            lastShaderCx = centerX;
            lastShaderCy = centerY;
            lastShaderInnerR = fillInnerRadius;
            lastShaderOuterR = maxOuterForShader;
            lastShaderBgColor = backgroundColor;
            lastShaderPressedColor = pressedColor;
        }

        canvas.save();
        float scale = 0.86f + 0.14f * popupAnimProgress;
        canvas.scale(scale, scale, centerX, centerY);

        float sweepAngle = WheelPadGeometry.sweepAngleDegrees(segmentCount);
        float halfGap = segmentCount >= 2 ? GLASS_SEGMENT_GAP_DEG / 2.0f : 0f;
        float drawSweep = Math.max(1f, sweepAngle - halfGap * 2f);
        float glassStroke = Math.max(1.2f, thick * 0.35f);

        // 1. 镂空毛玻璃扇区
        for (int i = 0; i < segmentCount; i++) {
            float pop = segPopProgress[i];
            boolean isAct = (i == activeIndex);
            float startAngle = WheelPadGeometry.segmentStartAngleDegrees(i, segmentCount, alignSegmentEdge) + halfGap;
            float segInnerR = fillInnerRadius + 1.5f;
            float segOuterR = fillOuterRadius + pop * (popOutMax * 0.65f);

            buildAnnularSectorPath(segmentPath, centerX, centerY, segInnerR, segOuterR, startAngle, drawSweep);

            paintGlassFill.setShader(isAct ? cachedPressedShader : cachedNormalShader);
            canvas.drawPath(segmentPath, paintGlassFill);
            paintGlassFill.setShader(null);

            paintGlassStroke.setStrokeWidth(isAct ? Math.max(1.8f, glassStroke * 1.4f) : glassStroke);
            if (isAct) {
                paintGlassStroke.setColor(Color.argb(215,
                        Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor)));
            } else {
                paintGlassStroke.setColor(0x2EFFFFFF);
            }
            canvas.drawPath(segmentPath, paintGlassStroke);

            if (pop > 0.05f) {
                float accentR = segOuterR + 2.5f;
                tempOuterRect.set(centerX - accentR, centerY - accentR, centerX + accentR, centerY + accentR);
                float padDeg = Math.min(4f, drawSweep * 0.12f);
                paintAccentArc.setStrokeWidth(Math.max(2.5f, thick * 0.65f));
                paintAccentArc.setColor(Color.argb(Math.round(230 * pop), 255, 255, 255));
                paintAccentArc.setShadowLayer(8f * pop, 0, 0, pressedColor);
                canvas.drawArc(tempOuterRect, startAngle + padDeg, Math.max(1f, drawSweep - padDeg * 2f), false, paintAccentArc);
                paintAccentArc.setShadowLayer(0, 0, 0, 0);
            }
        }

        // 2. 通透半透明中心底盘
        float innerDiscR = Math.max(0f, fillInnerRadius - 1.5f);
        paintBackground.setColor(0x5A0B0F17);
        canvas.drawCircle(centerX, centerY, innerDiscR, paintBackground);

        paintGlassStroke.setStrokeWidth(Math.max(1.2f, glassStroke));
        paintGlassStroke.setColor(activeIndex != -1
                ? Color.argb(190, Color.red(pressedColor), Color.green(pressedColor), Color.blue(pressedColor))
                : 0x33FFFFFF);
        canvas.drawCircle(centerX, centerY, innerDiscR, paintGlassStroke);

        // 3. 中心随动方向指针
        if (activeIndex != -1 && innerDiscR > 14f) {
            drawCenterDirectionPointer(canvas, centerX, centerY, innerDiscR, lastPointerAngleDeg);
        }

        // 4. 分区文字（自动缩字号 + 防消失兜底）
        float ringThickness = fillOuterRadius - fillInnerRadius;
        float baseSegmentTextSize = ringThickness * (this.textSizePercent / 100.0f);
        paintText.setShadowLayer(3f, 0, 1.5f, 0xAA000000);
        float effectiveSweepDeg = Math.min(120f, Math.max(10f, drawSweep));

        for (int i = 0; i < segmentCount; i++) {
            if (i >= segmentValues.size()) continue;
            float pop = segPopProgress[i];
            float startAngle = WheelPadGeometry.segmentStartAngleDegrees(i, segmentCount, alignSegmentEdge);
            float textAngle = startAngle + sweepAngle / 2.0f;

            String displayName;
            if (i < segmentNames.size() && segmentNames.get(i) != null && !segmentNames.get(i).isEmpty()) {
                displayName = segmentNames.get(i);
            } else {
                displayName = getDisplayStringForValue(segmentValues.get(i));
            }

            paintText.setColor(i == activeIndex ? this.pressedTextColor : this.normalTextColor);
            paintText.setFakeBoldText(i == activeIndex);

            float textRadius = fillInnerRadius + ringThickness * 0.52f + pop * (popOutMax * 0.35f);
            // 取偏内侧一点的安全弦长，防止字宽时左右顶到分割线
            float chordRadius = fillInnerRadius + ringThickness * 0.42f + pop * (popOutMax * 0.35f);
            float maxChordWidth = (float) (2.0 * chordRadius * Math.sin(Math.toRadians(effectiveSweepDeg / 2.0))) * 0.86f;
            float targetSize = baseSegmentTextSize + pop * 2.0f;

            displayName = fitSegmentText(displayName, targetSize, maxChordWidth, ringThickness);

            double textRad = Math.toRadians(textAngle);
            float textCenterX = centerX + (float) (textRadius * Math.cos(textRad));
            float textCenterY = centerY + (float) (textRadius * Math.sin(textRad));
            Paint.FontMetrics fm = paintText.getFontMetrics();
            float baselineY = textCenterY - (fm.ascent + fm.descent) / 2.0f;
            canvas.drawText(displayName, textCenterX, baselineY, paintText);
        }

        // 5. 中心预览文字
        boolean showCenterTextInNormalMode = isPopupMode && activeIndex != -1 && currentMode == ElementController.Mode.Normal;
        if (showCenterTextInNormalMode || isEditPreview) {
            String displayName;
            if (showCenterTextInNormalMode && activeIndex < segmentValues.size()) {
                if (activeIndex < segmentNames.size() && segmentNames.get(activeIndex) != null && !segmentNames.get(activeIndex).isEmpty()) {
                    displayName = segmentNames.get(activeIndex);
                } else {
                    displayName = getDisplayStringForValue(segmentValues.get(activeIndex));
                }
            } else {
                displayName = getContext().getString(R.string.crown_preview);
            }

            paintCenterText.setColor(this.centerTextColor);
            float centerTextSize = fillInnerRadius * 2f * (this.centerTextSizePercent / 100.0f);
            paintCenterText.setTextSize(centerTextSize);
            paintCenterText.setShadowLayer(Math.max(2f, centerTextSize * 0.06f), 0, Math.max(1f, centerTextSize * 0.04f), 0x99000000);

            float textY = centerY - ((paintCenterText.descent() + paintCenterText.ascent()) / 2f);
            canvas.drawText(displayName, centerX, textY, paintCenterText);
        }

        canvas.restore();

        if (needNextFrame) {
            postOnAnimation(this::invalidateWheel);
        }
    }

    private String fitSegmentText(String text, float targetSize, float maxWidth, float ringThickness) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        paintText.setTextSize(targetSize);
        if (maxWidth <= 4f) {
            return text;
        }

        float measured = paintText.measureText(text);
        if (measured <= maxWidth) {
            return text;
        }

        // 先按比例缩放到刚好放得下（留 6% 余量对抗字体微调与加粗误差）
        float minTextSize = Math.min(targetSize, Math.max(12f, ringThickness * 0.16f));
        float scaledSize = Math.max(minTextSize, targetSize * ((maxWidth * 0.94f) / measured));
        paintText.setTextSize(scaledSize);
        measured = paintText.measureText(text);

        // 若因非等比缩放仍略微超出，再收敛一次字号
        if (measured > maxWidth && scaledSize > minTextSize) {
            scaledSize = Math.max(minTextSize, scaledSize * ((maxWidth * 0.94f) / measured));
            paintText.setTextSize(scaledSize);
            measured = paintText.measureText(text);
        }

        // 只有缩到 minTextSize 依然放不下（例如很长的组合键）才做省略号截断
        if (measured > maxWidth) {
            textMeasurePaint.set(paintText);
            String result = TextUtils.ellipsize(
                    text, textMeasurePaint, maxWidth, TextUtils.TruncateAt.END).toString();
            // 兜底：如果省略号本身比 maxWidth 还宽导致返回空串，强制保留首字符并缩到能放下
            if (result.isEmpty()) {
                String firstChar = text.substring(0, 1);
                float singleW = paintText.measureText(firstChar);
                if (singleW > maxWidth && singleW > 0f) {
                    paintText.setTextSize(Math.max(8f, scaledSize * ((maxWidth * 0.92f) / singleW)));
                }
                return firstChar;
            }
            return result;
        }
        return text;
    }

    private void buildAnnularSectorPath(Path path, float cx, float cy,
                                        float innerR, float outerR,
                                        float startAngle, float sweepAngle) {
        path.reset();
        tempOuterRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        tempInnerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);
        path.arcTo(tempOuterRect, startAngle, sweepAngle, true);
        path.arcTo(tempInnerRect, startAngle + sweepAngle, -sweepAngle, false);
        path.close();
    }

    private void drawCenterDirectionPointer(Canvas canvas, float cx, float cy, float innerR, float angleDeg) {
        float arcR = Math.max(4f, innerR - 3f);
        tempInnerRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
        paintAccentArc.setStrokeWidth(3.2f);
        paintAccentArc.setColor(0xEEFFFFFF);
        paintAccentArc.setShadowLayer(6f, 0, 0, pressedColor);
        canvas.drawArc(tempInnerRect, angleDeg - 18f, 36f, false, paintAccentArc);
        paintAccentArc.setShadowLayer(0, 0, 0, 0);

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(angleDeg);
        pointerPath.reset();
        pointerPath.moveTo(innerR + 2f, 0f);
        pointerPath.lineTo(innerR - 6f, -4.5f);
        pointerPath.lineTo(innerR - 6f, 4.5f);
        pointerPath.close();
        paintPointer.setColor(0xFFFFFFFF);
        canvas.drawPath(pointerPath, paintPointer);
        canvas.restore();
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        if (elementController.getMode() != ElementController.Mode.Normal) {
            return true;
        }

        if (!isPopupMode) {
            handleDirectModeTouchEvent(event);
            return true;
        }

        if (isWheelActive) {
            handlePopupModeTouchEvent(event);
            return true;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float dx = event.getX() - (getWidth() / 2.0f);
            float dy = event.getY() - (getHeight() / 2.0f);
            if (Math.hypot(dx, dy) <= getTriggerRadius()) {
                handlePopupModeTouchEvent(event);
                return true;
            }
        }
        return false;
    }

    private void handleDirectModeTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            if (action == MotionEvent.ACTION_DOWN) {
                elementController.buttonVibrator();
            }
            float dx = event.getX() - (getWidth() / 2.0f);
            float dy = event.getY() - (getHeight() / 2.0f);
            double distance = Math.hypot(dx, dy);

            float outerRadius = getWheelFillOuterRadius();
            float innerRadius = getWheelInnerRadiusPx();

            if (distance > innerRadius && distance < outerRadius) {
                activeIndex = WheelPadGeometry.segmentIndexForVector(dx, dy, segmentCount, alignSegmentEdge);
                lastPointerAngleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));
            } else {
                activeIndex = -1;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeIndex = -1;
        }
        updateSendingState();
        invalidateWheel();
    }

    private void handlePopupModeTouchEvent(MotionEvent event) {
        float dx = event.getX() - (getWidth() / 2.0f);
        float dy = event.getY() - (getHeight() / 2.0f);
        double distance = Math.hypot(dx, dy);
        float triggerRadius = getTriggerRadius();
        // 屏幕中心模式：轮盘不在手指下，手指的物理边界就是触发按钮本身；
        // 原地弹出模式：触发圆本身就是轮盘内圈，两者天然相等。
        float segmentInnerRadius = popupAtScreenCenter ? triggerRadius : getWheelInnerRadiusPx();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (distance <= triggerRadius) {
                    isWheelActive = true;
                    popupAnimProgress = 0.0f;
                    // 复位时间戳，否则首帧 dt 会被夹到上限，弹出动画一帧就结束了。
                    lastAnimTimestamp = 0L;
                    elementController.buttonVibrator();
                    invalidateWheel();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (isWheelActive) {
                    hoveredGroupButton = null;
                    lastPointerAngleDeg = (float) Math.toDegrees(Math.atan2(dy, dx));

                    if (distance > segmentInnerRadius) {
                        activeIndex = WheelPadGeometry.segmentIndexForVector(dx, dy, segmentCount, alignSegmentEdge);
                        if (activeIndex != -1 && activeIndex < segmentValues.size()) {
                            String value = segmentValues.get(activeIndex);
                            if (value != null && value.startsWith("gb")) {
                                try {
                                    long groupId = Long.parseLong(value.substring(2));
                                    Element element = elementController.findElementById(groupId);
                                    if (element instanceof GroupButton) {
                                        hoveredGroupButton = (GroupButton) element;
                                    }
                                } catch (Exception e) {
                                    hoveredGroupButton = null;
                                }
                            }
                        }
                    } else {
                        activeIndex = -1;
                    }
                    invalidateWheel();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (isWheelActive) {
                    if (activeIndex != -1 && activeIndex < segmentValues.size()) {
                        String value = segmentValues.get(activeIndex);
                        List<ElementController.SendEventHandler> handlers = getHandlersForValueNow(value);

                        for (ElementController.SendEventHandler handler : handlers) {
                            handler.sendEvent(true);
                        }

                        if (!handlers.isEmpty()) {
                            elementController.getHandler().postDelayed(() -> {
                                for (int i = handlers.size() - 1; i >= 0; i--) {
                                    handlers.get(i).sendEvent(false);
                                }
                            }, 30);
                        }
                    }
                }
                // 穿透到 CANCEL 统一复位状态
            case MotionEvent.ACTION_CANCEL:
                isWheelActive = false;
                activeIndex = -1;
                hoveredGroupButton = null;
                invalidateWheel();
                break;
        }
    }

    private void updateSendingState() {
        if (activeIndex != lastActiveIndex) {
            if (lastActiveIndex != -1 && lastActiveIndex < segmentValues.size()) {
                String lastValue = segmentValues.get(lastActiveIndex);
                List<ElementController.SendEventHandler> lastHandlers = getHandlersForValueNow(lastValue);
                for (int i = lastHandlers.size() - 1; i >= 0; i--) {
                    lastHandlers.get(i).sendEvent(false);
                }
            }

            if (activeIndex != -1 && activeIndex < segmentValues.size()) {
                String activeValue = segmentValues.get(activeIndex);
                List<ElementController.SendEventHandler> activeHandlers = getHandlersForValueNow(activeValue);
                for (ElementController.SendEventHandler handler : activeHandlers) {
                    handler.sendEvent(true);
                }
            }
            lastActiveIndex = activeIndex;
        }
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (wheelPadPage == null) {
            wheelPadPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_wheel_pad_clean, null);
            centralXNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_central_x);
            centralYNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_central_y);
            valuesContainer = wheelPadPage.findViewById(R.id.page_wheel_pad_values_container);
        }

        ElementEditText centerTextInput = wheelPadPage.findViewById(R.id.page_wheel_pad_center_text);
        final SwitchCompat popupSwitch = wheelPadPage.findViewById(R.id.page_wheel_pad_popup_at_center_switch);
        final SwitchCompat previewGroupSwitch = wheelPadPage.findViewById(R.id.page_wheel_pad_preview_group_switch);
        final LinearLayout popupOptionsContainer = wheelPadPage.findViewById(R.id.page_wheel_pad_popup_options_container);
        final NumberSeekbar triggerSizeNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_trigger_size);

        NumberSeekbar sizeNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_size);
        NumberSeekbar thickNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_thick);
        NumberSeekbar layerNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_layer);
        ElementEditText normalColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_normal_color);
        ElementEditText pressedColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_pressed_color);
        ElementEditText backgroundColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_background_color);
        Button copyButton = wheelPadPage.findViewById(R.id.page_wheel_pad_copy);
        Button deleteButton = wheelPadPage.findViewById(R.id.page_wheel_pad_delete);
        NumberSeekbar segmentCountNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_segment_count);
        Spinner segmentAlignmentSpinner = wheelPadPage.findViewById(R.id.page_wheel_pad_segment_alignment);
        NumberSeekbar innerRadiusNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_inner_radius);

        NumberSeekbar textSizeNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_text_size);
        ElementEditText normalTextColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_normal_text_color);
        ElementEditText pressedTextColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_pressed_text_color);
        NumberSeekbar centerTextSizeNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_center_text_size);
        ElementEditText centerTextColorElementEditText = wheelPadPage.findViewById(R.id.page_wheel_pad_center_text_color);
        NumberSeekbar triggerTextSizeNumberSeekbar = wheelPadPage.findViewById(R.id.page_wheel_pad_trigger_text_size);

        setupCommonControls(centerTextInput, sizeNumberSeekbar, thickNumberSeekbar, layerNumberSeekbar,
                normalColorElementEditText, pressedColorElementEditText, backgroundColorElementEditText,
                copyButton, deleteButton);

        if (triggerSizeNumberSeekbar != null) {
            triggerSizeNumberSeekbar.setProgressMin(TRIGGER_MIN_DIAMETER);
            triggerSizeNumberSeekbar.setProgressMax(Math.min(TRIGGER_MAX_DIAMETER, Math.min(widthMax, heightMax)));
            triggerSizeNumberSeekbar.setValueWithNoCallBack(this.triggerDiameter);
            triggerSizeNumberSeekbar.setVisibility((isPopupMode && popupAtScreenCenter) ? View.VISIBLE : View.GONE);
            triggerSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    setTriggerDiameter(progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    save();
                }
            });
        }

        popupSwitch.setChecked(this.popupAtScreenCenter);
        popupSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            this.popupAtScreenCenter = isChecked;
            if (triggerSizeNumberSeekbar != null) {
                triggerSizeNumberSeekbar.setVisibility((isPopupMode && isChecked) ? View.VISIBLE : View.GONE);
            }
            syncViewSizeForCurrentMode();
            invalidateWheel();
            save();
        });

        previewGroupSwitch.setChecked(this.previewGroupChildren);
        previewGroupSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            this.previewGroupChildren = isChecked;
            save();
        });

        boolean isPopup = !this.centerText.isEmpty();
        popupOptionsContainer.setVisibility(isPopup ? View.VISIBLE : View.GONE);

        centerTextInput.setOnTextChangedListener(text -> {
            setCenterText(text);
            boolean isPopupNow = !text.isEmpty();
            popupOptionsContainer.setVisibility(isPopupNow ? View.VISIBLE : View.GONE);
            if (triggerSizeNumberSeekbar != null) {
                triggerSizeNumberSeekbar.setVisibility((isPopupNow && popupAtScreenCenter) ? View.VISIBLE : View.GONE);
            }
            invalidateWheel();
            save();
        });

        segmentCountNumberSeekbar.setValueWithNoCallBack(segmentCount);
        segmentCountNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setSegmentCount(progress);
                updateValuesContainerUI();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        ArrayAdapter<String> alignmentAdapter = new ArrayAdapter<>(
                getContext(),
                R.layout.crown_spinner_item,
                Arrays.asList(
                        getContext().getString(R.string.wheel_alignment_center),
                        getContext().getString(R.string.wheel_alignment_edge)
                )
        );
        alignmentAdapter.setDropDownViewResource(R.layout.crown_spinner_item);
        segmentAlignmentSpinner.setAdapter(alignmentAdapter);
        segmentAlignmentSpinner.setSelection(alignSegmentEdge ? 1 : 0, false);
        segmentAlignmentSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean newAlignSegmentEdge = position == 1;
                if (alignSegmentEdge != newAlignSegmentEdge) {
                    alignSegmentEdge = newAlignSegmentEdge;
                    invalidateWheel();
                    save();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        innerRadiusNumberSeekbar.setValueWithNoCallBack(innerRadiusPercent);
        innerRadiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setInnerRadiusPercent(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        textSizeNumberSeekbar.setProgressMin(10);
        textSizeNumberSeekbar.setProgressMax(100);
        textSizeNumberSeekbar.setValueWithNoCallBack(this.textSizePercent);
        textSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textSizePercent = progress;
                invalidateWheel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        centerTextSizeNumberSeekbar.setProgressMin(10);
        centerTextSizeNumberSeekbar.setProgressMax(150);
        centerTextSizeNumberSeekbar.setValueWithNoCallBack(this.centerTextSizePercent);
        centerTextSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                centerTextSizePercent = progress;
                invalidateWheel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        CrownColorPickerBinder.bind(this, normalTextColorElementEditText, () -> this.normalTextColor, color -> {
            this.normalTextColor = color;
            invalidateWheel();
        });
        CrownColorPickerBinder.bind(this, pressedTextColorElementEditText, () -> this.pressedTextColor, color -> {
            this.pressedTextColor = color;
            invalidateWheel();
        });
        CrownColorPickerBinder.bind(this, centerTextColorElementEditText, () -> this.centerTextColor, color -> {
            this.centerTextColor = color;
            invalidateWheel();
        });

        triggerTextSizeNumberSeekbar.setProgressMin(5);
        triggerTextSizeNumberSeekbar.setProgressMax(150);
        triggerTextSizeNumberSeekbar.setValueWithNoCallBack(this.triggerTextSizePercent);
        triggerTextSizeNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                triggerTextSizePercent = progress;
                invalidateWheel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                save();
            }
        });

        updateValuesContainerUI();
        return wheelPadPage;
    }

    @Override
    public void save() {
        int viewSize = getExpectedViewSize();
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, viewSize);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, viewSize);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, this.centerText);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, layer);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, getElementCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
        contentValues.put(COLUMN_INT_ELEMENT_THICK, thick);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, normalColor);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, pressedColor);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, backgroundColor);
        contentValues.put(COLUMN_INT_ELEMENT_MODE, segmentCount);
        contentValues.put(COLUMN_INT_ELEMENT_SENSE, innerRadiusPercent);
        contentValues.put(COLUMN_INT_ELEMENT_FLAG1, popupAtScreenCenter ? 1 : 0);

        contentValues.put("extra_attributes", new Gson().toJson(buildExtraAttributes()));

        List<String> combinedSegments = new ArrayList<>();
        for (int i = 0; i < segmentValues.size(); i++) {
            String value = segmentValues.get(i);
            String name = (i < segmentNames.size()) ? segmentNames.get(i) : "";
            if (name != null && !name.isEmpty()) {
                combinedSegments.add(value + "|" + name);
            } else {
                combinedSegments.add(value);
            }
        }
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, String.join(",", combinedSegments));
        elementController.updateElement(elementId, contentValues);
    }

    public static ContentValues getInitialInfo() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_WHEEL_PAD);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE, "k51,k32,k47,k29,k45,k33,k46,k31");
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH, 400);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT, 400);
        contentValues.put(COLUMN_STRING_ELEMENT_TEXT, "");
        contentValues.put(COLUMN_INT_ELEMENT_LAYER, 40);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X, 250);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y, 250);
        contentValues.put(COLUMN_INT_ELEMENT_THICK, 5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, 0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, 0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, 0xAA444444);
        contentValues.put(COLUMN_INT_ELEMENT_MODE, 8);
        contentValues.put(COLUMN_INT_ELEMENT_SENSE, 30);
        contentValues.put(COLUMN_INT_ELEMENT_FLAG1, 1);

        JsonObject extraAttrs = new JsonObject();
        extraAttrs.addProperty("normalTextColor", 0xFFFFFFFF);
        extraAttrs.addProperty("pressedTextColor", 0xFFFFFFFF);
        extraAttrs.addProperty("centerTextColor", 0xFFFFFFFF);
        extraAttrs.addProperty("textSizePercent", 35);
        extraAttrs.addProperty("centerTextSizePercent", 60);
        extraAttrs.addProperty("triggerTextSizePercent", 40);
        extraAttrs.addProperty("previewGroupChildren", true);
        extraAttrs.addProperty(EXTRA_SEGMENT_ALIGNMENT, SEGMENT_ALIGNMENT_CENTER);
        extraAttrs.addProperty(EXTRA_WHEEL_DIAMETER, 400);
        extraAttrs.addProperty(EXTRA_TRIGGER_DIAMETER, 130);
        contentValues.put("extra_attributes", new Gson().toJson(extraAttrs));

        return contentValues;
    }

    private void setupCommonControls(ElementEditText centerTextInput, NumberSeekbar size, NumberSeekbar thick, NumberSeekbar layer,
                                     ElementEditText normalColor, ElementEditText pressedColor, ElementEditText backgroundColor,
                                     Button copy, Button delete) {

        centerTextInput.setTextWithNoTextChangedCallBack(this.centerText);
        centerTextInput.setOnTextChangedListener(text -> {
            setCenterText(text);
            save();
        });

        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                setElementCentralX(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save();
            }
        });
        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                setElementCentralY(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save();
            }
        });
        size.setProgressMax(Math.min(widthMax, heightMax));
        size.setProgressMin(WHEEL_MIN_DIAMETER);
        size.setValueWithNoCallBack(this.diameter);
        size.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                setDiameter(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save();
            }
        });
        thick.setValueWithNoCallBack(this.thick);
        thick.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
                setElementThick(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                save();
            }
        });
        layer.setValueWithNoCallBack(this.layer);
        layer.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean f) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                setElementLayer(layer.getValue());
                save();
            }
        });

        CrownColorPickerBinder.bind(this, normalColor, () -> this.normalColor, this::setElementNormalColor);
        CrownColorPickerBinder.bind(this, pressedColor, () -> this.pressedColor, this::setElementPressedColor);
        CrownColorPickerBinder.bind(this, backgroundColor, () -> this.backgroundColor, this::setElementBackgroundColor);

        copy.setOnClickListener(v -> {
            int viewSize = getExpectedViewSize();
            ContentValues cv = new ContentValues();
            cv.put(COLUMN_INT_ELEMENT_TYPE, ELEMENT_TYPE_WHEEL_PAD);
            cv.put(COLUMN_INT_ELEMENT_WIDTH, viewSize);
            cv.put(COLUMN_INT_ELEMENT_HEIGHT, viewSize);
            cv.put(COLUMN_STRING_ELEMENT_TEXT, this.centerText);
            cv.put(COLUMN_INT_ELEMENT_LAYER, this.layer);
            cv.put(COLUMN_INT_ELEMENT_CENTRAL_X, Math.max(Math.min(getElementCentralX() + viewSize / 2, centralXMax), centralXMin));
            cv.put(COLUMN_INT_ELEMENT_CENTRAL_Y, getElementCentralY());
            cv.put(COLUMN_INT_ELEMENT_THICK, this.thick);
            cv.put(COLUMN_INT_ELEMENT_NORMAL_COLOR, this.normalColor);
            cv.put(COLUMN_INT_ELEMENT_PRESSED_COLOR, this.pressedColor);
            cv.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR, this.backgroundColor);
            cv.put(COLUMN_INT_ELEMENT_MODE, this.segmentCount);
            cv.put(COLUMN_INT_ELEMENT_SENSE, this.innerRadiusPercent);
            cv.put(COLUMN_INT_ELEMENT_FLAG1, this.popupAtScreenCenter ? 1 : 0);

            cv.put("extra_attributes", new Gson().toJson(buildExtraAttributes()));

            List<String> combinedSegments = new ArrayList<>();
            for (int i = 0; i < segmentValues.size(); i++) {
                String value = segmentValues.get(i);
                String name = (i < segmentNames.size()) ? segmentNames.get(i) : "";
                if (name != null && !name.isEmpty()) {
                    combinedSegments.add(value + "|" + name);
                } else {
                    combinedSegments.add(value);
                }
            }
            cv.put(COLUMN_STRING_ELEMENT_VALUE, String.join(",", combinedSegments));
            elementController.addElement(cv);
        });
        delete.setOnClickListener(v -> {
            elementController.toggleInfoPage(wheelPadPage);
            elementController.deleteElement(wheelPad);
        });
    }

    protected void setDiameter(int newDiameter) {
        this.diameter = clampWheelDiameter(newDiameter);
        syncViewSizeForCurrentMode();
        invalidateWheel();
    }

    protected void setTriggerDiameter(int newTriggerDiameter) {
        this.triggerDiameter = clampTriggerDiameter(newTriggerDiameter);
        syncViewSizeForCurrentMode();
        invalidateWheel();
    }

    private JsonObject buildExtraAttributes() {
        JsonObject extraAttrs = new JsonObject();
        extraAttrs.addProperty("normalTextColor", this.normalTextColor);
        extraAttrs.addProperty("pressedTextColor", this.pressedTextColor);
        extraAttrs.addProperty("centerTextColor", this.centerTextColor);
        extraAttrs.addProperty("textSizePercent", this.textSizePercent);
        extraAttrs.addProperty("centerTextSizePercent", this.centerTextSizePercent);
        extraAttrs.addProperty("triggerTextSizePercent", this.triggerTextSizePercent);
        extraAttrs.addProperty("previewGroupChildren", this.previewGroupChildren);
        extraAttrs.addProperty(EXTRA_SEGMENT_ALIGNMENT,
                this.alignSegmentEdge ? SEGMENT_ALIGNMENT_EDGE : SEGMENT_ALIGNMENT_CENTER);
        extraAttrs.addProperty(EXTRA_WHEEL_DIAMETER, this.diameter);
        extraAttrs.addProperty(EXTRA_TRIGGER_DIAMETER, this.triggerDiameter);
        return extraAttrs;
    }

    protected void setSegmentCount(int count) {
        if (count < 2) count = 2;
        this.segmentCount = count;
        ensureSegPopCapacity(count);
        while (segmentValues.size() < count) {
            segmentValues.add("null");
            segmentNames.add("");
        }
        while (segmentValues.size() > count) {
            segmentValues.remove(segmentValues.size() - 1);
            segmentNames.remove(segmentNames.size() - 1);
        }
        invalidateWheel();
    }

    protected void setSegmentValue(int index, String value) {
        if (index >= 0 && index < segmentValues.size()) {
            segmentValues.set(index, value);
            invalidateWheel();
        }
    }

    protected void setInnerRadiusPercent(int percent) {
        this.innerRadiusPercent = percent;
        invalidateWheel();
    }

    protected void setElementThick(int thick) {
        this.thick = thick;
        invalidateWheel();
    }

    protected void setElementNormalColor(int normalColor) {
        this.normalColor = normalColor;
        invalidateWheel();
    }

    protected void setElementPressedColor(int pressedColor) {
        this.pressedColor = pressedColor;
        invalidateWheel();
    }

    protected void setElementBackgroundColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
        invalidateWheel();
    }

    protected void setElementNormalTextColor(int normalTextColor) {
        this.normalTextColor = normalTextColor;
        invalidateWheel();
    }

    protected void setElementPressedTextColor(int pressedTextColor) {
        this.pressedTextColor = pressedTextColor;
        invalidateWheel();
    }

    protected void setElementCenterTextColor(int centerTextColor) {
        this.centerTextColor = centerTextColor;
        invalidateWheel();
    }

    protected void setCenterText(String text) {
        this.centerText = text;
        this.isPopupMode = !text.isEmpty();
        syncViewSizeForCurrentMode();
        invalidateWheel();
    }

    @Override
    protected void updatePage() {
        if (wheelPadPage != null) {
            centralXNumberSeekbar.setValueWithNoCallBack(getElementCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getElementCentralY());
        }
    }

    private String getDisplayStringForValue(String value) {
        if (value == null || value.isEmpty() || value.equals("null")) {
            return getContext().getString(R.string.empty_value);
        }

        if (value.startsWith("gb")) {
            try {
                long elementId = Long.parseLong(value.substring(2));
                Element element = elementController.findElementById(elementId);
                if (element instanceof GroupButton) {
                    return getContext().getString(R.string.wheel_value_group, ((GroupButton) element).getText());
                } else {
                    return getContext().getString(R.string.wheel_value_invalid_group);
                }
            } catch (NumberFormatException e) {
                return getContext().getString(R.string.wheel_value_bad_group_id);
            }
        }

        String[] singleKeyValues = value.split("\\+");
        List<String> keyNames = new ArrayList<>();
        for (String singleKeyValue : singleKeyValues) {
            keyNames.add(pageDeviceController.getKeyNameByValue(singleKeyValue));
        }
        return String.join(" + ", keyNames);
    }

    private void updateKeyCombinationDisplay(LinearLayout keysContainer, List<String> currentKeys, Button addButton, Button addGroupButton, final AlertDialog dialog) {
        keysContainer.removeAllViews();
        Context context = keysContainer.getContext();
        boolean isGroup = !currentKeys.isEmpty() && currentKeys.get(0).startsWith("gb");

        if (isGroup) {
            addButton.setVisibility(View.GONE);
            addGroupButton.setVisibility(View.GONE);
        } else {
            addButton.setVisibility(View.VISIBLE);
            addGroupButton.setVisibility(View.VISIBLE);
        }

        if (currentKeys.isEmpty()) {
            TextView emptyText = new TextView(context);
            emptyText.setText(R.string.wheel_empty_keys);
            emptyText.setPadding(0, 10, 0, 10);
            keysContainer.addView(emptyText);
        } else if (isGroup) {
            try {
                long elementId = Long.parseLong(currentKeys.get(0).substring(2));
                Element element = elementController.findElementById(elementId);
                if (element instanceof GroupButton) {
                    final GroupButton groupButton = (GroupButton) element;
                    final SuperPageLayout groupButtonSettingsPage = groupButton.getInfoPage();
                    Button groupBtnDisplay = new Button(context);
                    groupBtnDisplay.setText(context.getString(
                            R.string.wheel_group_button_action,
                            context.getString(R.string.wheel_value_group, groupButton.getText())));
                    groupBtnDisplay.setAllCaps(false);

                    groupBtnDisplay.setOnClickListener(v -> {
                        if (dialog != null) {
                            dialog.dismiss();
                        }
                        final SuperPageLayout wheelPadSettingsPage = wheelPad.getInfoPage();
                        groupButtonSettingsPage.setLastPage(wheelPadSettingsPage);
                        groupButtonSettingsPage.setPageReturnListener(new SuperPageLayout.ReturnListener() {
                            @Override
                            public void returnCallBack() {
                                elementController.getSuperPagesController().openNewPage(wheelPadSettingsPage);
                            }
                        });
                        elementController.getSuperPagesController().openNewPage(groupButtonSettingsPage);
                    });

                    groupBtnDisplay.setOnLongClickListener(v -> {
                        currentKeys.clear();
                        updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog);
                        Toast.makeText(context, R.string.wheel_group_removed, Toast.LENGTH_SHORT).show();
                        return true;
                    });
                    keysContainer.addView(groupBtnDisplay);
                } else {
                    currentKeys.clear();
                    addButton.setVisibility(View.VISIBLE);
                    addGroupButton.setVisibility(View.VISIBLE);
                    TextView errorText = new TextView(context);
                    errorText.setText(context.getString(R.string.wheel_group_wrong_type, elementId));
                    keysContainer.addView(errorText);
                }
            } catch (Exception e) {
                currentKeys.clear();
                addButton.setVisibility(View.VISIBLE);
                addGroupButton.setVisibility(View.VISIBLE);
                TextView errorText = new TextView(context);
                errorText.setText(R.string.wheel_group_missing);
                keysContainer.addView(errorText);
            }
        } else {
            for (int i = 0; i < currentKeys.size(); i++) {
                final int keyIndex = i;
                TextView keyView = new TextView(context);
                keyView.setText(context.getString(
                        R.string.wheel_key_tap_remove,
                        pageDeviceController.getKeyNameByValue(currentKeys.get(keyIndex))));
                keyView.setTextSize(16);
                keyView.setBackgroundResource(R.drawable.enabled_square);
                keyView.setPadding(20, 15, 20, 15);
                keyView.setGravity(Gravity.CENTER);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, 5, 0, 5);
                keyView.setLayoutParams(params);

                keyView.setOnClickListener(v -> {
                    currentKeys.remove(keyIndex);
                    updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog);
                });
                keysContainer.addView(keyView);
            }
        }
    }

    private void showKeyCombinationDialog(Context context, final int index, final TextView valueText) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppDialogStyle);
        builder.setTitle(context.getString(R.string.wheel_edit_keys_title, index + 1));

        LinearLayout dialogLayout = new LinearLayout(context);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(30, 20, 30, 20);

        LinearLayout keysContainer = new LinearLayout(context);
        keysContainer.setOrientation(LinearLayout.VERTICAL);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        );
        scrollParams.setMargins(0, 0, 0, 20);
        scrollView.setLayoutParams(scrollParams);
        scrollView.addView(keysContainer);

        String currentValue = segmentValues.get(index);
        final List<String> currentKeys = new ArrayList<>();
        if (currentValue != null && !currentValue.isEmpty() && !currentValue.equals("null")) {
            if (currentValue.startsWith("gb")) {
                currentKeys.add(currentValue);
            } else {
                currentKeys.addAll(Arrays.asList(currentValue.split("\\+")));
            }
        }

        LinearLayout buttonContainer = new LinearLayout(context);
        buttonContainer.setOrientation(LinearLayout.HORIZONTAL);
        buttonContainer.setGravity(Gravity.CENTER);

        Button addButton = new Button(context);
        addButton.setText(R.string.wheel_add_key);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        buttonParams.setMarginEnd(10);
        addButton.setLayoutParams(buttonParams);

        Button addGroupButton = new Button(context);
        addGroupButton.setText(R.string.wheel_add_group_key);
        LinearLayout.LayoutParams groupButtonParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        groupButtonParams.setMarginStart(10);
        addGroupButton.setLayoutParams(groupButtonParams);

        buttonContainer.addView(addButton);
        buttonContainer.addView(addGroupButton);

        dialogLayout.addView(scrollView);
        dialogLayout.addView(buttonContainer);

        final AlertDialog dialog = builder.create();

        updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog);
        dialog.setView(dialogLayout);

        dialog.setButton(AlertDialog.BUTTON_POSITIVE, context.getString(R.string.dialog_button_ok), (d, which) -> {
            String finalValue;
            if (currentKeys.isEmpty()) {
                finalValue = "null";
            } else if (currentKeys.get(0).startsWith("gb")) {
                finalValue = currentKeys.get(0);
            } else {
                finalValue = String.join("+", currentKeys);
            }
            setSegmentValue(index, finalValue);
            valueText.setText(getDisplayStringForValue(finalValue));
            save();
        });
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, context.getString(R.string.dialog_button_cancel), (d, which) -> d.dismiss());

        addGroupButton.setOnClickListener(v -> {
            List<GroupButton> groupButtons = new ArrayList<>();
            for (Element el : elementController.getElements()) {
                if (el instanceof GroupButton) {
                    groupButtons.add((GroupButton) el);
                }
            }

            final List<String> options = new ArrayList<>();
            for (GroupButton gb : groupButtons) {
                options.add(gb.getText() + " (ID: " + gb.elementId + ")");
            }
            options.add(context.getString(R.string.wheel_create_new_group));

            AlertDialog groupSelectionDialog = new AlertDialog.Builder(context, R.style.AppDialogStyle)
                    .setTitle(R.string.wheel_select_group_title)
                    .setItems(options.toArray(new String[0]), (selectionDialog, which) -> {

                        String selectedValue = null;
                        Element newElementToEdit = null;

                        if (which == options.size() - 1) {
                            ContentValues cv = GroupButton.getInitialInfo();
                            Element newElement = elementController.addElement(cv);
                            if (newElement != null) {
                                selectedValue = "gb" + newElement.elementId;
                                newElementToEdit = newElement;
                            }
                        } else {
                            GroupButton selectedGb = groupButtons.get(which);
                            selectedValue = "gb" + selectedGb.elementId;
                        }

                        if (selectedValue != null) {
                            setSegmentValue(index, selectedValue);
                            save();
                            valueText.setText(getDisplayStringForValue(selectedValue));
                            dialog.dismiss();

                            if (newElementToEdit != null) {
                                Toast.makeText(context, R.string.wheel_new_group_created, Toast.LENGTH_LONG).show();
                                elementController.toggleInfoPage(newElementToEdit.getInfoPage());
                            }
                        }
                    })
                    .show();
            AppDialogStyler.INSTANCE.applySystemChoiceList(groupSelectionDialog, context);
        });

        addButton.setOnClickListener(v -> {
            dialog.hide();
            PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                @Override
                public void OnKeyClick(TextView key) {
                    currentKeys.add(key.getTag().toString());
                    dialog.show();
                    updateKeyCombinationDisplay(keysContainer, currentKeys, addButton, addGroupButton, dialog);
                }
            };
            pageDeviceController.open(deviceCallBack, View.VISIBLE, View.VISIBLE, View.VISIBLE);
        });

        dialog.show();
        AppDialogStyler.INSTANCE.apply(dialog, context);
    }

    private void updateValuesContainerUI() {
        if (valuesContainer == null) return;
        valuesContainer.removeAllViews();
        for (int i = 0; i < segmentCount; i++) {
            if (i >= segmentValues.size()) continue;
            View valueView = LayoutInflater.from(getContext()).inflate(R.layout.item_key_value, valuesContainer, false);
            TextView title = valueView.findViewById(R.id.item_key_value_title);
            ElementEditText nameInput = valueView.findViewById(R.id.item_key_value_name);
            TextView valueText = valueView.findViewById(R.id.item_key_value_value);

            title.setText(getContext().getString(R.string.wheel_section_title, i + 1));
            final int index = i;

            if (index < segmentNames.size()) {
                nameInput.setTextWithNoTextChangedCallBack(segmentNames.get(index));
            }
            nameInput.setOnTextChangedListener(text -> {
                if (index < segmentNames.size()) {
                    segmentNames.set(index, text);
                    invalidateWheel();
                    save();
                }
            });

            valueText.setText(getDisplayStringForValue(segmentValues.get(i)));

            valueText.setOnClickListener(v -> showKeyCombinationDialog(getContext(), index, valueText));
            valuesContainer.addView(valueView);
        }
    }
}