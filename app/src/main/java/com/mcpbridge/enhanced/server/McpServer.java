package com.mcpbridge.enhanced.server;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量级 MCP HTTP Server (ServerSocket 实现)
 *
 * 基于 Streamable HTTP 传输协议，处理 JSON-RPC 2.0 MCP 请求。
 * 单例模式，与 TunnelService / CloudflareTunnelService 共享同一实例。
 *
 * 支持的端点:
 *   GET    /                    → 服务发现
 *   GET    /.well-known/mcp    → MCP 发现
 *   GET    /health             → 健康检查
 *   GET    /mcp                → MCP 信息 (Accept: text/event-stream 返回 SSE hello)
 *   GET    /sse                → SSE 端点
 *   POST   /mcp                → JSON-RPC MCP 调用
 *   POST   /rpc                → JSON-RPC 调用 (别名)
 *   POST   /messages           → JSON-RPC 调用 (别名)
 *   OPTIONS *                  → CORS 预检
 */
public class McpServer {

    private static final String TAG = "McpServer";

    private static volatile McpServer instance;
    private static final Object lock = new Object();

    private final int port;
    private final String host;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;
    private final AtomicLong requestCount = new AtomicLong(0);
    private final long startedAt = System.currentTimeMillis();

    // ===== 单例管理 =====

    /**
     * 获取或创建 McpServer 实例 (默认端口 8080)
     */
    public static McpServer getInstance() {
        return getInstance(8080);
    }

