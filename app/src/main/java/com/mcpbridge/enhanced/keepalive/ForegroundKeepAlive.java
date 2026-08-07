package com.mcpbridge.enhanced.keepalive;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * ForegroundKeepAlive - 保活第三层：使用 AlarmManager 定时唤醒
 * 通过精确的 Alarm 定时器周期性检查服务状态
 */
public class ForegroundKeepAlive {

    private static final String ACTION_KEEPALIVE_CHECK =
            "com.mcpbridge.enhanced.ACTION_KEEPALIVE_CHECK";
    private static final long INTERVAL_MS = 10 * 60 * 1000L; // 10分钟

    /**
     * 注册定时保活检查
     */
    public static void register(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(ACTION_KEEPALIVE_CHECK);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        long triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            } else {
                alarmManager.setRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        INTERVAL_MS,
                        pendingIntent
                );
            }
        } catch (SecurityException e) {
            // 无精确 Alarm 权限，降级为 setWindow
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setWindow(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        INTERVAL_MS,
                        pendingIntent
                );
            } else {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                );
            }
        }
    }

    /**
     * 取消定时保活检查
     */
    public static void unregister(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(ACTION_KEEPALIVE_CHECK);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        alarmManager.cancel(pendingIntent);
    }

    /**
     * 保活检查广播接收器
     */
    public static class KeepAliveReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_KEEPALIVE_CHECK.equals(intent.getAction())) {
                KeepAliveManager.getInstance(context).checkAndRestart();
                // 重新注册下一次检查
                register(context);
            }
        }
    }
}