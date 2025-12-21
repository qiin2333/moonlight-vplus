package com.limelight.nvstream;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.Semaphore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.xmlpull.v1.XmlPullParserException;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;

public class NvConnection {
    // Context parameters
    private LimelightCryptoProvider cryptoProvider;
    private String uniqueId;
    private String clientName;
    private ConnectionContext context;
    private static Semaphore connectionAllowed = new Semaphore(1);
    private final boolean isMonkey;
    private final Context appContext;
    private ComputerDetails.AddressTuple host;

    public NvConnection(Context appContext, ComputerDetails.AddressTuple host, int httpsPort, String uniqueId, String pairName, StreamConfiguration config, LimelightCryptoProvider cryptoProvider, X509Certificate serverCert)
    {
        this(appContext, host, httpsPort, uniqueId, pairName, config, cryptoProvider, serverCert, null);
    }
    
    public NvConnection(Context appContext, ComputerDetails.AddressTuple host, int httpsPort, String uniqueId, String pairName, StreamConfiguration config, LimelightCryptoProvider cryptoProvider, X509Certificate serverCert, String displayName)
    {
        this.appContext = appContext;
        this.host = host;
        this.cryptoProvider = cryptoProvider;
        this.uniqueId = uniqueId;
        this.clientName = !pairName.isEmpty() ? pairName : Settings.Global.getString(appContext.getContentResolver(), "device_name");

        this.context = new ConnectionContext();
        this.context.serverAddress = host;
        this.context.httpsPort = httpsPort;
        this.context.streamConfig = config;
        this.context.serverCert = serverCert;
        this.context.displayName = displayName;

        // This is unique per connection
        this.context.riKey = generateRiAesKey();
        this.context.riKeyId = generateRiKeyId();
        
        // 获取设备亮度范围（min, max, maxAverage）
        int[] brightnessRange = getBrightnessRange(appContext);
        this.context.minBrightness = brightnessRange[0];
        this.context.maxBrightness = brightnessRange[1];
        this.context.maxAverageBrightness = brightnessRange[2];

        this.isMonkey = ActivityManager.isUserAMonkey();
    }
    
    /**
      * 获取设备屏幕亮度的最小值、最大值与最大平均亮度（以 nits 为单位）
      * @param context 应用上下文
      * @return 数组，[0]=最小亮度 nits, [1]=最大亮度 nits, [2]=最大平均亮度 nits (用于 HDR 平均亮度参考)
     */
    private static int[] getBrightnessRange(Context context) {
        int minBrightness = 2; // 大多数设备最低亮度约2 nits
        int maxBrightness = 500; // 默认SDR最大亮度约500 nits
        int maxAverageBrightness = 200; // 默认最大平均亮度
        // Try to obtain a Display regardless of whether we have an Activity.
        // Some callers pass the application context, so relying on instanceof Activity
        // causes us to always fall back to defaults.
        if (context == null) {
            LimeLog.info("Device brightness range: " + minBrightness + " - " + maxBrightness + " nits (default, null context)");
            return new int[]{minBrightness, maxBrightness};
        }

        try {
            Object wmObj = context.getSystemService(Context.WINDOW_SERVICE);
            if (wmObj == null) {
                LimeLog.info("WindowManager unavailable, using default brightness range");
                return new int[]{minBrightness, maxBrightness};
            }

            android.view.WindowManager wm = (android.view.WindowManager) wmObj;
            android.view.Display display = wm.getDefaultDisplay();
            if (display == null) {
                LimeLog.info("Display unavailable, using default brightness range");
                return new int[]{minBrightness, maxBrightness};
            }

            // Android 7.0+ 可以获取 HDR 信息（以 nits 为单位）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                android.view.Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();
                if (hdrCaps != null) {
                    float maxLuminance = hdrCaps.getDesiredMaxLuminance();
                    float minLuminance = hdrCaps.getDesiredMinLuminance();
                    float maxAvgLuminance = hdrCaps.getDesiredMaxAverageLuminance();

                    // 有些设备/实现可能返回 0 或 NaN，当值合理时才使用
                    if (Float.isFinite(maxLuminance) && maxLuminance > 0.1f) {
                        // 向上取整以避免过小
                        maxBrightness = Math.max(maxBrightness, (int) Math.ceil(maxLuminance));
                    }
                    if (Float.isFinite(maxAvgLuminance) && maxAvgLuminance > 0.1f) {
                        maxAverageBrightness = Math.max(maxAverageBrightness, (int) Math.ceil(maxAvgLuminance));
                    }
                    if (Float.isFinite(minLuminance) && minLuminance >= 0f) {
                        minBrightness = Math.max(1, (int) Math.floor(minLuminance));
                    }

                    LimeLog.info("HDR capabilities - Max: " + maxLuminance + " nits, Min: " + minLuminance + " nits, MaxAvg: " + maxAvgLuminance + " nits");
                }
            }

            LimeLog.info("Device brightness range: " + minBrightness + " - " + maxBrightness + " nits");
        } catch (Exception e) {
            LimeLog.warning("Failed to get brightness range, using defaults: " + e.getMessage());
            minBrightness = 2;
            maxBrightness = 500;
            maxAverageBrightness = 200;
        }

