package com.mcpbridge.enhanced.keepalive;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import com.mcpbridge.enhanced.floatwindow.FloatWindowService;
import com.mcpbridge.enhanced.tunnel.TunnelService;
import com.mcpbridge.enhanced.tunnel.cloudflare.CloudflareTunnelService;

/**
 * KeepAliveManager - 保活第六层：统一保活管理器
 * 整合所有保活策略，提供统一的检查与重启接口
 */
public class KeepAliveManager {

    private static volatile KeepAliveManager instance;
    private final Context context;

    private KeepAliveManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static KeepAliveManager getInstance(Context context) {
        if (instance == null) {
            synchronized (KeepAliveManager.class) {
                if (instance == null) {
                    instance = new KeepAliveManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * 启动所有保活策略
     */
    public void start() {
        // 第一层：前台服务保活
        startDaemonService();

        // 第二层：JobScheduler 保活
        JobSchedulerKeepAlive.schedule(context);

        // 第三层：AlarmManager 保活
        ForegroundKeepAlive.register(context);

        // 第四层：广播保活 (已在 Manifest 中注册)

        // 第五层：双进程守护
        startDoubleProcessKeepAlive();

        // 启动悬浮窗服务
        startFloatWindowService();
    }

    /**
     * 停止所有保活策略
     */
    public void stop() {
        JobSchedulerKeepAlive.cancel(context);
        ForegroundKeepAlive.unregister(context);
        context.stopService(new Intent(context, DaemonService.class));
        context.stopService(new Intent(context, DoubleProcessKeepAlive.class));
    }

    /**
     * 检查并重启服务（保活用：服务被系统杀死后重启，不是应用启动时自启动）
     * 隧道客户端内部已有自动重连逻辑（掉线后自动重连），这里只处理服务进程被杀的情况
     *
     * 关键：仅当用户之前主动启动过隧道（tunnel_user_started=true）才重启，
     * 避免用户从未启动过的隧道被保活机制自动拉起。
     */
    public void checkAndRestart() {
        // 检查 TunnelService (Bore) — 仅当用户主动启动过且被系统杀死时重启
        if (!TunnelService.isRunning(context)) {
            if (hasTunnelConfig() && isTunnelUserStarted()) {
                Intent intent = new Intent(context, TunnelService.class);
                intent.putExtra("bore_host", getBoreHost());
                intent.putExtra("local_port", getLocalPort());
                try {
                    context.startService(intent);
                } catch (Exception ignored) {
                }
            }
        }

        // 检查 CloudflareTunnelService — 仅当用户主动启动过且被系统杀死时重启
        if (!CloudflareTunnelService.isRunning(context)) {
            if (hasCfTunnelConfig() && isCfTunnelUserStarted()) {
                Intent intent = new Intent(context, CloudflareTunnelService.class);
                intent.putExtra("cf_mode", getCfMode());
                intent.putExtra("cf_local_port", getCfLocalPort());
                String token = getCfToken();
                if (token != null && !token.isEmpty()) {
                    intent.putExtra("cf_token", token);
                }
                try {
                    context.startService(intent);
                } catch (Exception ignored) {
                }
            }
        }

        // 检查前台服务
        if (!isServiceRunning(DaemonService.class)) {
            startDaemonService();
        }

        // 检查双进程守护
        if (!isServiceRunning(DoubleProcessKeepAlive.class)) {
            startDoubleProcessKeepAlive();
        }

        // 检查悬浮窗服务
        if (!isServiceRunning(FloatWindowService.class)) {
            startFloatWindowService();
        }
    }

    private void startDaemonService() {
        Intent intent = new Intent(context, DaemonService.class);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    private void startDoubleProcessKeepAlive() {
        Intent intent = new Intent(context, DoubleProcessKeepAlive.class);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    private void startFloatWindowService() {
        Intent intent = new Intent(context, FloatWindowService.class);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return false;
            for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ===== 用户主动启动标记（用于区分自启动和保活重启） =====

    private static final String PREF_TUNNEL_STARTED = "tunnel_user_started";
    private static final String PREF_CF_TUNNEL_STARTED = "cf_tunnel_user_started";

    /**
     * 设置 Bore 隧道用户主动启动标记
     * @param started true=用户主动启动, false=用户主动停止
     */
    public void setTunnelUserStarted(boolean started) {
        context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_TUNNEL_STARTED, started)
                .apply();
    }

    /**
     * 检查 Bore 隧道是否曾被用户主动启动过
     */
    public boolean isTunnelUserStarted() {
        return context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
                .getBoolean(PREF_TUNNEL_STARTED, false);
    }

    /**
     * 设置 CF 隧道用户主动启动标记
     * @param started true=用户主动启动, false=用户主动停止
     */
    public void setCfTunnelUserStarted(boolean started) {
        context.getSharedPreferences("cf_tunnel", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CF_TUNNEL_STARTED, started)
                .apply();
    }

    /**
     * 检查 CF 隧道是否曾被用户主动启动过
     */
    public boolean isCfTunnelUserStarted() {
        return context.getSharedPreferences("cf_tunnel", Context.MODE_PRIVATE)
                .getBoolean(PREF_CF_TUNNEL_STARTED, false);
    }

    public void saveTunnelConfig(String host, int localPort) {
        context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
                .edit()
                .putString("bore_host", host)
                .putInt("local_port", localPort)
                .apply();
    }

    private boolean hasTunnelConfig() {
        return context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
                .contains("bore_host");
    }

    private String getBoreHost() {
        return context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
                .getString("bore_host", "bore.pub");
    }

    private int getLocalPort() {
        return context.getSharedPreferences("tunnel", Context.MODE_PRIVATE)
                .getInt("local_port", 8080);
    }

    public void saveCfTunnelConfig(String mode, int localPort, String token) {
        context.getSharedPreferences("cf_tunnel", Context.MODE_PRIVATE)
                .edit()
                .putString("cf_mode", mode)
                .putInt("cf_local_port", localPort)
                .putString("cf_token", token != null ? token : "")
                .apply();
    }

    private boolean hasCfTunnelConfig() {
        return context.getSharedPreferences("cf_tunnel", Context.MODE_PRIVATE)
                .contains("cf_mode");
    }

    private String getCfMode() {
        return context.getSharedPreferences("cf_tunnel", Context.MODE_PRIVATE)
                .getString("cf_mode", "quick");
    }

    private int getCfLocalPort() {
        return context.getSharedPreferences("cf_tunnel", Context.MODE_PRIVATE)
                .getInt("cf_local_port", 8080);
    }

    private String getCfToken() {
        return context.getSharedPreferences("cf_tunnel", Context.MODE_PRIVATE)
                .getString("cf_token", "");
    }
}