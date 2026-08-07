package com.mcpbridge.enhanced.tunnel.cloudflare;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.mcpbridge.enhanced.R;
import com.mcpbridge.enhanced.keepalive.KeepAliveManager;

public class CloudflareTunnelActivity extends AppCompatActivity {

    private RadioGroup rgMode;
    private RadioButton rbQuick, rbPermanent;
    private TextInputLayout tilToken;
    private TextInputEditText etToken, etCfLocalPort;
    private MaterialButton btnConnect, btnDisconnect, btnCopyUrl, btnClearLog;
    private TextView tvStatus, tvUrl, tvEventLog;
    private MaterialCardView cardEventLog;
    private View cardUrlLayout;

    private boolean isConnected = false;
    private final StringBuilder eventLogBuilder = new StringBuilder(4096);
    private final StringBuilder eventBuffer = new StringBuilder(1024);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean eventFlushScheduled = false;
    private static final long EVENT_FLUSH_INTERVAL_MS = 200;
    private static final int MAX_LOG_LENGTH = 20000;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (CloudflareTunnelService.ACTION_CF_STATUS.equals(action)) {
                boolean connected = intent.getBooleanExtra("connected", false);
                String url = intent.getStringExtra("url");
                String error = intent.getStringExtra("error");
                if (error != null && !error.isEmpty() && !connected) {
                    tvStatus.setText("错误");
                    tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
                    btnConnect.setEnabled(true);
                    btnDisconnect.setEnabled(false);
                    cardUrlLayout.setVisibility(View.GONE);
                } else {
                    updateStatus(connected, url);
                }
            } else if (CloudflareTunnelService.ACTION_CF_EVENT.equals(action)) {
                String event = intent.getStringExtra("event");
                if (event != null) {
                    queueEvent(event);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloudflare);

        // 设置 Toolbar 返回按钮
        MaterialToolbar toolbar = findViewById(R.id.toolbar_cf);
        toolbar.setNavigationOnClickListener(v -> finish());

        initViews();
        setupListeners();
        registerReceiver();

        // 恢复状态
        if (CloudflareTunnelService.isRunning(this)) {
            updateStatus(true, CloudflareTunnelService.getTunnelUrl(this));
        }
    }

    private void initViews() {
        rgMode = findViewById(R.id.rgTunnelMode);
        rbQuick = findViewById(R.id.rbQuick);
        rbPermanent = findViewById(R.id.rbPermanent);
        tilToken = findViewById(R.id.tilToken);
        etToken = findViewById(R.id.etToken);
        etCfLocalPort = findViewById(R.id.etCfLocalPort);
        btnConnect = findViewById(R.id.btnCfConnect);
        btnDisconnect = findViewById(R.id.btnCfDisconnect);
        tvStatus = findViewById(R.id.tvCfStatus);
        tvUrl = findViewById(R.id.tvCfUrl);
        btnCopyUrl = findViewById(R.id.btnCopyUrl);
        cardUrlLayout = findViewById(R.id.layoutUrl);
        cardEventLog = findViewById(R.id.cardCfEventLog);
        tvEventLog = findViewById(R.id.tvCfEventLog);
        btnClearLog = findViewById(R.id.btnCfClearLog);
    }