        return new int[]{minBrightness, maxBrightness, maxAverageBrightness};
    }
    
    private static SecretKey generateRiAesKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");

            // RI keys are 128 bits
            keyGen.init(128);

            return keyGen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
    
    private static int generateRiKeyId() {
        return new SecureRandom().nextInt();
    }

    public void stop() {
        // Interrupt any pending connection. This is thread-safe.
        MoonBridge.interruptConnection();

        // Moonlight-core is not thread-safe with respect to connection start and stop, so
        // we must not invoke that functionality in parallel.
        synchronized (MoonBridge.class) {
            MoonBridge.stopConnection();
            MoonBridge.cleanupBridge();
        }

        // Now a pending connection can be processed
        connectionAllowed.release();
    }

    private InetAddress resolveServerAddress() throws IOException {
        // Try to find an address that works for this host
        InetAddress[] addrs = InetAddress.getAllByName(context.serverAddress.address);
        for (InetAddress addr : addrs) {
            try (Socket s = new Socket()) {
                s.setSoLinger(true, 0);
                s.connect(new InetSocketAddress(addr, context.serverAddress.port), 1000);
                return addr;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // If we made it here, we didn't manage to find a working address. If DNS returned any
        // address, we'll use the first available address and hope for the best.
        if (addrs.length > 0) {
            return addrs[0];
        }
        else {
            throw new IOException("No addresses found for "+context.serverAddress);
        }
    }

    private int detectServerConnectionType() {
        ConnectivityManager connMgr = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = connMgr.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities netCaps = connMgr.getNetworkCapabilities(activeNetwork);
                if (netCaps != null) {
                    if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                            !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                        // VPNs are treated as remote connections
                        return StreamConfiguration.STREAM_CFG_REMOTE;
                    }
                    else if (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        // Cellular is always treated as remote to avoid any possible
                        // issues with 464XLAT or similar technologies.
                        return StreamConfiguration.STREAM_CFG_REMOTE;
                    }
                }

                // Check if the server address is on-link
                LinkProperties linkProperties = connMgr.getLinkProperties(activeNetwork);
                if (linkProperties != null) {
                    InetAddress serverAddress;
                    try {
                        serverAddress = resolveServerAddress();
                    } catch (IOException e) {
                        e.printStackTrace();

                        // We can't decide without being able to resolve the server address
                        return StreamConfiguration.STREAM_CFG_AUTO;
                    }

                    // If the address is in the NAT64 prefix, always treat it as remote
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        IpPrefix nat64Prefix = linkProperties.getNat64Prefix();
                        if (nat64Prefix != null && nat64Prefix.contains(serverAddress)) {
                            return StreamConfiguration.STREAM_CFG_REMOTE;
                        }
                    }

                    for (RouteInfo route : linkProperties.getRoutes()) {
                        // Skip non-unicast routes (which are all we get prior to Android 13)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && route.getType() != RouteInfo.RTN_UNICAST) {
                            continue;
                        }

                        // Find the first route that matches this address
                        if (route.matches(serverAddress)) {
                            // If there's no gateway, this is an on-link destination
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                // We want to use hasGateway() because getGateway() doesn't adhere
                                // to documented behavior of returning null for on-link addresses.
                                if (!route.hasGateway()) {
                                    return StreamConfiguration.STREAM_CFG_LOCAL;
                                }
                            }
                            else {
                                // getGateway() is documented to return null for on-link destinations,
                                // but it actually returns the unspecified address (0.0.0.0 or ::).
                                InetAddress gateway = route.getGateway();
                                if (gateway == null || gateway.isAnyLocalAddress()) {
                                    return StreamConfiguration.STREAM_CFG_LOCAL;
                                }
                            }

                            // We _should_ stop after the first matching route, but for some reason
                            // Android doesn't always report IPv6 routes in descending order of
                            // specificity and metric. To handle that case, we enumerate all matching
                            // routes, assuming that an on-link route will always be preferred.
                        }
                    }
                }
            }
        }
        else {
            NetworkInfo activeNetworkInfo = connMgr.getActiveNetworkInfo();
            if (activeNetworkInfo != null) {
                switch (activeNetworkInfo.getType()) {
                    case ConnectivityManager.TYPE_VPN:
                    case ConnectivityManager.TYPE_MOBILE:
                    case ConnectivityManager.TYPE_MOBILE_DUN:
                    case ConnectivityManager.TYPE_MOBILE_HIPRI:
                    case ConnectivityManager.TYPE_MOBILE_MMS:
                    case ConnectivityManager.TYPE_MOBILE_SUPL:
                    case ConnectivityManager.TYPE_WIMAX:
                        // VPNs and cellular connections are always remote connections
                        return StreamConfiguration.STREAM_CFG_REMOTE;
                }
            }
        }

        // If we can't determine the connection type, let moonlight-common-c decide.
        return StreamConfiguration.STREAM_CFG_AUTO;
    }
    
    private boolean startApp() throws XmlPullParserException, IOException, InterruptedException {
        NvHTTP h = new NvHTTP(context.serverAddress, context.httpsPort, uniqueId, clientName, context.serverCert, cryptoProvider);

        String serverInfo = h.getServerInfo(true);
        
        context.serverAppVersion = h.getServerVersion(serverInfo);
        if (context.serverAppVersion == null) {
            context.connListener.displayMessage("Server version malformed");
            return false;
        }

        ComputerDetails details = h.getComputerDetails(serverInfo);
        context.isNvidiaServerSoftware = details.nvidiaServer;

        // May be missing for older servers
        context.serverGfeVersion = h.getGfeVersion(serverInfo);
                
        if (h.getPairState(serverInfo) != PairingManager.PairState.PAIRED) {
            context.connListener.displayMessage("Device not paired with computer");
            return false;
        }

        context.serverCodecModeSupport = (int)h.getServerCodecModeSupport(serverInfo);

        context.negotiatedHdr = (context.streamConfig.getSupportedVideoFormats() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) != 0;
        if ((context.serverCodecModeSupport & 0x20200) == 0 && context.negotiatedHdr) {
            context.connListener.displayTransientMessage("Your PC GPU does not support streaming HDR. The stream will be SDR.");
            context.negotiatedHdr = false;
        }
        
        //
        // Decide on negotiated stream parameters now
        //
        
        // Check for a supported stream resolution
        if ((context.streamConfig.getReqWidth() > 4096 || context.streamConfig.getReqHeight() > 4096) &&
                (h.getServerCodecModeSupport(serverInfo) & 0x200) == 0 && context.isNvidiaServerSoftware) {
            context.connListener.displayMessage("Your host PC does not support streaming at resolutions above 4K.");
            return false;
        }
        else if ((context.streamConfig.getReqWidth() > 4096 || context.streamConfig.getReqHeight() > 4096) &&
                (context.streamConfig.getSupportedVideoFormats() & ~MoonBridge.VIDEO_FORMAT_MASK_H264) == 0) {
            context.connListener.displayMessage("Your streaming device must support HEVC or AV1 to stream at resolutions above 4K.");
            return false;
        }
        else if (context.streamConfig.getReqHeight() >= 2160 && !h.supports4K(serverInfo)) {
            // Client wants 4K but the server can't do it
            context.connListener.displayTransientMessage("You must update GeForce Experience to stream in 4K. The stream will be 1080p.");
            
            // Lower resolution to 1080p
            context.negotiatedWidth = 1920;
            context.negotiatedHeight = 1080;
        }
        else {
            // Take what the client wanted
            context.negotiatedWidth = context.streamConfig.getWidth();
            context.negotiatedHeight = context.streamConfig.getHeight();
        }

        // We will perform some connection type detection if the caller asked for it
        if (context.streamConfig.getRemote() == StreamConfiguration.STREAM_CFG_AUTO) {
            context.negotiatedRemoteStreaming = detectServerConnectionType();
            context.negotiatedPacketSize =
                    context.negotiatedRemoteStreaming == StreamConfiguration.STREAM_CFG_REMOTE ?
                            1024 : context.streamConfig.getMaxPacketSize();
        }
        else {
            context.negotiatedRemoteStreaming = context.streamConfig.getRemote();
            context.negotiatedPacketSize = context.streamConfig.getMaxPacketSize();
        }
        
        //
        // Video stream format will be decided during the RTSP handshake
        //
        
        NvApp app = context.streamConfig.getApp();
        
        // If the client did not provide an exact app ID, do a lookup with the applist
        if (!context.streamConfig.getApp().isInitialized()) {
            LimeLog.info("Using deprecated app lookup method - Please specify an app ID in your StreamConfiguration instead");
            app = h.getAppByName(context.streamConfig.getApp().getAppName());
            if (app == null) {
                context.connListener.displayMessage("The app " + context.streamConfig.getApp().getAppName() + " is not in GFE app list");
                return false;
            }
        }
        
        // If there's a game running, resume it
        if (h.getCurrentGame(serverInfo) != 0) {
            try {
                if (h.getCurrentGame(serverInfo) == app.getAppId()) {
                    if (!h.launchApp(context, "resume", app.getAppId(), context.negotiatedHdr)) {
                        context.connListener.displayMessage("Failed to resume existing session");
                        return false;
                    }
                } else {
                    return quitAndLaunch(h, context);
                }
            } catch (HostHttpResponseException e) {
                if (e.getErrorCode() == 470) {
                    // This is the error you get when you try to resume a session that's not yours.
                    // Because this is fairly common, we'll display a more detailed message.
                    context.connListener.displayMessage("This session wasn't started by this device," +
                            " so it cannot be resumed. End streaming on the original " +
                            "device or the PC itself and try again. (Error code: "+e.getErrorCode()+")");
                    return false;
                }
                else if (e.getErrorCode() == 525) {
                    context.connListener.displayMessage("The application is minimized. Resume it on the PC manually or " +
                            "quit the session and start streaming again.");
                    return false;
                } else {
                    throw e;
                }
            }
            
            LimeLog.info("Resumed existing game session");
            return true;
        }
        else {
            return launchNotRunningApp(h, context);
        }
    }

    protected boolean quitAndLaunch(NvHTTP h, ConnectionContext context) throws IOException,
            XmlPullParserException, InterruptedException {
        try {
            if (!h.quitApp()) {
                context.connListener.displayMessage("Failed to quit previous session! You must quit it manually");
                return false;
            } 
        } catch (HostHttpResponseException e) {
            if (e.getErrorCode() == 599) {
                context.connListener.displayMessage("This session wasn't started by this device," +
                        " so it cannot be quit. End streaming on the original " +
                        "device or the PC itself. (Error code: "+e.getErrorCode()+")");
                return false;
            }
            else {
                throw e;
            }
        }

        return launchNotRunningApp(h, context);
    }
    
    private boolean launchNotRunningApp(NvHTTP h, ConnectionContext context)
            throws IOException, XmlPullParserException, InterruptedException {
        // Launch the app since it's not running
        if (!h.launchApp(context, "launch", context.streamConfig.getApp().getAppId(), context.negotiatedHdr)) {
            context.connListener.displayMessage("Failed to launch application");
            return false;
        }
        
        LimeLog.info("Launched new game session");
        
        return true;
    }

    public void start(final AudioRenderer audioRenderer, final VideoDecoderRenderer videoDecoderRenderer, final NvConnectionListener connectionListener)
    {
        new Thread(() -> {
            context.connListener = connectionListener;
            context.videoCapabilities = videoDecoderRenderer.getCapabilities();

            String appName = context.streamConfig.getApp().getAppName();

            context.connListener.stageStarting(appName);

            try {
                if (!startApp()) {
                    context.connListener.stageFailed(appName, 0, 0);
                    return;
                }
                context.connListener.stageComplete(appName);
            } catch (HostHttpResponseException e) {
                e.printStackTrace();
                context.connListener.displayMessage(e.getMessage());
                context.connListener.stageFailed(appName, 0, e.getErrorCode());
                return;
            } catch (XmlPullParserException | IOException e) {
                e.printStackTrace();
                context.connListener.displayMessage(e.getMessage());
                context.connListener.stageFailed(appName, MoonBridge.ML_PORT_FLAG_TCP_47984 | MoonBridge.ML_PORT_FLAG_TCP_47989, 0);
                return;
            } catch (InterruptedException e) {
                // Thread was interrupted during app start
                context.connListener.displayMessage("Connection interrupted");
                context.connListener.stageFailed(appName, 0, 0);
                return;
            }

            ByteBuffer ib = ByteBuffer.allocate(16);
            ib.putInt(context.riKeyId);

            // Acquire the connection semaphore to ensure we only have one
            // connection going at once.
            try {
                connectionAllowed.acquire();
            } catch (InterruptedException e) {
                context.connListener.displayMessage(e.getMessage());
                context.connListener.stageFailed(appName, 0, 0);
                return;
            }

            // Moonlight-core is not thread-safe with respect to connection start and stop, so
            // we must not invoke that functionality in parallel.
            synchronized (MoonBridge.class) {
                MoonBridge.setupBridge(videoDecoderRenderer, audioRenderer, connectionListener);
                int ret = MoonBridge.startConnection(context.serverAddress.address,
                        context.serverAppVersion, context.serverGfeVersion, context.rtspSessionUrl,
                        context.serverCodecModeSupport,
                        context.negotiatedWidth, context.negotiatedHeight,
                        context.streamConfig.getRefreshRate(), context.streamConfig.getBitrate(),
                        context.negotiatedPacketSize, context.negotiatedRemoteStreaming,
                        context.streamConfig.getAudioConfiguration().toInt(),
                        context.streamConfig.getSupportedVideoFormats(),
                        context.streamConfig.getClientRefreshRateX100(),
                        context.riKey.getEncoded(), ib.array(),
                        context.videoCapabilities,
                        context.streamConfig.getColorSpace(),
                        context.streamConfig.getColorRange(),
                        context.streamConfig.getEnableMic(),
                        context.streamConfig.getControlOnly());
                if (ret != 0) {
                    // LiStartConnection() failed, so the caller is not expected
                    // to stop the connection themselves. We need to release their
                    // semaphore count for them.
                    connectionAllowed.release();
                    return;
                }
            }
        }).start();
    }
    
    public void sendMouseMove(final short deltaX, final short deltaY)
    {
        if (!isMonkey) {
            MoonBridge.sendMouseMove(deltaX, deltaY);
        }
    }

    public void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight)
    {
        if (!isMonkey) {
            MoonBridge.sendMousePosition(x, y, referenceWidth, referenceHeight);
        }
    }

    public void sendMouseMoveAsMousePosition(short deltaX, short deltaY, short referenceWidth, short referenceHeight)
    {
        if (!isMonkey) {
            MoonBridge.sendMouseMoveAsMousePosition(deltaX, deltaY, referenceWidth, referenceHeight);
        }
    }

    public void sendMouseButtonDown(final byte mouseButton)
    {
        if (!isMonkey) {
            MoonBridge.sendMouseButton(MouseButtonPacket.PRESS_EVENT, mouseButton);
        }
    }
    
    public void sendMouseButtonUp(final byte mouseButton)
    {
        if (!isMonkey) {
            MoonBridge.sendMouseButton(MouseButtonPacket.RELEASE_EVENT, mouseButton);
        }
    }
    
    public void sendControllerInput(final short controllerNumber,
            final short activeGamepadMask, final int buttonFlags,
            final byte leftTrigger, final byte rightTrigger,
            final short leftStickX, final short leftStickY,
            final short rightStickX, final short rightStickY)
    {
        if (!isMonkey) {
            MoonBridge.sendMultiControllerInput(controllerNumber, activeGamepadMask, buttonFlags,
                    leftTrigger, rightTrigger, leftStickX, leftStickY, rightStickX, rightStickY);
        }
    }

    public void sendKeyboardInput(final short keyMap, final byte keyDirection, final byte modifier, final byte flags) {
        if (!isMonkey) {
            MoonBridge.sendKeyboardInput(keyMap, keyDirection, modifier, flags);
        }
    }
    
    public void sendMouseScroll(final byte scrollClicks) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResScroll((short)(scrollClicks * 120)); // WHEEL_DELTA
        }
    }

    public void sendMouseHScroll(final byte scrollClicks) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResHScroll((short)(scrollClicks * 120)); // WHEEL_DELTA
        }
    }

    public void sendMouseHighResScroll(final short scrollAmount) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResScroll(scrollAmount);
        }
    }

    public void sendMouseHighResHScroll(final short scrollAmount) {
        if (!isMonkey) {
            MoonBridge.sendMouseHighResHScroll(scrollAmount);
        }
    }

    public int sendTouchEvent(byte eventType, int pointerId, float x, float y, float pressureOrDistance,
                              float contactAreaMajor, float contactAreaMinor, short rotation) {
        if (!isMonkey) {
            return MoonBridge.sendTouchEvent(eventType, pointerId, x, y, pressureOrDistance,
                    contactAreaMajor, contactAreaMinor, rotation);
        }
        else {
            return MoonBridge.LI_ERR_UNSUPPORTED;
        }
    }

    public int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                            float pressureOrDistance, float contactAreaMajor, float contactAreaMinor,
                            short rotation, byte tilt) {
        if (!isMonkey) {
            return MoonBridge.sendPenEvent(eventType, toolType, penButtons, x, y, pressureOrDistance,
                    contactAreaMajor, contactAreaMinor, rotation, tilt);
        }
        else {
            return MoonBridge.LI_ERR_UNSUPPORTED;
        }
    }

    public int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask, byte type,
                                          int supportedButtonFlags, short capabilities) {
        return MoonBridge.sendControllerArrivalEvent(controllerNumber, activeGamepadMask, type, supportedButtonFlags, capabilities);
    }

    public int sendControllerTouchEvent(byte controllerNumber, byte eventType, int pointerId,
                                        float x, float y, float pressure) {
        if (!isMonkey) {
            return MoonBridge.sendControllerTouchEvent(controllerNumber, eventType, pointerId, x, y, pressure);
        }
        else {
            return MoonBridge.LI_ERR_UNSUPPORTED;
        }
    }

    public int sendControllerMotionEvent(byte controllerNumber, byte motionType,
                                         float x, float y, float z) {
        if (!isMonkey) {
            return MoonBridge.sendControllerMotionEvent(controllerNumber, motionType, x, y, z);
        }
        else {
            return MoonBridge.LI_ERR_UNSUPPORTED;
        }
    }

    public void sendControllerBatteryEvent(byte controllerNumber, byte batteryState, byte batteryPercentage) {
        MoonBridge.sendControllerBatteryEvent(controllerNumber, batteryState, batteryPercentage);
    }

    public void sendUtf8Text(final String text) {
        if (!isMonkey) {
            MoonBridge.sendUtf8Text(text);
        }
    }

    public static String findExternalAddressForMdns(String stunHostname, int stunPort) {
        return MoonBridge.findExternalAddressIP4(stunHostname, stunPort);
    }

    public void doStopAndQuit() throws IOException, XmlPullParserException {
        this.stop();
        new Thread(() -> {
            NvHTTP h;
            try {
                h = new NvHTTP(context.serverAddress, context.httpsPort, uniqueId, clientName, context.serverCert, cryptoProvider);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                h.quitApp();
            } catch (InterruptedException e) {
                // Thread was interrupted during quit, just return
                LimeLog.info("Quit app interrupted");
            } catch (IOException | XmlPullParserException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void sendSuperCmd(String cmdId) throws IOException, XmlPullParserException {
        new Thread(() -> {
            NvHTTP h;
            try {
                h = new NvHTTP(context.serverAddress, context.httpsPort, uniqueId, clientName, context.serverCert, cryptoProvider);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                h.sendSuperCmd(cmdId);
            } catch (InterruptedException e) {
                // Thread was interrupted during super command, just return
                LimeLog.info("Send super command interrupted");
            } catch (IOException | XmlPullParserException e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * 发送码率调整命令
     * @param bitrateKbps 目标码率（kbps）
     * @param callback 回调接口，用于通知结果
     */
    public void setBitrate(int bitrateKbps, BitrateAdjustmentCallback callback) throws IOException, XmlPullParserException {
        new Thread(() -> {
            NvHTTP h;
            try {
                h = new NvHTTP(context.serverAddress, context.httpsPort, uniqueId, clientName, context.serverCert, cryptoProvider);
                LimeLog.info("NvHTTP created successfully for bitrate adjustment");
            } catch (IOException e) {
                // 记录错误但不抛出RuntimeException，避免应用崩溃
                LimeLog.warning("Failed to create NvHTTP for bitrate adjustment: " + e.getMessage());
                if (callback != null) {
                    callback.onFailure("Failed to create HTTP connection: " + e.getMessage());
                }
                return;
            }
            try {
                LimeLog.info("Sending bitrate adjustment request...");
                boolean success = h.setBitrate(bitrateKbps);
                if (success) {
                    // 更新本地配置
                    context.streamConfig.setBitrate(bitrateKbps);
                    LimeLog.info("Bitrate adjustment successful, updated local config to " + bitrateKbps + " kbps");
                    if (callback != null) {
                        callback.onSuccess(bitrateKbps);
                    }
                } else {
                    LimeLog.warning("Bitrate adjustment request failed (server returned false)");
                    if (callback != null) {
                        callback.onFailure("Server returned failure response");
                    }
                }
            } catch (InterruptedException e) {
                // Thread was interrupted during bitrate adjustment
                Thread.currentThread().interrupt(); // Restore interrupt status
                LimeLog.warning("Bitrate adjustment interrupted: " + e.getMessage());
                if (callback != null) {
                    callback.onFailure("Operation interrupted");
                }
            } catch (IOException | XmlPullParserException e) {
                // 记录错误但不抛出RuntimeException，避免应用崩溃
                LimeLog.warning("Failed to set bitrate: " + e.getMessage());
                e.printStackTrace();
                if (callback != null) {
                    callback.onFailure("Network error: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 码率调整回调接口
     */
    public interface BitrateAdjustmentCallback {
        void onSuccess(int newBitrate);
        void onFailure(String errorMessage);
    }

    /**
     * 获取主机地址
     * @return 主机地址字符串
     */
    public String getHost() {
        return context.serverAddress.address;
    }

    /**
     * 获取当前码率
     * @return 当前码率（kbps）
     */
    public int getCurrentBitrate() {
        return context.streamConfig.getBitrate();
    }
}
