package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class to manage and run an iperf3 network test with a custom UI.
 * This class loads the iperf3 binary from the app's native library directory
 * to comply with modern Android security policies (SELinux).
 * It includes features like command injection prevention, output translation,
 * auto-scrolling, and a final summary of results.
 */
public class Iperf3Tester {

    private final Context context;
    private final String defaultServerAddress;
    private volatile Process iperfProcess;

    // A whitelist of allowed iperf3 arguments for security reasons
    private static final Set<String> ALLOWED_IPERF_ARGS = new HashSet<>(Arrays.asList(
            "-f", "--format",
            "-i", "--interval",
            "-J", "--json",
            "-P", "--parallel",
            "-w", "--window",
            "-M", "--set-mss",
            "-N", "--no-delay",
            "-V", "--version",
            "-l", "--len",
            "-Z", "--zerocopy",
            "-O", "--omit",
            "-T", "--title",
            "-C", "--congestion",
            "-k", "--blockcount"
    ));

    // UI elements
    private EditText serverInput, portEditText, udpBandwidthEditText, rawArgsInput;
    private RadioGroup directionRadioGroup;
    private RadioButton downloadRadioButton;
    private SeekBar durationSeekBar;
    private TextView durationValueTextView, outputView;
    private CheckBox udpCheckBox;
    private LinearLayout udpBandwidthLayout;
    private ScrollView outputScrollView;

    private final List<String> allOutputLines = new ArrayList<>();

    public Iperf3Tester(Context context, String defaultServerAddress) {
        this.context = context;
        this.defaultServerAddress = defaultServerAddress;
    }

