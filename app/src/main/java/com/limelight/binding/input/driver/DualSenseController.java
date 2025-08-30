package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal DualSense (PS5) USB driver: basic input (sticks, buttons, analog triggers).
 * Touchpad/LED/haptics/adaptive triggers are not implemented in this version.
 */
public class DualSenseController extends AbstractController {

    private static final int SONY_VID = 0x054c;
    private static final int DS5_PID  = 0x0ce6; // DualSense
    private static final int DS5_EDGE_PID = 0x0df2; // DualSense Edge

    private final UsbDevice device;
    private final UsbDeviceConnection connection;

    private UsbEndpoint inEndpt;
    private UsbEndpoint outEndpt;
    private Thread inputThread;
    private boolean stopped;

    public static boolean canClaimDevice(UsbDevice device) {
        if (device.getVendorId() != SONY_VID) {
            return false;
        }
        int pid = device.getProductId();
        if (pid != DS5_PID && pid != DS5_EDGE_PID) {
            return false;
        }
        if (device.getInterfaceCount() < 1) {
            return false;
        }
        UsbInterface iface = device.getInterface(0);
        return iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID;
    }

    public DualSenseController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;

        // 标准 PlayStation 映射 -> Xbox 语义
        this.buttonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG;
        this.supportedButtonFlags = this.buttonFlags;

