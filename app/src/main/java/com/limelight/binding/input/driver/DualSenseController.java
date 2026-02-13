package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;

/**
 * USB driver for Sony DualSense and DualSense Edge controllers.
 * <p>
 * Supports DualSense (CFI-ZCT1x, PID 0x0CE6) and DualSense Edge (CFI-ZCP1x, PID 0x0DF2).
 * Protocol reference: https://controllers.fandom.com/wiki/Sony_DualSense
 * </p>
 */
public class DualSenseController extends AbstractHidController {

    private static final int SONY_VID = 0x054C;
    private static final int DUALSENSE_PID = 0x0CE6;
    private static final int DUALSENSE_EDGE_PID = 0x0DF2;

    // D-Pad direction values
    private static final int DPAD_UP         = 0;
    private static final int DPAD_UP_RIGHT   = 1;
    private static final int DPAD_RIGHT      = 2;
    private static final int DPAD_DOWN_RIGHT = 3;
    private static final int DPAD_DOWN       = 4;
    private static final int DPAD_DOWN_LEFT  = 5;
    private static final int DPAD_LEFT       = 6;
    private static final int DPAD_UP_LEFT    = 7;

    public DualSenseController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
        this.type = MoonBridge.LI_CTYPE_PS;
        this.capabilities = MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE |
                MoonBridge.LI_CCAP_TRIGGER_RUMBLE | MoonBridge.LI_CCAP_TOUCHPAD;
        this.supportedButtonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG |
                ControllerPacket.TOUCHPAD_FLAG | ControllerPacket.MISC_FLAG;
    }

    public static boolean canClaimDevice(UsbDevice device) {
        if (device.getVendorId() != SONY_VID) {
            return false;
        }

        int pid = device.getProductId();
        if (pid != DUALSENSE_PID && pid != DUALSENSE_EDGE_PID) {
            return false;
        }

        return device.getInterfaceCount() >= 1;
    }

    @Override
    protected boolean doInit() {
        // DualSense doesn't require special initialization over USB
        return true;
    }

    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() < 10) {
            return false;
        }

        byte[] raw = buffer.array();
        int base = buffer.arrayOffset() + buffer.position();

        // USB report ID must be 0x01
        if (raw[base] != 0x01) {
            return false;
        }

        // Sticks (bytes 1-4)
        leftStickX  = normalizeStick(raw[base + 1] & 0xFF);
        leftStickY  = -normalizeStick(raw[base + 2] & 0xFF);
        rightStickX = normalizeStick(raw[base + 3] & 0xFF);
        rightStickY = -normalizeStick(raw[base + 4] & 0xFF);

        // Triggers (bytes 5-6)
        leftTrigger  = (raw[base + 5] & 0xFF) / 255.0f;
        rightTrigger = (raw[base + 6] & 0xFF) / 255.0f;

        // Counter (byte 7) - skip

        // Buttons (bytes 8-10)
        int b8  = raw[base + 8] & 0xFF;
        int b9  = raw[base + 9] & 0xFF;
        int b10 = raw[base + 10] & 0xFF;

        // D-Pad (byte 8, low nibble)
        int dpad = b8 & 0x0F;
        setButtonFlag(ControllerPacket.UP_FLAG,
                (dpad == DPAD_UP || dpad == DPAD_UP_RIGHT || dpad == DPAD_UP_LEFT) ? 1 : 0);
        setButtonFlag(ControllerPacket.DOWN_FLAG,
                (dpad == DPAD_DOWN || dpad == DPAD_DOWN_RIGHT || dpad == DPAD_DOWN_LEFT) ? 1 : 0);
        setButtonFlag(ControllerPacket.LEFT_FLAG,
                (dpad == DPAD_LEFT || dpad == DPAD_UP_LEFT || dpad == DPAD_DOWN_LEFT) ? 1 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG,
                (dpad == DPAD_RIGHT || dpad == DPAD_UP_RIGHT || dpad == DPAD_DOWN_RIGHT) ? 1 : 0);

        // Face buttons (byte 8, upper nibble)
        setButtonFlag(ControllerPacket.X_FLAG, b8 & 0x10);  // Square → X
        setButtonFlag(ControllerPacket.A_FLAG, b8 & 0x20);  // Cross → A
        setButtonFlag(ControllerPacket.B_FLAG, b8 & 0x40);  // Circle → B
        setButtonFlag(ControllerPacket.Y_FLAG, b8 & 0x80);  // Triangle → Y

        // Shoulder and function buttons (byte 9)
        setButtonFlag(ControllerPacket.LB_FLAG,     b9 & 0x01);  // L1
        setButtonFlag(ControllerPacket.RB_FLAG,     b9 & 0x02);  // R1
        // L2/R2 digital (0x04, 0x08) - we use analog values above
        setButtonFlag(ControllerPacket.BACK_FLAG,   b9 & 0x10);  // Create
        setButtonFlag(ControllerPacket.PLAY_FLAG,   b9 & 0x20);  // Options
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b9 & 0x40);  // L3
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b9 & 0x80);  // R3

        // PS, Touchpad, Mute (byte 10)
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b10 & 0x01);  // PS
        setButtonFlag(ControllerPacket.TOUCHPAD_FLAG,       b10 & 0x02);  // Touchpad click
        setButtonFlag(ControllerPacket.MISC_FLAG,           b10 & 0x04);  // Mute button

        return true;
    }

    private static float normalizeStick(int value) {
        return (2.0f * value / 255.0f) - 1.0f;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        byte[] data = new byte[48];
        data[0] = 0x02;  // Report ID: output
        data[1] = (byte) 0xFF;  // Validity flags 0
        data[2] = (byte) 0xF7;  // Validity flags 1

        // Rumble motors
        data[3] = (byte) ((highFreqMotor >> 8) & 0xFF);  // Right motor (high-freq)
        data[4] = (byte) ((lowFreqMotor >> 8) & 0xFF);   // Left motor (low-freq)

        // Player LED indicator
        data[44] = 0x02;

        sendData(data);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        byte[] data = new byte[48];
        data[0] = 0x02;  // Report ID
        data[1] = 0x04;  // Only update triggers

        // Right trigger effect
        data[11] = 0x01;  // Continuous resistance mode
        data[12] = 0x00;  // Start position
        data[13] = (byte) ((rightTrigger >> 8) & 0xFF);  // Force

        // Left trigger effect
        data[22] = 0x01;
        data[23] = 0x00;
        data[24] = (byte) ((leftTrigger >> 8) & 0xFF);

        sendData(data);
    }
}
