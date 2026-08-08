package com.mcpbridge.enhanced.tunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bore 隧道客户端 - 严格遵循 ekzhang/bore 协议
 *
 * 协议 (ekzhang/bore shared.rs):
 * 1. 连接控制端口 (默认 7835)
 * 2. 客户端发送 {"Hello":本地端口}\0
 * 3. 服务器响应 {"Hello":分配端口}\0 或 {"Error":"消息"}\0
 * 4. 控制通道循环接收:
 *    - {"Heartbeat":null}\0 (心跳)
 *    - {"Connection":"uuid"|数字}\0 (新连接通知)
 *    - {"Error":"消息"}\0 (服务器错误)
 * 5. 每个 Connection: 新建数据连接 → 发送 {"Accept":"连接ID"}\0 → 双向转发
 * 6. 可选认证: {"Challenge":"uuid"}\0 → {"Authenticate":"密码"}\0
 *
 * 兼容 bore.pub 等非标准服务器: 服务器主动发送 Hello，无需客户端先发
 */
public class BoreClient {

    public interface BoreListener {
        void onConnected(String publicUrl);
        void onDisconnected();
        void onError(String message);
        void onBytesTransferred(long bytes);
        void onConnectionEvent(String event);
    }

    private final String boreHost;
    private final int borePort;
    private final int localPort;
    private final String secret;
    private BoreListener listener;
    private Thread tunnelThread;
    private volatile boolean running = false;
    private final ExecutorService dataExecutor;

    private static final int DEFAULT_BORE_PORT = 7835;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int LOCAL_CONNECT_TIMEOUT_MS = 5000;
    private static final int MAX_DATA_THREADS = 32;
    private static final byte NULL_DELIMITER = 0x00;
    private static final int MAX_FRAME_LENGTH = 65536;

    // 自动重连
    private volatile boolean autoReconnect = true;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int RECONNECT_DELAY_MS = 5000;
    private Thread reconnectThread;
    private volatile boolean stopRequested = false;

    // 参考 SOMCP 方案：generation 并发控制
    private final AtomicInteger generation = new AtomicInteger(0);

    public BoreClient(String boreHost, int localPort) {
        this(parseHost(boreHost), parsePort(boreHost, DEFAULT_BORE_PORT), localPort, parseSecret(boreHost));
    }

    public BoreClient(String boreHost, int borePort, int localPort) {
        this(boreHost, borePort, localPort, null);
    }

    public BoreClient(String boreHost, int borePort, int localPort, String secret) {
        this.boreHost = boreHost != null && !boreHost.isEmpty() ? boreHost : "bore.pub";
        this.borePort = borePort > 0 ? borePort : DEFAULT_BORE_PORT;
        this.localPort = localPort > 0 ? localPort : 8080;
        this.secret = secret;
        this.dataExecutor = Executors.newFixedThreadPool(MAX_DATA_THREADS);
    }

