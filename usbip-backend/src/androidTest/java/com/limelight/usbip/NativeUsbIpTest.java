package com.limelight.usbip;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import android.os.ParcelFileDescriptor;
import java.io.File;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeUsbIpTest {
    private static Socket authorizedSocket(int port) throws Exception {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        NativeUsbIp.authorizeLocalConnection(socket.getLocalPort());
        socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000);
        return socket;
    }

    @Test public void repeatedStartProtocolAndStop() throws Exception {
        NativeUsbIp.load();
        for (int i = 0; i < 100; i++) {
            int port = NativeUsbIp.start();
            try (Socket socket = authorizedSocket(port)) {
                assertTrue(port > 0);
                socket.setSoTimeout(3000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeShort(0x0111); out.writeShort(0x8005); out.writeInt(0); out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                assertEquals(0x0111, in.readUnsignedShort());
                assertEquals(0x0005, in.readUnsignedShort());
                assertEquals(0, in.readInt());
                assertEquals(0, in.readInt()); // No silently auto-exported devices.
            } finally { NativeUsbIp.stop(); }
        }
    }

    @Test public void unauthorizedLoopbackClientIsRejected() throws Exception {
        NativeUsbIp.load();
        int port = NativeUsbIp.start();
        try (Socket unauthorized = new Socket("127.0.0.1", port)) {
            unauthorized.setSoTimeout(3000);
            try {
                unauthorized.getOutputStream().write(new byte[]{0x01, 0x11, (byte) 0x80, 0x05, 0, 0, 0, 0});
                assertEquals(-1, unauthorized.getInputStream().read());
            } catch (java.net.SocketException reset) { /* rejection may reset instead of EOF */ }
        } finally { NativeUsbIp.stop(); }
    }

    @Test public void invalidFdDoesNotPreventRestart() {
        NativeUsbIp.load();
        NativeUsbIp.start();
        try {
            try { NativeUsbIp.bind(-1); fail("Invalid FD was accepted"); }
            catch (Exception expected) { /* JNI IOException */ }
        } finally { NativeUsbIp.stop(); }
        assertTrue(NativeUsbIp.start() > 0);
        NativeUsbIp.stop();
        NativeUsbIp.stop();
    }

    @Test public void failedWrapReleasesDuplicatedFd() throws Exception {
        NativeUsbIp.load();
        int before = new File("/proc/self/fd").list().length;
        for (int i = 0; i < 30; ++i) {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            NativeUsbIp.start();
            try {
                try { NativeUsbIp.bind(pipe[0].getFd()); fail("Pipe accepted as USB"); }
                catch (Exception expected) { /* dup succeeded, USB wrap must fail */ }
            } finally {
                NativeUsbIp.stop();
                pipe[0].close(); pipe[1].close();
            }
        }
        assertTrue("Failed exports leaked FDs", new File("/proc/self/fd").list().length <= before + 4);
    }

    @Test public void stopClosesIdleClient() throws Exception {
        NativeUsbIp.load();
        int port = NativeUsbIp.start();
        try (Socket socket = authorizedSocket(port)) {
            socket.setSoTimeout(3000);
            // A partial request leaves the native receiver waiting for more bytes.
            socket.getOutputStream().write(1);
            NativeUsbIp.stop();
            try { assertEquals(-1, socket.getInputStream().read()); }
            catch (java.net.SocketException reset) { /* reset is also a closed connection */ }
        } finally { NativeUsbIp.stop(); }
    }
}
