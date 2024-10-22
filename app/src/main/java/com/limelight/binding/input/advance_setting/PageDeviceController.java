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
                deviceCallBack.OnKeyClick((TextView) v);
                controllerManager.getSuperPagesController().close();
            }
        };
        setListenersForDevice(devicePage,onClickListener);

        devicePage.findViewById(R.id.device_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                controllerManager.getSuperPagesController().close();
            }
        });


    }

    public void open(DeviceCallBack deviceCallBack, int keyboardVisible, int mouseVisible, int gamepadVisible){
        this.deviceCallBack = deviceCallBack;
        keyboardDrawing.setVisibility(keyboardVisible);
        mouseDrawing.setVisibility(mouseVisible);
        gamepadDrawing.setVisibility(gamepadVisible);
        controllerManager.getSuperPagesController().open(devicePage);
    }

    private void setListenersForDevice(ViewGroup viewGroup, View.OnClickListener listener) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView) {
                child.setOnClickListener(listener);
            } else if (child instanceof ViewGroup) {
                setListenersForDevice((ViewGroup) child, listener);
            }
        }
    }

    public String getKeyNameByValue(String value){
        return ((TextView)devicePage.findViewWithTag(value)).getText().toString();
    }
}
