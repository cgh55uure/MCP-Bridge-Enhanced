package com.mcpbridge.enhanced.tunnel.cloudflare;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cloudflare Tunnel 客户端 - 优化版
 *
 * 两种模式：
 * 1. Quick Tunnel (临时隧道): cloudflared tunnel --url http://localhost:PORT
 * 2. Permanent Tunnel (永久隧道): cloudflared tunnel run --token TOKEN
 *
 * 修复：
 * - 使用 synchronized 防止并发执行
 * - 重连时通过 start() 方法，确保线程安全
 * - 进程管理更可靠
 */
public class CloudflareTunnelClient {

    public interface CloudflareListener {
        void onConnected(String publicUrl);
        void onDisconnected();
        void onError(String message);
        void onLog(String line);
        void onDownloadProgress(int percent);
    }

    private static final String TAG = "CloudflareTunnel";
    public static final String CLOUDFLARED_BIN = "cloudflared";

    // 使用 Android 专用构建（cloudflared-android-arm64），已经是 PIE 格式，
    // 无需 ELF 修补，可直接用 ProcessBuilder 执行（参考 SOMCP 方案）
    private static final String CLOUDFLARED_DOWNLOAD_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-android-arm64";
    private static final String[] CLOUDFLARED_FALLBACK_URLS = {
        "https://github.com/cloudflare/cloudflared/releases/download/2024.12.2/cloudflared-android-arm64",
        "https://github.com/cloudflare/cloudflared/releases/download/2024.11.0/cloudflared-android-arm64",
        "https://github.com/cloudflare/cloudflared/releases/download/2024.10.0/cloudflared-android-arm64"
    };

    private static final long CONNECT_TIMEOUT_MS = 60000;
    private static final long RECONNECT_DELAY_MS = 5000;

    public enum TunnelMode {
        QUICK,
        PERMANENT
    }

    private final Context context;
    private final TunnelMode mode;
    private final int localPort;
    private final String token;
    private CloudflareListener listener;
    private Process tunnelProcess;
    private Thread outputThread;
    private Thread stderrThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong lastOutputTime = new AtomicLong(0);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cf-tunnel");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String currentPublicUrl;
    private String cloudflaredPath;
    private boolean autoReconnect = true;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private final Object lock = new Object();
    private Runnable pendingReconnectRunnable; // 跟踪待处理的重连，用于取消

    // 参考 SOMCP 方案：generation 并发控制 + stopRequested 硬门控
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean stopRequested = false;

    // 健康检查线程（参考 SOMCP 的 startHealthCheck + probeLocal）
    private Thread healthCheckThread;
    private static final long HEALTH_CHECK_INTERVAL_MS = 5000;
    private static final long HEALTH_CHECK_TIMEOUT_MS = 800;
    private volatile boolean healthCheckPassed = false;

    // 健康检查防抖：参考 SOMCP 的 downSince / lastRestartAt 方案
    private volatile long healthCheckDownSince = 0L;
    private volatile long healthCheckLastRestartAt = 0L;
    private volatile int healthCheckStableCount = 0;
    private final java.util.concurrent.atomic.AtomicInteger keepaliveRestarts = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final long HEALTH_CHECK_DOWN_THRESHOLD_MS = 8000;   // 连续失败 8 秒才触发保活重启
    private static final long HEALTH_CHECK_RESTART_COOLDOWN_MS = 15000; // 两次保活重启至少间隔 15 秒

    // URL 匹配模式
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[a-zA-Z0-9][-a-zA-Z0-9]*\\.trycloudflare\\.com");
    private static final Pattern URL_ANY_PATTERN =
            Pattern.compile("https?://[a-zA-Z0-9][-a-zA-Z0-9]*(?:\\.[a-zA-Z0-9][-a-zA-Z0-9]*)+");

    public CloudflareTunnelClient(Context context, int localPort) {
        this.context = context.getApplicationContext();
        this.mode = TunnelMode.QUICK;
        this.localPort = localPort;
        this.token = null;
    }

    public CloudflareTunnelClient(Context context, int localPort, String token) {
        this.context = context.getApplicationContext();
        this.mode = TunnelMode.PERMANENT;
        this.localPort = localPort;
        this.token = token;
    }

