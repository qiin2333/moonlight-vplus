package com.limelight.preferences;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;

import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

public class AddComputerManually extends Activity {
    private TextView hostText;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final LinkedBlockingQueue<String> computersToAdd = new LinkedBlockingQueue<>();
    private Thread addThread;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, final IBinder binder) {
            managerBinder = ((ComputerManagerService.ComputerManagerBinder)binder);
            startAddThread();
        }

        public void onServiceDisconnected(ComponentName className) {
            joinAddThread();
            managerBinder = null;
        }
    };

    /**
     * 检测是否为IPv6地址
     */
    private boolean isIPv6Address(String address) {
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            // IPv6地址的字节长度为16，IPv4为4
            return inetAddress.getAddress().length == 16;
        } catch (Exception e) {
            // 如果无法解析，通过格式判断（包含多个冒号）
            int colonCount = 0;
            for (char c : address.toCharArray()) {
                if (c == ':') colonCount++;
            }
            return colonCount >= 2;
        }
    }

    private boolean isWrongSubnetSiteLocalAddress(String address) {
        try {
            InetAddress targetAddress = InetAddress.getByName(address);
            if (!(targetAddress instanceof Inet4Address) || !targetAddress.isSiteLocalAddress()) {
                return false;
            }

            // We have a site-local address. Look for a matching local interface.
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (!(addr.getAddress() instanceof Inet4Address) || !addr.getAddress().isSiteLocalAddress()) {
                        // Skip non-site-local or non-IPv4 addresses
                        continue;
                    }

                    byte[] targetAddrBytes = targetAddress.getAddress();
                    byte[] ifaceAddrBytes = addr.getAddress().getAddress();

                    // Compare prefix to ensure it's the same
                    boolean addressMatches = true;
                    for (int i = 0; i < addr.getNetworkPrefixLength(); i++) {
                        if ((ifaceAddrBytes[i / 8] & (1 << (i % 8))) != (targetAddrBytes[i / 8] & (1 << (i % 8)))) {
                            addressMatches = false;
                            break;
                        }
                    }

                    if (addressMatches) {
                        return false;
                    }
                }
            }

            // Couldn't find a matching interface
            return true;
        } catch (Exception e) {
            // Catch all exceptions because some broken Android devices
            // will throw an NPE from inside getNetworkInterfaces().
            e.printStackTrace();
            return false;
        }
    }

    private URI parseRawUserInputToUri(String rawUserInput) {
        // 检测是否可能是IPv6地址（包含多个冒号）
        // IPv6地址至少有2个冒号，例如 ::1 或 fe80::1
        int colonCount = 0;
        for (char c : rawUserInput.toCharArray()) {
            if (c == ':') colonCount++;
        }
        
        // 如果有多个冒号，很可能是IPv6地址
        boolean likelyIPv6 = colonCount >= 2;
        
        try {
            // Try adding a scheme and parsing the remaining input.
            // This handles input like 127.0.0.1:47989, [::1], [::1]:47989, and 127.0.0.1.
            URI uri = new URI("moonlight://" + rawUserInput);
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {}

        // 如果第一次解析失败，且输入看起来像IPv6地址
        if (likelyIPv6 && !rawUserInput.startsWith("[")) {
            try {
                // 尝试智能添加中括号
                // 情况1: 纯IPv6地址，如 "::1" 或 "2001:0db8::1"
                // 情况2: IPv6地址+端口，如 "::1:47989" 或 "2001:0db8::1:47989"
                
                // 查找最后一个冒号，可能是端口分隔符
                int lastColonIndex = rawUserInput.lastIndexOf(':');
                
                // 尝试判断最后部分是否是端口号（纯数字）
                boolean hasPort = false;
                if (lastColonIndex > 0 && lastColonIndex < rawUserInput.length() - 1) {
                    String possiblePort = rawUserInput.substring(lastColonIndex + 1);
                    try {
                        int port = Integer.parseInt(possiblePort);
                        // 合理的端口范围
                        if (port > 0 && port <= 65535) {
                            hasPort = true;
                        }
                    } catch (NumberFormatException e) {
                        // 不是数字，不是端口
                    }
                }
                
                String addressWithBrackets;
                if (hasPort) {
                    // 将地址部分用中括号括起来，保留端口
                    // "2001:0db8::1:47989" -> "[2001:0db8::1]:47989"
                    String address = rawUserInput.substring(0, lastColonIndex);
                    String port = rawUserInput.substring(lastColonIndex + 1);
                    addressWithBrackets = "[" + address + "]:" + port;
                } else {
                    // 整个地址用中括号括起来
                    // "2001:0db8::1" -> "[2001:0db8::1]"
                    addressWithBrackets = "[" + rawUserInput + "]";
                }
                
                URI uri = new URI("moonlight://" + addressWithBrackets);
                if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                    return uri;
                }
            } catch (URISyntaxException ignored) {}
        }
        
        // 最后的回退：尝试简单地添加中括号（处理边缘情况）
        if (!rawUserInput.startsWith("[")) {
            try {
                // Attempt to escape the input as an IPv6 literal.
                // This handles input like ::1.
                URI uri = new URI("moonlight://[" + rawUserInput + "]");
                if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                    return uri;
                }
            } catch (URISyntaxException ignored) {}
        }

        return null;
    }

    private void doAddPc(String rawUserInput) throws InterruptedException {
        boolean wrongSiteLocal = false;
        boolean invalidInput = false;
        boolean success;
        int portTestResult;
        String hostAddress = null; // 保存主机地址用于后续判断
        boolean isIPv6 = false; // 标记是否为IPv6地址

        SpinnerDialog dialog = SpinnerDialog.displayDialog(this, getResources().getString(R.string.title_add_pc),
            getResources().getString(R.string.msg_add_pc), false);

        try {
            ComputerDetails details = new ComputerDetails();

            // Check if we parsed a host address successfully
            URI uri = parseRawUserInputToUri(rawUserInput);
            if (uri != null && uri.getHost() != null && !uri.getHost().isEmpty()) {
                String host = uri.getHost();
                int port = uri.getPort();

                // 保存主机地址并检测是否为IPv6
                hostAddress = host;
                isIPv6 = isIPv6Address(host);

                // If a port was not specified, use the default
                if (port == -1) {
                    port = NvHTTP.DEFAULT_HTTP_PORT;
                }

                details.manualAddress = new ComputerDetails.AddressTuple(host, port);
                success = managerBinder.addComputerBlocking(details);
                if (!success){
                    wrongSiteLocal = isWrongSubnetSiteLocalAddress(host);
                }
            } else {
                // Invalid user input
                success = false;
                invalidInput = true;
            }
        } catch (InterruptedException e) {
            // Propagate the InterruptedException to the caller for proper handling
            dialog.dismiss();
            throw e;
        } catch (IllegalArgumentException e) {
            // This can be thrown from OkHttp if the host fails to canonicalize to a valid name.
            // https://github.com/square/okhttp/blob/okhttp_27/okhttp/src/main/java/com/squareup/okhttp/HttpUrl.java#L705
            e.printStackTrace();
            success = false;
            invalidInput = true;
        }

        // Keep the SpinnerDialog open while testing connectivity
        if (!success && !wrongSiteLocal && !invalidInput) {
            // Run the test before dismissing the spinner because it can take a few seconds.
            portTestResult = MoonBridge.testClientConnectivity(ServerHelper.CONNECTION_TEST_SERVER, 443,
                    MoonBridge.ML_PORT_FLAG_TCP_47984 | MoonBridge.ML_PORT_FLAG_TCP_47989);
        } else {
            // Don't bother with the test if we succeeded or the IP address was bogus
            portTestResult = MoonBridge.ML_TEST_RESULT_INCONCLUSIVE;
        }

        dialog.dismiss();

        if (invalidInput) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_unknown_host), false);
        }
        else if (wrongSiteLocal) {
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), getResources().getString(R.string.addpc_wrong_sitelocal), false);
        }
        else if (!success) {
            String dialogText;
            if (portTestResult != MoonBridge.ML_TEST_RESULT_INCONCLUSIVE && portTestResult != 0)  {
                dialogText = getResources().getString(R.string.nettest_text_blocked);
            }
            else {
                dialogText = getResources().getString(R.string.addpc_fail);
            }
            
            // 如果是IPv6地址连接失败，添加防火墙提示
            if (isIPv6) {
                dialogText += "\n\n提示：如果您使用的是IPv6地址，请检查：\n" +
                             "1. 光猫防火墙是否放行了IPv6流量\n" +
                             "2. 路由器是否启用了IPv6端口转发\n" +
                             "3. 目标主机的IPv6防火墙设置";
            }
            
            Dialog.displayDialog(this, getResources().getString(R.string.conn_error_title), dialogText, false);
        }
        else {
            AddComputerManually.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                Toast.makeText(AddComputerManually.this, getResources().getString(R.string.addpc_success), Toast.LENGTH_LONG).show();

                if (!isFinishing()) {
                    // Close the activity
                    AddComputerManually.this.finish();
                }
                }
            });
        }

    }

    private void startAddThread() {
        addThread = new Thread() {
            @Override
            public void run() {
                while (!isInterrupted()) {
                    try {
                        String computer = computersToAdd.take();
                        doAddPc(computer);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        };
        addThread.setName("UI - AddComputerManually");
        addThread.start();
    }

    private void joinAddThread() {
        if (addThread != null) {
            addThread.interrupt();

            try {
                addThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }

            addThread = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
        SpinnerDialog.closeDialogs(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            joinAddThread();
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_add_computer_manually);

        UiHelper.notifyNewRootView(this);

        this.hostText = findViewById(R.id.hostTextView);
        hostText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        hostText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int actionId, KeyEvent keyEvent) {
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                        (keyEvent != null &&
                                keyEvent.getAction() == KeyEvent.ACTION_DOWN &&
                                keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    return handleDoneEvent();
                }
                else if (actionId == EditorInfo.IME_ACTION_PREVIOUS) {
                    // This is how the Fire TV dismisses the keyboard
                    InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(hostText.getWindowToken(), 0);
                    return false;
                }

                return false;
            }
        });

        findViewById(R.id.addPcButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                handleDoneEvent();
            }
        });

        // Bind to the ComputerManager service
        bindService(new Intent(AddComputerManually.this,
                    ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    // Returns true if the event should be eaten
    private boolean handleDoneEvent() {
        String hostAddress = hostText.getText().toString().trim();

        if (hostAddress.length() == 0) {
            Toast.makeText(AddComputerManually.this, getResources().getString(R.string.addpc_enter_ip), Toast.LENGTH_LONG).show();
            return true;
        }

        computersToAdd.add(hostAddress);
        return false;
    }
}
