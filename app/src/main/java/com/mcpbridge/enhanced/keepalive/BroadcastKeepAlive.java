package com.mcpbridge.enhanced.keepalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BroadcastKeepAlive - 保活第四层：监听系统广播唤醒
 * 仅监听关键系统广播（开机、应用更新），避免不必要的保活检查
 * 注意：不监听 TIME_TICK、SCREEN_ON/OFF、USER_PRESENT 等高频广播，
 * 以免触发隧道自启动。
 */
public class BroadcastKeepAlive extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_PACKAGE_REPLACED:
            case Intent.ACTION_PACKAGE_RESTARTED:
                KeepAliveManager.getInstance(context).checkAndRestart();
                break;
        }
    }
}