    private static String parseHost(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) return "bore.pub";
        // 去掉 http:// 或 https:// 前缀
        String raw = hostPort;
        if (raw.startsWith("https://")) raw = raw.substring(8);
        else if (raw.startsWith("http://")) raw = raw.substring(7);
        int colonIdx = raw.indexOf(':');
        if (colonIdx > 0) {
            return raw.substring(0, colonIdx);
        }
        return raw;
    }

    private static int parsePort(String hostPort, int defaultPort) {
        if (hostPort == null || hostPort.isEmpty()) return defaultPort;
        // 去掉 http:// 或 https:// 前缀
        String raw = hostPort;
        if (raw.startsWith("https://")) raw = raw.substring(8);
        else if (raw.startsWith("http://")) raw = raw.substring(7);
        int colonIdx = raw.indexOf(':');
        if (colonIdx > 0 && colonIdx < raw.length() - 1) {
            try {
                return Integer.parseInt(raw.substring(colonIdx + 1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultPort;
    }

    private static String parseSecret(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) return null;
        // 去掉 http:// 或 https:// 前缀
        String raw = hostPort;
        if (raw.startsWith("https://")) raw = raw.substring(8);
        else if (raw.startsWith("http://")) raw = raw.substring(7);
        int firstColon = raw.indexOf(':');
        if (firstColon <= 0) return null;
        int secondColon = raw.indexOf(':', firstColon + 1);
        if (secondColon > 0 && secondColon < raw.length() - 1) {
            return raw.substring(secondColon + 1);
        }
        return null;
    }

    public void setListener(BoreListener listener) {
        this.listener = listener;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    private void fireEvent(String event) {
        if (listener != null) listener.onConnectionEvent(event);
    }

    private String now() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    public synchronized void start() {
        if (running) return;
        // 参考 SOMCP 方案：重置门控状态，递增 generation
        stopRequested = false;
        generation.incrementAndGet();
        running = true;
        reconnectAttempts.set(0);

        final int runGeneration = generation.get();

        tunnelThread = new Thread(() -> {
            Socket controlSocket = null;
            stopRequested = false;

            // 参考 SOMCP 方案：generation 检查 — 启动后可能已被 stop()
            if (generation.get() != runGeneration || stopRequested) {
                running = false;
                return;
            }

            try {
                fireEvent(now() + " 正在连接 " + boreHost + ":" + borePort + "...");
                controlSocket = new Socket();
                controlSocket.connect(
                    new InetSocketAddress(boreHost, borePort),
                    CONNECT_TIMEOUT_MS
                );
                controlSocket.setSoTimeout(0);

                InputStream controlIn = controlSocket.getInputStream();
                OutputStream controlOut = controlSocket.getOutputStream();

                fireEvent(now() + " 已连接到 " + boreHost + ":" + borePort);

                // 启用 TCP keepalive 防止连接异常断开
                try {
                    controlSocket.setKeepAlive(true);
                } catch (SocketException e) {
                    // keepalive 不是关键功能，忽略失败
                }

                // ===== 协议握手 (标准 ekzhang/bore 协议) =====
                // 1. 客户端发送 {"Hello":期望的远程端口}\0 (0=让服务器自动分配)
                // 2. 服务器响应 {"Hello":分配的公网端口}\0
                // 3. 可选认证: 先收到 {"Challenge":"uuid"}\0
                controlSocket.setSoTimeout(10000);

                // 发送 Hello，请求服务器自动分配公网端口
                // 注意: Hello 的值是期望的远程端口，不是本地端口
                // 本地端口仅用于客户端收到连接后转发到本地服务
                String helloMsg = "{\"Hello\":0}\0";
                controlOut.write(helloMsg.getBytes(StandardCharsets.UTF_8));
                controlOut.flush();
                fireEvent(now() + " 已发送Hello消息，请求分配公网端口");

                // 读取服务器响应
                String response = readJsonMessage(controlIn);
                if (response == null) {
                    throw new IOException("未收到服务器响应");
                }

                int assignedPort = -1;

                // 处理可能的 Challenge 认证
                if (response.contains("\"Challenge\"")) {
                    if (secret == null || secret.isEmpty()) {
                        throw new IOException("服务器需要认证但未提供密码");
                    }
                    String challengeId = parseStringValue(response, "Challenge");
                    fireEvent(now() + " 服务器要求认证, Challenge=" + challengeId);
                    String authMsg = "{\"Authenticate\":\"" + escapeJson(secret) + "\"}\0";
                    controlOut.write(authMsg.getBytes(StandardCharsets.UTF_8));
                    controlOut.flush();
                    fireEvent(now() + " 已发送认证响应");
                    // 认证后读取 Hello 响应
                    response = readJsonMessage(controlIn);
                    if (response == null) {
                        throw new IOException("认证后未收到服务器响应");
                    }
                }

                if (response.contains("\"Hello\"")) {
                    assignedPort = parseHelloResponse(response);
                    fireEvent(now() + " 服务器已分配端口: " + assignedPort);
                } else if (response.contains("\"Error\"")) {
                    String errMsg = parseStringValue(response, "Error");
                    throw new IOException("服务器错误: " + (errMsg != null ? errMsg : response));
                } else {
                    // 尝试兼容非标准服务器（如 bore.pub 可能先发 Connection）
                    // 忽略非 Hello 消息，等待下一条
                    fireEvent(now() + " 收到非预期消息，等待 Hello: " + response.substring(0, Math.min(50, response.length())));
                    controlSocket.setSoTimeout(15000);
                    response = readJsonMessage(controlIn);
                    if (response != null && response.contains("\"Hello\"")) {
                        assignedPort = parseHelloResponse(response);
                        fireEvent(now() + " 服务器已分配端口: " + assignedPort);
                    } else if (response != null && response.contains("\"Connection\"")) {
                        // 某些服务器直接发 Connection 消息（可能是 Hello 已隐含）
                        // 尝试从旧响应中解析端口
                        fireEvent(now() + " 收到 Connection 消息，视为已连接");
                        assignedPort = 1; // 非零即表示已连接
                    } else {
                        throw new IOException("无法建立连接，服务器响应: " + (response != null ? response.substring(0, Math.min(50, response.length())) : "null"));
                    }
                }

                String publicUrl = "http://" + boreHost + ":" + assignedPort;
                fireEvent(now() + " 隧道已建立，公网地址: " + publicUrl);

                if (listener != null) {
                    listener.onConnected(publicUrl);
                }

                // 设置控制 socket 读取超时，以便检测心跳超时
                controlSocket.setSoTimeout(30000);

                // 控制通道循环：读取 JSON 消息
                while (running && !controlSocket.isClosed()) {
                    // 参考 SOMCP 方案：generation 检查
                    if (generation.get() != runGeneration || stopRequested) break;
                    String msg;
                    try {
                        msg = readJsonMessage(controlIn);
                    } catch (SocketTimeoutException e) {
                        // 读取超时不代表连接断开，继续等待
                        continue;
                    } catch (SocketException e) {
                        if (running) fireEvent(now() + " 控制连接异常: " + e.getMessage());
                        break;
                    } catch (IOException e) {
                        if (running) fireEvent(now() + " 读取消息失败: " + e.getMessage());
                        break;
                    }

                    if (msg == null) {
                        fireEvent(now() + " 控制连接已关闭");
                        break;
                    }

                    if (msg.contains("\"Heartbeat\"")) {
                        // 心跳消息，忽略
                        continue;
                    } else if (msg.contains("\"Connection\"")) {
                        // 兼容字符串UUID和整数ID两种格式
                        String connId = parseConnectionId(msg);
                        if (connId != null) {
                            fireEvent(now() + " 新连接请求 ID=" + connId + "，正在转发到本地 :" + localPort);
                            final String id = connId;
                            dataExecutor.submit(() -> handleDataConnection(id));
                        }
                    } else if (msg.contains("\"Error\"")) {
                        String errMsg = parseStringValue(msg, "Error");
                        fireEvent(now() + " 服务器错误: " + (errMsg != null ? errMsg : msg));
                    }
                }
            } catch (ConnectException e) {
                String msg = "无法连接到 Bore 服务器 " + boreHost + ":" + borePort
                        + " - " + e.getMessage();
                fireEvent(now() + " " + msg);
                if (listener != null) listener.onError(msg);
            } catch (SocketTimeoutException e) {
                String msg = "连接 Bore 服务器超时";
                fireEvent(now() + " " + msg);
                if (listener != null) listener.onError(msg);
            } catch (IOException e) {
                String msg = "隧道错误: " + e.getMessage();
                fireEvent(now() + " " + msg);
                if (listener != null) listener.onError(msg);
            } finally {
                running = false;
                if (controlSocket != null) {
                    try { controlSocket.close(); } catch (IOException ignored) {}
                }
                fireEvent(now() + " 隧道已断开");
                if (listener != null) listener.onDisconnected();

                // 自动重连
                if (autoReconnect && !stopRequested && reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
                    scheduleReconnect();
                }
            }
        });
        tunnelThread.setDaemon(true);
        tunnelThread.setName("bore-tunnel");
        tunnelThread.start();
    }

    private void scheduleReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();
        int delay = Math.min(RECONNECT_DELAY_MS * (1 << (attempts - 1)), 30000);
        fireEvent(now() + " 将在 " + (delay / 1000) + " 秒后自动重连 (第 " + attempts + "/" + MAX_RECONNECT_ATTEMPTS + " 次)");
        final int reconnectGeneration = generation.get();
        reconnectThread = new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                return;
            }
            if (running) return;
            // 参考 SOMCP 方案：generation 检查 — 重连前可能已被 stop()
            if (generation.get() != reconnectGeneration || stopRequested) return;
            fireEvent(now() + " 开始自动重连...");
            start();
        }, "bore-reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * 读取一条 JSON 消息 (null 字节分隔)
     * 根据 bore 协议: AnyDelimiterCodec::new_with_max_length(vec![0], vec![0], MAX_FRAME_LENGTH)
     * 直接从 InputStream 逐字节读取，避免 BufferedInputStream 预读导致数据丢失
     */
    private String readJsonMessage(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(MAX_FRAME_LENGTH);
        while (true) {
            int b = in.read();
            if (b == -1) {
                return buf.size() > 0 ? buf.toString("UTF-8") : null;
            }
            if (b == NULL_DELIMITER) {
                return buf.toString("UTF-8");
            }
            buf.write(b);
            if (buf.size() > MAX_FRAME_LENGTH) {
                throw new IOException("消息超过最大长度 " + MAX_FRAME_LENGTH);
            }
        }
    }

    private int parseHelloResponse(String json) {
        try {
            int keyIdx = json.indexOf("\"Hello\"");
            if (keyIdx < 0) return -1;
            int colonIdx = json.indexOf(':', keyIdx);
            if (colonIdx < 0) return -1;
            int startIdx = colonIdx + 1;
            while (startIdx < json.length() && json.charAt(startIdx) == ' ') startIdx++;
            int endIdx = startIdx;
            while (endIdx < json.length() && json.charAt(endIdx) >= '0' && json.charAt(endIdx) <= '9') endIdx++;
            if (startIdx == endIdx) return -1;
            return Integer.parseInt(json.substring(startIdx, endIdx));
        } catch (Exception e) {
            return -1;
        }
    }

    private String parseStringValue(String json, String key) {
        try {
            int keyIdx = json.indexOf('"' + key + '"');
            if (keyIdx < 0) return null;
            int colonIdx = json.indexOf(':', keyIdx);
            if (colonIdx < 0) return null;
            int startIdx = json.indexOf('"', colonIdx + 1);
            if (startIdx < 0) return null;
            int endIdx = json.indexOf('"', startIdx + 1);
            if (endIdx < 0) return null;
            return json.substring(startIdx + 1, endIdx);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析连接ID，兼容字符串UUID和整数ID两种格式
     */
    private String parseConnectionId(String json) {
        try {
            int keyIdx = json.indexOf("\"Connection\"");
            if (keyIdx < 0) return null;
            int colonIdx = json.indexOf(':', keyIdx);
            if (colonIdx < 0) return null;
            int startIdx = colonIdx + 1;
            while (startIdx < json.length() && json.charAt(startIdx) == ' ') startIdx++;
            if (startIdx >= json.length()) return null;
            if (json.charAt(startIdx) == '"') {
                int endIdx = json.indexOf('"', startIdx + 1);
                if (endIdx < 0) return null;
                return json.substring(startIdx + 1, endIdx);
            } else if (json.charAt(startIdx) >= '0' && json.charAt(startIdx) <= '9') {
                int endIdx = startIdx;
                while (endIdx < json.length() && json.charAt(endIdx) >= '0' && json.charAt(endIdx) <= '9') endIdx++;
                return json.substring(startIdx, endIdx);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void handleDataConnection(String connId) {
        Socket dataSocket = null;
        Socket localSocket = null;
        try {
            dataSocket = new Socket();
            dataSocket.connect(new InetSocketAddress(boreHost, borePort), CONNECT_TIMEOUT_MS);
            dataSocket.setSoTimeout(0);

            InputStream dataIn = dataSocket.getInputStream();
            OutputStream dataOut = dataSocket.getOutputStream();

            // 如果设置了密码，先检查 Challenge 认证
            if (secret != null && !secret.isEmpty()) {
                dataSocket.setSoTimeout(3000);
                try {
                    String challengeMsg = readJsonMessage(dataIn);
                    if (challengeMsg != null && challengeMsg.contains("\"Challenge\"")) {
                        fireEvent(now() + " 数据连接要求认证，发送 Authenticate");
                        String authMsg = "{\"Authenticate\":\"" + escapeJson(secret) + "\"}\0";
                        dataOut.write(authMsg.getBytes(StandardCharsets.UTF_8));
                        dataOut.flush();
                    }
                } catch (SocketTimeoutException e) {
                    // 没有 Challenge 消息，正常继续
                }
                dataSocket.setSoTimeout(0);
            }

            // 发送 Accept 消息: {"Accept":"uuid"}\0
            String acceptMsg = "{\"Accept\":\"" + connId + "\"}\0";
            dataOut.write(acceptMsg.getBytes(StandardCharsets.UTF_8));
            dataOut.flush();

            // 连接到本地服务
            localSocket = new Socket();
            localSocket.connect(new InetSocketAddress("127.0.0.1", localPort), LOCAL_CONNECT_TIMEOUT_MS);

            fireEvent(now() + " 连接 ID=" + connId + " 已建立，开始转发");

            // 用于在 lambda 中关闭 socket 的 final 引用
            final Socket finalDataSocket = dataSocket;
            final Socket finalLocalSocket = localSocket;

            InputStream localIn = localSocket.getInputStream();
            OutputStream localOut = localSocket.getOutputStream();

            // 双向转发 - 使用 daemon 线程，不阻塞 dataExecutor
            Thread serverToLocal = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = dataIn.read(buf)) != -1) {
                        localOut.write(buf, 0, n);
                        localOut.flush();
                        if (listener != null) listener.onBytesTransferred(n);
                    }
                } catch (IOException ignored) {
                } finally {
                    // 任一方向完成，关闭两个 socket 以终止另一方向
                    try { finalDataSocket.close(); } catch (IOException ignored) {}
                    try { finalLocalSocket.close(); } catch (IOException ignored) {}
                }
            }, "relay-s2l-" + connId);

            Thread localToServer = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = localIn.read(buf)) != -1) {
                        dataOut.write(buf, 0, n);
                        dataOut.flush();
                        if (listener != null) listener.onBytesTransferred(n);
                    }
                } catch (IOException ignored) {
                } finally {
                    // 任一方向完成，关闭两个 socket 以终止另一方向
                    try { finalDataSocket.close(); } catch (IOException ignored) {}
                    try { finalLocalSocket.close(); } catch (IOException ignored) {}
                }
            }, "relay-l2s-" + connId);

            serverToLocal.setDaemon(true);
            localToServer.setDaemon(true);
            serverToLocal.start();
            localToServer.start();

        } catch (ConnectException e) {
            fireEvent(now() + " 连接 ID=" + connId + " 本地服务 :" + localPort + " 未连接");
        } catch (SocketTimeoutException e) {
            fireEvent(now() + " 连接 ID=" + connId + " 超时");
        } catch (IOException e) {
            fireEvent(now() + " 连接 ID=" + connId + " 转发错误: " + e.getMessage());
        } finally {
            if (dataSocket != null) {
                try { dataSocket.close(); } catch (IOException ignored) {}
            }
            if (localSocket != null) {
                try { localSocket.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 参考 SOMCP 方案：轻量级停止请求，从主线程同步设置 stopRequested，
     * 防止在 stop() 实际执行前有重连线程重新进入 start()。
     */
    public void requestStop() {
        stopRequested = true;
        generation.incrementAndGet();
        autoReconnect = false;
        if (reconnectThread != null) {
            reconnectThread.interrupt();
            reconnectThread = null;
        }
    }

    public synchronized void stop() {
        autoReconnect = false;
        running = false;
        stopRequested = true;
        generation.incrementAndGet();
        if (reconnectThread != null) {
            reconnectThread.interrupt();
            reconnectThread = null;
        }
        if (tunnelThread != null) {
            tunnelThread.interrupt();
            tunnelThread = null;
        }
        dataExecutor.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }
}