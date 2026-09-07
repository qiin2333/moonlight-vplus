package com.limelight.usbip;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import android.util.Log;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** One-shot transport. Ready means byte forwarding, never host attach success.
 * Development configuration only: the owner supplies paired credentials and closes
 * this tunnel before releasing its export. No retries or persistent token storage.
 */
public final class UsbReverseTunnel implements AutoCloseable {
    private static final String TAG = "MoonlightUsbIp";
    private final Object lock = new Object();
    private Socket remote;
    private Socket local;
    private boolean closed;
    private boolean started;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    /** Exact paired leaf certificate match. No system CA or hostname fallback. */
    static SSLContext tlsContext(X509Certificate client, PrivateKey key, X509Certificate pinned)
            throws GeneralSecurityException {
        byte[] expected = pinned.getEncoded();
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        try { store.load(null); }
        catch (IOException error) { throw new GeneralSecurityException(error); }
        store.setKeyEntry("paired-client", key, new char[0], new java.security.cert.Certificate[]{client});
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, new char[0]);
        X509TrustManager trust = new X509TrustManager() {
            private void check(X509Certificate[] chain) throws CertificateException {
                if (chain == null || chain.length == 0 ||
                        !MessageDigest.isEqual(expected, chain[0].getEncoded()))
                    throw new CertificateException("USB tunnel paired certificate mismatch");
            }
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { check(chain); }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException { check(chain); }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), new TrustManager[]{trust}, null);
        return context;
    }

    /** API 28+ prototype; invoke once. Returned future completes when forwarding starts. */
    public CompletableFuture<Void> start(String host, int port, String token, UsbIpBackend.Export export,
            X509Certificate client, PrivateKey key, X509Certificate pinned) {
        return startInternal(host, port, token, export.busId, export.port, client, key, pinned, true);
    }

    // Package-visible endpoint form is used by transport tests without claiming a USB device.
    CompletableFuture<Void> start(String host, int port, String token, String busId, int localPort,
            X509Certificate client, PrivateKey key, X509Certificate pinned) {
        return startInternal(host, port, token, busId, localPort, client, key, pinned, false);
    }

    private CompletableFuture<Void> startInternal(String host, int port, String token, String busId, int localPort,
            X509Certificate client, PrivateKey key, X509Certificate pinned, boolean authorizeLocal) {
        if (host == null || host.isEmpty() || port < 1 || port > 65535 || localPort < 1 || localPort > 65535
                || token == null || token.isEmpty() || busId == null || !busId.matches("[A-Za-z0-9.:-]{1,31}")
                || client == null || key == null || pinned == null)
            throw new IllegalArgumentException("Invalid USB tunnel configuration");
        synchronized (lock) {
            if (started || closed) throw new IllegalStateException("Tunnel already started or closed");
            started = true;
        }
        new Thread(() -> run(host, port, token, busId, localPort, client, key, pinned, authorizeLocal), "UsbTunnelConnect").start();
        return ready;
    }

    public CompletableFuture<Void> completion() { return completion; }

    private void run(String host, int port, String token, String busId, int localPort,
            X509Certificate client, PrivateKey key, X509Certificate pinned, boolean authorizeLocal) {
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
        deadline.schedule(() -> finish(new IOException("USB tunnel startup timed out")), 15, TimeUnit.SECONDS);
        try {
            Log.i(TAG, "tunnel connecting to " + host + ":" + port + " for busid=" + busId);
            byte[] request = (new JSONObject().put("op", "forward").put("token", token)
                    .put("busid", busId).toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (request.length > 4096) throw new IOException("USB tunnel handshake too large");
            SSLSocket tls = (SSLSocket) tlsContext(client, key, pinned).getSocketFactory().createSocket();
            register(tls, true);
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});
            tls.setTcpNoDelay(true);
            tls.connect(new InetSocketAddress(host, port), 15000);
            tls.setSoTimeout(15000);
            tls.startHandshake();
            Log.i(TAG, "tunnel TLS established");
            tls.getOutputStream().write(request);
            JSONObject response = new JSONObject(readLine(tls.getInputStream()));
            Log.i(TAG, "tunnel host response=" + response);
            if (!"ready".equals(response.optString("op")))
                throw new IOException("USB tunnel host rejected forwarding");
            Socket backend = new Socket();
            register(backend, false);
            backend.setTcpNoDelay(true);
            if (authorizeLocal) {
                backend.bind(new InetSocketAddress("127.0.0.1", 0));
                NativeUsbIp.authorizeLocalConnection(backend.getLocalPort());
            }
            backend.connect(new InetSocketAddress("127.0.0.1", localPort), 5000);
            Log.i(TAG, "host ready; local exporter connected");
            tls.setSoTimeout(0);
            // Cancel only after both sockets are usable. The deadline also closes blocked writes.
            deadline.shutdownNow();
            synchronized (lock) {
                if (closed) throw new IOException("USB tunnel closed");
            }
            ready.complete(null);
            Thread upstream = new Thread(() -> pump(backend, tls), "UsbTunnelUp");
            upstream.start();
            pump(tls, backend);
            upstream.join();
        } catch (Exception error) {
            Log.e(TAG, "reverse tunnel failed", error);
            finish(error);
        } finally {
            deadline.shutdownNow();
        }
    }

    private void register(Socket socket, boolean isRemote) throws IOException {
        synchronized (lock) {
            if (closed) { socket.close(); throw new IOException("USB tunnel closed"); }
            if (isRemote) remote = socket; else local = socket;
        }
    }

    // Read exactly through LF so a coalesced first USB/IP packet is never discarded.
    static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 4096; i++) {
            int value = input.read();
            if (value < 0) throw new IOException("USB tunnel handshake EOF");
            if (value == '\n') return bytes.toString("UTF-8");
            bytes.write(value);
        }
        throw new IOException("USB tunnel handshake too large");
    }

    private void pump(Socket source, Socket destination) {
        try {
            byte[] buffer = new byte[64 * 1024];
            InputStream input = source.getInputStream();
            OutputStream output = destination.getOutputStream();
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            finish(null);
        } catch (IOException error) { finish(error); }
    }

    private void finish(Throwable error) {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            closeSocket(remote);
            closeSocket(local);
        }
        ready.completeExceptionally(error != null ? error : new IOException("USB tunnel closed before ready"));
        if (error == null) completion.complete(null); else completion.completeExceptionally(error);
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    /** Cancels startup or active forwarding; a replacement must use a new instance. */
    @Override public void close() { finish(null); }
}
