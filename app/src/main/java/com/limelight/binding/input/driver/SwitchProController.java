package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;

/**
 * USB driver for Nintendo Switch Pro Controller and Joy-Cons.
 * <p>
 * Implements the Switch Pro Controller USB protocol including:
 * - Handshake and high-speed mode initialization
 * - Standard full input report (0x30) parsing
 * - Stick calibration (default values)
 * - HD Rumble output
 * - Button remapping (Nintendo layout → Xbox layout)
 * </p>
 */
public class SwitchProController extends AbstractHidController {

    private static final int NINTENDO_VID        = 0x057E;
    private static final int PRO_PID             = 0x2009;
    private static final int JOYCON_LEFT_PID     = 0x2006;
    private static final int JOYCON_RIGHT_PID    = 0x2007;
    private static final int JOYCON_PAIR_PID     = 0x2008;

    private static final int PACKET_SIZE = 64;
    private static final int COMMAND_RETRIES = 10;

    private int sendPacketCount = 0;

    // Stick calibration: [stick][axis] = {min, center, max}
    private final int[][][] stickCalibration = {
            {{0, 0x800, 0xFFF}, {0, 0x800, 0xFFF}},
            {{0, 0x800, 0xFFF}, {0, 0x800, 0xFFF}}
    };
    // Pre-computed axis ranges: [stick][axis] = {negativeExtent, positiveExtent}
    private final int[][][] stickExtents = {
            {{-0x700, 0x700}, {-0x700, 0x700}},
            {{-0x700, 0x700}, {-0x700, 0x700}}
    };

