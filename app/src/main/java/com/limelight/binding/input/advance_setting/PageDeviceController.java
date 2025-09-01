package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

public class PageDeviceController {

    public interface DeviceCallBack{
        void OnKeyClick(TextView key);
    }

    private Context context;
    private ControllerManager controllerManager;
    private SuperPageLayout devicePage;
    private DeviceCallBack deviceCallBack;
    private LinearLayout keyboardDrawing;
    private FrameLayout mouseDrawing;
    private FrameLayout gamepadDrawing;

    public PageDeviceController(Context context, ControllerManager controllerManager) {
        this.context = context;
        this.controllerManager = controllerManager;
        devicePage = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_device,null);
        keyboardDrawing = devicePage.findViewById(R.id.keyboard_drawing);
        mouseDrawing = devicePage.findViewById(R.id.mouse_drawing);
        gamepadDrawing = devicePage.findViewById(R.id.gamepad_drawing);

        View.OnClickListener onClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 确保回调不为空，并且点击的是TextView，避免意外的类型转换错误
                if (deviceCallBack != null && v instanceof TextView) {
                    deviceCallBack.OnKeyClick((TextView) v);
                    close();
                }
            }
        };
        setListenersForDevice(devicePage,onClickListener);

        devicePage.findViewById(R.id.device_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });


    }

    public void open(DeviceCallBack deviceCallBack, int keyboardVisible, int mouseVisible, int gamepadVisible){
        this.deviceCallBack = deviceCallBack;
        keyboardDrawing.setVisibility(keyboardVisible);
        mouseDrawing.setVisibility(mouseVisible);
        gamepadDrawing.setVisibility(gamepadVisible);
        controllerManager.getSuperPagesController().openNewPage(devicePage);
    }

    private void setListenersForDevice(ViewGroup viewGroup, View.OnClickListener listener) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            // 只为带有tag的TextView设置监听器，这些是实际的按键
            if (child instanceof TextView && child.getTag() != null) {
                child.setOnClickListener(listener);
            } else if (child instanceof ViewGroup) {
                setListenersForDevice((ViewGroup) child, listener);
            }
        }
    }

    /**
     * 根据按键的tag值（例如 "k51"）安全地获取其显示的名称（例如 "W"）。
     * @param value 要查找的按键的tag值。
     * @return 按键的显示名称，如果找不到则返回一个安全的默认值。
     */
    public String getKeyNameByValue(String value){
        // 1. 预处理无效的输入值
        if (value == null || value.isEmpty() || value.equals("null")) {
            return "空"; // 返回一个明确的“未设置”状态
        }

        // 2. 查找视图
        View foundView = devicePage.findViewWithTag(value);

        // 3. 安全地检查和转换
        if (foundView instanceof TextView) {
            // 确保视图是TextView后，才进行转换和获取文本
            return ((TextView) foundView).getText().toString();
        }

        // 4. 如果找不到视图，或者找到的视图不是TextView，返回原始tag值
        // 这对于调试非常有用，用户可以看到是哪个值出了问题
        return value;
    }


    public void close(){
        controllerManager.getSuperPagesController().openNewPage(devicePage.getLastPage());
    }
}