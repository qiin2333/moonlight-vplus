package com.limelight.binding.input.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.limelight.nvstream.input.ControllerPacket;

import java.nio.ByteBuffer;

public class DualSenseController extends AbstractDualSenseController {
    private static final int[] SUPPORTED_VENDORS = {
            0x054C, // 索尼
            0x1532  // 雷蛇
    };
    private static final int[] SUPPORTED_PRODUCTS = {
            0x0CE6, // ps5
            0x0DF2, // ps5 edge
            0x100b, // 有线模式 雷蛇幻影战狼v2pro
            0x100c  // 无线模式 雷蛇幻影战狼v2pro
    };

    public static boolean canClaimDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        for (int supportedVid : SUPPORTED_VENDORS) {
            for (int supportedPid : SUPPORTED_PRODUCTS) {
                if (device.getVendorId() == supportedVid
                        && device.getProductId() == supportedPid
                        && device.getInterfaceCount() >= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    public DualSenseController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
    }

    private float normalizeThumbStickAxis(int value) {
        return (2.0f * value / 255.0f) - 1.0f;
    }

    private float normalizeTriggerAxis(int value) {
        return value / 255.0f;
    }

    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < 64) {
            if (buffer != null) {
                Log.d("DualSenseController", "No DualSense input: " + buffer.remaining());
            }
            return false;
        }

        // 检查报告ID
        int reportId = buffer.get(0) & 0xFF;
        if (reportId != 0x01) {
            Log.d("DualSenseController", "Unexpected report ID: 0x" + Integer.toHexString(reportId));
        }

        // Skip first byte
        buffer.get();

        // 确保有足够的数据访问位置 8-10
        int capacity = buffer.capacity();
        if (capacity < 11) {
            Log.w("DualSenseController", "Buffer too small for button data");
            return false;
        }

        // Process D-pad (buttons0 & 0x0F)
        int dpad = buffer.get(8) & 0x0F;

        setButtonFlag(ControllerPacket.UP_FLAG, (dpad == 0 || dpad == 1 || dpad == 7) ? 0x01 : 0);
        setButtonFlag(ControllerPacket.DOWN_FLAG, (dpad == 3 || dpad == 4 || dpad == 5) ? 0x02 : 0);
        setButtonFlag(ControllerPacket.LEFT_FLAG, (dpad == 5 || dpad == 6 || dpad == 7) ? 0x04 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, (dpad == 1 || dpad == 2 || dpad == 3) ? 0x08 : 0);

        // ABXY
        byte b8 = buffer.get(8);
        setButtonFlag(ControllerPacket.A_FLAG, b8 & 0x20);
        setButtonFlag(ControllerPacket.B_FLAG, b8 & 0x40);
        setButtonFlag(ControllerPacket.X_FLAG, b8 & 0x10);
        setButtonFlag(ControllerPacket.Y_FLAG, b8 & 0x80);

        // LB/RB
        byte b9 = buffer.get(9);
        setButtonFlag(ControllerPacket.LB_FLAG, b9 & 0x01);
        setButtonFlag(ControllerPacket.RB_FLAG, b9 & 0x02);
        // Start/Select
        setButtonFlag(ControllerPacket.BACK_FLAG, b9 & 0x10);
        setButtonFlag(ControllerPacket.PLAY_FLAG, b9 & 0x20);
        // LS/RS
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b9 & 0x40);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b9 & 0x80);

        // PS button
        byte b10 = buffer.get(10);
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b10 & 0x01);
        setButtonFlag(ControllerPacket.MISC_FLAG, b10 & 0x04); // Screenshot
        setButtonFlag(ControllerPacket.TOUCHPAD_FLAG, b10 & 0x02);

        // Process analog sticks
        int axes0 = (capacity > 1) ? (buffer.get(1) & 0xFF) : 0x80;
        int axes1 = (capacity > 2) ? (buffer.get(2) & 0xFF) : 0x80;
        int axes2 = (capacity > 3) ? (buffer.get(3) & 0xFF) : 0x80;
        int axes3 = (capacity > 4) ? (buffer.get(4) & 0xFF) : 0x80;
        int axes4 = (capacity > 5) ? (buffer.get(5) & 0xFF) : 0;
        int axes5 = (capacity > 6) ? (buffer.get(6) & 0xFF) : 0;

        float lsx = normalizeThumbStickAxis(axes0);
        float lsy = normalizeThumbStickAxis(axes1);
        float rsx = normalizeThumbStickAxis(axes2);
        float rsy = normalizeThumbStickAxis(axes3);

        float l2axis = normalizeTriggerAxis(axes4);
        float r2axis = normalizeTriggerAxis(axes5);

        leftTrigger = l2axis;
        rightTrigger = r2axis;

        leftStickX = lsx;
        leftStickY = lsy;

        rightStickX = rsx;
        rightStickY = rsy;

        // IMU data
        final float GYRO_SCALE = 2000.0f / 32768.0f;
        final float ACCEL_SCALE = 4.0f / 32768.0f;
        final float G_TO_MS2 = 9.81f;

        // 读取 IMU 数据（确保有足够的数据）
        // 需要访问到位置 26，所以至少需要 27 字节容量
        if (capacity < 27) {
            Log.w("DualSenseController", "Buffer too small for IMU data: " + capacity);
            return false;
        }
        
        try {
            int gyrox = buffer.getShort(16);
            int gyroy = buffer.getShort(18);
            int gyroz = buffer.getShort(20);

            int accelx = buffer.getShort(22);
            int accely = buffer.getShort(24);
            int accelz = buffer.getShort(26);

        // 转换陀螺仪数据到 deg/s
        float gyroX_dps = gyrox * GYRO_SCALE;
        float gyroY_dps = gyroy * GYRO_SCALE;
        float gyroZ_dps = gyroz * GYRO_SCALE;

        // 转换加速度数据到 m/s²
        float accelX_ms2 = (accelx * ACCEL_SCALE) * G_TO_MS2;
        float accelY_ms2 = (accely * ACCEL_SCALE) * G_TO_MS2;
        float accelZ_ms2 = (accelz * ACCEL_SCALE) * G_TO_MS2;

            gyroX = gyroX_dps;
            gyroY = gyroY_dps;
            gyroZ = gyroZ_dps;

            accelX = accelX_ms2;
            accelY = accelY_ms2;
            accelZ = accelZ_ms2;
        } catch (IndexOutOfBoundsException e) {
            Log.w("DualSenseController", "Failed to read IMU data", e);
            // 继续处理，但不设置 IMU 数据
            gyroX = gyroY = gyroZ = 0;
            accelX = accelY = accelZ = 0;
        }

        // Return true to send input
        return true;
    }

    @Override
    protected boolean doInit() {
        Log.d("DualSenseController", "doInit");
        sendCommand(getDualSenseInit());
        return true;
    }

    // 参考 https://gist.github.com/stealth-alex/10a8e7cc6027b78fa18a7f48a0d3d1e4
    // https://github.com/flok/pydualsense/blob/master/pydualsense/pydualsense.py
    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        byte[] reportData = new byte[]{
                0x02, // Report ID
                (byte) (0x01 | 0x02), // valid_flag0
                (byte) 0x00, // valid_flag1
                (byte) (highFreqMotor >> 8), // right trigger rumble
                (byte) (lowFreqMotor >> 8), // left trigger rumble
                0x00, 0x00, 0x00, 0x00,
                0x00, // mute_button_led (0: mute LED off  | 1: mute LED on)
                0x10, // power_save_control(mute led on  = 0x00, off = 0x10)
                0x00, // R2 trigger effect mode
                0x00, // R2 trigger effect parameter 1
                0x00, // R2 trigger effect parameter 2
                0x00, // R2 trigger effect parameter 3
                0x00, // R2 trigger effect parameter 4
                0x00, // R2 trigger effect parameter 5
                0x00, // R2 trigger effect parameter 6
                0x00, // R2 trigger effect parameter 7
                0x00, 0x00, 0x00,
                0x00, // L2 trigger effect mode
                0x00, // L2 trigger effect parameter 1
                0x00, // L2 trigger effect parameter 2
                0x00, // L2 trigger effect parameter 3
                0x00, // L2 trigger effect parameter 4
                0x00, // L2 trigger effect parameter 5
                0x00, // L2 trigger effect parameter 6
                0x00, // L2 trigger effect parameter 7
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x02, 0x00, 0x02, 0x00,
                0x00, // player leds
                (byte) 0x78, (byte) 0x78, (byte) 0xEF // RGB values
        };
        sendCommand(reportData);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // DS5 supports trigger rumble but implementation is complex
        // For now, leave it empty
    }

    @Override
    protected void sendCommand(byte[] data) {
        if (data == null || outEndpt == null || connection == null) {
            Log.w("DualSenseController", "Cannot send command: invalid parameters");
            return;
        }
        Log.d("DualSenseController", "sendCommand");
        int res = connection.bulkTransfer(outEndpt, data, data.length, 1000);
        if (res != data.length) {
            Log.w("DualSenseController", "Command transfer failed: expected " + data.length + ", got " + res);
        }
    }

    private byte[] getDualSenseInit() {
        return new byte[]{
                0x02, // Report ID
                (byte) (0x10 | 0x20 | 0x40 | 0x80), // valid_flag0
                (byte) 0xf7, // valid_flag1
                0x00, // right trigger rumble
                0x00, // left trigger rumble
                0x00, 0x00, 0x00, 0x00,
                0x00, // mute_button_led (0: mute LED off  | 1: mute LED on)
                0x10, // power_save_control(mute led on  = 0x00, off = 0x10)
                0x00, // R2 trigger effect mode
                0x00, // R2 trigger effect parameter 1
                0x00, // R2 trigger effect parameter 2
                0x00, // R2 trigger effect parameter 3
                0x00, // R2 trigger effect parameter 4
                0x00, // R2 trigger effect parameter 5
                0x00, // R2 trigger effect parameter 6
                0x00, // R2 trigger effect parameter 7
                0x00, 0x00, 0x00,
                0x00, // L2 trigger effect mode
                0x00, // L2 trigger effect parameter 1
                0x00, // L2 trigger effect parameter 2
                0x00, // L2 trigger effect parameter 3
                0x00, // L2 trigger effect parameter 4
                0x00, // L2 trigger effect parameter 5
                0x00, // L2 trigger effect parameter 6
                0x00, // L2 trigger effect parameter 7
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x02, 0x00, 0x02, 0x00,
                0x00, // player leds
                (byte) 0x78, (byte) 0x78, (byte) 0xEF // RGB values
        };
    }
}

