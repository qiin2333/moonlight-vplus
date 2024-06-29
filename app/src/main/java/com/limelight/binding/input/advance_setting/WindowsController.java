package com.limelight.binding.input.advance_setting;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

import java.util.List;

public class WindowsController {

    public interface TextWindowListener{
        boolean onConfirmCLick();

        boolean onCancelClick();

    }

    public interface EditTextWindowListener{
        boolean onConfirmClick(String text);

        boolean onCancelClick(String text);

    }

    public interface DeviceWindowListener{
        void onElementClick(String text, String tag);
    }

    private ControllerManager controllerManager;
    private Context context;
    private FrameLayout layout;
    public static int KEYBOARD_DEVICE_MASK = 1;
    public static int MOUSE_DEVICE_MASK = 2;
    public static int GAMEPAD_DEVICE_MASK = 4;


    //text window
    private FrameLayout textWindow;
    private TextView textWindowText;
    private Button textWindowConfirmButton;
    private Button textWindowCancelButton;
    private TextWindowListener textWindowListener;

    //edittext window
    private FrameLayout editTextWindow;
    private EditText editTextWindowText;
    private TextView editTextWindowTitle;
    private Button editTextWindowConfirmButton;
    private Button editTextWindowCancelButton;
    private EditTextWindowListener editTextWindowListener;

    //device window
    private FrameLayout deviceWindow;
    private Button keyboardButton;
    private Button mouseButton;
    private Button gamepadButton;
    private FrameLayout keyboardLayout;
    private FrameLayout mouseLayout;
    private FrameLayout gamepadLayout;
    private DeviceWindowListener deviceWindowListener;




    public WindowsController(ControllerManager controllerManager, FrameLayout layout, Context context){
        this.controllerManager = controllerManager;
        this.context = context;
        this.layout = layout;


        initTextWindow();
        initEditTextWindow();
        initDeviceWindow();
    }

    private void initTextWindow(){
        textWindow = layout.findViewById(R.id.text_window);
        textWindowText = layout.findViewById(R.id.text_window_text);
        textWindowConfirmButton = layout.findViewById(R.id.text_window_confirm);
        textWindowCancelButton = layout.findViewById(R.id.text_window_canel);

        textWindowConfirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (textWindowListener.onConfirmCLick()){
                    textWindow.setVisibility(View.GONE);
                }
            }
        });
        textWindowCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (textWindowListener.onCancelClick()){
                    textWindow.setVisibility(View.GONE);
                }
            }
        });
    }

    private void initEditTextWindow(){
        editTextWindow = layout.findViewById(R.id.edittext_window);
        editTextWindowTitle = layout.findViewById(R.id.edittext_window_title);
        editTextWindowText = layout.findViewById(R.id.edittext_window_text);
        editTextWindowConfirmButton = layout.findViewById(R.id.edittext_window_confirm);
        editTextWindowCancelButton = layout.findViewById(R.id.edittext_window_canel);

        editTextWindowConfirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = editTextWindowText.getText().toString();
                if (editTextWindowListener.onConfirmClick(text)) {
                    editTextWindow.setVisibility(View.GONE);
                }
            }
        });
        editTextWindowCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = editTextWindowText.getText().toString();
                if (editTextWindowListener.onCancelClick(text)) {
                    editTextWindow.setVisibility(View.GONE);
                }
            }
        });
    }

    private void initDeviceWindow(){
        deviceWindow = layout.findViewById(R.id.device_layout_container);
        keyboardButton = layout.findViewById(R.id.keyboard_open_button);
        mouseButton = layout.findViewById(R.id.mouse_open_button);
        gamepadButton = layout.findViewById(R.id.gamepad_open_button);
        keyboardLayout = layout.findViewById(R.id.keyboard_device_layout);
        mouseLayout = layout.findViewById(R.id.mouse_device_layout);
        gamepadLayout = layout.findViewById(R.id.gamepad_device_layout);

        keyboardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchDeviceLayout(keyboardButton,keyboardLayout);
            }
        });
        mouseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchDeviceLayout(mouseButton,mouseLayout);
            }
        });
        gamepadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchDeviceLayout(gamepadButton,gamepadLayout);
            }
        });
        View.OnClickListener keyListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = ((TextView)v).getText().toString();
                String tag = ((TextView)v).getTag().toString();
                deviceWindowListener.onElementClick(text,tag);
                deviceWindow.setVisibility(View.GONE);
            }
        };
        LinearLayout keyboardDrawing = keyboardLayout.findViewById(R.id.keyboard_drawing);
        setListenersForDevice(keyboardDrawing,keyListener);

        LinearLayout mouseDrawing = mouseLayout.findViewById(R.id.mouse_drawing);
        setListenersForDevice(mouseDrawing,keyListener);

        LinearLayout gamepadDrawing = gamepadLayout.findViewById(R.id.gamepad_drawing);
        setListenersForDevice(gamepadDrawing,keyListener);

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


    private void switchDeviceLayout(Button button,FrameLayout deviceLayout){
        keyboardButton.setAlpha(0.4f);
        mouseButton.setAlpha(0.4f);
        gamepadButton.setAlpha(0.4f);
        keyboardLayout.setVisibility(View.GONE);
        mouseLayout.setVisibility(View.GONE);
        gamepadLayout.setVisibility(View.GONE);

        button.setAlpha(1);
        deviceLayout.setVisibility(View.VISIBLE);
    }

    public void openTextWindow(TextWindowListener textWindowListener, String text){
        this.textWindowListener = textWindowListener;
        if (text != null){
            textWindowText.setText(text);
        }
        textWindow.setVisibility(View.VISIBLE);
    }


    public void openEditTextWindow(EditTextWindowListener editTextWindowListener, String text, String hint, String title,int inputType){
        this.editTextWindowListener = editTextWindowListener;
        if (text != null){
            editTextWindowText.setText(text);
        }
        if (hint != null) {
            editTextWindowText.setHint(hint);
        }
        if (title != null){
            editTextWindowTitle.setText(title);
        }
        editTextWindowText.setInputType(inputType);
        editTextWindow.setVisibility(View.VISIBLE);
    }


    public void openDeviceWindow(DeviceWindowListener deviceWindowListener){
        this.deviceWindowListener = deviceWindowListener;
        deviceWindow.setVisibility(View.VISIBLE);
        keyboardButton.setVisibility(View.VISIBLE);
        mouseButton.setVisibility(View.VISIBLE);
        gamepadButton.setVisibility(View.VISIBLE);

    }
}
