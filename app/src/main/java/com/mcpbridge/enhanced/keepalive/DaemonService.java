package com.mcpbridge.enhanced.keepalive;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.mcpbridge.enhanced.MCPBridgeApp;
import com.mcpbridge.enhanced.MainActivity;
import com.mcpbridge.enhanced.R;

/**
 * 守护前台服务 - 保活第一层：前台服务 + 高优先级通知
 */
public class DaemonService extends Service {

    private static final int NOTIFICATION_ID = 1003;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, MCPBridgeApp.CHANNEL_DAEMON)
                .setContentTitle("隧道桥接 守护")
                .setContentText("守护进程正在运行")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}