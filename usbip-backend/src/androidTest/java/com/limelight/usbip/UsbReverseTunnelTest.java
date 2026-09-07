package com.limelight.usbip;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.CertificateFactory;
import java.io.ByteArrayOutputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.*;
import javax.net.ssl.*;

public class UsbReverseTunnelTest {
    /** Opt-in hardware run: diagnostic app owns the authorized physical export. */
    @Test public void physicalExportThroughSunshine() throws Exception {
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String localPort = args.getString("physicalExportPort");
        org.junit.Assume.assumeTrue("Physical export not configured", localPort != null);
        Identity host = new Identity("server"), client = new Identity("client");
        try (UsbReverseTunnel tunnel = new UsbReverseTunnel()) {
            tunnel.start("127.0.0.1", Integer.parseInt(args.getString("sunshineProbePort")),
                    "interop-test-only", args.getString("physicalBusId"), Integer.parseInt(localPort),
                    client.cert, client.key, host.cert).get(15, TimeUnit.SECONDS);
            // Bound window for desktop PnP/driver checks; never leaves a persistent tunnel.
            try { tunnel.completion().get(45, TimeUnit.SECONDS); fail("Physical tunnel ended early"); }
            catch (TimeoutException expected) { }
        }
    }

    /** Optional interoperability run against Sunshine's production reverse_tunnel_probe.
     * Its synthetic importer sends a binary request and only completes attach after a reply.
     */
    @Test public void sunshineProductionServiceInterop() throws Exception {
        String port = InstrumentationRegistry.getArguments().getString("sunshineProbePort");
        org.junit.Assume.assumeTrue("External Sunshine probe not configured", port != null);
        Identity host = new Identity("server"), client = new Identity("client");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket backend = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             UsbReverseTunnel tunnel = new UsbReverseTunnel()) {
            backend.setSoTimeout(10000);
            Future<?> exchange = worker.submit(() -> {
                try (Socket socket = backend.accept()) {
                    socket.setSoTimeout(10000);
                    assertArrayEquals(new byte[]{0, 'I', 'M', 'P', 'O', 'R', 'T', '\n'}, read(socket, 8));
                    socket.getOutputStream().write(new byte[]{'R', 'E', 'P', 'L', 'Y', 0, '\r', '\n'});
                    // Give the host importer time to report attach before client teardown.
                    Thread.sleep(250);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            tunnel.start("127.0.0.1", Integer.parseInt(port), "interop-test-only", "1-9:0", backend.getLocalPort(),
                    client.cert, client.key, host.cert).get(15, TimeUnit.SECONDS);
            exchange.get(15, TimeUnit.SECONDS);
            tunnel.completion().get(5, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }

    private static final class Identity {
        final X509Certificate cert;
        final PrivateKey key;
        Identity(String name) throws Exception {
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open(name + ".crt.der")) {
                cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open(name + ".key.der")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes.toByteArray()));
            }
        }
    }

    private SSLServerSocket server(Identity host, Identity client) throws Exception {
        SSLServerSocket socket = (SSLServerSocket) UsbReverseTunnel.tlsContext(host.cert, host.key, client.cert)
                .getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        socket.setNeedClientAuth(true);
        socket.setSoTimeout(5000);
        return socket;
    }

    private static byte[] read(Socket socket, int count) throws Exception {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int n = socket.getInputStream().read(bytes, offset, count - offset);
            if (n < 0) throw new AssertionError("Early EOF");
            offset += n;
        }
        return bytes;
    }

