package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;
import android.util.Log;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractDualSenseController extends AbstractController {
    protected final UsbDevice device;
    protected final UsbDeviceConnection connection;

    private Thread inputThread;
    private boolean stopped;

    protected UsbEndpoint inEndpt, outEndpt;

    // IMU data fields
    protected float gyroX, gyroY, gyroZ;
    protected float accelX, accelY, accelZ;

    public AbstractDualSenseController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.type = MoonBridge.LI_CTYPE_PS;
        this.capabilities = MoonBridge.LI_CCAP_GYRO | MoonBridge.LI_CCAP_ACCEL | MoonBridge.LI_CCAP_RUMBLE;
        this.buttonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                        ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                        ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                        ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                        ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG;
        this.supportedButtonFlags = this.buttonFlags;
    }

    private Thread createInputThread() {
        return new Thread() {
            public void run() {
                try {
                    // Delay for a moment before reporting the new gamepad and
                    // accepting new input. This allows time for the old InputDevice
                    // to go away before we reclaim its spot. If the old device is still
                    // around when we call notifyDeviceAdded(), we won't be able to claim
                    // the controller number used by the original InputDevice.
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }

                // Report that we're added _before_ reporting input
                notifyDeviceAdded();

                while (!isInterrupted() && !stopped) {
                    byte[] buffer = new byte[64];

                    int res = -1; // Initialize to error state

                    //
                    // There's no way that I can tell to determine if a device has failed
                    // or if the timeout has simply expired. We'll check how long the transfer
                    // took to fail and assume the device failed if it happened before the timeout
                    // expired.
                    //

                    do {
                        // Check if we should stop before attempting transfer
                        if (stopped || isInterrupted()) {
                            res = -1; // Set to error state before break
                            break;
                        }

                        // Read the next input state packet
                        long lastMillis = SystemClock.uptimeMillis();
                        if (connection == null || inEndpt == null) {
                            Log.w("DualSenseController", "Connection or endpoint is null");
                            res = -1; // Set to error state before break
                            break;
                        }
                        res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 3000);

                        // If we get a zero length response, treat it as an error
                        if (res == 0) {
                            res = -1;
                        }

                        if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                            Log.d("DualSenseController", "Detected device I/O error");
                            AbstractDualSenseController.this.stop();
                            break;
                        }
                    } while (res == -1 && !isInterrupted() && !stopped);

                    if (res == -1 || stopped || isInterrupted()) {
                        break;
                    }

                    if (res > 0 && handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                        // Report input if handleRead() returns true
                        reportInput();
                        reportMotion();
                    }
                }
            }
        };
    }

    private static UsbInterface findInterface(UsbDevice device) {
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_HID && intf.getEndpointCount() >= 2) {
                Log.d("DualSenseController", "Found HID interface: " + i);
                return intf;
            }
        }
        return null;
    }

    private List<UsbInterface> ifaces = new ArrayList<>();

    public boolean start() {
        ifaces.clear();
        Log.d("DualSenseController", "start");
        // Force claim all interfaces
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);

            if (!connection.claimInterface(iface, true)) {
                Log.d("DualSenseController", "Failed to claim interface: " + i);
                return false;
            } else {
                ifaces.add(iface);
            }
        }
        Log.d("DualSenseController", "getInterfaceCount:" + device.getInterfaceCount());

        // Find the endpoints
        UsbInterface iface = findInterface(device);

        if (iface == null) {
            Log.e("DualSenseController", "Failed to find interface");
            return false;
        }

        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_OUT) {
                if (outEndpt != null) {
                    Log.d("DualSenseController", "Found duplicate OUT endpoint");
                    return false;
                }
                outEndpt = endpt;
            } else if (endpt.getDirection() == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    Log.d("DualSenseController", "Found duplicate IN endpoint");
                    return false;
                }
                inEndpt = endpt;
            }
        }
        Log.d("DualSenseController", "inEndpt: " + inEndpt);
        Log.d("DualSenseController", "outEndpt: " + outEndpt);
        // Make sure the required endpoints were present
        if (inEndpt == null || outEndpt == null) {
            Log.d("DualSenseController", "Missing required endpoint");
            return false;
        }
        // Run the init function
        if (!doInit()) {
            return false;
        }
        // Start listening for controller input
        inputThread = createInputThread();
        inputThread.start();
        return true;
    }

    public void stop() {
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
        }

        // Cancel any rumble effects (may fail if device is already disconnected)
        try {
            rumble((short) 0, (short) 0);
        } catch (Exception e) {
            Log.d("DualSenseController", "Failed to cancel rumble during stop", e);
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
                        Log.w("DualSenseController", "Failed to release interface", e);
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
                Log.w("DualSenseController", "Failed to close connection", e);
            }
        }

        // Report the device removed
        notifyDeviceRemoved();
    }

    protected void reportMotion() {
        // Report gyroscope data (in deg/s)
        notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ);
        // Report accelerometer data (in m/s²)
        notifyControllerMotion(MoonBridge.LI_MOTION_TYPE_ACCEL, accelX, accelY, accelZ);
    }

    protected abstract boolean handleRead(ByteBuffer buffer);

    protected abstract boolean doInit();

    protected abstract void sendCommand(byte[] data);
}

