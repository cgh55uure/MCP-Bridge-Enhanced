package com.mcpbridge.enhanced;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.mcpbridge.enhanced.floatwindow.FloatWindowService;
import com.mcpbridge.enhanced.floatwindow.FloatWindowSettingActivity;
import com.mcpbridge.enhanced.keepalive.KeepAliveManager;
import com.mcpbridge.enhanced.tunnel.TunnelService;
import com.mcpbridge.enhanced.tunnel.cloudflare.CloudflareTunnelActivity;
import com.mcpbridge.enhanced.tunnel.cloudflare.CloudflareTunnelService;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText etBoreHost, etLocalPort;
    private TextInputLayout tilBoreHost, tilLocalPort;
    private MaterialButton btnConnect, btnDisconnect, btnFloatSetting, btnCloudflare, btnClearLog;
    private TextView tvStatus, tvStatusIndicator, tvTunnelUrl, tvTunnelType, tvEventLog, tvLogTitle;
    private MaterialCardView cardEventLog;
    private LinearLayout llLogHeader, llLogContent;
    private ChipGroup chipTunnelMode;
    private boolean isTempTunnel = true;

    private boolean isConnected = false;
    private final StringBuilder eventLogBuilder = new StringBuilder(4096);
    private final StringBuilder cfEventLogBuilder = new StringBuilder(4096);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static final int MAX_LOG_LENGTH = 20000;
    private boolean showingBoreLog = true;
    private boolean keepAliveInitialized = false;
    private boolean isLogExpanded = false;

    private com.google.android.material.chip.Chip chipLogBore, chipLogCf;
    private TextView tvLogSource;

    private final BroadcastReceiver tunnelStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TunnelService.ACTION_TUNNEL_STATUS.equals(action)) {
                boolean connected = intent.getBooleanExtra("connected", false);
                String url = intent.getStringExtra("url");
                isConnected = connected;
                updateTunnelStatus(connected, url);
                if (connected) {
                    appendBoreEvent("Bore 隧道已连接: " + url);
                } else {
                    appendBoreEvent("Bore 隧道已断开");
                }
            } else if (TunnelService.ACTION_TUNNEL_EVENT.equals(action)) {
                String event = intent.getStringExtra("event");
                if (event != null) {
                    appendBoreEvent(event);
                }
            } else if (CloudflareTunnelService.ACTION_CF_STATUS.equals(action)) {
                // CF 状态不显示在主面板上，只记录到 CF 日志
                boolean connected = intent.getBooleanExtra("connected", false);
                String url = intent.getStringExtra("url");
                if (connected) {
                    appendCfEvent("Cloudflare 隧道已连接: " + url);
                } else {
                    appendCfEvent("Cloudflare 隧道已断开");
                }
            } else if (CloudflareTunnelService.ACTION_CF_EVENT.equals(action)) {
                String event = intent.getStringExtra("event");
                if (event != null) {
                    appendCfEvent(event);
                }
            }
        }
    };

    private static final String PREF_INPUT = "input_prefs";
    private static final String KEY_SAVED_HOST = "saved_host";
    private static final String KEY_SAVED_PORT = "saved_port";
    private static final String KEY_SAVED_MODE = "saved_mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 设置 Toolbar
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setTitle("隧道桥接");

        initViews();
        loadSavedInput();
        setupListeners();
        checkPermissions();
        registerReceivers();

        // 保存输入变更 - 失去焦点时保存
        etBoreHost.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveInput();
        });
        etLocalPort.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveInput();
        });

        // 轻量级检查：仅读取 SharedPreferences 判断隧道状态，不影响 UI 渲染
        checkTunnelStatus();

        // 保活服务等重型初始化延迟到界面渲染完成后执行
        uiHandler.postDelayed(this::initKeepAlive, 500);
    }

    /**
     * 界面渲染后执行重型初始化（保活服务、悬浮窗等）
     * 延迟执行，避免阻塞 UI 渲染
     */
    private void initKeepAlive() {
        if (keepAliveInitialized) return;
        keepAliveInitialized = true;
        new Thread(() -> {
            KeepAliveManager.getInstance(this).start();
        }).start();
    }

    /**
     * 轻量级检查隧道状态，仅读取 SharedPreferences，不启动任何服务
     */
    private void checkTunnelStatus() {
        // 检查 Bore 隧道状态
        if (TunnelService.isRunning(this)) {
            isConnected = true;
            uiHandler.post(() -> updateTunnelStatus(true, TunnelService.getTunnelUrl(this)));
            List<String> savedLog = TunnelService.getEventLog();
            if (savedLog != null && !savedLog.isEmpty()) {
                for (String event : savedLog) {
                    uiHandler.post(() -> appendBoreEvent(event));
                }
            }
        }

        // 检查 Cloudflare 隧道状态（日志记录到 CF 日志）
        if (CloudflareTunnelService.isRunning(this)) {
            uiHandler.post(() -> appendCfEvent("Cloudflare 隧道运行中: " +
                    (CloudflareTunnelService.getTunnelUrl(this) != null ?
                            CloudflareTunnelService.getTunnelUrl(this) : "等待连接")));
        }
    }

    private void saveInput() {
        String host = etBoreHost.getText().toString().trim();
        String port = etLocalPort.getText().toString().trim();
        getSharedPreferences(PREF_INPUT, MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED_HOST, host)
                .putString(KEY_SAVED_PORT, port)
                .putBoolean(KEY_SAVED_MODE, isTempTunnel)
                .apply();
        // 同步保存到 tunnel 配置，确保悬浮窗和保活使用最新设置
        int localPort;
        try {
            localPort = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            localPort = 8080;
        }
        KeepAliveManager.getInstance(this).saveTunnelConfig(host, localPort);
    }

    private void loadSavedInput() {
        SharedPreferences sp = getSharedPreferences(PREF_INPUT, MODE_PRIVATE);
        String savedHost = sp.getString(KEY_SAVED_HOST, null);
        String savedPort = sp.getString(KEY_SAVED_PORT, null);
        boolean savedMode = sp.getBoolean(KEY_SAVED_MODE, true);

        if (savedHost != null) {
            etBoreHost.setText(savedHost);
        }
        if (savedPort != null) {
            etLocalPort.setText(savedPort);
        }

        // 恢复隧道模式
        isTempTunnel = savedMode;
        if (savedMode) {
            chipTunnelMode.check(R.id.chipTempTunnel);
            tilBoreHost.setHelperText("自动分配公网端口");
            tilBoreHost.setEndIconMode(TextInputLayout.END_ICON_NONE);
        } else {
            chipTunnelMode.check(R.id.chipPermTunnel);
            tilBoreHost.setHelperText("输入你的固定隧道服务器");
            tilBoreHost.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
        }
    }

    private void initViews() {
        etBoreHost = findViewById(R.id.etBoreHost);
        etLocalPort = findViewById(R.id.etLocalPort);
        tilBoreHost = findViewById(R.id.tilBoreHost);
        tilLocalPort = findViewById(R.id.tilLocalPort);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnFloatSetting = findViewById(R.id.btnFloatSetting);
        btnCloudflare = findViewById(R.id.btnCloudflare);
        btnClearLog = findViewById(R.id.btnClearLog);
        tvStatus = findViewById(R.id.tvStatus);
        tvStatusIndicator = findViewById(R.id.tvStatusIndicator);
        tvTunnelUrl = findViewById(R.id.tvTunnelUrl);
        tvTunnelType = findViewById(R.id.tvTunnelType);
        tvEventLog = findViewById(R.id.tvEventLog);
        cardEventLog = findViewById(R.id.cardEventLog);
        chipTunnelMode = findViewById(R.id.chipTunnelMode);
        chipLogBore = findViewById(R.id.chipLogBore);
        chipLogCf = findViewById(R.id.chipLogCf);
        tvLogSource = findViewById(R.id.tvLogSource);
        tvLogTitle = findViewById(R.id.tvLogTitle);
        llLogHeader = findViewById(R.id.llLogHeader);
        llLogContent = findViewById(R.id.llLogContent);
    }

    private void setupListeners() {
        btnConnect.setOnClickListener(v -> connectTunnel());
        btnDisconnect.setOnClickListener(v -> disconnectTunnel());
        btnClearLog.setOnClickListener(v -> {
            if (showingBoreLog) {
                eventLogBuilder.setLength(0);
            } else {
                cfEventLogBuilder.setLength(0);
            }
            tvEventLog.setText("");
            cardEventLog.setVisibility(android.view.View.GONE);
        });

        // 隧道模式切换
        chipTunnelMode.setOnCheckedStateChangeListener((group, checkedIds) -> {
            Chip chip = findViewById(group.getCheckedChipId());
            if (chip == null) return;
            isTempTunnel = chip.getId() == R.id.chipTempTunnel;
            saveInput();
            if (isTempTunnel) {
                // 临时隧道：使用 bore.pub 自动分配端口
                etBoreHost.setText("bore.pub");
                tilBoreHost.setHelperText("自动分配公网端口");
                tilBoreHost.setEndIconMode(TextInputLayout.END_ICON_NONE);
            } else {
                // 永久隧道：使用固定服务器
                etBoreHost.setText("");
                tilBoreHost.setHelperText("输入你的固定隧道服务器");
                tilBoreHost.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT);
            }
        });
        btnFloatSetting.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("需要悬浮窗权限")
                        .setMessage("请在设置中允许本应用显示悬浮窗")
                        .setPositiveButton("去设置", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                startActivity(new Intent(this, FloatWindowSettingActivity.class));
            }
        });

        btnCloudflare.setOnClickListener(v ->
                startActivity(new Intent(this, CloudflareTunnelActivity.class)));

        // 日志切换
        chipLogBore.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                showingBoreLog = true;
                chipLogCf.setChecked(false);
                switchToBoreLog();
            }
        });
        chipLogCf.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                showingBoreLog = false;
                chipLogBore.setChecked(false);
                switchToCfLog();
            }
        });

        // 运行日志折叠/展开
        llLogHeader.setOnClickListener(v -> toggleLogVisibility());
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "如需悬浮窗功能，请授予悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS
            }, 1001);
        }

        // 请求电池优化白名单（保活）
        requestBatteryOptimization();
    }

    /**
     * 请求电池优化白名单 - 让系统不杀后台
     * 参考 SOMCP 的保活策略
     */
    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;

        // 已经加入白名单，跳过
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;

        // 延迟弹出，避免干扰启动流程
        uiHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            new AlertDialog.Builder(this)
                    .setTitle("电池优化")
                    .setMessage("为了保持后台隧道连接稳定，建议将本应用加入电池优化白名单，系统将不会在后台限制本应用的运行。")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + getPackageName())
                        );
                        startActivity(intent);
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        }, 3000);
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter(TunnelService.ACTION_TUNNEL_STATUS);
        filter.addAction(TunnelService.ACTION_TUNNEL_EVENT);
        filter.addAction(CloudflareTunnelService.ACTION_CF_STATUS);
        filter.addAction(CloudflareTunnelService.ACTION_CF_EVENT);
        LocalBroadcastManager.getInstance(this).registerReceiver(tunnelStatusReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(tunnelStatusReceiver);
        // 用户主动退出（back/划掉后台）时停止隧道并清除启动标记，
        // 避免下次进入时保活系统自动重启隧道
        if (isFinishing()) {
            stopTunnelsOnExit();
        }
    }

    /**
     * 用户退出时停止所有隧道并清除标记，防止下次进入时自启动
     */
    private void stopTunnelsOnExit() {
        // 停止 Bore 隧道，清除启动标记（保活系统不会再重启）
        KeepAliveManager.getInstance(this).setTunnelUserStarted(false);
        stopService(new Intent(this, TunnelService.class));

        // 停止 CF 隧道，清除启动标记
        KeepAliveManager.getInstance(this).setCfTunnelUserStarted(false);
        stopService(new Intent(this, CloudflareTunnelService.class));
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveInput();
    }

    private void connectTunnel() {
        String host = etBoreHost.getText().toString().trim();
        String localPortStr = etLocalPort.getText().toString().trim();

        if (host.isEmpty() || localPortStr.isEmpty()) {
            Toast.makeText(this, "请填写服务器地址和本地端口", Toast.LENGTH_SHORT).show();
            return;
        }

        int localPort;
        try {
            localPort = Integer.parseInt(localPortStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口号格式错误", Toast.LENGTH_SHORT).show();
            return;
        }

        // 保存输入
        saveInput();

        // 清空 Bore 旧日志，切换到 Bore 日志视图
        eventLogBuilder.setLength(0);
        tvEventLog.setText("");
        showingBoreLog = true;
        chipLogBore.setChecked(true);
        chipLogCf.setChecked(false);
        cardEventLog.setVisibility(android.view.View.VISIBLE);

        Intent intent = new Intent(this, TunnelService.class);
        intent.putExtra("bore_host", host);
        intent.putExtra("local_port", localPort);

        // 用户主动启动：设置标记，保活时才会自动拉起
        KeepAliveManager.getInstance(this).setTunnelUserStarted(true);

        // 使用 startForegroundService (Android 8+ 要求)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        tvTunnelType.setText("Bore");
        tvStatus.setText("连接中...");
        appendBoreEvent("正在启动隧道 " + host + ":" + localPort + "...");
    }

    private void disconnectTunnel() {
        // 用户主动停止：清除启动标记，保活不再自动拉起
        KeepAliveManager.getInstance(this).setTunnelUserStarted(false);
        stopService(new Intent(this, TunnelService.class));
        isConnected = false;
        updateTunnelStatus(false, null);
        appendBoreEvent("隧道已断开");
    }

    

    private void updateTunnelStatus(boolean connected, String url) {
        isConnected = connected;
        GradientDrawable drawable = (GradientDrawable) tvStatusIndicator.getBackground();

        if (connected) {
            tvStatus.setText("已连接");
            tvStatus.setTextColor(getColor(R.color.status_connected));
            if (drawable != null) drawable.setColor(getColor(R.color.status_connected));
            btnConnect.setEnabled(false);
            btnDisconnect.setEnabled(true);
            if (url != null) {
                tvTunnelUrl.setText("公网地址: " + url);
                tvTunnelUrl.setVisibility(android.view.View.VISIBLE);
            }
        } else {
            tvStatus.setText("未连接");
            tvStatus.setTextColor(getColor(R.color.status_disconnected));
            if (drawable != null) drawable.setColor(getColor(R.color.status_disconnected));
            btnConnect.setEnabled(true);
            btnDisconnect.setEnabled(false);
            tvTunnelUrl.setVisibility(android.view.View.GONE);
            tvTunnelType.setText("");
        }
    }

    /**
     * 启动定时状态检测（每 3 秒刷新一次）
     */
    private void startStatusCheck() {
        statusHandler.removeCallbacks(statusRunnable);
        statusHandler.postDelayed(statusRunnable, 3000);
    }

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            statusHandler.postDelayed(this, 3000);
        }
    };

    // ===== 日志分离 =====

    /** 切换运行日志的折叠/展开状态 */
    private void toggleLogVisibility() {
        isLogExpanded = !isLogExpanded;
        llLogContent.setVisibility(isLogExpanded ? View.VISIBLE : View.GONE);
        tvLogTitle.setText(isLogExpanded ? "▼ 运行日志" : "▶ 运行日志");
    }

    /** 追加 Bore 日志并自动切换到 Bore 日志视图 */
    private void appendBoreEvent(String event) {
        synchronized (eventLogBuilder) {
            if (eventLogBuilder.length() > 0) eventLogBuilder.append("\n");
            eventLogBuilder.append(event);
            // 限制日志长度
            if (eventLogBuilder.length() > MAX_LOG_LENGTH) {
                String trimmed = eventLogBuilder.substring(eventLogBuilder.length() - MAX_LOG_LENGTH);
                eventLogBuilder.setLength(0);
                eventLogBuilder.append(trimmed);
            }
        }
        if (showingBoreLog) {
            tvEventLog.setText(eventLogBuilder.toString());
            cardEventLog.setVisibility(android.view.View.VISIBLE);
        }
        // 更新日志源提示
        updateLogSourceHint();
    }

    /** 追加 CF 日志并自动切换到 CF 日志视图 */
    private void appendCfEvent(String event) {
        synchronized (cfEventLogBuilder) {
            if (cfEventLogBuilder.length() > 0) cfEventLogBuilder.append("\n");
            cfEventLogBuilder.append(event);
            // 限制日志长度
            if (cfEventLogBuilder.length() > MAX_LOG_LENGTH) {
                String trimmed = cfEventLogBuilder.substring(cfEventLogBuilder.length() - MAX_LOG_LENGTH);
                cfEventLogBuilder.setLength(0);
                cfEventLogBuilder.append(trimmed);
            }
        }
        // 新事件到来时自动切换到 CF 日志视图
        if (!showingBoreLog) {
            tvEventLog.setText(cfEventLogBuilder.toString());
            cardEventLog.setVisibility(android.view.View.VISIBLE);
        }
        // 如果有新日志但用户在看 Bore 日志，更新提示
        updateLogSourceHint();
    }

    private void switchToBoreLog() {
        showingBoreLog = true;
        tvEventLog.setText(eventLogBuilder.length() > 0 ? eventLogBuilder.toString() : "");
        if (eventLogBuilder.length() > 0) {
            cardEventLog.setVisibility(android.view.View.VISIBLE);
        }
        updateLogSourceHint();
    }

    private void switchToCfLog() {
        showingBoreLog = false;
        tvEventLog.setText(cfEventLogBuilder.length() > 0 ? cfEventLogBuilder.toString() : "");
        if (cfEventLogBuilder.length() > 0) {
            cardEventLog.setVisibility(android.view.View.VISIBLE);
        }
        updateLogSourceHint();
    }

    private void updateLogSourceHint() {
        if (tvLogSource == null) return;
        if (showingBoreLog) {
            int boreCount = eventLogBuilder.length();
            int cfCount = cfEventLogBuilder.length();
            if (cfCount > 0 && boreCount == 0) {
                tvLogSource.setText("(CF 有日志，点击 CF 查看)");
            } else {
                tvLogSource.setText("");
            }
        } else {
            int boreCount = eventLogBuilder.length();
            int cfCount = cfEventLogBuilder.length();
            if (boreCount > 0 && cfCount == 0) {
                tvLogSource.setText("(Bore 有日志，点击 Bore 查看)");
            } else {
                tvLogSource.setText("");
            }
        }
    }
}