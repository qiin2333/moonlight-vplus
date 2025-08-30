package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal USB wired Nintendo Switch Pro Controller driver (input only).
 *
 * Parses standard input report 0x30: buttons and 12-bit stick values.
 * Rumble/IMU are not implemented in this version.
 */
public class SwitchProController extends AbstractController {

    private static final int NINTENDO_VID = 0x057e;
    private static final int PRO_PID = 0x2009;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;

    private UsbEndpoint inEndpt;
    private UsbEndpoint outEndpt;

    private Thread inputThread;
    private boolean stopped;

    public static boolean canClaimDevice(UsbDevice device) {
        if (device.getVendorId() != NINTENDO_VID || device.getProductId() != PRO_PID) {
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

        // Supported buttons bitmask (align with Xbox-style flags for host mapping)
        this.buttonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG;
        this.supportedButtonFlags = this.buttonFlags;
        // No analog triggers on Switch Pro controller
        // capabilities left at default (0) for now
    }

    @Override
    public boolean start() {
        // Claim HID interface 0
        UsbInterface iface = device.getInterface(0);
        if (!connection.claimInterface(iface, true)) {
            LimeLog.warning("Failed to claim HID interface for Switch Pro");
            return false;
        }

        // Find endpoints
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_IN && inEndpt == null) {
                inEndpt = endpt;
            }
            else if (endpt.getDirection() == UsbConstants.USB_DIR_OUT && outEndpt == null) {
                outEndpt = endpt;
            }
        }

        if (inEndpt == null) {
            LimeLog.warning("Missing IN endpoint for Switch Pro");
            return false;
        }

        // Start reading input
        inputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // allow previous InputDevice to settle
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}

                notifyDeviceAdded();

                byte[] buffer = new byte[64];
                while (!Thread.currentThread().isInterrupted() && !stopped) {
                    long startMs = SystemClock.uptimeMillis();
                    int res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 3000);
                    if (res == 0) {
                        res = -1;
                    }
                    if (res == -1 && SystemClock.uptimeMillis() - startMs < 1000) {
                        LimeLog.warning("Switch Pro I/O error; stopping");
                        stop();
                        break;
                    }
                    if (res <= 0 || stopped) {
                        continue;
                    }

                    if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
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
        if (stopped) {
            return;
        }
        stopped = true;
        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }
        try {
            connection.close();
        } catch (Exception ignored) {}
        notifyDeviceRemoved();
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        // Not implemented for now
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // Not present on Switch Pro
    }

    private boolean handleRead(ByteBuffer buf) {
        if (buf.remaining() < 12) {
            return false;
        }
        int reportId = buf.get(0) & 0xFF;
        if (reportId != 0x30) {
            // Only handle standard input report for now
            return false;
        }

        // buttons
        int b3 = buf.get(3) & 0xFF;
        int b4 = buf.get(4) & 0xFF;
        int b5 = buf.get(5) & 0xFF;

        // ABXY (bits on = pressed)
        setButtonFlag(ControllerPacket.A_FLAG, b3 & (1 << 3));
        setButtonFlag(ControllerPacket.B_FLAG, b3 & (1 << 2));
        setButtonFlag(ControllerPacket.X_FLAG, b3 & (1 << 1));
        setButtonFlag(ControllerPacket.Y_FLAG, b3 & (1 << 0));

        // Shoulder buttons
        setButtonFlag(ControllerPacket.RB_FLAG, b3 & (1 << 6)); // R
        setButtonFlag(ControllerPacket.LB_FLAG, b5 & (1 << 6)); // L

        // Stick clicks
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b4 & (1 << 2));
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b4 & (1 << 3));

        // Start/Back (Plus/Minus)
        setButtonFlag(ControllerPacket.PLAY_FLAG, b4 & (1 << 1)); // Plus
        setButtonFlag(ControllerPacket.BACK_FLAG, b4 & (1 << 0)); // Minus

        // Special/Home
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b4 & (1 << 4)); // Home

        // D-pad
        setButtonFlag(ControllerPacket.DOWN_FLAG, b5 & (1 << 0));
        setButtonFlag(ControllerPacket.UP_FLAG, b5 & (1 << 1));
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b5 & (1 << 2));
        setButtonFlag(ControllerPacket.LEFT_FLAG, b5 & (1 << 3));

        // Triggers (digital ZL/ZR -> 0/1)
        leftTrigger = ((b5 & (1 << 7)) != 0) ? 1.0f : 0.0f;  // ZL
        rightTrigger = ((b3 & (1 << 7)) != 0) ? 1.0f : 0.0f; // ZR

        // Sticks: 12-bit per axis packed
        int lx = (buf.get(6) & 0xFF) | ((buf.get(7) & 0x0F) << 8);
        int ly = ((buf.get(7) & 0xF0) >> 4) | ((buf.get(8) & 0xFF) << 4);
        int rx = (buf.get(9) & 0xFF) | ((buf.get(10) & 0x0F) << 8);
        int ry = ((buf.get(10) & 0xF0) >> 4) | ((buf.get(11) & 0xFF) << 4);

        // Normalize 0..4095 -> -1..1, invert Y to match expected up-positive
        leftStickX = normalizeStick(lx);
        leftStickY = -normalizeStick(ly);
        rightStickX = normalizeStick(rx);
        rightStickY = -normalizeStick(ry);

        return true;
    }

    private static float normalizeStick(int v12) {
        // center 2048, range ~2047
        return Math.max(-1f, Math.min(1f, (v12 - 2048) / 2047.0f));
    }
}