    @Test public void mutualTlsAndCoalescedBinaryRoundTrip() throws Exception {
        Identity host = new Identity("server"), client = new Identity("client");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        byte[] payload = new byte[256 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        try (SSLServerSocket listener = server(host, client);
             ServerSocket backend = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             UsbReverseTunnel tunnel = new UsbReverseTunnel()) {
            backend.setSoTimeout(5000);
            Future<?> echo = workers.submit(() -> {
                try (Socket socket = backend.accept()) {
                    socket.setSoTimeout(5000);
                    assertArrayEquals(payload, read(socket, payload.length));
                    socket.getOutputStream().write(payload);
                    // Let the host receive all data before ending both pumps.
                    assertEquals(-1, socket.getInputStream().read());
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            Future<?> peer = workers.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    assertArrayEquals(client.cert.getEncoded(), socket.getSession().getPeerCertificates()[0].getEncoded());
                    JSONObject request = new JSONObject(UsbReverseTunnel.readLine(socket.getInputStream()));
                    assertEquals("forward", request.getString("op"));
                    assertEquals("test-token", request.getString("token"));
                    byte[] prefix = "{\"op\":\"ready\"}\n".getBytes("UTF-8");
                    byte[] combined = Arrays.copyOf(prefix, prefix.length + payload.length);
                    System.arraycopy(payload, 0, combined, prefix.length, payload.length);
                    socket.getOutputStream().write(combined);
                    assertArrayEquals(payload, read(socket, payload.length));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            tunnel.start("127.0.0.1", listener.getLocalPort(), "test-token", "1-9:0", backend.getLocalPort(),
                    client.cert, client.key, host.cert).get(10, TimeUnit.SECONDS);
            peer.get(10, TimeUnit.SECONDS);
            echo.get(10, TimeUnit.SECONDS);
            // Peer EOF closes the local backend too.
            tunnel.completion().get(5, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
    }

    @Test public void wrongPinRejectedBeforeJsonOrBackend() throws Exception {
        Identity host = new Identity("server"), client = new Identity("client");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SSLServerSocket listener = server(host, client);
             ServerSocket backend = new ServerSocket(0);
             UsbReverseTunnel tunnel = new UsbReverseTunnel()) {
            Future<?> peer = worker.submit(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(5000);
                    try { socket.startHandshake(); fail("Wrong pin accepted"); }
                    catch (java.io.IOException expected) { }
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            try {
                tunnel.start("127.0.0.1", listener.getLocalPort(), "secret", "1-1", backend.getLocalPort(),
                        client.cert, client.key, client.cert).get(10, TimeUnit.SECONDS);
                fail("Wrong pin accepted");
            } catch (ExecutionException expected) { }
            peer.get(10, TimeUnit.SECONDS);
            backend.setSoTimeout(200);
            try (Socket ignored = backend.accept()) { fail("Backend opened before authentication"); }
            catch (java.net.SocketTimeoutException expected) { }
        } finally { worker.shutdownNow(); }
    }

    @Test public void hostRejectionAndOversizedHandshake() throws Exception {
        for (String response : new String[]{"{\"op\":\"error\",\"reason\":\"unauthorized\"}\n",
                new String(new char[4096]).replace('\0', 'x')}) {
            Identity host = new Identity("server"), client = new Identity("client");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try (SSLServerSocket listener = server(host, client);
                 ServerSocket backend = new ServerSocket(0);
                 UsbReverseTunnel tunnel = new UsbReverseTunnel()) {
                Future<?> peer = worker.submit(() -> {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(5000);
                        UsbReverseTunnel.readLine(socket.getInputStream());
                        socket.getOutputStream().write(response.getBytes("UTF-8"));
                    } catch (Exception error) { throw new RuntimeException(error); }
                });
                try {
                    tunnel.start("127.0.0.1", listener.getLocalPort(), "token", "1-1", backend.getLocalPort(),
                            client.cert, client.key, host.cert).get(10, TimeUnit.SECONDS);
                    fail("Invalid response accepted");
                } catch (ExecutionException expected) { }
                peer.get(10, TimeUnit.SECONDS);
                backend.setSoTimeout(200);
                try (Socket ignored = backend.accept()) { fail("Backend opened after rejection"); }
                catch (java.net.SocketTimeoutException expected) { }
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void closeInterruptsStalledTlsAndIsIdempotent() throws Exception {
        Identity host = new Identity("server"), client = new Identity("client");
        try (ServerSocket listener = new ServerSocket(0);
             UsbReverseTunnel tunnel = new UsbReverseTunnel()) {
            listener.setSoTimeout(5000);
            CompletableFuture<Void> ready = tunnel.start("127.0.0.1", listener.getLocalPort(), "token", "1-1", 3240,
                    client.cert, client.key, host.cert);
            try (Socket ignored = listener.accept()) {
                tunnel.close(); tunnel.close();
                tunnel.completion().get(2, TimeUnit.SECONDS);
                assertTrue(ready.isCompletedExceptionally());
            }
        }
    }
}