        // DS5 有模拟扳机
        this.capabilities = (short)(MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_ACCEL | MoonBridge.LI_CCAP_GYRO);
        this.type = MoonBridge.LI_CTYPE_PS;
    }

    @Override
    public boolean start() {
        UsbInterface iface = device.getInterface(0);
        if (!connection.claimInterface(iface, true)) {
            LimeLog.warning("Failed to claim HID interface for DualSense");
            return false;
        }

        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint ep = iface.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN && inEndpt == null) inEndpt = ep;
            else if (ep.getDirection() == UsbConstants.USB_DIR_OUT && outEndpt == null) outEndpt = ep;
        }
        if (inEndpt == null) {
            LimeLog.warning("Missing IN endpoint for DualSense");
            return false;
        }

        inputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                notifyDeviceAdded();

                byte[] buf = new byte[64];
                while (!Thread.currentThread().isInterrupted() && !stopped) {
                    long t0 = SystemClock.uptimeMillis();
                    int res = connection.bulkTransfer(inEndpt, buf, buf.length, 3000);
                    if (res == 0) res = -1;
                    if (res == -1 && SystemClock.uptimeMillis() - t0 < 1000) {
                        LimeLog.warning("DualSense I/O error; stopping");
                        stop();
                        break;
                    }
                    if (res <= 0 || stopped) continue;

                    if (handleRead(ByteBuffer.wrap(buf, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                        reportInput();
                    }
                }
            }
        });
        inputThread.start();
        return true;
    }

    @Override
    public void stop() {
        if (stopped) return;
        stopped = true;
        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }
        try { connection.close(); } catch (Exception ignored) {}
        notifyDeviceRemoved();
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        if (outEndpt == null) {
            return;
        }
        try {
            // 基础震动：构造简化输出报文占位（不同固件格式可能不同，后续可按实测调整）
            // 这里用固定长度缓冲，设置一个简单的强度字段，避免阻塞。
            byte[] out = new byte[32];
            out[0] = 0x02; // 假设的输出 reportId
            // 映射强度到 0..255
            out[1] = (byte)Math.max(0, Math.min(255, (highFreqMotor >> 8))); // high
            out[2] = (byte)Math.max(0, Math.min(255, (lowFreqMotor >> 8)));  // low
            connection.bulkTransfer(outEndpt, out, out.length, 50);
        } catch (Throwable ignored) {}
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // 未实现
    }

    private boolean handleRead(ByteBuffer b) {
        // 参考 DS4/DS5 常见布局：Report ID 0x01，随后是 8-bit 轴与按钮位
        if (b.remaining() < 16) return false;
        int reportId = b.get(0) & 0xFF;
        if (reportId != 0x01) return false;

        // 轴值（0..255）
        int lx = b.get(1) & 0xFF;
        int ly = b.get(2) & 0xFF;
        int rx = b.get(3) & 0xFF;
        int ry = b.get(4) & 0xFF;

        leftStickX = normalize8(lx);
        leftStickY = -normalize8(ly);
        rightStickX = normalize8(rx);
        rightStickY = -normalize8(ry);

        int b5 = b.get(5) & 0xFF; // dpad(低4位) + 方/叉/圆/三角(高4位)
        int b6 = b.get(6) & 0xFF; // L1,R1,L2(数字),R2(数字),Share,Options,L3,R3
        int b7 = b.get(7) & 0xFF; // PS, Touchpad, 等

        // 面键：方(0x10)、叉(0x20)、圆(0x40)、三角(0x80)
        setButtonFlag(ControllerPacket.X_FLAG, (b5 & 0x10)); // Square -> X
        setButtonFlag(ControllerPacket.A_FLAG, (b5 & 0x20)); // Cross  -> A
        setButtonFlag(ControllerPacket.B_FLAG, (b5 & 0x40)); // Circle -> B
        setButtonFlag(ControllerPacket.Y_FLAG, (b5 & 0x80)); // Triangle -> Y

        // D-Pad（HAT，0..7，8=无）
        int hat = b5 & 0x0F;
        boolean up=false, right=false, down=false, left=false;
        switch (hat) {
            case 0: up = true; break;         // Up
            case 1: up = true; right = true; break; // Up-Right
            case 2: right = true; break;      // Right
            case 3: right = true; down = true; break;
            case 4: down = true; break;       // Down
            case 5: down = true; left = true; break;
            case 6: left = true; break;       // Left
            case 7: left = true; up = true; break;
            default: break;                    // 8 = released
        }
        setButtonFlag(ControllerPacket.UP_FLAG, up ? 1 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, right ? 1 : 0);
        setButtonFlag(ControllerPacket.DOWN_FLAG, down ? 1 : 0);
        setButtonFlag(ControllerPacket.LEFT_FLAG, left ? 1 : 0);

        // 肩键/点击
        setButtonFlag(ControllerPacket.LB_FLAG, (b6 & 0x01));
        setButtonFlag(ControllerPacket.RB_FLAG, (b6 & 0x02));
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, (b6 & 0x40));
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, (b6 & 0x80));

        // 数字触发（按下）
        boolean l2Pressed = (b6 & 0x04) != 0;
        boolean r2Pressed = (b6 & 0x08) != 0;

        // Share/Options -> Back/Play
        setButtonFlag(ControllerPacket.BACK_FLAG, (b6 & 0x10));
        setButtonFlag(ControllerPacket.PLAY_FLAG, (b6 & 0x20));

        // PS -> Special
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, (b7 & 0x01));

        // 模拟扳机（8/9字节）
        int l2 = b.get(8) & 0xFF;
        int r2 = b.get(9) & 0xFF;
        leftTrigger = (l2 / 255.0f);
        rightTrigger = (r2 / 255.0f);
        // 若无模拟值而只有数字按键，则用数字按键兜底
        if (l2 == 0 && l2Pressed) leftTrigger = 1.0f;
        if (r2 == 0 && r2Pressed) rightTrigger = 1.0f;

        // IMU（若存在）：常见社区实现中，USB 报文中包含 16-bit 小端的陀螺/加速度采样，
        // 这里采用通用偏移占位并做容错：当长度足够时解析；单位转换到 Moonlight 期望。
        if (b.limit() >= 48) {
            try {
                // 示例偏移（可能因固件差异不同，需要后续校准）：
                // gyro X/Y/Z: 0x13..0x18, accel X/Y/Z: 0x19..0x1E（小端，单位示意）
                int gx = (short)((b.get(0x13) & 0xFF) | ((b.get(0x14) & 0xFF) << 8));
                int gy = (short)((b.get(0x15) & 0xFF) | ((b.get(0x16) & 0xFF) << 8));
                int gz = (short)((b.get(0x17) & 0xFF) | ((b.get(0x18) & 0xFF) << 8));
                int ax = (short)((b.get(0x19) & 0xFF) | ((b.get(0x1A) & 0xFF) << 8));
                int ay = (short)((b.get(0x1B) & 0xFF) | ((b.get(0x1C) & 0xFF) << 8));
                int az = (short)((b.get(0x1D) & 0xFF) | ((b.get(0x1E) & 0xFF) << 8));

                // 常见缩放：gyro 原始值 -> deg/s（示例比率 1/131.072），accel -> m/s^2（示例 1/8192*g）
                float gyroScale = (1.0f / 131.072f);
                float accelScale = (9.80665f / 8192.0f);

                float gxDps = gx * gyroScale;
                float gyDps = gy * gyroScale;
                float gzDps = gz * gyroScale;
                float axMs2 = ax * accelScale;
                float ayMs2 = ay * accelScale;
                float azMs2 = az * accelScale;

                // 上报到主机（陀螺按 deg/s，accel 按 m/s^2）
                notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_GYRO, gxDps, gyDps, gzDps);
                notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_ACCEL, axMs2, ayMs2, azMs2);
            } catch (Throwable ignored) {}
        }

        return true;
    }

    private static float normalize8(int v) {
        // 0..255 -> -1..1（中心128）
        return Math.max(-1f, Math.min(1f, (v - 128) / 127.0f));
    }
}


