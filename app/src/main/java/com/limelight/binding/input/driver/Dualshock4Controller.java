package com.limelight.binding.input.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;

import java.nio.ByteBuffer;

public class Dualshock4Controller extends AbstractDualSenseController {
    private static final int[] SUPPORTED_VENDORS = {
            0x054C // 索尼
    };
    private static final int[] SUPPORTED_PRODUCTS = {
            0x05c4, // ps4一代
            0x09cc  // ps4二代
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

    public Dualshock4Controller(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
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
        // https://www.psdevwiki.com/ps4/DS4-USB 参考
        if (buffer == null || buffer.remaining() < 64) {
            if (buffer != null) {
                Log.d("Dualshock4Controller", "No Dualshock4Controller input: " + buffer.remaining());
            }
            return false;
        }

        // 检查报告ID，但不移动position
        int reportId = buffer.get(0) & 0xFF;
        if (reportId != 0x01 && reportId != 0x11) {
            // DS4 可能使用不同的报告ID，但通常第一个字节是报告ID
            // 如果不符合预期，记录但继续处理
            Log.d("Dualshock4Controller", "Unexpected report ID: 0x" + Integer.toHexString(reportId));
        }

        // Skip first byte
        buffer.get();

        // 确保有足够的数据访问位置 5-9
        if (buffer.remaining() < 9) {
            Log.w("Dualshock4Controller", "Buffer too small for button data");
            return false;
        }

        // Process D-pad (buttons0 & 0x0F)
        // 注意：buffer.get(5) 是绝对位置，需要确保 buffer 有足够容量
        int dpad = (buffer.capacity() > 5) ? (buffer.get(5) & 0x0F) : 0;

        setButtonFlag(ControllerPacket.UP_FLAG, (dpad == 0 || dpad == 1 || dpad == 7) ? 0x01 : 0);
        setButtonFlag(ControllerPacket.DOWN_FLAG, (dpad == 3 || dpad == 4 || dpad == 5) ? 0x02 : 0);
        setButtonFlag(ControllerPacket.LEFT_FLAG, (dpad == 5 || dpad == 6 || dpad == 7) ? 0x04 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, (dpad == 1 || dpad == 2 || dpad == 3) ? 0x08 : 0);

        // 使用绝对位置访问，需要确保 buffer 有足够容量
        int capacity = buffer.capacity();
        
        // ABXY
        if (capacity > 5) {
            byte b5 = buffer.get(5);
            setButtonFlag(ControllerPacket.A_FLAG, b5 & 0x20);
            setButtonFlag(ControllerPacket.B_FLAG, b5 & 0x40);
            setButtonFlag(ControllerPacket.X_FLAG, b5 & 0x10);
            setButtonFlag(ControllerPacket.Y_FLAG, b5 & 0x80);
        }

        // LB/RB
        if (capacity > 6) {
            byte b6 = buffer.get(6);
            setButtonFlag(ControllerPacket.LB_FLAG, b6 & 0x01);
            setButtonFlag(ControllerPacket.RB_FLAG, b6 & 0x02);
            // Start/Select
            setButtonFlag(ControllerPacket.BACK_FLAG, b6 & 0x10);
            setButtonFlag(ControllerPacket.PLAY_FLAG, b6 & 0x20);
            // LS/RS
            setButtonFlag(ControllerPacket.LS_CLK_FLAG, b6 & 0x40);
            setButtonFlag(ControllerPacket.RS_CLK_FLAG, b6 & 0x80);
        }

        // PS button
        if (capacity > 7) {
            byte b7 = buffer.get(7);
            setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b7 & 0x01);
            // setButtonFlag(ControllerPacket.MISC_FLAG, b7 & 0x04); // Screenshot
            setButtonFlag(ControllerPacket.TOUCHPAD_FLAG, b7 & 0x02);
        }

        // Process analog sticks
        int axes0 = (capacity > 1) ? (buffer.get(1) & 0xFF) : 0x80;
        int axes1 = (capacity > 2) ? (buffer.get(2) & 0xFF) : 0x80;
        int axes2 = (capacity > 3) ? (buffer.get(3) & 0xFF) : 0x80;
        int axes3 = (capacity > 4) ? (buffer.get(4) & 0xFF) : 0x80;

        int axes4 = (capacity > 8) ? (buffer.get(8) & 0xFF) : 0;
        int axes5 = (capacity > 9) ? (buffer.get(9) & 0xFF) : 0;

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
        // 需要访问到位置 23，所以至少需要 24 字节容量
        // 注意：buffer.get() 已经移动了 position，所以 remaining() 会减少
        // 但 getShort(23) 是绝对位置访问，需要检查 capacity
        if (capacity < 24) {
            Log.w("Dualshock4Controller", "Buffer too small for IMU data: " + capacity);
            return false;
        }
        
        try {
            int gyrox = buffer.getShort(13);
            int gyroy = buffer.getShort(15);
            int gyroz = buffer.getShort(17);

            int accelx = buffer.getShort(19);
            int accely = buffer.getShort(21);
            int accelz = buffer.getShort(23);

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
            Log.w("Dualshock4Controller", "Failed to read IMU data", e);
            // 继续处理，但不设置 IMU 数据
            gyroX = gyroY = gyroZ = 0;
            accelX = accelY = accelZ = 0;
        }

        // Return true to send input
        return true;
    }

    @Override
    protected boolean doInit() {
        Log.d("Dualshock4Controller", "doInit");
        sendCommand(getInitData());
        return true;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        // https://github.com/Ryochan7/DS4Windows/blob/master/DS4Windows/DS4Library/DS4Device.cs line:1561
        byte[] report = new byte[32];
        report[0] = 0x05;
        // Headphone volume L (0x10), Headphone volume R (0x20), Mic volume (0x40), Speaker volume (0x80)
        // enable rumble (0x01), lightbar (0x02), flash (0x04). Default: 0x07
        report[1] = (byte) 0x01;
        report[2] = 0x04;

        report[4] = (byte) (highFreqMotor >> 8); // fast motor
        report[5] = (byte) (lowFreqMotor >> 8); // slow motor
        report[6] = (byte) 0x78;  // red
        report[7] = (byte) 0x78;  // green
        report[8] = (byte) 0xEF;  // blue

        sendCommand(report);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // DS4 doesn't support trigger rumble
    }

    @Override
    protected void sendCommand(byte[] data) {
        if (data == null || outEndpt == null || connection == null) {
            Log.w("Dualshock4Controller", "Cannot send command: invalid parameters");
            return;
        }
        Log.d("Dualshock4Controller", "sendCommand");
        int res = connection.bulkTransfer(outEndpt, data, data.length, 1000);
        if (res != data.length) {
            Log.w("Dualshock4Controller", "Command transfer failed: expected " + data.length + ", got " + res);
        }
    }

    private byte[] getInitData() {
        byte[] report = new byte[32];
        report[0] = 0x05;
        // Headphone volume L (0x10), Headphone volume R (0x20), Mic volume (0x40), Speaker volume (0x80)
        // enable rumble (0x01), lightbar (0x02), flash (0x04). Default: 0x07
        report[1] = (byte) 0x02;
        report[2] = 0x04;

        report[4] = 0x00; // fast motor
        report[5] = 0x00; // slow motor
        report[6] = (byte) 0x78;  // red
        report[7] = (byte) 0x78;  // green
        report[8] = (byte) 0xEF;  // blue

        return report;
    }
}

