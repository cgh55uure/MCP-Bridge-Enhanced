package com.mcpbridge.enhanced;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class MCPBridgeApp extends Application {

    public static final String CHANNEL_TUNNEL = "channel_tunnel";
    public static final String CHANNEL_FLOAT = "channel_float";
    public static final String CHANNEL_DAEMON = "channel_daemon";
    public static final String CHANNEL_MEDIA = "channel_media";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel tunnelChannel = new NotificationChannel(
                    CHANNEL_TUNNEL,
                    getString(R.string.channel_tunnel),
                    NotificationManager.IMPORTANCE_LOW
            );
            tunnelChannel.setDescription("隧道服务通知");
            tunnelChannel.setShowBadge(false);

            NotificationChannel floatChannel = new NotificationChannel(
                    CHANNEL_FLOAT,
                    getString(R.string.channel_float),
                    NotificationManager.IMPORTANCE_LOW
            );
            floatChannel.setDescription("悬浮窗服务通知");
            floatChannel.setShowBadge(false);

            NotificationChannel daemonChannel = new NotificationChannel(
                    CHANNEL_DAEMON,
                    getString(R.string.channel_daemon),
                    NotificationManager.IMPORTANCE_MIN
            );
            daemonChannel.setDescription("守护进程通知");
            daemonChannel.setShowBadge(false);

            // 媒体播放通知频道（用于 mediaPlayback 前台服务）
            NotificationChannel mediaChannel = new NotificationChannel(
                    CHANNEL_MEDIA,
                    "媒体保活",
                    NotificationManager.IMPORTANCE_MIN
            );
            mediaChannel.setDescription("媒体保活服务通知");
            mediaChannel.setShowBadge(false);
            mediaChannel.setSound(null, null);

            nm.createNotificationChannel(tunnelChannel);
            nm.createNotificationChannel(floatChannel);
            nm.createNotificationChannel(daemonChannel);
            nm.createNotificationChannel(mediaChannel);
        }
    }
}