    public void show() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.Iperf3DialogTheme);
        builder.setTitle("iPerf3 Network Test");

        LayoutInflater inflater = LayoutInflater.from(context);
        View dialogView = inflater.inflate(R.layout.dialog_iperf3_test, null);
        builder.setView(dialogView);

        initializeUiElements(dialogView);
        setupUiListeners();

        if (!TextUtils.isEmpty(defaultServerAddress)) {
            serverInput.setText(defaultServerAddress);
        }

        builder.setPositiveButton("Start", null);
        builder.setNegativeButton("Stop", null);
        builder.setNeutralButton("Close", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(dialogInterface -> killProcess());
        dialog.setOnShowListener(dialogInterface -> {
            Button startButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button stopButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            stopButton.setEnabled(false);

            startButton.setOnClickListener(v -> executeTest(startButton, stopButton));
            stopButton.setOnClickListener(v -> killProcess());
        });

        dialog.show();
    }

    private void initializeUiElements(View view) {
        serverInput = view.findViewById(R.id.edit_text_server_ip);
        directionRadioGroup = view.findViewById(R.id.radio_group_direction);
        downloadRadioButton = view.findViewById(R.id.radio_download);
        durationSeekBar = view.findViewById(R.id.seek_bar_duration);
        durationValueTextView = view.findViewById(R.id.text_view_duration_value);
        portEditText = view.findViewById(R.id.edit_text_port);
        udpCheckBox = view.findViewById(R.id.checkbox_udp);
        udpBandwidthLayout = view.findViewById(R.id.layout_udp_bandwidth);
        udpBandwidthEditText = view.findViewById(R.id.edit_text_udp_bandwidth);
        rawArgsInput = view.findViewById(R.id.edit_text_raw_args);
        outputScrollView = view.findViewById(R.id.output_scroll_view);
        outputView = view.findViewById(R.id.text_view_iperf3_output);
    }

    private void setupUiListeners() {
        durationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                durationValueTextView.setText(String.format("%ds", Math.max(1, progress)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        durationValueTextView.setText(String.format("%ds", durationSeekBar.getProgress()));

        udpCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                udpBandwidthLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE));
    }

    private void executeTest(Button startButton, Button stopButton) {
        if (!validateInputs()) return;

        killProcess();
        allOutputLines.clear();
        outputView.setText("");

        setUiState(false, startButton, stopButton);

        new Thread(() -> {
            try {
                String iperfPath = getIperfPath();
                ArrayList<String> command = buildCommand(iperfPath);

                appendOutput("正在执行: " + TextUtils.join(" ", command) + "\n\n");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                iperfProcess = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(iperfProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        allOutputLines.add(line);
                        appendOutput(translateIperfOutput(line) + "\n");
                    }
                }

                int exitCode = iperfProcess.waitFor();
                appendOutput("\n--- 测试完成 (退出码: " + exitCode + ") ---\n");
                parseAndDisplaySummary();

            } catch (IOException e) {
                e.printStackTrace();
                appendOutput("\n错误: " + e.getMessage() + "\n请确保 iPerf3 服务器已在PC上运行，并且防火墙已正确配置。\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                appendOutput("\n--- 测试已中断 ---\n");
            } finally {
                iperfProcess = null;
                setUiState(true, startButton, stopButton);
            }
        }).start();
    }

    private boolean validateInputs() {
        String serverIp = serverInput.getText().toString().trim();
        if (serverIp.isEmpty() || serverIp.matches(".*[;&|`<>\\$\\(\\)].*")) {
            Toast.makeText(context, "无效的服务器地址", Toast.LENGTH_SHORT).show();
            return false;
        }
        String portStr = portEditText.getText().toString().trim();
        if (!portStr.matches("\\d+") || Integer.parseInt(portStr) > 65535) {
            Toast.makeText(context, "无效的端口号 (1-65535)", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private String getIperfPath() throws IOException {
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        String iperfPath = nativeLibraryDir + "/libiperf3.so";
        File iperfFile = new File(iperfPath);
        if (!iperfFile.exists()) {
            throw new IOException("iPerf3 二进制文件未在原生库目录中找到。请确保 'libiperf3.so' 位于 'jniLibs/arm64-v8a' 文件夹中。");
        }
        return iperfPath;
    }

    private ArrayList<String> buildCommand(String iperfPath) {
        ArrayList<String> command = new ArrayList<>();
        command.add(iperfPath);
        command.add("-c");
        command.add(serverInput.getText().toString().trim());
        command.add("-p");
        command.add(portEditText.getText().toString().trim());

        if (downloadRadioButton.isChecked()) command.add("-R");

        command.add("-t");
        command.add(String.valueOf(durationSeekBar.getProgress()));

        if (udpCheckBox.isChecked()) {
            command.add("-u");
            String bandwidth = udpBandwidthEditText.getText().toString().trim();
            if (!bandwidth.isEmpty()) {
                command.add("-b");
                command.add(bandwidth);
            } else {
                appendOutput("警告: UDP 测试需要指定带宽, 使用默认值 1M。\n");
                command.add("-b");
                command.add("1M");
            }
        }

        // Sanitize and add raw arguments
        String rawArgs = rawArgsInput.getText().toString().trim();
        if (!rawArgs.isEmpty()) {
            Set<String> managedArgs = new HashSet<>(Arrays.asList("-p", "--port", "-R", "--reverse", "-t", "--time", "-u", "--udp", "-b", "--bandwidth"));
            List<String> rawArgsList = Arrays.asList(rawArgs.split("\\s+"));
            List<String> filteredArgs = new ArrayList<>();
            List<String> ignoredArgs = new ArrayList<>();

            for (int i = 0; i < rawArgsList.size(); i++) {
                String currentArg = rawArgsList.get(i);
                if (managedArgs.contains(currentArg.toLowerCase())) {
                    ignoredArgs.add(currentArg);
                    if (i + 1 < rawArgsList.size() && !rawArgsList.get(i + 1).startsWith("-")) {
                        ignoredArgs.add(rawArgsList.get(i + 1));
                        i++;
                    }
                } else if (ALLOWED_IPERF_ARGS.contains(currentArg.toLowerCase())) {
                    filteredArgs.add(currentArg);
                } else {
                    ignoredArgs.add(currentArg); // Not in whitelist
                }
            }

            if (!filteredArgs.isEmpty()) command.addAll(filteredArgs);
            if (!ignoredArgs.isEmpty()) {
                appendOutput("提示: 为避免冲突或安全风险，已自动忽略以下参数: " + TextUtils.join(" ", ignoredArgs) + "\n");
            }
        }
        return command;
    }

    private void parseAndDisplaySummary() {
        // 根据用户在UI上的选择，直接确定测试方向的中文名称。
        final String direction = downloadRadioButton.isChecked() ? "下载" : "上传";

        // TCP 总结行正则表达式
        Pattern tcpPattern = Pattern.compile(
                "\\[\\s*\\d+\\s*\\]\\s+[\\d.-]+\\s+sec\\s+.*\\s+([\\d.]+)\\s+(Mbits/sec|Gbits/sec|Kbits/sec|bits/sec)"
        );

        // UDP 总结行正则表达式
        Pattern udpPattern = Pattern.compile(
                "\\[\\s*\\d+\\s*\\]\\s+[\\d.-]+\\s+sec\\s+.*\\s+" + // Interval and Transfer
                        "([\\d.]+)\\s+(Mbits/sec|Gbits/sec|Kbits/sec|bits/sec)\\s+" + // Bitrate
                        "([\\d.]+)\\s+(ms)\\s+" + // Jitter
                        "(\\d+)\\s*/\\s*(\\d+)\\s+\\(([\\d.]+)%\\)" // Lost/Total Datagrams
        );

        String summaryResult = "";
        boolean foundSummary = false;

        // 从后往前遍历所有原始输出行
        for (int i = allOutputLines.size() - 1; i >= 0; i--) {
            if (foundSummary) break;

            String line = allOutputLines.get(i);
            // 我们只需要找到包含 "sec" 和 "Mbits/sec" (或类似单位) 的总结行即可
            if (!line.contains(" sec ") || !line.contains("bits/sec")) {
                continue;
            }

            if (udpCheckBox.isChecked()) {
                // --- UDP 测试结果解析 ---
                Matcher udpMatcher = udpPattern.matcher(line);
                if (udpMatcher.find()) {
                    String bitrateValue = udpMatcher.group(1);
                    String bitrateUnit = udpMatcher.group(2);
                    String jitterValue = udpMatcher.group(3);
                    String lostPackets = udpMatcher.group(5);
                    String totalPackets = udpMatcher.group(6);
                    String lossPercent = udpMatcher.group(7);

                    summaryResult = String.format(
                            "\n========================================\n" +
                                    "             测试结果总结\n" +
                                    "----------------------------------------\n" +
                                    " 平均%s带宽: %s %s\n" +
                                    "           抖动: %s ms\n" +
                                    "           丢包: %s / %s (%s%%)\n" +
                                    "========================================\n",
                            direction, bitrateValue, bitrateUnit, jitterValue, lostPackets, totalPackets, lossPercent
                    );
                    foundSummary = true;
                }

            } else {
                // --- TCP 测试结果解析 ---
                Matcher tcpMatcher = tcpPattern.matcher(line);
                if (tcpMatcher.find()) {
                    String bitrateValue = tcpMatcher.group(1);
                    String bitrateUnit = tcpMatcher.group(2);

                    summaryResult = String.format(
                            "\n========================================\n" +
                                    "             测试结果总结\n" +
                                    "----------------------------------------\n" +
                                    " 平均%s带宽: %s %s\n" +
                                    "========================================\n",
                            direction, bitrateValue, bitrateUnit
                    );
                    foundSummary = true;
                }
            }
        }

        if (!summaryResult.isEmpty()) {
            appendOutput(summaryResult);
        } else {
            appendOutput("\n未能自动解析最终结果，请查看以上详细日志。\n");
        }
    }

    private String translateIperfOutput(String originalLine) {
        if (originalLine.contains("Connecting to host"))
            return originalLine.replace("Connecting to host", "正在连接到主机");
        if (originalLine.contains("local") && originalLine.contains("port"))
            return originalLine.replace("local", "本地").replace("port", "端口");
        if (originalLine.contains("Interval"))
            return originalLine.replace("Interval", "时间段").replace("Transfer", "传输量").replace("Bitrate", "比特率").replace("Bandwidth", "带宽").replace("Retr", "重传").replace("Jitter", "抖动").replace("Lost/Total", "丢包/总计").replace("Datagrams", "数据报");
        if (originalLine.contains("sender")) return originalLine.replace("sender", "发送方");
        if (originalLine.contains("receiver")) return originalLine.replace("receiver", "接收方");
        if (originalLine.contains("iperf Done.")) return "iPerf3 测试完毕。";
        if (originalLine.contains("unable to connect to server"))
            return "错误：无法连接到服务器。请检查IP地址和端口是否正确，以及服务器是否正在运行。";
        if (originalLine.contains("interrupt - the client has terminated"))
            return "中断 - 客户端已终止。";
        return originalLine;
    }

    private void killProcess() {
        if (iperfProcess != null) {
            iperfProcess.destroy();
            iperfProcess = null;
        }
    }

    private void setUiState(boolean isEnabled, Button startButton, Button stopButton) {
        runOnUiThread(() -> {
            startButton.setEnabled(isEnabled);
            stopButton.setEnabled(!isEnabled);
            serverInput.setEnabled(isEnabled);
            directionRadioGroup.setEnabled(isEnabled);
            for (int i = 0; i < directionRadioGroup.getChildCount(); i++) {
                directionRadioGroup.getChildAt(i).setEnabled(isEnabled);
            }
            durationSeekBar.setEnabled(isEnabled);
            portEditText.setEnabled(isEnabled);
            udpCheckBox.setEnabled(isEnabled);
            udpBandwidthEditText.setEnabled(isEnabled);
            rawArgsInput.setEnabled(isEnabled);
        });
    }

    private void appendOutput(final String text) {
        runOnUiThread(() -> {
            outputView.append(text);
            outputScrollView.post(() -> outputScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void runOnUiThread(Runnable action) {
        if (context instanceof Activity) {
            ((Activity) context).runOnUiThread(action);
        }
    }
}