    /**
     * 获取或创建 McpServer 实例 (指定端口)
     */
    public static McpServer getInstance(int port) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new McpServer(port);
                }
            }
        }
        return instance;
    }

    /**
     * 重置单例 (通常在端口变更时使用)
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    private McpServer(int port) {
        this.port = port > 0 ? port : 8080;
        this.host = "127.0.0.1";
    }

    // ===== 生命周期 =====

    /**
     * 启动 MCP Server
     */
    public boolean start() {
        if (running.get()) {
            Log.d(TAG, "MCP Server 已在运行，端口: " + port);
            return true;
        }

        try {
            serverSocket = new ServerSocket(port, 50, java.net.InetAddress.getByName(host));
            serverSocket.setReuseAddress(true);
            threadPool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mcp-server-" + requestCount.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            running.set(true);

            acceptThread = new Thread(this::acceptLoop, "mcp-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            Log.i(TAG, "MCP Server 已启动: " + host + ":" + port);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "MCP Server 启动失败: " + e.getMessage());
            running.set(false);
            return false;
        }
    }

    /**
     * 停止 MCP Server
     */
    public void stop() {
        if (!running.get()) return;
        running.set(false);

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
        }

        Log.i(TAG, "MCP Server 已停止");
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }

    public long getUptimeMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    // ===== 连接接收循环 =====

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(30000);
                threadPool.submit(() -> handleConnection(client));
            } catch (SocketException e) {
                if (!running.get()) break;
                Log.e(TAG, "Accept 异常: " + e.getMessage());
            } catch (IOException e) {
                if (!running.get()) break;
                Log.e(TAG, "Accept 失败: " + e.getMessage());
            }
        }
    }

    // ===== HTTP 请求处理 =====

    private void handleConnection(Socket socket) {
        try (socket;
             InputStream input = socket.getInputStream();
             OutputStream output = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));

            // 解析请求行
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) return;

            String method = parts[0].toUpperCase();
            String path = parts[1];

            // 解析请求头
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            int contentLength = 0;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int colonIdx = headerLine.indexOf(':');
                if (colonIdx > 0) {
                    String key = headerLine.substring(0, colonIdx).trim().toLowerCase();
                    String value = headerLine.substring(colonIdx + 1).trim();
                    headers.put(key, value);
                    if ("content-length".equals(key)) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // 读取请求体
            String body = "";
            if (contentLength > 0 && (method.equals("POST") || method.equals("PUT"))) {
                char[] buf = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = reader.read(buf, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                body = new String(buf, 0, totalRead);
            }

            // 路由处理
            if (method.equals("OPTIONS")) {
                // CORS 预检 — 返回 204 No Content 即可
                sendOptionsResponse(output);
            } else if (method.equals("GET")) {
                handleGet(output, path, headers);
            } else if (method.equals("POST")) {
                handlePost(output, path, body, headers);
            } else {
                sendResponse(output, 405, "{\"error\":\"Method not allowed\"}");
            }

        } catch (Exception e) {
            Log.e(TAG, "处理连接异常: " + e.getMessage());
            // 尝试返回 500，避免隧道侧读到 EOF 当成 502
            try {
                Socket s = socket;
                if (s != null && !s.isClosed()) {
                    OutputStream out = s.getOutputStream();
                    byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}".getBytes(StandardCharsets.UTF_8);
                    StringBuilder resp = new StringBuilder();
                    resp.append("HTTP/1.1 500 Internal Server Error\r\n");
                    resp.append("Content-Type: application/json; charset=utf-8\r\n");
                    resp.append("Content-Length: ").append(body.length).append("\r\n");
                    resp.append("Connection: close\r\n");
                    resp.append("Access-Control-Allow-Origin: *\r\n");
                    resp.append("\r\n");
                    out.write(resp.toString().getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                    out.flush();
                }
            } catch (Exception ignored) {}
        }
    }

    // ===== OPTIONS 处理 =====

    private void sendOptionsResponse(OutputStream output) throws IOException {
        StringBuilder resp = new StringBuilder();
        resp.append("HTTP/1.1 204 No Content\r\n");
        resp.append("Content-Length: 0\r\n");
        resp.append("Connection: keep-alive\r\n");
        resp.append("Access-Control-Allow-Origin: *\r\n");
        resp.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        resp.append("Access-Control-Allow-Headers: Content-Type, Authorization, Accept\r\n");
        resp.append("Access-Control-Max-Age: 86400\r\n");
        resp.append("\r\n");
        output.write(resp.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    // ===== GET 请求处理 =====

    private void handleGet(OutputStream output, String path, Map<String, String> headers) throws IOException {
        try {
            switch (path) {
                case "/":
                case "/.well-known/mcp":
                    sendJsonResponse(output, 200, serverDiscovery());
                    break;

                case "/health":
                    sendJsonResponse(output, 200, healthResponse().toString());
                    break;

                case "/mcp":
                    String accept = headers.getOrDefault("accept", "");
                    if (accept.contains("text/event-stream")) {
                        sendResponse(output, 200, "event: endpoint\ndata: {\"uri\":\"/messages\",\"method\":\"POST\"}\n\n: MCP Bridge ready\n\n", "text/event-stream");
                    } else {
                        sendJsonResponse(output, 200, serverDiscovery());
                    }
                    break;

                case "/sse":
                    sendResponse(output, 200, "event: endpoint\ndata: {\"uri\":\"/messages\",\"method\":\"POST\"}\n\n: MCP Bridge ready\n\n", "text/event-stream");
                    break;

                default:
                    sendJsonResponse(output, 404, "{\"error\":\"Not found\",\"path\":\"" + escapeJson(path) + "\"}");
                    break;
            }
        } catch (org.json.JSONException e) {
            Log.e(TAG, "GET 处理 JSON 异常: " + e.getMessage());
            sendJsonResponse(output, 500, "{\"error\":\"JSON error\"}");
        }
    }

    // ===== POST 请求处理 =====

    private void handlePost(OutputStream output, String path, String body, Map<String, String> headers) throws IOException {
        if (!path.equals("/mcp") && !path.equals("/rpc") && !path.equals("/messages")) {
            sendJsonResponse(output, 404, "{\"error\":\"Not found\",\"path\":\"" + escapeJson(path) + "\"}");
            return;
        }

        if (body == null || body.trim().isEmpty()) {
            sendJsonResponse(output, 400, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}");
            return;
        }

        // 处理 JSON-RPC 请求
        String response = handleJsonRpc(body.trim());
        String accept = headers.getOrDefault("accept", "");

        if (response == null || response.isEmpty()) {
            // Notification (no response) → 202 Accepted
            sendResponse(output, 202, "", "application/json");
            return;
        }

        if (accept.contains("text/event-stream")) {
            sendResponse(output, 200, "event: message\ndata: " + response + "\n\n", "text/event-stream");
        } else {
            sendJsonResponse(output, 200, response);
        }
    }

    // ===== JSON-RPC 处理 =====

    private String handleJsonRpc(String body) {
        try {
            org.json.JSONObject req = new org.json.JSONObject(body);

            // 验证 jsonrpc 字段
            String jsonrpc = req.optString("jsonrpc", "");
            if (!"2.0".equals(jsonrpc)) {
                Object id = req.has("id") ? req.get("id") : org.json.JSONObject.NULL;
                return new org.json.JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", id)
                        .put("error", new org.json.JSONObject()
                                .put("code", -32600)
                                .put("message", "Invalid Request: jsonrpc field must be \"2.0\""))
                        .toString();
            }

            String method = req.optString("method", "");
            if (method.isEmpty()) {
                Object id = req.has("id") ? req.get("id") : org.json.JSONObject.NULL;
                return new org.json.JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", id)
                        .put("error", new org.json.JSONObject()
                                .put("code", -32600)
                                .put("message", "Invalid Request: method is required"))
                        .toString();
            }

            // 通知 (没有 id) — 不返回响应体，handlePost 返回 202
            if (!req.has("id") || req.isNull("id")) {
                return null;
            }

            Object id = req.get("id");
            org.json.JSONObject params = req.optJSONObject("params");
            if (params == null) params = new org.json.JSONObject();

            org.json.JSONObject result;
            switch (method) {
                case "initialize":
                    result = handleInitialize(params);
                    break;
                case "ping":
                    result = new org.json.JSONObject().put("ok", true);
                    break;
                case "notifications/initialized":
                    // 纯通知 — 无 id 时已在上面返回 null；若有 id 则返回空结果
                    return null;
                case "tools/list":
                    result = handleToolsList();
                    break;
                case "tools/call":
                    result = handleToolsCall(params);
                    break;
                case "resources/list":
                    result = new org.json.JSONObject().put("resources", new org.json.JSONArray());
                    break;
                case "prompts/list":
                    result = new org.json.JSONObject().put("prompts", new org.json.JSONArray());
                    break;
                default:
                    return new org.json.JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", id)
                            .put("error", new org.json.JSONObject()
                                    .put("code", -32601)
                                    .put("message", "Method not found: " + method))
                            .toString();
            }

            return new org.json.JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("result", result)
                    .toString();

        } catch (org.json.JSONException e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error: " + escapeJson(e.getMessage()) + "\"}}";
        }
    }

    // ===== MCP 方法处理器 =====

    private org.json.JSONObject handleInitialize(org.json.JSONObject params) throws org.json.JSONException {
        return new org.json.JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", new org.json.JSONObject()
                        .put("tools", new org.json.JSONObject().put("listChanged", false)))
                .put("serverInfo", new org.json.JSONObject()
                        .put("name", "MCPBridgeEnhanced")
                        .put("version", "2.1.16"));
    }

    private org.json.JSONObject handleToolsList() throws org.json.JSONException {
        org.json.JSONArray tools = new org.json.JSONArray();

        // 添加内置工具
        tools.put(toolDescriptor("echo", "回显输入参数", new org.json.JSONObject()
                .put("type", "object")
                .put("properties", new org.json.JSONObject()
                        .put("message", new org.json.JSONObject()
                                .put("type", "string")
                                .put("description", "要回显的消息")))
                .put("required", new org.json.JSONArray().put("message"))));

        tools.put(toolDescriptor("health", "检查服务器健康状态", new org.json.JSONObject()
                .put("type", "object")
                .put("properties", new org.json.JSONObject())));

        return new org.json.JSONObject()
                .put("tools", tools);
    }

    private org.json.JSONObject handleToolsCall(org.json.JSONObject params) throws org.json.JSONException {
        String name = params.optString("name", "");
        org.json.JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new org.json.JSONObject();

        switch (name) {
            case "echo":
                String message = args.optString("message", "");
                return new org.json.JSONObject()
                        .put("isError", false)
                        .put("content", new org.json.JSONArray()
                                .put(new org.json.JSONObject()
                                        .put("type", "text")
                                        .put("text", "Echo: " + message)));

            case "health":
                return new org.json.JSONObject()
                        .put("isError", false)
                        .put("content", new org.json.JSONArray()
                                .put(new org.json.JSONObject()
                                        .put("type", "text")
                                        .put("text", healthResponse().toString())));

            default:
                return new org.json.JSONObject()
                        .put("isError", true)
                        .put("content", new org.json.JSONArray()
                                .put(new org.json.JSONObject()
                                        .put("type", "text")
                                        .put("text", "Unknown tool: " + name)));
        }
    }

    // ===== 辅助方法 =====

    private org.json.JSONObject toolDescriptor(String name, String description, org.json.JSONObject inputSchema) throws org.json.JSONException {
        return new org.json.JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", inputSchema);
    }

    private String serverDiscovery() throws org.json.JSONException {
        return new org.json.JSONObject()
                .put("ok", true)
                .put("name", "MCPBridgeEnhanced")
                .put("protocol", "MCP JSON-RPC 2.0")
                .put("endpoint", "/mcp")
                .put("sseEndpoint", "/sse")
                .put("messagesEndpoint", "/messages")
                .put("hint", "POST JSON-RPC to /mcp. GET /mcp with Accept: text/event-stream returns SSE hello.")
                .toString();
    }

    private org.json.JSONObject healthResponse() throws org.json.JSONException {
        return new org.json.JSONObject()
                .put("ok", true)
                .put("server", "MCPBridgeEnhanced")
                .put("endpoint", "/mcp")
                .put("port", port)
                .put("uptimeMillis", getUptimeMillis())
                .put("running", running.get());
    }

    private void sendResponse(OutputStream output, int statusCode, String body) throws IOException {
        sendResponse(output, statusCode, body, "application/json");
    }

    private void sendResponse(OutputStream output, int statusCode, String body, String contentType) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = getStatusText(statusCode);
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        response.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n");
        response.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        // 对 202 Accepted (notification) 保持短连接；对正常响应使用 keep-alive
        if (statusCode == 202 && bodyBytes.length == 0) {
            response.append("Connection: close\r\n");
        } else {
            response.append("Connection: keep-alive\r\n");
        }
        response.append("Access-Control-Allow-Origin: *\r\n");
        response.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        response.append("Access-Control-Allow-Headers: Content-Type, Authorization, Accept\r\n");
        response.append("\r\n");
        output.write(response.toString().getBytes(StandardCharsets.UTF_8));
        output.write(bodyBytes);
        output.flush();
    }

    private void sendJsonResponse(OutputStream output, int statusCode, String jsonBody) throws IOException {
        sendResponse(output, statusCode, jsonBody, "application/json");
    }

    private String getStatusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            default: return "Unknown";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}