    public SwitchProController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
        this.type = MoonBridge.LI_CTYPE_NINTENDO;
        this.capabilities = MoonBridge.LI_CCAP_RUMBLE;
        this.supportedButtonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG |
                ControllerPacket.SPECIAL_BUTTON_FLAG | ControllerPacket.MISC_FLAG;
    }

    public static boolean canClaimDevice(UsbDevice device) {
        if (device.getVendorId() != NINTENDO_VID) {
            return false;
        }

        int pid = device.getProductId();
        if (pid != PRO_PID && pid != JOYCON_LEFT_PID &&
                pid != JOYCON_RIGHT_PID && pid != JOYCON_PAIR_PID) {
            return false;
        }

        // Must have at least one HID interface
        if (device.getInterfaceCount() < 1) {
            return false;
        }
        return device.getInterface(0).getInterfaceClass() == UsbConstants.USB_CLASS_HID;
    }

    @Override
    protected boolean doInit() {
        // Synchronous init — nothing needed here.
        // All I/O-based init is done in doPostInit().
        return true;
    }

    @Override
    protected boolean doPostInit() {
        // Handshake
        if (!sendCommand((byte) 0x02, true)) {
            LimeLog.warning("Switch Pro: handshake failed");
            return false;
        }

        // High-speed mode
        sendCommand((byte) 0x03, true);

        // Second handshake
        sendCommand((byte) 0x02, true);

        // Apply default calibration
        applyDefaultCalibration(0);
        applyDefaultCalibration(1);

        // Set input report mode to full report (0x30)
        sendSubcommand((byte) 0x03, new byte[]{0x30});

        // Force USB
        sendCommand((byte) 0x04, true);

        // Enable vibration
        sendSubcommand((byte) 0x48, new byte[]{0x01});

        // Set player LED (based on controller ID)
        sendSubcommand((byte) 0x30, new byte[]{(byte) ((getControllerId() + 1) & 0x0F)});

        // Enable IMU (gyro + accel)
        sendSubcommand((byte) 0x40, new byte[]{0x01});

        return true;
    }

    // —— Low-level protocol ——

    /**
     * Send a top-level command (0x80 prefix) and optionally wait for an 0x81 reply.
     */
    private boolean sendCommand(byte commandId, boolean waitReply) {
        byte[] data = new byte[]{(byte) 0x80, commandId};

        for (int attempt = 0; attempt < COMMAND_RETRIES; attempt++) {
            if (sendData(data) < 0) {
                continue;
            }

            if (!waitReply) {
                return true;
            }

            // Wait for matching 0x81 reply
            byte[] reply = new byte[PACKET_SIZE];
            for (int retry = 0; retry < 20; retry++) {
                int len = readData(reply, 100);
                if (len >= 2 && reply[0] == (byte) 0x81 && reply[1] == commandId) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Send a subcommand (0x01 prefix with rumble data and subcommand byte).
     * Waits for an 0x21 reply with matching subcommand byte.
     *
     * @return reply buffer, or null on failure
     */
    private byte[] sendSubcommand(byte subcommand, byte[] payload) {
        byte[] data = new byte[11 + (payload != null ? payload.length : 0)];
        data[0] = 0x01;  // Rumble + subcommand
        data[1] = (byte) (sendPacketCount++ & 0x0F);
        // Bytes 2-9: neutral rumble data (all zeros is fine)
        data[10] = subcommand;
        if (payload != null) {
            System.arraycopy(payload, 0, data, 11, payload.length);
        }

        for (int attempt = 0; attempt < COMMAND_RETRIES; attempt++) {
            if (sendData(data) < 0) {
                continue;
            }

            byte[] reply = new byte[PACKET_SIZE];
            for (int retry = 0; retry < 20; retry++) {
                int len = readData(reply, 100);
                if (len >= 15 && reply[0] == 0x21 && reply[14] == subcommand) {
                    return reply;
                }
            }
        }

        return null;
    }

    // —— Input processing ——

    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() < PACKET_SIZE) {
            return false;
        }

        byte[] raw = buffer.array();
        int base = buffer.arrayOffset() + buffer.position();

        // Only process standard full input reports (0x30)
        if (raw[base] != 0x30) {
            return false;
        }

        // Button bytes (Nintendo layout → Xbox mapping)
        int b3 = raw[base + 3] & 0xFF;
        int b4 = raw[base + 4] & 0xFF;
        int b5 = raw[base + 5] & 0xFF;

        // Nintendo uses swapped A/B and X/Y relative to Xbox
        setButtonFlag(ControllerPacket.B_FLAG, b3 & 0x08);  // Nintendo A → Xbox B
        setButtonFlag(ControllerPacket.A_FLAG, b3 & 0x04);  // Nintendo B → Xbox A
        setButtonFlag(ControllerPacket.Y_FLAG, b3 & 0x02);  // Nintendo X → Xbox Y
        setButtonFlag(ControllerPacket.X_FLAG, b3 & 0x01);  // Nintendo Y → Xbox X

        setButtonFlag(ControllerPacket.UP_FLAG,    b5 & 0x02);
        setButtonFlag(ControllerPacket.DOWN_FLAG,  b5 & 0x01);
        setButtonFlag(ControllerPacket.LEFT_FLAG,  b5 & 0x08);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b5 & 0x04);

        setButtonFlag(ControllerPacket.BACK_FLAG,           b4 & 0x01);  // Minus
        setButtonFlag(ControllerPacket.PLAY_FLAG,           b4 & 0x02);  // Plus
        setButtonFlag(ControllerPacket.MISC_FLAG,           b4 & 0x20);  // Capture
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b4 & 0x10);  // Home

        setButtonFlag(ControllerPacket.LB_FLAG,     b5 & 0x40);  // L
        setButtonFlag(ControllerPacket.RB_FLAG,     b3 & 0x40);  // R
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b4 & 0x08);  // LS
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b4 & 0x04);  // RS

        // Digital triggers (ZL/ZR)
        leftTrigger  = (b5 & 0x80) != 0 ? 1.0f : 0.0f;
        rightTrigger = (b3 & 0x80) != 0 ? 1.0f : 0.0f;

        // Sticks: 12-bit values packed in 3 bytes each
        int lsx = (raw[base + 6] & 0xFF) | (((raw[base + 7] & 0x0F)) << 8);
        int lsy = ((raw[base + 7] & 0xF0) >> 4) | ((raw[base + 8] & 0xFF) << 4);
        int rsx = (raw[base + 9] & 0xFF) | (((raw[base + 10] & 0x0F)) << 8);
        int rsy = ((raw[base + 10] & 0xF0) >> 4) | ((raw[base + 11] & 0xFF) << 4);

        leftStickX  = applyStickCalibration(lsx, 0, 0);
        leftStickY  = -applyStickCalibration(lsy, 0, 1);
        rightStickX = applyStickCalibration(rsx, 1, 0);
        rightStickY = -applyStickCalibration(rsy, 1, 1);

        return true;
    }

    // —— Stick calibration ——

    private void applyDefaultCalibration(int stick) {
        for (int axis = 0; axis < 2; axis++) {
            stickCalibration[stick][axis][0] = 0x000;
            stickCalibration[stick][axis][1] = 0x800;
            stickCalibration[stick][axis][2] = 0xFFF;
            stickExtents[stick][axis][0] = -0x700;
            stickExtents[stick][axis][1] = 0x700;
        }
    }

    private float applyStickCalibration(int value, int stick, int axis) {
        int center = stickCalibration[stick][axis][1];

        if (value < 0) {
            value += 0x1000;
        }

        value -= center;

        // Dynamically extend range
        if (value < stickExtents[stick][axis][0]) {
            stickExtents[stick][axis][0] = value;
            return -1.0f;
        } else if (value > stickExtents[stick][axis][1]) {
            stickExtents[stick][axis][1] = value;
            return 1.0f;
        }

        if (value > 0) {
            int divisor = stickExtents[stick][axis][1];
            return (divisor == 0) ? 0.0f : (float) value / divisor;
        } else if (value < 0) {
            int divisor = stickExtents[stick][axis][0];
            return (divisor == 0) ? 0.0f : (float) -value / divisor;
        }
        return 0.0f;
    }

    // —— Rumble ——

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        byte[] data = new byte[10];
        data[0] = 0x10;
        data[1] = (byte) (sendPacketCount++ & 0x0F);

        if (lowFreqMotor != 0) {
            int low = lowFreqMotor & 0xFFFF;
            data[4] = data[8] = (byte) (0x50 - (low >> 12));
            data[5] = data[9] = (byte) (((low >> 8) / 5) + 0x40);
        }
        if (highFreqMotor != 0) {
            int high = highFreqMotor & 0xFFFF;
            data[6] = (byte) ((0x70 - (high >> 10)) & 0xFC);
            data[7] = (byte) ((high >> 8) * 0xC8 / 0xFF);
        }

        // Default neutral rumble encoding
        data[2] |= 0x00;
        data[3] |= 0x01;
        data[5] |= 0x40;
        data[6] |= 0x00;
        data[7] |= 0x01;
        data[9] |= 0x40;

        sendData(data);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // Switch Pro Controller does not support trigger motors
    }
}
