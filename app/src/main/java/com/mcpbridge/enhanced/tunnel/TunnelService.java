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
import com.mcpbridge.enhanced.MCPBridgeApp;
import com.mcpbridge.enhanced.MainActivity;
import com.mcpbridge.enhanced.R;

import java.util.ArrayList;
import java.util.List;

public class TunnelService extends Service {

    public static final String ACTION_TUNNEL_STATUS = "com.mcpbridge.enhanced.TUNNEL_STATUS";
    public static final String ACTION_TUNNEL_EVENT = "com.mcpbridge.enhanced.TUNNEL_EVENT";
    public static final String ACTION_STOP = "com.mcpbridge.enhanced.tunnel.STOP";

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

        // 参考 SOMCP 方案：处理 ACTION_STOP，先 requestStop 防止重连
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop();
            stopForeground(STOP_FOREGROUND_REMOVE);
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

    /**
     * 参考 SOMCP 方案：轻量级停止请求，从主线程同步设置停止标志，
     * 防止在 onDestroy 执行前有保活/重连线程重新进入 startTunnel。
     */
    public void requestStop() {
        if (boreClient != null) {
            boreClient.requestStop();
        }
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
        getSharedPreferences("tunnel", MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_TUNNEL_RUNNING, false)
                .apply();
        lastTunnelUrl = null;
        // 发送断开广播，通知悬浮窗和主界面状态变更
        broadcastStatus(false, null);
    }

    private Notification buildNotification(String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, MCPBridgeApp.CHANNEL_TUNNEL)
                .setContentTitle("隧道桥接")
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
        // 检查 SharedPreferences 标记
        // Bore 隧道是无状态转发，不监听本地端口，对端进程一死端口立刻释放
        // 状态只有两种：连接中（活着）或 EOF（死了），不需要 TCP 探活
        return context.getSharedPreferences("tunnel", MODE_PRIVATE)
                .getBoolean(PREF_TUNNEL_RUNNING, false);
    }

    public static String getTunnelUrl(Context context) {
        return context.getSharedPreferences("tunnel", MODE_PRIVATE)
                .getString(PREF_TUNNEL_URL, null);
    }
}