    public void setListener(CloudflareListener listener) {
        this.listener = listener;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    public synchronized void start() {
        if (running.get()) return;
        // 参考 SOMCP 方案：重置门控状态，递增 generation
        stopRequested = false;
        generation.incrementAndGet();
        // 取消任何待处理的重连
        cancelPendingReconnect();
        running.set(true);
        connected.set(false);
        reconnectAttempts = 0;
        currentPublicUrl = null;
        // 重置健康检查状态
        healthCheckPassed = false;
        healthCheckDownSince = 0L;
        healthCheckLastRestartAt = 0L;
        healthCheckStableCount = 0;
        executor.submit(this::startInternal);
    }

    private void startInternal() {
        final int runGeneration = generation.get();

        // 参考 SOMCP 方案：startInternal 入口检查 stopRequested 和 generation
        if (stopRequested || generation.get() != runGeneration) {
            running.set(false);
            return;
        }

        // 防止并发执行
        synchronized (lock) {
            if (tunnelProcess != null && tunnelProcess.isAlive()) {
                fireLog("上一个进程仍在运行，先停止");
                stopProcess();
            }
        }

        try {
            // 1. 查找或下载 cloudflared
            cloudflaredPath = findCloudflaredPath();
            if (cloudflaredPath == null) {
                fireLog("cloudflared 未找到，开始自动下载...");
                cloudflaredPath = downloadCloudflared();
                if (cloudflaredPath == null) {
                    fireError("cloudflared 下载失败，请手动下载并放置到 " + context.getFilesDir().getAbsolutePath() + "/");
                    running.set(false);
                    return;
                }
                fireLog("cloudflared 下载完成: " + cloudflaredPath);
            } else {
                fireLog("找到 cloudflared: " + cloudflaredPath);
            }

            // 参考 SOMCP 方案：generation 检查 — 下载后可能已被 stop()
            if (stopRequested || generation.get() != runGeneration) {
                fireLog("startInternal: 在启动前已被停止，放弃本次启动");
                running.set(false);
                return;
            }

            // 2. 预解析边缘节点 IP（IPv4 only）
            List<String> edgeIps = resolveEdgeIps();

            // 3. QUICK 模式：从 Java 调用 API 注册隧道，完全绕过 Go 的 DNS 解析
        //    （参考 SOMCP 方案）
        String tunnelId = null;
        String tunnelHostname = null;
        String tunnelSecret = null;
        String configFilePath = null;

        if (mode == TunnelMode.QUICK) {
            fireLog("通过 Java 注册快速隧道 (API: api.trycloudflare.com)...");
            String apiResult = callQuickTunnelApi();
            if (apiResult == null) {
                fireError("快速隧道注册失败：API 调用失败（DNS 解析可能异常）");
                running.set(false);
                if (listener != null) listener.onDisconnected();
                return;
            }
            try {
                org.json.JSONObject json = new org.json.JSONObject(apiResult);
                org.json.JSONObject result = json.getJSONObject("result");
                tunnelId = result.getString("id");
                tunnelHostname = "https://" + result.getString("hostname");
                tunnelSecret = result.getString("secret");
                String accountTag = result.getString("account_tag");
                fireLog("隧道注册成功: id=" + tunnelId + " hostname=" + tunnelHostname);
                fireLog("accountTag=" + accountTag + " secret=[已隐藏]");

                String appCacheDir = context.getCacheDir().getAbsolutePath();

                // 写入凭证文件
                String credsPath = appCacheDir + "/tunnel_creds.json";
                org.json.JSONObject creds = new org.json.JSONObject();
                creds.put("AccountTag", accountTag);
                creds.put("TunnelID", tunnelId);
                creds.put("TunnelSecret", tunnelSecret);
                try (java.io.FileWriter fw = new java.io.FileWriter(credsPath)) {
                    fw.write(creds.toString());
                    fw.flush();
                }
                fireLog("凭证文件已写入: " + credsPath);

                // 写入 YAML 配置文件（参考 SOMCP 方案，包含 retry-dns-errors: true）
                configFilePath = appCacheDir + "/tunnel_config.yml";
                String configYaml =
                    "tunnel: " + tunnelId + "\n" +
                    "credentials-file: " + credsPath + "\n" +
                    "protocol: auto\n" +
                    "no-autoupdate: true\n" +
                    "edge-ip-version: \"4\"\n" +
                    "retry-dns-errors: true\n" +
                    "ingress:\n" +
                    "  - hostname: " + tunnelHostname.replace("https://", "") + "\n" +
                    "    service: http://localhost:" + localPort + "\n" +
                    "  - service: http_status:404\n";
                try (java.io.FileWriter fw = new java.io.FileWriter(configFilePath)) {
                    fw.write(configYaml);
                    fw.flush();
                }
                fireLog("配置文件已写入: " + configFilePath);
            } catch (Exception e) {
                fireError("解析隧道注册响应失败: " + e.getMessage());
                running.set(false);
                if (listener != null) listener.onDisconnected();
                return;
            }
        }

        // 参考 SOMCP 方案：generation 检查 — API 注册后可能已被 stop()
        if (stopRequested || generation.get() != runGeneration) {
            fireLog("startInternal: API 注册后被停止，放弃本次启动");
            running.set(false);
            return;
        }

        // 4. 构建命令列表（参考 SOMCP 方案）
        String appCacheDir = context.getCacheDir().getAbsolutePath();
        java.util.List<String> cmdList = new java.util.ArrayList<>();
        cmdList.add(cloudflaredPath);
        cmdList.add("tunnel");
        cmdList.add("--no-autoupdate");
        // 添加预解析的边缘节点 IP（绕过 DNS）
        for (String edgeIp : edgeIps) {
            cmdList.add("--edge");
            cmdList.add(edgeIp);
        }

        if (mode == TunnelMode.QUICK) {
            // QUICK 模式：使用 YAML 配置文件 + tunnel run（参考 SOMCP 方案）
            cmdList.add("--config");
            cmdList.add(configFilePath);
            cmdList.add("run");
            cmdList.add(tunnelId);
            fireLog("启动快速隧道 (Java API + YAML config + tunnel run)，公网地址: " + tunnelHostname);
        } else {
            // PERMANENT 模式：直接使用 token
            cmdList.add("--edge-ip-version");
            cmdList.add("4");
            cmdList.add("run");
            cmdList.add("--token");
            cmdList.add(token);
            fireLog("启动永久隧道 (IPv4 forced)");
        }

        // 5. 启动进程
        ProcessBuilder pb = new ProcessBuilder(cmdList);
        String appBinDir = context.getFilesDir().getAbsolutePath();
        String existingPath = System.getenv("PATH");
        if (existingPath == null) existingPath = "";
        pb.environment().put("PATH", appBinDir + ":/data/local/tmp:" + existingPath);
        pb.environment().put("HOME", appCacheDir);
        pb.environment().put("NO_AUTOUPDATE", "true");
        // 使用 cgo 模式让 Go 使用 Android 系统 DNS 解析器（而非读取 /etc/resolv.conf）
        // 避免设备上 IPv6 DNS 不可用导致的解析失败
        pb.environment().put("GODEBUG", "netdns=cgo");
        pb.directory(new File(appCacheDir));
        // 合并 stderr 到 stdout，避免 stderr 缓冲区满导致进程阻塞
        pb.redirectErrorStream(true);

            synchronized (lock) {
                fireLog("执行: " + String.join(" ", cmdList));
                tunnelProcess = pb.start();
            }

            // QUICK 模式：API 已返回公网地址，立即通知连接成功
            if (mode == TunnelMode.QUICK && tunnelHostname != null) {
                currentPublicUrl = tunnelHostname;
                connected.set(true);
                fireLog(">>> 隧道已连接! 公网地址: " + tunnelHostname);
                if (listener != null) {
                    listener.onConnected(tunnelHostname);
                }
            }

            // 6. 启动连接超时检测
            startConnectTimeout();

            // 7. 启动本地端口健康检查（参考 SOMCP 的 startHealthCheck 方案）
            // 每 5 秒探测一次本地服务端口，确保服务可达
            startHealthCheck();

            // 7. 读取 stdout（同步读取，确保不丢失输出）
            final Process proc = tunnelProcess;
            final int captureGeneration = runGeneration;
            StringBuilder outputBuffer = new StringBuilder();
            outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while (running.get() && (line = reader.readLine()) != null) {
                        // 参考 SOMCP 方案：generation 检查
                        if (generation.get() != captureGeneration || stopRequested) break;
                        lastOutputTime.set(System.currentTimeMillis());
                        outputBuffer.append(line).append("\n");
                        processLine(line);
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        fireLog("stdout 读取错误: " + e.getMessage());
                    }
                }
            }, "cf-stdout");
            outputThread.setDaemon(true);
            outputThread.start();

