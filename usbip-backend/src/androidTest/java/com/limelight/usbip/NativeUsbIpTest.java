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
    private static Socket authorizedSocket(long handle, int port) throws Exception {
        Socket socket = new Socket();
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        NativeUsbIp.authorizeLocalConnection(handle, socket.getLocalPort());
        socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000);
        return socket;
    }

    /** Requests a device list and asserts the reply of an exporter with nothing bound.
     * A session serves exactly one request, so every check needs its own connection. */
    private static void assertEmptyDeviceList(Socket socket) throws Exception {
        socket.setSoTimeout(3000);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeShort(0x0111); out.writeShort(0x8005); out.writeInt(0); out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        assertEquals(0x0111, in.readUnsignedShort());
        assertEquals(0x0005, in.readUnsignedShort());
        assertEquals(0, in.readInt());
        assertEquals(0, in.readInt()); // No silently auto-exported devices.
    }

    /** Connects from {@code sourcePort} without authorizing it for {@code handle}. */
    private static Socket unauthorizedSocket(int sourcePort, int port) throws Exception {
        Socket socket = new Socket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), sourcePort));
        socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000);
        return socket;
    }

    private static void assertRejected(Socket socket) throws Exception {
        socket.setSoTimeout(3000);
        try {
            socket.getOutputStream().write(new byte[]{0x01, 0x11, (byte) 0x80, 0x05, 0, 0, 0, 0});
            assertEquals(-1, socket.getInputStream().read());
        } catch (java.net.SocketException reset) { /* rejection may reset instead of EOF */ }
    }

    @Test public void repeatedStartProtocolAndStop() throws Exception {
        NativeUsbIp.load();
        for (int i = 0; i < 100; i++) {
            long handle = NativeUsbIp.start();
            try (Socket socket = authorizedSocket(handle, NativeUsbIp.localPort(handle))) {
                assertEmptyDeviceList(socket);
            } finally { NativeUsbIp.stop(handle); }
        }
    }

    /** One exporter serves several tunnels at once, one authorized port each. */
    @Test public void oneExporterServesSeveralTunnelsAtOnce() throws Exception {
        NativeUsbIp.load();
        long handle = NativeUsbIp.start();
        int port = NativeUsbIp.localPort(handle);
        try (Socket first = authorizedSocket(handle, port);
             Socket second = authorizedSocket(handle, port)) {
            assertNotEquals(first.getLocalPort(), second.getLocalPort());
            assertEmptyDeviceList(first);
            assertEmptyDeviceList(second);
        } finally { NativeUsbIp.stop(handle); }
    }

    /** Revoking one tunnel's port leaves the exporter's other authorizations alone. */
    @Test public void revokingOnePortKeepsTheOtherAuthorized() throws Exception {
        NativeUsbIp.load();
        long handle = NativeUsbIp.start();
        int port = NativeUsbIp.localPort(handle);
        Socket reservation = new Socket();
        reservation.setReuseAddress(true);
        reservation.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        int revokedPort = reservation.getLocalPort();
        NativeUsbIp.authorizeLocalConnection(handle, revokedPort);
        // Keep one authorized connection unused until the check below.
        try (Socket keeper = authorizedSocket(handle, port)) {
            NativeUsbIp.revokeLocalConnection(handle, revokedPort);
            reservation.close();
            try (Socket rejected = unauthorizedSocket(revokedPort, port)) {
                assertRejected(rejected);
            }
            assertEmptyDeviceList(keeper);
        } finally { NativeUsbIp.stop(handle); }
    }

    /** Unbinding a device the exporter does not serve must leave it serving. */
    @Test public void unbindUnknownDeviceLeavesExporterServing() throws Exception {
        NativeUsbIp.load();
        long handle = NativeUsbIp.start();
        int port = NativeUsbIp.localPort(handle);
        try {
            NativeUsbIp.unbind(handle, "9-9:0");
            try (Socket socket = authorizedSocket(handle, port)) {
                assertEmptyDeviceList(socket);
            }
        } finally { NativeUsbIp.stop(handle); }
    }

    /** Two exporters share one process: separate listeners, separate authorizations. */
    @Test public void instancesForwardIndependently() throws Exception {
        NativeUsbIp.load();
        long first = NativeUsbIp.start();
        long second = NativeUsbIp.start();
        int firstPort = NativeUsbIp.localPort(first);
        int secondPort = NativeUsbIp.localPort(second);
        assertNotEquals(firstPort, secondPort);
        try {
            try (Socket a = authorizedSocket(first, firstPort)) {
                assertEmptyDeviceList(a);
            }
            try (Socket b = authorizedSocket(second, secondPort)) {
                assertEmptyDeviceList(b);
            }
            // Stopping one exporter leaves the other one serving. A session serves
            // exactly one request, so this needs a connection of its own.
            NativeUsbIp.stop(first);
            try (Socket survivor = authorizedSocket(second, secondPort)) {
                assertEmptyDeviceList(survivor);
            }
        } finally {
            NativeUsbIp.stop(first);
            NativeUsbIp.stop(second);
        }
    }

    /** A port authorized on one exporter must not open another exporter. */
    @Test public void authorizationDoesNotLeakBetweenInstances() throws Exception {
        NativeUsbIp.load();
        long first = NativeUsbIp.start();
        long second = NativeUsbIp.start();
        int secondPort = NativeUsbIp.localPort(second);
        Socket reservation = new Socket();
        reservation.setReuseAddress(true);
        reservation.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        int sourcePort = reservation.getLocalPort();
        NativeUsbIp.authorizeLocalConnection(first, sourcePort);
        reservation.close();
        try (Socket foreign = unauthorizedSocket(sourcePort, secondPort)) {
            assertRejected(foreign);
        } finally {
            try {
                // The authorization belongs to the first exporter and is still unused.
                try (Socket owner = unauthorizedSocket(sourcePort, NativeUsbIp.localPort(first))) {
                    assertEmptyDeviceList(owner);
                }
            } finally {
                NativeUsbIp.stop(first);
                NativeUsbIp.stop(second);
            }
        }
    }

    @Test public void unauthorizedLoopbackClientIsRejected() throws Exception {
        NativeUsbIp.load();
        long handle = NativeUsbIp.start();
        try (Socket unauthorized = new Socket("127.0.0.1", NativeUsbIp.localPort(handle))) {
            assertRejected(unauthorized);
        } finally { NativeUsbIp.stop(handle); }
    }

    @Test public void revokedLocalPortCannotBeReused() throws Exception {
        NativeUsbIp.load();
        long handle = NativeUsbIp.start();
        int port = NativeUsbIp.localPort(handle);
        Socket reservation = new Socket();
        reservation.setReuseAddress(true);
        reservation.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        int sourcePort = reservation.getLocalPort();
        NativeUsbIp.authorizeLocalConnection(handle, sourcePort);
        NativeUsbIp.revokeLocalConnection(handle, sourcePort);
        reservation.close();
        try (Socket reused = unauthorizedSocket(sourcePort, port)) {
            assertRejected(reused);
        } finally { NativeUsbIp.stop(handle); }
    }

    @Test public void invalidFdDoesNotPreventRestart() {
        NativeUsbIp.load();
        long handle = NativeUsbIp.start();
        try {
            try { NativeUsbIp.bind(handle, -1); fail("Invalid FD was accepted"); }
            catch (Exception expected) { /* JNI IOException */ }
        } finally { NativeUsbIp.stop(handle); }
        long restarted = NativeUsbIp.start();
        assertTrue(NativeUsbIp.localPort(restarted) > 0);
        NativeUsbIp.stop(restarted);
        NativeUsbIp.stop(restarted);
    }

    @Test public void failedWrapReleasesDuplicatedFd() throws Exception {
        NativeUsbIp.load();
        int before = new File("/proc/self/fd").list().length;
        for (int i = 0; i < 30; ++i) {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            long handle = NativeUsbIp.start();
            try {
                try { NativeUsbIp.bind(handle, pipe[0].getFd()); fail("Pipe accepted as USB"); }
                catch (Exception expected) { /* dup succeeded, USB wrap must fail */ }
            } finally {
                NativeUsbIp.stop(handle);
                pipe[0].close(); pipe[1].close();
            }
        }
        assertTrue("Failed exports leaked FDs", new File("/proc/self/fd").list().length <= before + 4);
    }

    /** A failed export must not leak the exporter that another device still uses. */
    @Test public void failedWrapKeepsOtherInstancesRunning() throws Exception {
        NativeUsbIp.load();
        long keeper = NativeUsbIp.start();
        int keeperPort = NativeUsbIp.localPort(keeper);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        long failing = NativeUsbIp.start();
        try {
            try { NativeUsbIp.bind(failing, pipe[0].getFd()); fail("Pipe accepted as USB"); }
            catch (Exception expected) { /* expected */ }
            NativeUsbIp.stop(failing);
            try (Socket socket = authorizedSocket(keeper, keeperPort)) {
                assertEmptyDeviceList(socket);
            }
        } finally {
            NativeUsbIp.stop(failing);
            NativeUsbIp.stop(keeper);
            pipe[0].close(); pipe[1].close();
        }
    }

    @Test public void stopClosesIdleClient() throws Exception {
        NativeUsbIp.load();
        long handle = NativeUsbIp.start();
        try (Socket socket = authorizedSocket(handle, NativeUsbIp.localPort(handle))) {
            socket.setSoTimeout(3000);
            // A partial request leaves the native receiver waiting for more bytes.
            socket.getOutputStream().write(1);
            NativeUsbIp.stop(handle);
            try { assertEquals(-1, socket.getInputStream().read()); }
            catch (java.net.SocketException reset) { /* reset is also a closed connection */ }
        } finally { NativeUsbIp.stop(handle); }
    }
}
