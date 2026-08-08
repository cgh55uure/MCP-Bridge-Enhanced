package com.mcpbridge.enhanced.floatwindow;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.drawable.DrawableCompat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.mcpbridge.enhanced.R;
import com.mcpbridge.enhanced.keepalive.KeepAliveManager;
import com.mcpbridge.enhanced.tunnel.TunnelService;
import com.mcpbridge.enhanced.tunnel.cloudflare.CloudflareTunnelService;

import static android.content.Context.MODE_PRIVATE;

/**
 * 悬浮窗管理器 - 单例模式，确保 FloatWindowService 和 FloatWindowSettingActivity 共享同一实例
 *
 * 两种状态:
 * 1. 气泡模式 - 小圆点显示连接状态（任一隧道连接即显示绿色）
 * 2. 面板模式 - 展开的控制面板，分别显示 Bore 和 CF 隧道状态
 *
 * 自动吸边: 5秒不动 → 吸附到边缘，透明度由用户设置
 * 点击吸边后的气泡 → 恢复位置和透明度，并展开面板
 */
public class FloatWindowManager {

    private static final String PREF_NAME = "float_window";
    private static final String KEY_OPACITY = "opacity";
    private static final String KEY_AUTO_EDGE = "auto_edge";
    private static final String KEY_ENABLED = "enabled";

    private static FloatWindowManager instance;

    private WindowManager windowManager;
    private View floatView;
    private WindowManager.LayoutParams layoutParams;

    private final Context context;
    private boolean isShowing = false;
    private boolean isExpanded = false;

    // 自动吸边 (5秒不动)
    private final Handler idleHandler = new Handler(Looper.getMainLooper());
    private final Runnable idleEdgeSnapRunnable = this::doIdleEdgeSnap;
    private static final int IDLE_SNAP_DELAY_MS = 5000;
    private boolean isSnappedToEdge = false;

    // 吸边前的位置，用于点击恢复
    private int preSnapX = 0;
    private int preSnapY = 0;
    private boolean needsSnapRestore = false;

    private FloatWindowManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized FloatWindowManager getInstance(Context context) {
        if (instance == null) {
            instance = new FloatWindowManager(context.getApplicationContext());
        }
        return instance;
    }

    public void show() {
        if (isShowing) return;

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        showBubble();
    }

    // 防止 showBubble() / showExpandedPanel() 重入的标志
    private boolean isTransitioning = false;

