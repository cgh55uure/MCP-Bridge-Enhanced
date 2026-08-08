package com.mcpbridge.enhanced.floatwindow;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.mcpbridge.enhanced.MCPBridgeApp;
import com.mcpbridge.enhanced.R;
import com.mcpbridge.enhanced.tunnel.TunnelService;
import com.mcpbridge.enhanced.tunnel.cloudflare.CloudflareTunnelService;

/**
 * 悬浮窗前台服务 - 保持悬浮窗在后台持续运行
 * 同时监听 Bore 和 Cloudflare 隧道的状态变化
 */
public class FloatWindowService extends Service {

    private static final int NOTIFICATION_ID = 1002;
    private FloatWindowManager floatWindowManager;
    private boolean boreConnected = false;
    private String boreUrl = null;
    private boolean cfConnected = false;
    private String cfUrl = null;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TunnelService.ACTION_TUNNEL_STATUS.equals(action)) {
                boreConnected = intent.getBooleanExtra("connected", false);
                boreUrl = intent.getStringExtra("url");
                updateFloatWindow();
            } else if (CloudflareTunnelService.ACTION_CF_STATUS.equals(action)) {
                cfConnected = intent.getBooleanExtra("connected", false);
                cfUrl = intent.getStringExtra("url");
                updateFloatWindow();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        floatWindowManager = FloatWindowManager.getInstance(this);

        // 监听 Bore 和 CF 隧道状态
        IntentFilter filter = new IntentFilter();
        filter.addAction(TunnelService.ACTION_TUNNEL_STATUS);
        filter.addAction(CloudflareTunnelService.ACTION_CF_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());

        // 启动时检查两个隧道状态
        boreConnected = TunnelService.isRunning(this);
        boreUrl = TunnelService.getTunnelUrl(this);
        cfConnected = CloudflareTunnelService.isRunning(this);
        cfUrl = CloudflareTunnelService.getTunnelUrl(this);

        if (floatWindowManager.isEnabled()) {
            floatWindowManager.show();
            updateFloatWindow();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
        if (floatWindowManager != null) {
            floatWindowManager.hide();
        }
        super.onDestroy();
    }

    private void updateFloatWindow() {
        if (floatWindowManager == null) return;

        boolean anyConnected = boreConnected || cfConnected;
        floatWindowManager.updateStatus(anyConnected, null);

        // 同步更新通知栏，确保用户通过通知也能看到最新隧道状态
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, FloatWindowSettingActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String statusText;
        if (boreConnected && boreUrl != null) {
            statusText = "Bore: " + boreUrl;
        } else if (cfConnected && cfUrl != null) {
            statusText = "CF: " + cfUrl;
        } else {
            statusText = "未连接";
        }

        return new NotificationCompat.Builder(this, MCPBridgeApp.CHANNEL_FLOAT)
                .setContentTitle("隧道桥接 悬浮窗")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}