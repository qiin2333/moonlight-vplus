package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;

/**
 * USB driver for Sony DualShock 4 controllers.
 * <p>
 * Supports DualShock 4 v1 (CUH-ZCT1x, PID 0x05C4) and v2 (CUH-ZCT2x, PID 0x09CC).
 * Protocol reference: https://www.psdevwiki.com/ps4/DS4-USB
 * </p>
 */
public class DualShock4Controller extends AbstractHidController {

    private static final int SONY_VID = 0x054C;
    private static final int DS4_V1_PID = 0x05C4;
    private static final int DS4_V2_PID = 0x09CC;

    // D-Pad direction values (hat switch in low nibble of byte 5)
    private static final int DPAD_UP         = 0;
    private static final int DPAD_UP_RIGHT   = 1;
    private static final int DPAD_RIGHT      = 2;
    private static final int DPAD_DOWN_RIGHT = 3;
    private static final int DPAD_DOWN       = 4;
    private static final int DPAD_DOWN_LEFT  = 5;
    private static final int DPAD_LEFT       = 6;
    private static final int DPAD_UP_LEFT    = 7;

    public DualShock4Controller(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
        this.type = MoonBridge.LI_CTYPE_PS;
        this.capabilities = MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE | MoonBridge.LI_CCAP_TOUCHPAD;
        this.supportedButtonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG |
                ControllerPacket.TOUCHPAD_FLAG;
    }

    public static boolean canClaimDevice(UsbDevice device) {
        if (device.getVendorId() != SONY_VID) {
            return false;
        }

        int pid = device.getProductId();
        if (pid != DS4_V1_PID && pid != DS4_V2_PID) {
            return false;
        }

        // Verify it has at least one interface
        return device.getInterfaceCount() >= 1;
    }

    @Override
    protected boolean doInit() {
        // DualShock 4 doesn't require special initialization over USB
        return true;
    }

    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() < 10) {
            return false;
        }

        byte[] raw = buffer.array();
        int offset = buffer.arrayOffset() + buffer.position();

        // Check report ID
        byte reportId = raw[offset];
        if (reportId != 0x01) {
            return false;
        }

        // Sticks (bytes 1-4): 0x00=left/up, 0x80=center, 0xFF=right/down
        leftStickX  = normalizeStick(raw[offset + 1] & 0xFF);
        leftStickY  = -normalizeStick(raw[offset + 2] & 0xFF);  // Invert Y
        rightStickX = normalizeStick(raw[offset + 3] & 0xFF);
        rightStickY = -normalizeStick(raw[offset + 4] & 0xFF);  // Invert Y

        // Buttons and D-Pad (byte 5)
        int b5 = raw[offset + 5] & 0xFF;
        int dpad = b5 & 0x0F;
        setButtonFlag(ControllerPacket.UP_FLAG,
                (dpad == DPAD_UP || dpad == DPAD_UP_RIGHT || dpad == DPAD_UP_LEFT) ? 1 : 0);
        setButtonFlag(ControllerPacket.DOWN_FLAG,
                (dpad == DPAD_DOWN || dpad == DPAD_DOWN_RIGHT || dpad == DPAD_DOWN_LEFT) ? 1 : 0);
        setButtonFlag(ControllerPacket.LEFT_FLAG,
                (dpad == DPAD_LEFT || dpad == DPAD_UP_LEFT || dpad == DPAD_DOWN_LEFT) ? 1 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG,
                (dpad == DPAD_RIGHT || dpad == DPAD_UP_RIGHT || dpad == DPAD_DOWN_RIGHT) ? 1 : 0);

        // Face buttons (byte 5, upper nibble): Square=X, Cross=A, Circle=B, Triangle=Y
        setButtonFlag(ControllerPacket.X_FLAG, b5 & 0x10);  // Square → X
        setButtonFlag(ControllerPacket.A_FLAG, b5 & 0x20);  // Cross → A
        setButtonFlag(ControllerPacket.B_FLAG, b5 & 0x40);  // Circle → B
        setButtonFlag(ControllerPacket.Y_FLAG, b5 & 0x80);  // Triangle → Y

        // Shoulder and function buttons (byte 6)
        int b6 = raw[offset + 6] & 0xFF;
        setButtonFlag(ControllerPacket.LB_FLAG,     b6 & 0x01);  // L1
        setButtonFlag(ControllerPacket.RB_FLAG,     b6 & 0x02);  // R1
        setButtonFlag(ControllerPacket.BACK_FLAG,   b6 & 0x10);  // Share
        setButtonFlag(ControllerPacket.PLAY_FLAG,   b6 & 0x20);  // Options
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b6 & 0x40);  // L3
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b6 & 0x80);  // R3

        // PS and Touchpad buttons (byte 7)
        int b7 = raw[offset + 7] & 0xFF;
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b7 & 0x01);  // PS button
        setButtonFlag(ControllerPacket.TOUCHPAD_FLAG,       b7 & 0x02);  // Touchpad click

        // Triggers (bytes 8-9): 0x00=released, 0xFF=fully pressed
        leftTrigger  = (raw[offset + 8] & 0xFF) / 255.0f;
        rightTrigger = (raw[offset + 9] & 0xFF) / 255.0f;

        return true;
    }

    /**
     * Normalize an 8-bit stick axis (0-255) to -1.0..1.0
     */
    private static float normalizeStick(int value) {
        return (2.0f * value / 255.0f) - 1.0f;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        byte[] data = new byte[32];
        data[0] = 0x05;  // Report ID: rumble
        data[1] = (byte) 0xFF;  // Flags: enable all
        data[4] = (byte) ((highFreqMotor >> 8) & 0xFF);  // Small motor
        data[5] = (byte) ((lowFreqMotor >> 8) & 0xFF);   // Large motor
        // LED color (blue by default)
        data[6] = 0x00;  // R
        data[7] = 0x00;  // G
        data[8] = (byte) 0x40;  // B

        sendData(data);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // DualShock 4 does not support trigger rumble
    }
}
