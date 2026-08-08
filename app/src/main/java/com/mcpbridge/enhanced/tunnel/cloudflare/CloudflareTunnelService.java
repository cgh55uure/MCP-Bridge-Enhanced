package com.mcpbridge.enhanced.tunnel.cloudflare;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.mcpbridge.enhanced.MCPBridgeApp;
import com.mcpbridge.enhanced.MainActivity;
import com.mcpbridge.enhanced.R;
import com.mcpbridge.enhanced.server.McpServer;


/**
 * Cloudflare 隧道前台服务 - 优化版
 * 支持实时日志广播、自动重连
 */
public class CloudflareTunnelService extends Service {

    public static final String ACTION_CF_STATUS =
            "com.mcpbridge.enhanced.CF_TUNNEL_STATUS";
    public static final String ACTION_CF_EVENT =
            "com.mcpbridge.enhanced.CF_TUNNEL_EVENT";

    private static final String EXTRA_MODE = "cf_mode";
    private static final String EXTRA_LOCAL_PORT = "cf_local_port";
    private static final String EXTRA_TOKEN = "cf_token";

    private static final String PREF_CF_URL = "cf_tunnel_url";
    private static final String PREF_CF_RUNNING = "cf_tunnel_running";

    private CloudflareTunnelClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String modeStr = intent.getStringExtra(EXTRA_MODE);
        int localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 8080);
        String token = intent.getStringExtra(EXTRA_TOKEN);

        boolean isPermanent = "permanent".equals(modeStr);

        startForeground(2001, buildNotification("Cloudflare 隧道启动中..."));
        broadcastEvent("正在启动 Cloudflare 隧道...");

        // 强制重启 MCP Server 到隧道配置的端口
        // 确保端口与隧道设置一致，即使之前已被其他隧道启动
        McpServer mcpServer = McpServer.getInstance();
        boolean mcpStarted = mcpServer.restart(localPort);
        broadcastEvent("[MCP] 本地 MCP Server " + (mcpStarted ? "已启动" : "启动失败") + " (端口: " + localPort + ")");

        if (isPermanent && token != null && !token.isEmpty()) {
            client = new CloudflareTunnelClient(this, localPort, token);
        } else {
            client = new CloudflareTunnelClient(this, localPort);
        }

        client.setAutoReconnect(true);

        client.setListener(new CloudflareTunnelClient.CloudflareListener() {
            @Override
            public void onConnected(String publicUrl) {
                getSharedPreferences("cf_tunnel", MODE_PRIVATE)
                        .edit()
                        .putString(PREF_CF_URL, publicUrl)
                        .putBoolean(PREF_CF_RUNNING, true)
                        .apply();
                updateNotification("CF 隧道已连接: " + publicUrl);
                broadcastEvent(">>> 隧道已连接! 公网地址: " + publicUrl);
                broadcastStatus(true, publicUrl);
            }

            @Override
            public void onDisconnected() {
                getSharedPreferences("cf_tunnel", MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_CF_RUNNING, false)
                        .apply();
                updateNotification("CF 隧道已断开");
                broadcastEvent("隧道已断开");
                broadcastStatus(false, null);
            }

            @Override
            public void onError(String message) {
                getSharedPreferences("cf_tunnel", MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_CF_RUNNING, false)
                        .apply();
                updateNotification("CF 隧道错误");
                broadcastEvent("错误: " + message);
                broadcastStatus(false, message);
            }

            @Override
            public void onLog(String line) {
                broadcastEvent(line);
            }

            @Override
            public void onDownloadProgress(int percent) {
                if (percent < 100) {
                    String msg = "正在下载 cloudflared... " + percent + "%";
                    updateNotification(msg);
                    if (percent % 10 == 0) {
                        broadcastEvent(msg);
                    }
                }
            }
        });

        client.start();

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (client != null) {
            client.stop();
            client = null;
        }
        // 停止 MCP Server，释放端口供其他隧道使用
        McpServer.getInstance().stop();
        getSharedPreferences("cf_tunnel", MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CF_RUNNING, false)
                .apply();
        super.onDestroy();
    }

    /**
     * 强制断开隧道，阻止自动重连。
     */
    public void forceStop() {
        if (client != null) {
            client.forceStop();
            client = null;
        }
        // 停止 MCP Server，释放端口供其他隧道使用
        McpServer.getInstance().stop();
        // 清除运行状态
        getSharedPreferences("cf_tunnel", MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CF_RUNNING, false)
                .putString(PREF_CF_URL, null)
                .apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification(String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, MCPBridgeApp.CHANNEL_TUNNEL)
                .setContentTitle("Cloudflare 隧道")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build();
    }

    private void updateNotification(String content) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(2001, buildNotification(content));
    }

    private void broadcastStatus(boolean connected, String url) {
        Intent intent = new Intent(ACTION_CF_STATUS);
        intent.putExtra("connected", connected);
        intent.putExtra("url", connected ? url : null);
        if (!connected && url != null) {
            intent.putExtra("error", url);
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastEvent(String event) {
        Intent intent = new Intent(ACTION_CF_EVENT);
        intent.putExtra("event", event);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public static boolean isRunning(Context context) {
        return context.getSharedPreferences("cf_tunnel", MODE_PRIVATE)
                .getBoolean(PREF_CF_RUNNING, false);
    }

    public static String getTunnelUrl(Context context) {
        return context.getSharedPreferences("cf_tunnel", MODE_PRIVATE)
                .getString(PREF_CF_URL, null);
    }
}