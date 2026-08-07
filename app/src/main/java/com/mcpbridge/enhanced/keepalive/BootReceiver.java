package com.mcpbridge.enhanced.keepalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启接收器 - 设备重启后自动启动所有保活服务
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            // 延迟启动，等待系统完全加载
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                // ignore
            }

            // 启动所有保活服务
            KeepAliveManager.getInstance(context).start();
        }
    }
}