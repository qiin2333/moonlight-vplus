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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enhanced USB Nintendo Switch Pro Controller and Joy-Con driver.
 *
 * Supports:
 * - Switch Pro Controller (PID 0x2009)
 * - Joy-Con Left (PID 0x2006)
 * - Joy-Con Right (PID 0x2007)
 * - Joy-Con Pair (PID 0x2008)
 *
 * Features:
 * - Full input report parsing (0x30)
 * - IMU data (gyroscope and accelerometer)
 * - Rumble feedback (may not be supported on all Joy-Con variants)
 * - Stick calibration (factory and user calibration)
 * - Complete initialization sequence with device-specific adaptations
 * - Single stick support for individual Joy-Con controllers
 */
public class SwitchProController extends AbstractController {

    private static final int NINTENDO_VID = 0x057e;
    private static final int PRO_PID = 0x2009;
    private static final int JOYCON_LEFT_PID = 0x2006;
    private static final int JOYCON_RIGHT_PID = 0x2007;
    private static final int JOYCON_PAIR_PID = 0x2008;
    private static final int PACKET_SIZE = 64;
    private static final int COMMAND_RETRIES = 10;

    // SPI Flash offsets for calibration data
    private static final int FACTORY_IMU_CALIBRATION_OFFSET = 0x6020;
    private static final int FACTORY_LS_CALIBRATION_OFFSET = 0x603D;
    private static final int FACTORY_RS_CALIBRATION_OFFSET = 0x6046;
    private static final int USER_IMU_MAGIC_OFFSET = 0x8026;
    private static final int USER_IMU_CALIBRATION_OFFSET = 0x8028;
    private static final int USER_LS_MAGIC_OFFSET = 0x8010;
    private static final int USER_LS_CALIBRATION_OFFSET = 0x8012;
    private static final int USER_RS_MAGIC_OFFSET = 0x801B;
    private static final int USER_RS_CALIBRATION_OFFSET = 0x801D;
    private static final int IMU_CALIBRATION_LENGTH = 24;
    private static final int STICK_CALIBRATION_LENGTH = 9;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private UsbEndpoint inEndpt;
    private UsbEndpoint outEndpt;
    private Thread inputThread;
    private boolean stopped = false;
    private byte sendPacketCount = 0;

    // IMU data fields
    private float gyroX, gyroY, gyroZ;
    private float accelX, accelY, accelZ;

    // Stick calibration data: [stick][axis][min, center, max]
    private final int[][][] stickCalibration = new int[2][2][3];
    // Pre-calculated scale for each axis: [stick][axis][negative, positive]
    private final float[][][] stickExtends = new float[2][2][2];
    private List<UsbInterface> ifaces = new ArrayList<>();

    /**
     * 检测设备是否为 Joy-Con（单个或配对）
     */
    private boolean isJoyCon() {
        int pid = device.getProductId();
        return pid == JOYCON_LEFT_PID || pid == JOYCON_RIGHT_PID || pid == JOYCON_PAIR_PID;
    }

    /**
     * 检测设备是否为单个 Joy-Con（左或右）
     */
    private boolean isSingleJoyCon() {
        int pid = device.getProductId();
        return pid == JOYCON_LEFT_PID || pid == JOYCON_RIGHT_PID;
    }

    /**
     * 检测设备是否为 Joy-Con Left
     */
    private boolean isJoyConLeft() {
        return device.getProductId() == JOYCON_LEFT_PID;
    }

    /**
     * 检测设备是否为 Joy-Con Right
     */
    private boolean isJoyConRight() {
        return device.getProductId() == JOYCON_RIGHT_PID;
    }

    public static boolean canClaimDevice(UsbDevice device) {
        if (device == null) {
            return false;
        }
        if (device.getVendorId() != NINTENDO_VID) {
            return false;
        }
        int pid = device.getProductId();
        // 支持 Switch Pro Controller 和所有 Joy-Con 变体
        if (pid != PRO_PID && pid != JOYCON_LEFT_PID && pid != JOYCON_RIGHT_PID && pid != JOYCON_PAIR_PID) {
            return false;
        }
        if (device.getInterfaceCount() < 1) {
            return false;
        }
        UsbInterface iface = device.getInterface(0);
        return iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID;
    }