            // 8. 等待进程结束
            int exitCode = proc.waitFor();
            fireLog("cloudflared 进程退出，退出码: " + exitCode);

            if (running.get()) {
                running.set(false);
                connected.set(false);
                if (exitCode != 0) {
                    String output = outputBuffer.toString().trim();
                    fireLog("cloudflared 输出:\n" + (output.isEmpty() ? "(无输出)" : output));
                    fireLog("错误: cloudflared 异常退出 (" + exitCode + "): " + getExitCodeDescription(exitCode));
                    fireError("cloudflared 异常退出 (" + exitCode + "): " + getExitCodeDescription(exitCode));
                }
                if (listener != null) listener.onDisconnected();

                // 自动重连（进程启动但异常退出时）
                if (autoReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    scheduleReconnect();
                }
            }

        } catch (IOException e) {
            String msg = e.getMessage();
            fireLog("启动失败: " + msg);
            if (msg != null && msg.contains("No such file or directory")) {
                running.set(false);
                fireError("无法启动 cloudflared: 系统 shell 不可用或 cloudflared 无法执行。请确保 cloudflared 有执行权限");
            } else if (msg != null && msg.contains("Permission denied")) {
                running.set(false);
                fireError("cloudflared 权限不足，请确保文件可执行");
            } else {
                running.set(false);
                fireError("cloudflared 启动失败: " + msg);
            }
            if (listener != null) listener.onDisconnected();
        } catch (Exception e) {
            fireLog("cloudflared 错误: " + e.getMessage());
            running.set(false);
            connected.set(false);
            fireError("cloudflared 启动失败: " + e.getMessage());
            if (listener != null) listener.onDisconnected();
        }
    }

    private void processLine(String line) {
        // 过滤重复/无意义的日志行
        if (shouldSkipLogLine(line)) return;

        fireLog(line);

        // 参考 SOMCP 方案：检测 Registered tunnel connection 作为连接确认信号
        if (line.contains("INF Registered tunnel connection")) {
            if (!connected.getAndSet(true)) {
                String url = currentPublicUrl;
                if (url == null) url = "https://" + extractHostname(line);
                fireLog(">>> 隧道已连接! " + (url != null ? "公网地址: " + url : ""));
                if (listener != null) {
                    listener.onConnected(url != null ? url : "");
                }
            }
            return;
        }

        if (mode == TunnelMode.QUICK) {
            Matcher urlMatcher = URL_PATTERN.matcher(line);
            String matchedUrl = null;
            if (urlMatcher.find()) {
                matchedUrl = urlMatcher.group();
            } else {
                Matcher anyMatcher = URL_ANY_PATTERN.matcher(line);
                if (anyMatcher.find()) {
                    String candidate = anyMatcher.group();
                    if (candidate.contains("trycloudflare.com")) {
                        matchedUrl = candidate;
                    }
                }
            }
            if (matchedUrl != null) {
                if (!matchedUrl.startsWith("http://") && !matchedUrl.startsWith("https://")) {
                    matchedUrl = "https://" + matchedUrl;
                }
                while (matchedUrl.endsWith(".") || matchedUrl.endsWith(")") || matchedUrl.endsWith("]")) {
                    matchedUrl = matchedUrl.substring(0, matchedUrl.length() - 1);
                }
                if (!connected.getAndSet(true)) {
                    currentPublicUrl = matchedUrl;
                    fireLog(">>> 隧道已连接! 公网地址: " + matchedUrl);
                    if (listener != null) {
                        listener.onConnected(matchedUrl);
                    }
                }
            }
        }
    }

    /**
     * 从日志行中提取主机名（参考 SOMCP 的 urlPattern）
     */
    private String extractHostname(String line) {
        java.util.regex.Matcher m = URL_PATTERN.matcher(line);
        if (m.find()) {
            String url = m.group();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            return url;
        }
        return null;
    }

    /**
     * 过滤重复/无意义的 cloudflared 日志行。
     * 参考 SOMCP 方案：保留 Registered tunnel connection 作为连接确认信号，
     * 保留 WAR 级别日志用于问题诊断。
     */
    private boolean shouldSkipLogLine(String line) {
        if (line == null || line.isEmpty()) return true;

        // 跳过 INF 级别的感谢/提示信息（只在启动时出现一次，无实际意义）
        if (line.contains("INF Thank you for trying")) return true;
        if (line.contains("INF Requesting new quick Tunnel")) return true;
        if (line.contains("INF Each connection will be")) return true;
        if (line.contains("INF Cloudflare Tunnel")) return true;
        if (line.contains("INF 欢迎使用")) return true;
        // 跳过指标上报（无实际意义）
        if (line.contains("INF Metrics")) return true;
        // 跳过 DBG 级别调试日志
        if (line.contains("DBG")) return true;

        return false;
    }

    private void startConnectTimeout() {
        Thread timeoutThread = new Thread(() -> {
            long startTime = System.currentTimeMillis();
            while (running.get() && !connected.get()) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > CONNECT_TIMEOUT_MS) {
                    fireLog("连接超时 (" + CONNECT_TIMEOUT_MS / 1000 + "秒)");
                    fireError("连接超时，未能建立 Cloudflare 隧道");
                    // 先保存重连标志，再停止
                    boolean shouldReconnect = autoReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS;
                    stop();
                    if (shouldReconnect) {
                        scheduleReconnect();
                    }
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "cf-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();
    }

    /**
     * 启动本地端口健康检查（参考 SOMCP 的 startHealthCheck + probeLocal 方案）。
     * 每 5 秒探测一次本地端口，检测服务是否可达。
     *
     * 防抖逻辑（参考 SOMCP）：
     * - 连续失败超过 8 秒才触发保活重启
     * - 两次保活重启至少间隔 15 秒
     * - 重启前检查 stopRequested，防止在停止过程中误重启
     * - 整个循环包在 try/catch(InterruptedException) 中，防止 stop() 中断线程时崩溃
     */
    private void startHealthCheck() {
        stopHealthCheck();
        final int runGeneration = generation.get();
        healthCheckThread = new Thread(() -> {
            // 参考 SOMCP 方案：防抖状态变量
            int stable = 0;
            long downSince = 0L;
            long lastRestartAt = 0L;
            try {
                while (!stopRequested && generation.get() == runGeneration && running.get()) {
                    try {
                        Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        // 参考 SOMCP：stop() 中断睡眠时预期抛出，直接退出循环
                        break;
                    }
                    if (stopRequested || generation.get() != runGeneration || !running.get()) break;

                    // TCP 探活本地端口（参考 SOMCP 的 probeLocal）
                    boolean probeOk = false;
                    try {
                        java.net.Socket s = new java.net.Socket();
                        s.connect(new java.net.InetSocketAddress("127.0.0.1", localPort), (int) HEALTH_CHECK_TIMEOUT_MS);
                        s.close();
                        probeOk = true;
                    } catch (Exception ignored) {
                    }

                    if (probeOk) {
                        stable++;
                        downSince = 0L;
                        if (!healthCheckPassed) {
                            healthCheckPassed = true;
                            healthCheckDownSince = 0L;
                            healthCheckStableCount = 0;
                            fireLog("[健康检查] 本地服务端口 " + localPort + " 可达 ✓");
                        }
                    } else {
                        stable = 0;
                        healthCheckPassed = false;
                        if (downSince == 0L) downSince = System.currentTimeMillis();
                        healthCheckDownSince = downSince;
                        long downElapsed = System.currentTimeMillis() - downSince;
                        fireLog("[健康检查] 本地服务端口 " + localPort + " 不可达 ✗ (已持续 " + (downElapsed / 1000) + " 秒)");

                        // 参考 SOMCP：仅当隧道已连接时才触发保活重启
                        if (connected.get() && downElapsed >= HEALTH_CHECK_DOWN_THRESHOLD_MS) {
                            long sinceLastRestart = System.currentTimeMillis() - lastRestartAt;
                            if (sinceLastRestart >= HEALTH_CHECK_RESTART_COOLDOWN_MS) {
                                // 参考 SOMCP：重启前检查 stopRequested 硬门控
                                if (stopRequested) {
                                    fireLog("[健康检查] 保活重启被 stopRequested 阻止");
                                    break;
                                }
                                lastRestartAt = System.currentTimeMillis();
                                healthCheckLastRestartAt = lastRestartAt;
                                keepaliveRestarts.incrementAndGet();
                                fireLog("[健康检查] 端口持续不可达，触发生保活重启 (距上次重启 " + (sinceLastRestart / 1000) + " 秒)");
                                fireError("本地服务端口 " + localPort + " 持续不可达，触发隧道保活重启");
                                // 参考 SOMCP：使用 startInternal 风格的重新启动
                                // 这里通过 stop() + start() 实现重启，generation 会递增，旧健康线程会退出
                                stop();
                                start();
                                return;
                            } else {
                                fireLog("[健康检查] 距上次重启仅 " + (sinceLastRestart / 1000) + " 秒，跳过保活重启");
                            }
                        } else if (connected.get()) {
                            fireLog("[健康检查] 端口不可达但未超过阈值 (" + HEALTH_CHECK_DOWN_THRESHOLD_MS / 1000 + " 秒)，等待...");
                        }
                    }
                }
            } catch (Exception e) {
                if (!stopRequested) {
                    fireLog("[健康检查] 异常: " + e.getMessage());
                }
            }
        }, "cf-health");
        healthCheckThread.setDaemon(true);
        healthCheckThread.start();
    }

    private void stopHealthCheck() {
        if (healthCheckThread != null) {
            healthCheckThread.interrupt();
            healthCheckThread = null;
        }
    }

    private void scheduleReconnect() {
        reconnectAttempts++;
        int delay = Math.min((int) (RECONNECT_DELAY_MS * Math.pow(1.5, reconnectAttempts - 1)), 30000);
        fireLog("将在 " + (delay / 1000) + " 秒后自动重连 (第 " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + " 次)");
        // 取消之前的待处理重连（防止重复调度）
        cancelPendingReconnect();
        pendingReconnectRunnable = () -> {
            // 只有 autoReconnect 仍为 true 且未在运行时才重连
            // 如果用户调用了 stop()，autoReconnect 会被设为 false，此处不会执行
            if (!running.get() && autoReconnect) {
                fireLog("开始自动重连...");
                pendingReconnectRunnable = null;
                start();
            }
        };
        mainHandler.postDelayed(pendingReconnectRunnable, delay);
    }

    /**
     * 参考 SOMCP 方案：轻量级停止请求，从主线程同步设置 stopRequested，
     * 防止在 stop() 实际执行前有重连/health 线程重新进入 start()。
     */
    public void requestStop() {
        stopRequested = true;
        generation.incrementAndGet();
        autoReconnect = false;
        cancelPendingReconnect();
    }

    public synchronized void stop() {
        stopRequested = true;
        autoReconnect = false;
        generation.incrementAndGet();
        running.set(false);
        connected.set(false);
        cancelPendingReconnect();
        stopProcess();
    }

    /**
     * 强制断开隧道，不再重连。
     * 与 stop() 的区别：forceStop() 确保即使 KeepAlive 机制也无法重启此隧道。
     * 调用后 autoReconnect 被永久设为 false，且不会自动恢复。
     */
    public synchronized void forceStop() {
        stopRequested = true;
        autoReconnect = false;
        generation.incrementAndGet();
        running.set(false);
        connected.set(false);
        cancelPendingReconnect();
        stopProcess();
        // 清除所有可能触发重连的状态
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS; // 防止意外重连
    }

    private void cancelPendingReconnect() {
        if (pendingReconnectRunnable != null) {
            mainHandler.removeCallbacks(pendingReconnectRunnable);
            pendingReconnectRunnable = null;
        }
    }

    private void stopProcess() {
        synchronized (lock) {
            // 停止健康检查
            stopHealthCheck();
            if (tunnelProcess != null) {
                tunnelProcess.destroy();
                try {
                    tunnelProcess.waitFor(3000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
                if (tunnelProcess.isAlive()) {
                    tunnelProcess.destroyForcibly();
                }
                tunnelProcess = null;
            }
            if (outputThread != null) {
                outputThread.interrupt();
                outputThread = null;
            }
            if (stderrThread != null) {
                stderrThread.interrupt();
                stderrThread = null;
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String getCurrentPublicUrl() {
        return currentPublicUrl;
    }

    public TunnelMode getMode() {
        return mode;
    }

    // ===== 日志和事件回调 =====

    private void fireLog(String message) {
        Log.d(TAG, message);
        if (listener != null) listener.onLog(message);
    }

    private void fireError(String message) {
        Log.e(TAG, message);
        if (listener != null) listener.onError(message);
    }

    // ===== cloudflared 二进制管理 =====

    private String findCloudflaredPath() {
        String filesDir = context.getFilesDir().getAbsolutePath();
        String filesPath = filesDir + "/cloudflared";
        File filesFile = new File(filesPath);

        // 诊断日志：打印 native library 目录路径
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        String nativeLibPath = nativeLibDir + "/libcloudflared.so";
        fireLog("[诊断] nativeLibraryDir: " + nativeLibDir);
        fireLog("[诊断] nativeLibPath: " + nativeLibPath + " exists=" + new File(nativeLibPath).exists());

        // 1. 优先使用 native library 目录（系统安装时提取，无 noexec 限制，最可靠）
        //    参考 SOMCP 方案：直接使用 nativeLibraryDir/libcloudflared.so 路径
        File nativeLibFile = new File(nativeLibPath);
        if (nativeLibFile.exists()) {
            fireLog("使用原生库: " + nativeLibPath + " (大小: " + nativeLibFile.length() / 1024 + " KB)");
            return nativeLibPath;
        }

        // 2. 检查 files 目录（已下载的二进制文件）
        if (filesFile.exists()) {
            // noexec 文件系统上 canExecute() 可能返回 false，但文件确实存在
            // 尝试设置执行权限，如果失败仍返回路径（让 ProcessBuilder 尝试）
            filesFile.setExecutable(true, false);
            fireLog("使用 files 目录 cloudflared: " + filesPath);
            return filesPath;
        }

        // 3. 检查其他常见路径
        String[] paths = {
            nativeLibDir + "/cloudflared",
            filesDir + "/cloudflared-linux-arm64",
            "/data/local/tmp/cloudflared",
            "/data/local/tmp/cloudflared-linux-arm64",
            "/system/bin/cloudflared",
            "/system/xbin/cloudflared"
        };
        for (String path : paths) {
            File f = new File(path);
            if (f.exists()) {
                f.setExecutable(true, false);
                return path;
            }
        }

        return null;
    }

    private String downloadCloudflared() {
        String destPath = context.getFilesDir().getAbsolutePath() + "/cloudflared";
        File destFile = new File(destPath);

        // 先检查 native library 目录（系统已提取，无需下载）
        String nativePath = context.getApplicationInfo().nativeLibraryDir + "/libcloudflared.so";
        File nativeFile = new File(nativePath);
        if (nativeFile.exists()) {
            fireLog("原生库已存在，无需下载: " + nativePath);
            if (listener != null) listener.onDownloadProgress(100);
            return nativePath;
        }

        // 收集所有要尝试的下载 URL
        String arch = getArchitecture();
        String primaryUrl = getDownloadUrlForArch(arch);
        java.util.ArrayList<String> urlsToTry = new java.util.ArrayList<>();
        urlsToTry.add(primaryUrl);
        // 如果主 URL 不是 ARM64，也尝试 ARM64
        if (!primaryUrl.contains("arm64")) {
            urlsToTry.add(CLOUDFLARED_DOWNLOAD_URL);
        }
        // 添加备用版本 URL
        for (String fallback : CLOUDFLARED_FALLBACK_URLS) {
            if (!urlsToTry.contains(fallback)) {
                urlsToTry.add(fallback);
            }
        }

        fireLog("设备架构: " + arch);
        fireLog("将尝试 " + urlsToTry.size() + " 个下载地址");

        for (int attempt = 0; attempt < urlsToTry.size(); attempt++) {
            String downloadUrl = urlsToTry.get(attempt);
            File tempFile = new File(destPath + ".tmp");

            try {
                if (tempFile.exists()) tempFile.delete();

                fireLog("下载尝试 " + (attempt + 1) + "/" + urlsToTry.size() + ": " + downloadUrl);

                URL url = new URL(downloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                connection.connect();

                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    fireLog("HTTP " + responseCode + "，跳过此地址");
                    continue;
                }

                int contentLength = connection.getContentLength();
                fireLog("文件大小: " + (contentLength > 0 ? (contentLength / 1024) + " KB" : "未知"));

                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalBytes = 0;
                    int lastPercent = -1;

                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                        if (contentLength > 0) {
                            int percent = (int) (totalBytes * 100 / contentLength);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                final int p = percent;
                                mainHandler.post(() -> {
                                    if (listener != null) listener.onDownloadProgress(p);
                                });
                            }
                        }
                    }
                    output.flush();
                }

                if (tempFile.renameTo(destFile) || destFile.exists()) {
                    destFile.setExecutable(true, false);
                    fireLog("下载完成，大小: " + (destFile.length() / 1024) + " KB");
                    if (listener != null) listener.onDownloadProgress(100);
                    return destPath;
                } else {
                    fireLog("文件移动失败");
                    tempFile.delete();
                    continue;
                }

            } catch (IOException e) {
                fireLog("下载异常: " + e.getMessage());
                if (tempFile.exists()) tempFile.delete();
            } catch (Exception e) {
                fireLog("下载失败: " + e.getMessage());
                if (tempFile.exists()) tempFile.delete();
            }
        }

        fireError("所有下载地址均失败，请检查网络连接");
        return null;
    }

    private String getArchitecture() {
        try {
            Process process = Runtime.getRuntime().exec("uname -m");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            String arch = reader.readLine();
            if (arch != null) return arch.trim().toLowerCase();
        } catch (IOException ignored) {}
        try {
            String arch = System.getProperty("os.arch");
            if (arch != null) return arch.toLowerCase();
        } catch (Exception ignored) {}
        return "aarch64";
    }

    private String getDownloadUrlForArch(String arch) {
        String base = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-";
        if (arch.contains("aarch64") || arch.contains("arm64")) return base + "arm64";
        else if (arch.contains("armv7") || arch.contains("armv8l") || arch.contains("arm")) return base + "arm";
        else if (arch.contains("x86_64") || arch.contains("amd64")) return base + "amd64";
        else if (arch.contains("i686") || arch.contains("i386") || arch.contains("x86")) return base + "386";
        return base + "arm64";
    }

    private String getExitCodeDescription(int exitCode) {
        switch (exitCode) {
            case 0: return "正常退出";
            case 1: return "一般错误";
            case 2: return "误用 shell 内建命令";
            case 126: return "命令不可执行（权限不足）";
            case 127: return "命令未找到";
            case 130: return "被 Ctrl+C 中断";
            case 137: return "被 SIGKILL 杀死（可能 OOM）";
            case 139: return "段错误 (Segmentation Fault)";
            case 143: return "被 SIGTERM 终止";
            default: return "未知错误 (" + exitCode + ")";
        }
    }

    // ===== DNS 辅助方法（参考 SOMCP 方案） =====

    /**
     * 检测指定主机名是否可解析（使用 Java 的 InetAddress，绕过系统 DNS 限制）
     * @return 解析到的 IP 地址字符串，失败返回 null
     */
    private String checkDnsResolution(String hostname) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(hostname);
            if (addresses != null && addresses.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (InetAddress addr : addresses) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(addr.getHostAddress());
                }
                return sb.toString();
            }
        } catch (Exception e) {
            fireLog("DNS 解析失败 " + hostname + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * 预解析 Cloudflare 边缘节点 IP（IPv4 only），参考 SOMCP 的 edgeIps() 方法。
     * 将主机名解析为 IP:PORT 格式，直接传给 cloudflared 的 --edge 参数，绕过 DNS。
     */
    private List<String> resolveEdgeIps() {
        List<String> result = new ArrayList<>();
        // 使用 IPv4 边缘节点主机名
        String[] hosts = {"region1.argotunnel.com", "region2.argotunnel.com"};
        // 边缘节点端口固定为 7844
        int port = 7844;

        for (String host : hosts) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress addr : addresses) {
                    // 只取 IPv4 地址
                    if (addr instanceof java.net.Inet4Address) {
                        String edge = addr.getHostAddress() + ":" + port;
                        if (!result.contains(edge)) {
                            result.add(edge);
                            fireLog("解析边缘节点: " + host + " -> " + edge);
                        }
                    }
                }
            } catch (Exception e) {
                fireLog("解析边缘节点失败 " + host + ": " + e.getMessage());
            }
        }

        if (result.isEmpty()) {
            fireLog("未解析到边缘节点 IP，将不使用 --edge 参数");
        } else {
            fireLog("共解析到 " + result.size() + " 个边缘节点 IP");
        }
        return result;
    }

    // ===== Java API 调用（绕过 Go DNS 解析器）=====

    /**
     * 从 Java 调用 api.trycloudflare.com 注册快速隧道。
     * Java 的 HttpURLConnection 使用 Android 原生 DNS 解析器，可以正常解析域名。
     * 返回 API 响应的 JSON 字符串，失败返回 null。
     */
    private String callQuickTunnelApi() {
        try {
            URL url = new URL("https://api.trycloudflare.com/tunnel");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            // POST 空 body
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write("{}".getBytes("UTF-8"));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            fireLog("API 响应码: " + responseCode);

            if (responseCode != 200) {
                // 读取错误响应
                String errorBody = "";
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), "UTF-8"))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    errorBody = sb.toString();
                } catch (Exception ignored) {}
                fireLog("API 错误响应: " + errorBody);
                return null;
            }

            // 读取成功响应
            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            String jsonStr = response.toString();
            fireLog("API 响应: " + jsonStr);
            return jsonStr;

        } catch (Exception e) {
            fireLog("API 调用异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }
}