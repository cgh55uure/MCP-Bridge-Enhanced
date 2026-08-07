package com.mcpbridge.enhanced.floatwindow;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 悬浮窗工具类 - 提供方便的静态方法
 */
public class FloatWindowUtil {

    /**
     * 检查悬浮窗权限是否可用
     */
    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(context);
        }
        return true;
    }

    /**
     * 启动悬浮窗服务
     */
    public static void startService(Context context) {
        Intent intent = new Intent(context, FloatWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * 停止悬浮窗服务
     */
    public static void stopService(Context context) {
        context.stopService(new Intent(context, FloatWindowService.class));
    }

    /**
     * 打开悬浮窗设置页面
     */
    public static void openSettings(Context context) {
        FloatWindowSettingActivity.open(context);
    }

    /**
     * 请求悬浮窗权限
     */
    public static void requestOverlayPermission(Context context) {
        if (!hasOverlayPermission(context)) {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + context.getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
}