package com.mcpbridge.enhanced.keepalive;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.mcpbridge.enhanced.MCPBridgeApp;
import com.mcpbridge.enhanced.MainActivity;
import com.mcpbridge.enhanced.R;

/**
 * DoubleProcessKeepAlive - 保活第五层：双进程守护
 * 在独立进程 (:daemon) 中运行，与主进程相互监控
 * 任一进程被杀死，另一个进程立即重启对方
 *
 * 使用 mediaPlayback 前台服务类型提升保活优先级。
 */
public class DoubleProcessKeepAlive extends Service {

    private static final int NOTIFICATION_ID = 1004;
    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        initMediaSession();
        // 绑定主进程
        bindMainProcess();
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
        releaseMediaSession();
        super.onDestroy();
        // 如果守护进程被销毁，尝试重启主进程
        KeepAliveManager.getInstance(this).checkAndRestart();
    }

    private void initMediaSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession = new MediaSession(this, "daemon_keepalive_double");
            mediaSession.setActive(true);
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 0.0f)
                    .build());
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, Long.MAX_VALUE)
                    .build());
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
        }
    }

    private void releaseMediaSession() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
    }

    private void bindMainProcess() {
        // 守护进程定期检查主进程是否存活
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    // 检查主进程服务是否存活
                    boolean isAlive = isMainProcessAlive();
                    if (!isAlive) {
                        // 主进程挂了，重启
                        KeepAliveManager.getInstance(DoubleProcessKeepAlive.this).checkAndRestart();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.setName("daemon-monitor");
        monitorThread.start();
    }

    private boolean isMainProcessAlive() {
        // 跨进程检查：通过 ActivityManager 检查主进程中的服务是否存活
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningServiceInfo service : am.getRunningServices(Integer.MAX_VALUE)) {
                if (service.service.getClassName().equals(
                        "com.mcpbridge.enhanced.tunnel.TunnelService")
                    || service.service.getClassName().equals(
                        "com.mcpbridge.enhanced.keepalive.DaemonService")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MCPBridgeApp.CHANNEL_DAEMON)
                .setContentTitle("隧道桥接 双进程守护")
                .setContentText("守护进程运行中")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN);

        // 使用媒体样式通知，与 mediaPlayback 前台服务类型配合
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_TRANSPORT);
        }

        return builder.build();
    }
}