    private void showBubble() {
        if (isTransitioning) return;
        isTransitioning = true;
        isExpanded = false;

        if (floatView != null) {
            try {
                windowManager.removeView(floatView);
            } catch (Exception ignored) {}
            floatView = null;
        }

        floatView = View.inflate(context, R.layout.float_window, null);
        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 使用显式像素尺寸，避免 WRAP_CONTENT 在某些设备上导致触摸区域为 0
        int bubbleSizePx = (int) (48 * context.getResources().getDisplayMetrics().density);
        layoutParams = new WindowManager.LayoutParams(
                bubbleSizePx,
                bubbleSizePx,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        layoutParams.gravity = Gravity.START | Gravity.TOP;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        layoutParams.x = sp.getInt("last_x", 0);
        layoutParams.y = sp.getInt("last_y", 200);
        // 窗口始终为完全不透明，透明效果通过 View 的 setAlpha 实现
        // 避免低 alpha 导致某些 Android 版本缩小触摸区域
        layoutParams.alpha = 1.0f;
        floatView.setAlpha(getOpacity() / 100f);

        // 注册触摸监听（处理拖拽）
        floatView.setOnTouchListener(bubbleTouchListener);
        // 注册点击监听（处理点击展开面板）
        floatView.setOnClickListener(v -> {
            if (!isExpanded) {
                showExpandedPanel();
            }
        });

        try {
            windowManager.addView(floatView, layoutParams);
            isShowing = true;
            isSnappedToEdge = false;
            isTransitioning = false;
            idleHandler.postDelayed(idleEdgeSnapRunnable, IDLE_SNAP_DELAY_MS);
            // 启动定时健康检查，确保状态及时更新
            startHealthCheck();
        } catch (Exception e) {
            isShowing = false;
            isTransitioning = false;
        }
    }

    private void showExpandedPanel() {
        if (!isExpanded && isTransitioning) return;
        isTransitioning = true;
        isExpanded = true;

        // 保存气泡位置，用于展开面板
        int savedX = (layoutParams != null) ? layoutParams.x : 0;
        int savedY = (layoutParams != null) ? layoutParams.y : 0;

        if (floatView != null) {
            try {
                windowManager.removeView(floatView);
            } catch (Exception ignored) {}
            floatView = null;
        }

        floatView = View.inflate(context, R.layout.float_window_expanded, null);
        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        layoutParams.gravity = Gravity.START | Gravity.TOP;
        // 使用气泡保存的位置，但避免面板在吸边位置（x=0 或 x 在右侧）
        // 如果吸边在左侧，偏移 20dp 避免面板贴边
        int edgeOffsetPx = (int) (20 * context.getResources().getDisplayMetrics().density);
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int panelWidthHint = (int) (240 * context.getResources().getDisplayMetrics().density);
        if (savedX <= 10) {
            // 吸边在左侧，偏移出来
            layoutParams.x = edgeOffsetPx;
        } else if (savedX + panelWidthHint > screenWidth) {
            // 吸边在右侧，面板可能超出屏幕，向左偏移
            layoutParams.x = Math.max(edgeOffsetPx, screenWidth - panelWidthHint);
        } else {
            layoutParams.x = Math.max(0, savedX);
        }
        layoutParams.y = Math.max(0, savedY);
        // 窗口始终为完全不透明，透明效果通过 View 的 setAlpha 实现
        layoutParams.alpha = 1.0f;
        // 展开面板必须完全可见（100% 透明度），子 View 不受吸边透明度影响
        floatView.setAlpha(1.0f);

        try {
            updatePanelView();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            setupPanelListeners();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 面板不设全局触摸监听，让子 View 的 OnClickListener 正常接收点击事件
        // 如果需要拖动面板，用户可以先折叠回气泡再拖动

        try {
            windowManager.addView(floatView, layoutParams);
            isShowing = true;
            isSnappedToEdge = false;
            isTransitioning = false;
            idleHandler.removeCallbacks(idleEdgeSnapRunnable);
        } catch (Exception e) {
            isShowing = false;
            isTransitioning = false;
        }
    }

    private void updatePanelView() {
        updatePanelView(
            TunnelService.isRunning(context),
            TunnelService.getTunnelUrl(context),
            CloudflareTunnelService.isRunning(context),
            CloudflareTunnelService.getTunnelUrl(context)
        );
    }

    private void updatePanelView(boolean boreConnected, String boreUrl,
                                  boolean cfConnected, String cfUrl) {
        if (floatView == null || !isExpanded) return;

        boolean anyConnected = boreConnected || cfConnected;

        // 标题栏总状态
        TextView tvStatus = floatView.findViewById(R.id.tvPanelStatus);
        TextView tvTitle = floatView.findViewById(R.id.tvPanelTitle);
        if (tvStatus != null) {
            setDrawableColor(tvStatus, anyConnected ? R.color.status_connected : R.color.status_disconnected);
        }
        if (tvTitle != null) {
            tvTitle.setText(anyConnected ? "已连接" : "未连接");
        }

        // ---- Bore 隧道 ----
        updateTunnelSection(
                R.id.tvBoreStatus, R.id.tvBoreUrl, R.id.tvBoreCopy,
                R.id.tvBoreToggle, R.id.tvBoreDisconnect,
                boreConnected, boreUrl, true
        );

        // ---- CF 隧道 ----
        updateTunnelSection(
                R.id.tvCfStatus, R.id.tvCfUrl, R.id.tvCfCopy,
                R.id.tvCfToggle, R.id.tvCfDisconnect,
                cfConnected, cfUrl, false
        );

        // ---- 隧道状态（纯内网穿透） ----
    }

    /**
     * 启动定时健康检查（每 3 秒检测一次隧道状态）。
     * 确保状态及时更新。
     */
    public void startHealthCheck() {
        healthCheckHandler.removeCallbacks(healthCheckRunnable);
        healthCheckHandler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS);
    }

    /**
     * 停止定时健康检查
     */
    public void stopHealthCheck() {
        healthCheckHandler.removeCallbacks(healthCheckRunnable);
    }

    private final Handler healthCheckHandler = new Handler(Looper.getMainLooper());
    private static final long HEALTH_CHECK_INTERVAL_MS = 3000;

    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (floatView == null) return;
            if (isExpanded) {
                updatePanelView();
            } else {
                // 气泡模式：更新连接状态指示
                boolean anyConnected = TunnelService.isRunning(context)
                        || CloudflareTunnelService.isRunning(context);
                TextView tvBubble = floatView.findViewById(R.id.tvFloatBubble);
                if (tvBubble != null) {
                    if (anyConnected) {
                        tvBubble.setTextColor(context.getColor(R.color.status_connected));
                        tvBubble.setText("●");
                        tvBubble.setContentDescription("已连接");
                    } else {
                        tvBubble.setTextColor(context.getColor(R.color.status_disconnected));
                        tvBubble.setText("○");
                        tvBubble.setContentDescription("未连接");
                    }
                }
            }
            // 继续下一轮检测
            healthCheckHandler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
        }
    };

    private void updateTunnelSection(int statusId, int urlId, int copyId,
                                     int toggleId, int disconnectId,
                                     boolean connected, String url, boolean isBore) {
        TextView tvStatus = floatView.findViewById(statusId);
        TextView tvUrl = floatView.findViewById(urlId);
        TextView tvCopy = floatView.findViewById(copyId);
        TextView tvToggle = floatView.findViewById(toggleId);
        TextView tvDisconnect = floatView.findViewById(disconnectId);

        if (tvStatus != null) {
            setDrawableColor(tvStatus, connected ? R.color.status_connected : R.color.status_disconnected);
        }

        if (tvUrl != null) {
            if (connected && url != null) {
                tvUrl.setText(url);
                tvUrl.setVisibility(View.VISIBLE);
            } else {
                tvUrl.setVisibility(View.GONE);
            }
        }

        if (tvCopy != null) {
            if (connected && url != null) {
                tvCopy.setVisibility(View.VISIBLE);
                final String fullUrl = url;
                tvCopy.setOnClickListener(v -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("tunnel_url", fullUrl));
                    Toast.makeText(context, "已复制: " + fullUrl, Toast.LENGTH_SHORT).show();
                });
            } else {
                tvCopy.setVisibility(View.GONE);
            }
        }

        if (tvToggle != null) {
            tvToggle.setText(connected ? "停止" : "启动");
        }

        if (tvDisconnect != null) {
            tvDisconnect.setEnabled(connected);
            tvDisconnect.setAlpha(connected ? 1.0f : 0.4f);
        }
    }

    private void setupPanelListeners() {
        if (floatView == null) return;

        TextView tvClose = floatView.findViewById(R.id.tvPanelClose);
        if (tvClose != null) {
            tvClose.setOnClickListener(v -> collapseToBubble());
        }

        // Bore 控制
        TextView tvBoreToggle = floatView.findViewById(R.id.tvBoreToggle);
        if (tvBoreToggle != null) {
            tvBoreToggle.setOnClickListener(v -> {
                toggleBoreTunnel();
                updatePanelView();
            });
        }
        TextView tvBoreDisconnect = floatView.findViewById(R.id.tvBoreDisconnect);
        if (tvBoreDisconnect != null) {
            tvBoreDisconnect.setOnClickListener(v -> {
                stopBoreTunnel();
                updatePanelView();
            });
        }

        // CF 控制
        TextView tvCfToggle = floatView.findViewById(R.id.tvCfToggle);
        if (tvCfToggle != null) {
            tvCfToggle.setOnClickListener(v -> {
                toggleCfTunnel();
                updatePanelView();
            });
        }
        TextView tvCfDisconnect = floatView.findViewById(R.id.tvCfDisconnect);
        if (tvCfDisconnect != null) {
            tvCfDisconnect.setOnClickListener(v -> {
                stopCfTunnel();
                updatePanelView();
            });
        }
    }

    private void collapseToBubble() {
        showBubble();
    }

    // === 隧道控制 ===

    private void toggleBoreTunnel() {
        if (TunnelService.isRunning(context)) {
            // 用户主动停止：先 requestStop 防止重连，再清除标记
            KeepAliveManager.getInstance(context).setTunnelUserStarted(false);
            Intent intent = new Intent(context, TunnelService.class)
                    .setAction(TunnelService.ACTION_STOP);
            context.startService(intent);
        } else {
            SharedPreferences sp = context.getSharedPreferences("tunnel", MODE_PRIVATE);
            String host = sp.getString("bore_host", "bore.pub");
            int port = sp.getInt("local_port", 8080);
            Intent intent = new Intent(context, TunnelService.class);
            intent.putExtra("bore_host", host);
            intent.putExtra("local_port", port);
            // 用户主动启动：设置标记，保活时才会自动拉起
            KeepAliveManager.getInstance(context).setTunnelUserStarted(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }
    }

    private void stopBoreTunnel() {
        // 用户主动停止：先 requestStop 防止重连，再清除标记
        KeepAliveManager.getInstance(context).setTunnelUserStarted(false);
        Intent intent = new Intent(context, TunnelService.class)
                .setAction(TunnelService.ACTION_STOP);
        context.startService(intent);
    }

    private void toggleCfTunnel() {
        if (CloudflareTunnelService.isRunning(context)) {
            // 用户主动停止：先 requestStop 防止重连，再清除标记
            KeepAliveManager.getInstance(context).setCfTunnelUserStarted(false);
            Intent intent = new Intent(context, CloudflareTunnelService.class)
                    .setAction(CloudflareTunnelService.ACTION_STOP);
            context.startService(intent);
        } else {
            SharedPreferences cfSp = context.getSharedPreferences("cf_tunnel", MODE_PRIVATE);
            int savedPort = cfSp.getInt("cf_local_port", 8080);
            Intent intent = new Intent(context, CloudflareTunnelService.class);
            intent.putExtra("cf_mode", "quick");
            intent.putExtra("cf_local_port", savedPort);
            // 保存配置用于保活重启
            KeepAliveManager.getInstance(context).saveCfTunnelConfig("quick", savedPort, null);
            // 用户主动启动：设置标记，保活时才会自动拉起
            KeepAliveManager.getInstance(context).setCfTunnelUserStarted(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }
    }

    private void stopCfTunnel() {
        // 用户主动停止：先 requestStop 防止重连，再清除标记
        KeepAliveManager.getInstance(context).setCfTunnelUserStarted(false);
        Intent intent = new Intent(context, CloudflareTunnelService.class)
                .setAction(CloudflareTunnelService.ACTION_STOP);
        context.startService(intent);
    }

    // === 空闲吸边 + 使用用户设置的透明度 ===
    private void doIdleEdgeSnap() {
        if (!isShowing || isExpanded || floatView == null || windowManager == null) return;
        if (!isAutoEdge()) return;

        // 保存吸边前的位置
        if (layoutParams != null) {
            preSnapX = layoutParams.x;
            preSnapY = layoutParams.y;
        }

        snapToEdge();
        isSnappedToEdge = true;

        // 吸边后使用用户设置的透明度（通过 View 的 setAlpha，不影响触摸区域）
        if (layoutParams != null && floatView != null) {
            floatView.setAlpha(getOpacity() / 100f);
        }
    }

    private void cancelIdleSnap() {
        idleHandler.removeCallbacks(idleEdgeSnapRunnable);
        if (isSnappedToEdge) {
            // 标记需要恢复位置，但不在触摸过程中调用 updateViewLayout（会中断触摸事件）
            needsSnapRestore = true;
            isSnappedToEdge = false;
        }
        // 无论是否吸边，触摸时立即恢复透明度，让用户能正常看到和点击悬浮窗
        if (floatView != null) {
            floatView.setAlpha(1.0f);
        }
    }

    private void resetIdleTimer() {
        idleHandler.removeCallbacks(idleEdgeSnapRunnable);
        if (isShowing && !isExpanded) {
            idleHandler.postDelayed(idleEdgeSnapRunnable, IDLE_SNAP_DELAY_MS);
        }
    }

    // === 气泡触摸监听 ===
    // 参考 SOMCP 方案：同时注册 OnClickListener 和 OnTouchListener
    // OnTouchListener 处理拖拽，OnClickListener 处理点击
    // 用 moved 标志位区分：移动超过 touchSlop 算拖拽，否则算点击
    private final View.OnTouchListener bubbleTouchListener = new View.OnTouchListener() {
        private float downX, downY;
        private float startLayoutX, startLayoutY;
        private boolean moved;
        private static final int TOUCH_SLOP_DP = 8;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    cancelIdleSnap();
                    moved = false;
                    startLayoutX = layoutParams.x;
                    startLayoutY = layoutParams.y;
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    int touchSlopPx = (int) (TOUCH_SLOP_DP * context.getResources().getDisplayMetrics().density);
                    if (!moved && (Math.abs(dx) > touchSlopPx || Math.abs(dy) > touchSlopPx)) {
                        moved = true;
                    }
                    if (moved) {
                        layoutParams.x = (int) (startLayoutX + dx);
                        layoutParams.y = (int) (startLayoutY + dy);
                        try {
                            windowManager.updateViewLayout(floatView, layoutParams);
                            // updateViewLayout 可能重置 alpha，确保拖拽时用户可见
                            floatView.setAlpha(1.0f);
                        } catch (Exception ignored) {}
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (moved) {
                        // 拖拽结束：吸边到屏幕边缘
                        needsSnapRestore = false;
                        snapToEdge();
                        // 拖拽后重新标记为吸边状态，确保下次触摸能正确恢复位置和透明度
                        isSnappedToEdge = true;
                        savePosition(layoutParams.x, layoutParams.y);
                        resetIdleTimer();
                    } else {
                        // 点击：先恢复吸边前的位置
                        if (needsSnapRestore && layoutParams != null) {
                            layoutParams.x = preSnapX;
                            layoutParams.y = preSnapY;
                            needsSnapRestore = false;
                            try {
                                windowManager.updateViewLayout(floatView, layoutParams);
                                // updateViewLayout 可能重置 alpha，确保恢复位置后用户可见
                                floatView.setAlpha(1.0f);
                            } catch (Exception ignored) {}
                        }
                        // 点击时恢复透明度为完全可见（用户在泡泡中看到的是不透明状态）
                        if (floatView != null) {
                            floatView.setAlpha(1.0f);
                        }
                        // 触发 OnClickListener（展开面板）
                        v.performClick();
                    }
                    return true;
            }
            return false;
        }
    };

    // === 公共 API ===

    public void hide() {
        if (!isShowing || windowManager == null || floatView == null) return;
        // 保存最后位置以便 restore
        if (layoutParams != null) {
            savePosition(layoutParams.x, layoutParams.y);
        }
        try {
            windowManager.removeView(floatView);
        } catch (Exception ignored) {}
        isShowing = false;
        floatView = null;
        idleHandler.removeCallbacks(idleEdgeSnapRunnable);
        stopHealthCheck();
    }

    public void restore() {
        if (!isShowing) {
            show();
        }
    }

    public boolean isShowing() {
        return isShowing;
    }

    // 防止频繁更新导致 UI 卡顿
    private long lastUpdateTime = 0;
    private static final long UPDATE_DEBOUNCE_MS = 100;

    public void updateStatus(boolean connected, String text) {
        if (floatView == null) return;

        // 确保在主线程更新 UI（broadcast receiver 可能从后台线程调用）
        if (Looper.myLooper() != Looper.getMainLooper()) {
            idleHandler.post(() -> updateStatus(connected, text));
            return;
        }

        // 防抖：100ms 内的重复更新跳过
        long now = System.currentTimeMillis();
        if (now - lastUpdateTime < UPDATE_DEBOUNCE_MS) {
            return;
        }
        lastUpdateTime = now;

        if (isExpanded) {
            updatePanelView();
        } else {
            // 气泡模式：检查任一隧道是否连接
            boolean anyConnected = TunnelService.isRunning(context)
                    || CloudflareTunnelService.isRunning(context);
            TextView tvBubble = floatView.findViewById(R.id.tvFloatBubble);
            if (tvBubble != null) {
                if (anyConnected) {
                    tvBubble.setTextColor(context.getColor(R.color.status_connected));
                    tvBubble.setText("●");
                    tvBubble.setContentDescription("已连接");
                } else {
                    tvBubble.setTextColor(context.getColor(R.color.status_disconnected));
                    tvBubble.setText("○");
                    tvBubble.setContentDescription("未连接");
                }
            }
        }
    }

    public void updateOpacity(int opacityPercent) {
        if (layoutParams == null || windowManager == null || floatView == null) return;
        float alpha = Math.max(0.05f, Math.min(1.0f, opacityPercent / 100f));
        // 通过 View 的 setAlpha 实现透明效果，不影响窗口管理器的触摸区域
        floatView.setAlpha(alpha);
        saveOpacity(opacityPercent);
    }

    public int getOpacity() {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_OPACITY, 80);
    }

    public void saveOpacity(int opacity) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_OPACITY, opacity)
                .apply();
    }

    private void savePosition(int x, int y) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("last_x", x)
                .putInt("last_y", y)
                .apply();
    }

    public boolean isAutoEdge() {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_EDGE, true);
    }

    public void setAutoEdge(boolean auto) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_EDGE, auto)
                .apply();
    }

    public boolean isEnabled() {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    /**
     * 吸边到屏幕边缘。
     * 气泡完全贴边（无 margin），左侧 x=0，右侧 x=screenWidth-bubbleWidth。
     * 拖动释放后也调用此方法，实现"拖动后自动吸边"。
     */
    private void snapToEdge() {
        if (windowManager == null || floatView == null || layoutParams == null) return;

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int bubbleWidthPx = (int) (48 * context.getResources().getDisplayMetrics().density);

        // 确保气泡在屏幕范围内（防止负坐标或超出右边界）
        layoutParams.x = Math.max(0, Math.min(layoutParams.x, screenWidth - bubbleWidthPx));

        // 计算中点，判断靠近哪一侧边缘
        int centerX = layoutParams.x + bubbleWidthPx / 2;
        if (centerX < screenWidth / 2) {
            // 靠左：吸附到左侧边缘（x=0）
            layoutParams.x = 0;
        } else {
            // 靠右：吸附到右侧边缘
            layoutParams.x = screenWidth - bubbleWidthPx;
        }

        // 确保 Y 坐标也在屏幕范围内
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        layoutParams.y = Math.max(0, Math.min(layoutParams.y, screenHeight - bubbleWidthPx));

        try {
            windowManager.updateViewLayout(floatView, layoutParams);
        } catch (Exception ignored) {}
        // updateViewLayout 可能会重置 View 的 alpha，确保吸边后用户可见
        floatView.setAlpha(1.0f);
    }

    public void checkAndSnap() {
        if (isAutoEdge() && isShowing && !isExpanded) {
            doIdleEdgeSnap();
        }
    }

    /**
     * 安全地设置 View 背景颜色（使用 DrawableCompat 避免类型转换异常）
     */
    private void setDrawableColor(View view, int colorRes) {
        try {
            Drawable drawable = view.getBackground();
            if (drawable != null) {
                drawable = DrawableCompat.wrap(drawable);
                DrawableCompat.setTint(drawable, context.getColor(colorRes));
            }
        } catch (Exception e) {
            // 忽略异常，不影响 UI 显示
        }
    }
}