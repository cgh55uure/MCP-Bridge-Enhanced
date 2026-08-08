package com.mcpbridge.enhanced.keepalive;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
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
 * 守护前台服务 - 保活第一层：前台服务 + 高优先级通知
 *
 * 使用 mediaPlayback 前台服务类型，Android 给媒体服务的 oom_adj 等级最低，
 * 是普通前台服务被杀后才被杀。不放声音，纯占位（静音 MediaSession）。
 */
public class DaemonService extends Service {

    private static final int NOTIFICATION_ID = 1003;
    private MediaSession mediaSession;
    // 持有 AudioTrack 引用防止被 GC 回收
    @SuppressWarnings("FieldCanBeLocal")
    private AudioTrack silentAudioTrack;

    @Override
    public void onCreate() {
        super.onCreate();
        initMediaSession();
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
    }

    private void initMediaSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession = new MediaSession(this, "daemon_keepalive");
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
        if (silentAudioTrack != null) {
            try {
                silentAudioTrack.stop();
                silentAudioTrack.release();
            } catch (Exception ignored) {
            }
            silentAudioTrack = null;
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MCPBridgeApp.CHANNEL_DAEMON)
                .setContentTitle("隧道桥接 守护")
                .setContentText("守护进程正在运行")
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