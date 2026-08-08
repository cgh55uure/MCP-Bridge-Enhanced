package com.mcpbridge.enhanced.tunnel;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.mcpbridge.enhanced.keepalive.KeepAliveManager;
import com.mcpbridge.enhanced.server.McpServer;
import com.mcpbridge.enhanced.MCPBridgeApp;
import com.mcpbridge.enhanced.MainActivity;
import com.mcpbridge.enhanced.R;

import java.util.ArrayList;
import java.util.List;

public class TunnelService extends Service {

    public static final String ACTION_TUNNEL_STATUS = "com.mcpbridge.enhanced.TUNNEL_STATUS";
    public static final String ACTION_TUNNEL_EVENT = "com.mcpbridge.enhanced.TUNNEL_EVENT";

    private static final String EXTRA_BORE_HOST = "bore_host";
    private static final String EXTRA_LOCAL_PORT = "local_port";

    private static final String PREF_TUNNEL_URL = "tunnel_url";
    private static final String PREF_TUNNEL_RUNNING = "tunnel_running";

    private BoreClient boreClient;

    private static String lastTunnelUrl = null;
    private static final List<String> eventLog = new ArrayList<>();
    private static final int MAX_EVENTS = 100;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String boreHost = intent.getStringExtra(EXTRA_BORE_HOST);
        int localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 8080);

        startForeground(1001, buildNotification("隧道启动中..."));

        // 清空旧日志
        synchronized (eventLog) {
            eventLog.clear();
        }

        startTunnel(boreHost, localPort);

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    private void addEvent(String event) {
        synchronized (eventLog) {
            eventLog.add(event);
            while (eventLog.size() > MAX_EVENTS) {
                eventLog.remove(0);
            }
        }
        // 广播事件到 UI
        Intent intent = new Intent(ACTION_TUNNEL_EVENT);
        intent.putExtra("event", event);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /** 获取当前事件日志的副本 */
    public static List<String> getEventLog() {
        synchronized (eventLog) {
            return new ArrayList<>(eventLog);
        }
    }

    private void startTunnel(String boreHost, int localPort) {
        stopTunnel();

        // 保存配置以便保活后自动重启
        KeepAliveManager.getInstance(this).saveTunnelConfig(boreHost, localPort);

        // 强制重启 MCP Server 到隧道配置的端口
        // 确保端口与隧道设置一致，即使之前已被其他隧道启动
        McpServer mcpServer = McpServer.getInstance();
        boolean mcpStarted = mcpServer.restart(localPort);
        addEvent("[MCP] 本地 MCP Server " + (mcpStarted ? "已启动" : "启动失败") + " (端口: " + localPort + ")");

        boreClient = new BoreClient(boreHost, localPort);
        boreClient.setListener(new BoreClient.BoreListener() {
            @Override
            public void onConnected(String publicUrl) {
                lastTunnelUrl = publicUrl;
                getSharedPreferences("tunnel", MODE_PRIVATE)
                        .edit()
                        .putString(PREF_TUNNEL_URL, publicUrl)
                        .putBoolean(PREF_TUNNEL_RUNNING, true)
                        .apply();

                updateNotification("隧道已连接: " + publicUrl);
                broadcastStatus(true, publicUrl);
            }

            @Override
            public void onDisconnected() {
                lastTunnelUrl = null;
                getSharedPreferences("tunnel", MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_TUNNEL_RUNNING, false)
                        .apply();

                updateNotification("隧道已断开");
                broadcastStatus(false, null);
            }

            @Override
            public void onError(String message) {
                addEvent("[错误] " + message);
                updateNotification("隧道错误: " + message);
                broadcastStatus(false, null);
            }

            @Override
            public void onBytesTransferred(long bytes) {
            }

            @Override
            public void onConnectionEvent(String event) {
                addEvent(event);
            }
        });
        boreClient.start();
    }

    private void stopTunnel() {
        if (boreClient != null) {
            boreClient.stop();
            boreClient = null;
        }
        // 停止 MCP Server，释放端口供其他隧道使用
        McpServer.getInstance().stop();
        getSharedPreferences("tunnel", MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_TUNNEL_RUNNING, false)
                .apply();
        lastTunnelUrl = null;
    }

    private Notification buildNotification(String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, MCPBridgeApp.CHANNEL_TUNNEL)
                .setContentTitle("MCP Bridge 隧道")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1001, buildNotification(content));
    }

    private void broadcastStatus(boolean connected, String url) {
        Intent intent = new Intent(ACTION_TUNNEL_STATUS);
        intent.putExtra("connected", connected);
        intent.putExtra("url", url);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public static boolean isRunning(Context context) {
        return context.getSharedPreferences("tunnel", MODE_PRIVATE)
                .getBoolean(PREF_TUNNEL_RUNNING, false);
    }

    public static String getTunnelUrl(Context context) {
        return context.getSharedPreferences("tunnel", MODE_PRIVATE)
                .getString(PREF_TUNNEL_URL, null);
    }
}