    public SwitchProController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.type = MoonBridge.LI_CTYPE_NINTENDO;
        this.capabilities = MoonBridge.LI_CCAP_GYRO | MoonBridge.LI_CCAP_ACCEL | MoonBridge.LI_CCAP_RUMBLE;

        // Supported buttons bitmask
        this.buttonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG | ControllerPacket.MISC_FLAG;
        this.supportedButtonFlags = this.buttonFlags;
    }

    private boolean sendData(byte[] data, int size) {
        if (outEndpt == null || connection == null || data == null) {
            return false;
        }
        return connection.bulkTransfer(outEndpt, data, size, 100) == size;
    }

    private boolean sendCommand(byte id, boolean waitReply) {
        byte[] data = new byte[] {(byte)0x80, id};
        for (int i = 0; i < COMMAND_RETRIES; i++) {
            if (!sendData(data, data.length)) {
                continue;
            }
            if (!waitReply) {
                return true;
            }

            byte[] buffer = new byte[PACKET_SIZE];
            int res;
            int retries = 0;
            do {
                if (inEndpt == null || connection == null) {
                    return false;
                }
                res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 100);
                if (res > 0 && (buffer[0] & 0xFF) == 0x81 && (buffer[1] & 0xFF) == id) {
                    return true;
                }
                retries += 1;
            } while (retries < 20 && res > 0 && !Thread.currentThread().isInterrupted() && !stopped);
        }
        return false;
    }

    private boolean sendSubcommand(byte subcommand, byte[] payload, byte[] buffer) {
        if (payload == null || buffer == null) {
            return false;
        }
        // 检查 buffer 大小是否足够（至少需要 15 字节来读取响应）
        if (buffer.length < 15) {
            LimeLog.warning("SwitchPro: Response buffer too small: " + buffer.length);
            return false;
        }
        byte[] data = new byte[11 + payload.length];
        data[0] = 0x01;  // Rumble and subcommand
        data[1] = sendPacketCount++;  // Counter (increments per call)
        if (sendPacketCount > 0xF) {
            sendPacketCount = 0;
        }

        data[10] = subcommand;
        System.arraycopy(payload, 0, data, 11, payload.length);

        for (int i = 0; i < COMMAND_RETRIES; i++) {
            if (!sendData(data, data.length)) {
                continue;
            }

            // Wait for response
            int res;
            int retries = 0;
            do {
                if (inEndpt == null || connection == null) {
                    return false;
                }
                res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 100);
                // 检查 buffer 是否有足够的数据
                if (res < 0 || res < 15 || buffer[0] != 0x21 || buffer[14] != subcommand) {
                    retries += 1;
                } else {
                    return true;
                }
            } while (retries < 20 && res > 0 && !Thread.currentThread().isInterrupted() && !stopped);
            // 安全地访问 buffer，避免越界
            String bufferInfo = (res > 0 && buffer.length > 0) ? 
                    String.format((Locale)null, "0x%02x", buffer[0] & 0xFF) : "N/A";
            if (res >= 15 && buffer.length > 14) {
                bufferInfo += String.format((Locale)null, ", 0x%02x", buffer[14] & 0xFF);
            }
            LimeLog.warning("SwitchPro: Failed to get subcmd reply: " + res + " bytes received, " + bufferInfo);
        }
        return false;
    }

    private boolean handshake() {
        return sendCommand((byte)0x02, true);
    }

    private boolean highSpeed() {
        return sendCommand((byte)0x03, true);
    }

    private boolean forceUSB() {
        return sendCommand((byte)0x04, true);
    }

    private boolean setInputReportMode(byte mode) {
        final byte[] data = new byte[] {mode};
        return sendSubcommand((byte) 0x03, data, new byte[PACKET_SIZE]);
    }

    private boolean setPlayerLED(int id) {
        final byte[] data = new byte[] {(byte)(id & 0b1111)};
        return sendSubcommand((byte)0x30, data, new byte[PACKET_SIZE]);
    }

    private boolean enableIMU(boolean enable) {
        byte[] data = new byte[]{(byte)(enable ? 0x01 : 0x00)};
        return sendSubcommand((byte)0x40, data, new byte[PACKET_SIZE]);
    }

    private boolean enableVibration(boolean enable) {
        byte[] data = new byte[]{(byte)(enable ? 0x01 : 0x00)};
        return sendSubcommand((byte)0x48, data, new byte[PACKET_SIZE]);
    }

    private boolean spiFlashRead(int offset, int length, byte[] buffer) {
        if (buffer == null || buffer.length < length + 20) {
            return false;
        }
        // SPI Read Address (Little Endian)
        byte[] address = {
                (byte) (offset & 0xFF),
                (byte) ((offset >> 8) & 0xFF),
                (byte) ((offset >> 16) & 0xFF),
                (byte) ((offset >> 24) & 0xFF),
                (byte) length
        };

        if (!sendSubcommand((byte) 0x10, address, buffer)) {
            LimeLog.warning("SwitchPro: Failed to receive SPI Flash data.");
            return false;
        }

        return true;
    }

    private boolean checkUserCalMagic(int offset) {
        byte[] buffer = new byte[PACKET_SIZE];
        if (!spiFlashRead(offset, 2, buffer)) {
            return false;
        }
        return ((buffer[20] & 0xFF) == 0xB2) && ((buffer[21] & 0xFF) == 0xA1);
    }

    private boolean loadStickCalibration() {
        byte[] buffer = new byte[PACKET_SIZE];
        boolean isJoyCon = isJoyCon();
        boolean isLeft = isJoyConLeft();

        int ls_addr = FACTORY_LS_CALIBRATION_OFFSET;
        int rs_addr = FACTORY_RS_CALIBRATION_OFFSET;

        if (checkUserCalMagic(USER_LS_MAGIC_OFFSET)) {
            ls_addr = USER_LS_CALIBRATION_OFFSET;
            LimeLog.info("SwitchPro: LS has user calibration!");
        }
        if (checkUserCalMagic(USER_RS_MAGIC_OFFSET)) {
            rs_addr = USER_RS_CALIBRATION_OFFSET;
            LimeLog.info("SwitchPro: RS has user calibration!");
        }

        // Joy-Con Left 只有左摇杆，Joy-Con Right 只有右摇杆，Joy-Con Pair 和 Pro Controller 都有两个
        boolean needLeftStick = !isJoyCon || isLeft || device.getProductId() == JOYCON_PAIR_PID;
        boolean needRightStick = !isJoyCon || !isLeft || device.getProductId() == JOYCON_PAIR_PID;

        boolean ls_calibrated = false;
        if (needLeftStick && spiFlashRead(ls_addr, STICK_CALIBRATION_LENGTH, buffer)) {
            // read offset 20
            int x_max = (buffer[20] & 0xFF) | ((buffer[21] & 0x0F) << 8);
            int y_max = ((buffer[21] & 0xF0) >> 4) | ((buffer[22] & 0xFF) << 4);
            int x_center = (buffer[23] & 0xFF) | ((buffer[24] & 0x0F) << 8);
            int y_center = ((buffer[24] & 0xF0) >> 4) | ((buffer[25] & 0xFF) << 4);
            int x_min = (buffer[26] & 0xFF) | ((buffer[27] & 0x0F) << 8);
            int y_min = ((buffer[27] & 0xF0) >> 4) | ((buffer[28] & 0xFF) << 4);
            stickCalibration[0][0][0] = x_center - x_min; // Min
            stickCalibration[0][0][1] = x_center; // Center
            stickCalibration[0][0][2] = x_center + x_max; // Max
            stickCalibration[0][1][0] = 0x1000 - y_center - y_max; // Min
            stickCalibration[0][1][1] = 0x1000 - y_center; // Center
            stickCalibration[0][1][2] = 0x1000 - y_center + y_min; // Max
            stickExtends[0][0][0] = (float) ((x_center - stickCalibration[0][0][0]) * -0.7);
            stickExtends[0][0][1] = (float) ((stickCalibration[0][0][2] - x_center) * 0.7);
            stickExtends[0][1][0] = (float) ((y_center - stickCalibration[0][1][0]) * -0.7);
            stickExtends[0][1][1] = (float) ((stickCalibration[0][1][2] - y_center) * 0.7);

            ls_calibrated = true;
        }

        if (!ls_calibrated && needLeftStick) {
            applyDefaultCalibration(0);
        } else if (!needLeftStick) {
            // Joy-Con Right 不需要左摇杆，设置为默认值但不使用
            applyDefaultCalibration(0);
        }

        boolean rs_calibrated = false;
        if (needRightStick && spiFlashRead(rs_addr, STICK_CALIBRATION_LENGTH, buffer)) {
            // read offset 20
            int x_center = (buffer[20] & 0xFF) | ((buffer[21] & 0x0F) << 8);
            int y_center = ((buffer[21] & 0xF0) >> 4) | ((buffer[22] & 0xFF) << 4);
            int x_min = (buffer[23] & 0xFF) | ((buffer[24] & 0x0F) << 8);
            int y_min = ((buffer[24] & 0xF0) >> 4) | ((buffer[25] & 0xFF) << 4);
            int x_max = (buffer[26] & 0xFF) | ((buffer[27] & 0x0F) << 8);
            int y_max = ((buffer[27] & 0xF0) >> 4) | ((buffer[28] & 0xFF) << 4);
            stickCalibration[1][0][0] = x_center - x_min; // Min
            stickCalibration[1][0][1] = x_center; // Center
            stickCalibration[1][0][2] = x_center + x_max; // Max
            stickCalibration[1][1][0] = 0x1000 - y_center - y_max; // Min
            stickCalibration[1][1][1] = 0x1000 - y_center; // Center
            stickCalibration[1][1][2] = 0x1000 - y_center + y_min; // Max
            stickExtends[1][0][0] = (float) ((x_center - stickCalibration[1][0][0]) * -0.7);
            stickExtends[1][0][1] = (float) ((stickCalibration[1][0][2] - x_center) * 0.7);
            stickExtends[1][1][0] = (float) ((y_center - stickCalibration[1][1][0]) * -0.7);
            stickExtends[1][1][1] = (float) ((stickCalibration[1][1][2] - y_center) * 0.7);

            rs_calibrated = true;
        }

        if (!rs_calibrated && needRightStick) {
            applyDefaultCalibration(1);
        } else if (!needRightStick) {
            // Joy-Con Left 不需要右摇杆，设置为默认值但不使用
            applyDefaultCalibration(1);
        }

        return true;
    }

    private void applyDefaultCalibration(int stick) {
        for (int axis = 0; axis < 2; axis++) {
            stickCalibration[stick][axis][0] = 0x000;  // Min
            stickCalibration[stick][axis][1] = 0x800;  // Center
            stickCalibration[stick][axis][2] = 0xFFF;  // Max

            stickExtends[stick][axis][0] = -0x700;
            stickExtends[stick][axis][1] = 0x700;
        }
    }

    private float applyStickCalibration(int value, int stick, int axis) {
        int center = stickCalibration[stick][axis][1];

        if (value < 0) {
            value += 0x1000;
        }

        value -= center;

        if (value < stickExtends[stick][axis][0]) {
            stickExtends[stick][axis][0] = value;
            return -1;
        } else if (value > stickExtends[stick][axis][1]) {
            stickExtends[stick][axis][1] = value;
            return 1;
        }

        // 防止除零错误
        if (value > 0) {
            float divisor = stickExtends[stick][axis][1];
            if (Math.abs(divisor) < 0.0001f) {
                return 0;
            }
            return value / divisor;
        } else if (value < 0) {
            float divisor = stickExtends[stick][axis][0];
            if (Math.abs(divisor) < 0.0001f) {
                return 0;
            }
            return -value / divisor;
        }
        return 0;
    }

    private Thread createInputThread() {
        return new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }

            boolean handshakeSuccess = handshake();
            if (!handshakeSuccess) {
                LimeLog.warning("SwitchPro: Initial handshake failed!");
                SwitchProController.this.stop();
                return;
            }

            boolean isJoyCon = isJoyCon();
            String deviceType = isJoyCon ? "Joy-Con" : "Switch Pro";
            
            LimeLog.info(deviceType + ": handshake " + handshakeSuccess);
            LimeLog.info(deviceType + ": highspeed " + highSpeed());
            LimeLog.info(deviceType + ": handshake " + handshake());
            
            // 加载摇杆校准（Joy-Con 可能只有一个摇杆，但校准流程相同）
            boolean calibrationLoaded = loadStickCalibration();
            LimeLog.info(deviceType + ": loadstickcalibration " + calibrationLoaded);
            
            // 设置输入报告模式（所有设备都需要）
            boolean reportModeSet = setInputReportMode((byte)0x30);
            LimeLog.info(deviceType + ": setinputreportmode " + reportModeSet);
            
            // 强制 USB 模式（所有设备都需要）
            boolean usbForced = forceUSB();
            LimeLog.info(deviceType + ": forceusb " + usbForced);
            
            // 根据设备类型调整特性启用
            if (!isJoyCon) {
                // Switch Pro Controller 支持所有特性
                LimeLog.info(deviceType + ": enablevibration " + enableVibration(true));
                LimeLog.info(deviceType + ": setplayerled " + setPlayerLED(getControllerId() + 1));
            } else {
                // Joy-Con 可能不支持某些特性，尝试启用但不强制要求成功
                boolean vibrationEnabled = enableVibration(true);
                LimeLog.info(deviceType + ": enablevibration " + vibrationEnabled + " (may not be supported)");
                
                // Joy-Con 可能不支持 LED，尝试但不强制
                boolean ledSet = setPlayerLED(getControllerId() + 1);
                LimeLog.info(deviceType + ": setplayerled " + ledSet + " (may not be supported)");
            }
            
            // IMU 所有设备都支持，启用
            boolean imuEnabled = enableIMU(true);
            LimeLog.info(deviceType + ": enableimu " + imuEnabled);

            LimeLog.info(deviceType + ": initialized!");

            notifyDeviceAdded();

            while (!Thread.currentThread().isInterrupted() && !stopped) {
                byte[] buffer = new byte[64];
                int res = -1;
                do {
                    if (stopped || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    long lastMillis = SystemClock.uptimeMillis();
                    if (inEndpt == null || connection == null) {
                        break;
                    }
                    res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 1000);
                    if (res == 0) {
                        res = -1;
                    }
                    if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                        LimeLog.warning("SwitchPro: Detected device I/O error");
                        SwitchProController.this.stop();
                        break;
                    }
                } while (res == -1 && !Thread.currentThread().isInterrupted() && !stopped);

                if (res == -1 || stopped) {
                    break;
                }

                if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                    reportInput();
                    reportMotion();
                }
            }
        });
    }

    @Override
    public boolean start() {
        ifaces.clear();
        // Claim all interfaces
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (!connection.claimInterface(iface, true)) {
                LimeLog.warning("SwitchPro: Failed to claim interface: " + i);
                return false;
            } else {
                ifaces.add(iface);
            }
        }

        // Find endpoints
        UsbInterface iface = device.getInterface(0);
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_IN && inEndpt == null) {
                inEndpt = endpt;
            } else if (endpt.getDirection() == UsbConstants.USB_DIR_OUT && outEndpt == null) {
                outEndpt = endpt;
            }
        }

        if (inEndpt == null || outEndpt == null) {
            LimeLog.warning("SwitchPro: Missing required endpoint");
            return false;
        }

        // Start initialization thread
        inputThread = createInputThread();
        inputThread.start();

        return true;
    }

    @Override
    public void stop() {
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
        }

        // Cancel any rumble effects
        try {
            rumble((short) 0, (short) 0);
        } catch (Exception e) {
            LimeLog.warning("SwitchPro: Failed to cancel rumble during stop");
        }

        // Stop the input thread
        if (inputThread != null) {
            inputThread.interrupt();
            try {
                inputThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inputThread = null;
        }

        // Release all claimed interfaces
        if (connection != null && !ifaces.isEmpty()) {
            synchronized (ifaces) {
                for (UsbInterface iface : ifaces) {
                    try {
                        connection.releaseInterface(iface);
                    } catch (Exception e) {
                        LimeLog.warning("SwitchPro: Failed to release interface");
                    }
                }
                ifaces.clear();
            }
        }

        // Close the USB connection
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                LimeLog.warning("SwitchPro: Failed to close connection");
            }
        }

        notifyDeviceRemoved();
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        if (outEndpt == null || connection == null) {
            return;
        }
        byte[] data = new byte[10];
        data[0] = 0x10;  // Rumble command
        data[1] = sendPacketCount++;  // Counter (increments per call)
        if (sendPacketCount > 0xF) {
            sendPacketCount = 0;
        }

        if (lowFreqMotor != 0) {
            // 修复运算符优先级：应该是 (lowFreqMotor & 0xFFFF) >> 12
            data[4] = data[8] = (byte)(0x50 - ((lowFreqMotor & 0xFFFF) >> 12));
            data[5] = data[9] = (byte)((((lowFreqMotor & 0xFFFF) >> 8) / 5) + 0x40);
        }
        if (highFreqMotor != 0) {
            // 修复运算符优先级和逻辑
            data[6] = (byte)((0x70 - ((highFreqMotor & 0xFFFF) >> 10)) & -0x04);
            data[7] = (byte)(((highFreqMotor & 0xFFFF) >> 8) * 0xC8 / 0xFF);
        }

        data[2] |= 0x00;
        data[3] |= 0x01;
        data[5] |= 0x40;
        data[6] |= 0x00;
        data[7] |= 0x01;
        data[9] |= 0x40;

        sendData(data, data.length);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // Switch Pro does not support trigger-specific rumble
    }

    private void reportMotion() {
        // Report gyroscope data (in deg/s)
        notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ);
        // Report accelerometer data (in m/s², converted from raw values)
        // Note: Switch Pro reports accel in raw units, need to convert to m/s²
        // Using approximate conversion: raw / 4096.0f gives approximate g-force
        // Then multiply by 9.81 to get m/s²
        float accelX_ms2 = accelX * 9.81f;
        float accelY_ms2 = accelY * 9.81f;
        float accelZ_ms2 = accelZ * 9.81f;
        notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_ACCEL, accelX_ms2, accelY_ms2, accelZ_ms2);
    }

    private boolean handleRead(ByteBuffer buf) {
        if (buf == null || buf.remaining() < PACKET_SIZE) {
            return false;
        }

        // 检查报告ID，但不移动position
        int reportId = buf.get(0) & 0xFF;
        if (reportId != 0x30) {
            return false;
        }
        
        // 现在跳过第一个字节
        buf.get();

        // 确保有足够的数据访问按钮数据（需要到位置 5）
        int capacity = buf.capacity();
        if (capacity < 6) {
            LimeLog.warning("SwitchPro: Buffer too small for button data");
            return false;
        }

        buttonFlags = 0;

        // Nintendo layout is swapped compared to Xbox
        // 使用绝对位置访问，已检查 capacity >= 6
        byte b3 = buf.get(3);
        byte b4 = buf.get(4);
        byte b5 = buf.get(5);
        
        setButtonFlag(ControllerPacket.B_FLAG, b3 & 0x08);
        setButtonFlag(ControllerPacket.A_FLAG, b3 & 0x04);
        setButtonFlag(ControllerPacket.Y_FLAG, b3 & 0x02);
        setButtonFlag(ControllerPacket.X_FLAG, b3 & 0x01);
        setButtonFlag(ControllerPacket.UP_FLAG, b5 & 0x02);
        setButtonFlag(ControllerPacket.DOWN_FLAG, b5 & 0x01);
        setButtonFlag(ControllerPacket.LEFT_FLAG, b5 & 0x08);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b5 & 0x04);
        setButtonFlag(ControllerPacket.BACK_FLAG, b4 & 0x01);
        setButtonFlag(ControllerPacket.PLAY_FLAG, b4 & 0x02);
        setButtonFlag(ControllerPacket.MISC_FLAG, b4 & 0x20); // Screenshot
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b4 & 0x10); // Home
        setButtonFlag(ControllerPacket.LB_FLAG, b5 & 0x40);
        setButtonFlag(ControllerPacket.RB_FLAG, b3 & 0x40);
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b4 & 0x08);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b4 & 0x04);

        // Triggers (digital ZL/ZR -> 0/1)
        leftTrigger = ((b5 & 0x80) != 0) ? 1.0f : 0.0f;  // ZL
        rightTrigger = ((b3 & 0x80) != 0) ? 1.0f : 0.0f; // ZR

        // Sticks: 12-bit per axis packed
        // 确保有足够的数据
        if (buf.capacity() < 12) {
            LimeLog.warning("SwitchPro: Buffer too small for stick data");
            return false;
        }
        
        boolean isJoyCon = isJoyCon();
        boolean isLeft = isJoyConLeft();
        boolean needLeftStick = !isJoyCon || isLeft || device.getProductId() == JOYCON_PAIR_PID;
        boolean needRightStick = !isJoyCon || !isLeft || device.getProductId() == JOYCON_PAIR_PID;
        
        int _leftStickX = (buf.get(6) & 0xFF) | ((buf.get(7) & 0x0F) << 8);
        // 注意：buf.get(8) 可能返回负数，需要转换为无符号
        int _leftStickY = ((buf.get(7) & 0xF0) >> 4) | ((buf.get(8) & 0xFF) << 4);
        int _rightStickX = (buf.get(9) & 0xFF) | ((buf.get(10) & 0x0F) << 8);
        // 注意：buf.get(11) 可能返回负数，需要转换为无符号
        int _rightStickY = ((buf.get(10) & 0xF0) >> 4) | ((buf.get(11) & 0xFF) << 4);

        // Apply stick calibration
        // 注意：Y轴需要反转，但要防止溢出
        if (needLeftStick) {
            leftStickX = applyStickCalibration(_leftStickX, 0, 0);
            leftStickY = applyStickCalibration((-_leftStickY) & 0xFFF, 0, 1); // 使用 & 0xFFF 防止负数溢出
        } else {
            // Joy-Con Right 没有左摇杆
            leftStickX = 0;
            leftStickY = 0;
        }
        
        if (needRightStick) {
            rightStickX = applyStickCalibration(_rightStickX, 1, 0);
            rightStickY = applyStickCalibration((-_rightStickY) & 0xFFF, 1, 1); // 使用 & 0xFFF 防止负数溢出
        } else {
            // Joy-Con Left 没有右摇杆
            rightStickX = 0;
            rightStickY = 0;
        }

        // IMU data (if available in report 0x30)
        // Note: Full IMU data may require report mode 0x31, but 0x30 also contains some IMU data
        // 确保有足够的数据读取 IMU（需要到位置 47，即至少 48 字节）
        if (buf.capacity() >= 48 && buf.position() <= 37) {
            // 使用绝对位置访问，确保不越界
            int pos = buf.position();
            try {
                // Accelerometer data (raw values, approximate)
                accelX = buf.getShort(37) / 4096.0f;
                accelY = buf.getShort(39) / 4096.0f;
                accelZ = buf.getShort(41) / 4096.0f;
                // Gyroscope data (deg/s)
                gyroZ = -buf.getShort(43) / 16.0f;
                gyroX = -buf.getShort(45) / 16.0f;
                gyroY = buf.getShort(47) / 16.0f;
            } catch (IndexOutOfBoundsException e) {
                LimeLog.warning("SwitchPro: IMU data out of bounds");
                // 重置 position 并继续
                buf.position(pos);
            }
        }

        return true;
    }
}