    private void setupListeners() {
        rgMode.setOnCheckedChangeListener((group, checkedId) -> {
            tilToken.setVisibility(checkedId == R.id.rbPermanent ? View.VISIBLE : View.GONE);
        });

        btnConnect.setOnClickListener(v -> connectTunnel());
        btnDisconnect.setOnClickListener(v -> disconnectTunnel());

        btnCopyUrl.setOnClickListener(v -> {
            String url = tvUrl.getText().toString();
            if (url != null && !url.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("隧道地址", url);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });

        btnClearLog.setOnClickListener(v -> {
            eventLogBuilder.setLength(0);
            tvEventLog.setText("");
            cardEventLog.setVisibility(View.GONE);
        });
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter(CloudflareTunnelService.ACTION_CF_STATUS);
        filter.addAction(CloudflareTunnelService.ACTION_CF_EVENT);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    private void connectTunnel() {
        String portStr = etCfLocalPort.getText().toString().trim();
        if (portStr.isEmpty()) {
            Toast.makeText(this, "请输入本地端口", Toast.LENGTH_SHORT).show();
            return;
        }
        int localPort;
        try {
            localPort = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口号格式错误", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, CloudflareTunnelService.class);
        intent.putExtra("cf_local_port", localPort);

        String mode = "quick";
        String token = "";

        if (rbPermanent.isChecked()) {
            token = etToken.getText().toString().trim();
            if (token.isEmpty()) {
                Toast.makeText(this, "请输入 Cloudflare Tunnel Token", Toast.LENGTH_SHORT).show();
                return;
            }
            mode = "permanent";
            intent.putExtra("cf_mode", "permanent");
            intent.putExtra("cf_token", token);
        } else {
            intent.putExtra("cf_mode", "quick");
        }

        // 清空旧日志
        eventLogBuilder.setLength(0);
        tvEventLog.setText("");
        cardEventLog.setVisibility(View.VISIBLE);
        appendEvent("正在启动 Cloudflare 隧道...");

        // 保存配置用于保活重启
        KeepAliveManager.getInstance(this).saveCfTunnelConfig(mode, localPort, token);
        // 用户主动启动：设置标记，保活时才会自动拉起
        KeepAliveManager.getInstance(this).setCfTunnelUserStarted(true);

        startService(intent);
        tvStatus.setText("连接中...");
        tvStatus.setTextColor(getColor(R.color.status_connecting));
        btnConnect.setEnabled(false);
    }

    private void disconnectTunnel() {
        appendEvent("正在断开隧道...");
        // 用户主动停止：清除启动标记，保活不再自动拉起
        KeepAliveManager.getInstance(this).setCfTunnelUserStarted(false);
        stopService(new Intent(this, CloudflareTunnelService.class));
        updateStatus(false, null);
    }

    private void updateStatus(boolean connected, String url) {
        isConnected = connected;
        if (connected) {
            tvStatus.setText("已连接");
            tvStatus.setTextColor(getColor(R.color.status_connected));
            btnConnect.setEnabled(false);
            btnDisconnect.setEnabled(true);
            if (url != null) {
                tvUrl.setText(url);
                cardUrlLayout.setVisibility(View.VISIBLE);
            }
        } else {
            tvStatus.setText("未连接");
            tvStatus.setTextColor(getColor(R.color.status_disconnected));
            btnConnect.setEnabled(true);
            btnDisconnect.setEnabled(false);
            cardUrlLayout.setVisibility(View.GONE);
        }
    }

    // ===== 事件日志 =====

    private void queueEvent(String event) {
        synchronized (eventBuffer) {
            if (eventBuffer.length() > 0) {
                eventBuffer.append("\n");
            }
            eventBuffer.append(event);
        }
        if (!eventFlushScheduled) {
            eventFlushScheduled = true;
            uiHandler.postDelayed(this::flushEvents, EVENT_FLUSH_INTERVAL_MS);
        }
    }

    private void flushEvents() {
        eventFlushScheduled = false;
        String batch;
        synchronized (eventBuffer) {
            if (eventBuffer.length() == 0) return;
            batch = eventBuffer.toString();
            eventBuffer.setLength(0);
        }
        if (eventLogBuilder.length() > 0) {
            eventLogBuilder.append("\n");
        }
        eventLogBuilder.append(batch);
        // 限制日志长度，防止 OOM
        if (eventLogBuilder.length() > MAX_LOG_LENGTH) {
            String trimmed = eventLogBuilder.substring(eventLogBuilder.length() - MAX_LOG_LENGTH);
            eventLogBuilder.setLength(0);
            eventLogBuilder.append(trimmed);
        }
        tvEventLog.setText(eventLogBuilder.toString());
        if (cardEventLog.getVisibility() != View.VISIBLE) {
            cardEventLog.setVisibility(View.VISIBLE);
        }
    }

    private void appendEvent(String event) {
        if (eventLogBuilder.length() > 0) {
            eventLogBuilder.append("\n");
        }
        eventLogBuilder.append(event);
        // 限制日志长度，防止 OOM
        if (eventLogBuilder.length() > MAX_LOG_LENGTH) {
            String trimmed = eventLogBuilder.substring(eventLogBuilder.length() - MAX_LOG_LENGTH);
            eventLogBuilder.setLength(0);
            eventLogBuilder.append(trimmed);
        }
        tvEventLog.setText(eventLogBuilder.toString());
        if (cardEventLog.getVisibility() != View.VISIBLE) {
            cardEventLog.setVisibility(View.VISIBLE);
        }
    }
}