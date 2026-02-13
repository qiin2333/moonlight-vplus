package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Abstract base class for HID-based USB controllers (DualShock 4, DualSense, Switch Pro, etc.).
 * <p>
 * Similar to {@link AbstractXboxController} but designed for USB HID class devices
 * (interface class = 3) rather than vendor-specific Xbox protocol interfaces.
 * </p>
 */
public abstract class AbstractHidController extends AbstractController {
    protected final UsbDevice device;
    protected final UsbDeviceConnection connection;

    private Thread inputThread;
    private boolean stopped;

    protected UsbEndpoint inEndpt, outEndpt;

    public AbstractHidController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
    }

    private Thread createInputThread() {
        return new Thread() {
            public void run() {
                try {
                    // Delay slightly before accepting input to allow the old InputDevice to go away
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }

                // Perform device-specific initialization that requires I/O (subcommands, etc.)
                if (!doPostInit()) {
                    LimeLog.warning("HID controller post-init failed");
                    AbstractHidController.this.stop();
                    return;
                }

                // Report that we're added _before_ reporting input
                notifyDeviceAdded();

                while (!isInterrupted() && !stopped) {
                    byte[] buffer = new byte[64];

                    int res;

                    do {
                        long lastMillis = SystemClock.uptimeMillis();
                        res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 3000);

                        // Zero length response treated as error
                        if (res == 0) {
                            res = -1;
                        }

                        if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                            LimeLog.warning("Detected HID device I/O error");
                            AbstractHidController.this.stop();
                            break;
                        }
                    } while (res == -1 && !isInterrupted() && !stopped);

                    if (res == -1 || stopped) {
                        break;
                    }

                    if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                        reportInput();
                    }
                }
            }
        };
    }

    public boolean start() {
        // Find the appropriate HID interface
        UsbInterface hidIface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                hidIface = iface;
                break;
            }
        }

        // Fall back to the first interface if no HID interface was found
        if (hidIface == null && device.getInterfaceCount() > 0) {
            hidIface = device.getInterface(0);
        }

        if (hidIface == null) {
            LimeLog.warning("No interface found on HID device");
            return false;
        }

        // Claim the interface
        if (!connection.claimInterface(hidIface, true)) {
            LimeLog.warning("Failed to claim HID interface");
            return false;
        }

        // Find the endpoints
        for (int i = 0; i < hidIface.getEndpointCount(); i++) {
            UsbEndpoint endpt = hidIface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    LimeLog.warning("Found duplicate IN endpoint on HID device");
                    return false;
                }
                inEndpt = endpt;
            } else if (endpt.getDirection() == UsbConstants.USB_DIR_OUT) {
                if (outEndpt != null) {
                    LimeLog.warning("Found duplicate OUT endpoint on HID device");
                    return false;
                }
                outEndpt = endpt;
            }
        }

        // IN endpoint is required, OUT endpoint is optional for some HID devices
        if (inEndpt == null) {
            LimeLog.warning("Missing IN endpoint on HID device");
            return false;
        }

        // Run synchronous init (before input thread starts)
        if (!doInit()) {
            return false;
        }

        // Start listening for controller input
        inputThread = createInputThread();
        inputThread.start();

        return true;
    }

    public void stop() {
        if (stopped) {
            return;
        }

        stopped = true;

        // Cancel any rumble effects
        rumble((short) 0, (short) 0);

        // Stop the input thread
        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }

        // Close the USB connection
        connection.close();

        // Report the device removed
        notifyDeviceRemoved();
    }

    /**
     * Send data to the controller's OUT endpoint.
     *
     * @return number of bytes transferred, or -1 on error
     */
    protected int sendData(byte[] data) {
        if (outEndpt == null) {
            return -1;
        }
        return connection.bulkTransfer(outEndpt, data, data.length, 100);
    }

    /**
     * Read data from the controller's IN endpoint.
     *
     * @return number of bytes read, 0 if timeout, -1 on error
     */
    protected int readData(byte[] buffer, int timeout) {
        return connection.bulkTransfer(inEndpt, buffer, buffer.length, timeout);
    }

    /**
     * Called during start() before the input thread is created.
     * Override for any synchronous initialization that doesn't require I/O.
     */
    protected abstract boolean doInit();

    /**
     * Called at the beginning of the input thread, before notifyDeviceAdded().
     * Override for initialization that requires USB I/O (e.g., subcommands for Switch Pro).
     * Default implementation returns true.
     */
    protected boolean doPostInit() {
        return true;
    }

    protected abstract boolean handleRead(ByteBuffer buffer);

    /**
     * Helper: Read a little-endian signed short from a byte array.
     */
    protected static short readShortLE(byte[] buffer, int offset) {
        int value = (buffer[offset] & 0xFF) | ((buffer[offset + 1] & 0xFF) << 8);
        return (short) value;
    }
}
