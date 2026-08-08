# MCP-Bridge-Enhanced

MCP 内网穿透 Android 客户端，支持 Bore 隧道和 Cloudflare Tunnel 两种穿透方式，让 MCP Server 可以通过公网地址被访问。

## 功能特点

- **双隧道支持**：同时支持 Bore 隧道（公共服务器穿透）和 Cloudflare Tunnel（永久/临时隧道）
- **悬浮窗控制**：悬浮窗实时显示隧道状态，一键启动/停止隧道
- **服务保活**：多种保活策略组合（前台服务 + JobScheduler + AlarmManager），维持隧道长期稳定运行
- **日志监控**：实时查看隧道运行日志，可折叠不遮挡界面
- **简单易用**：输入服务器地址或 Token 即可启动，无需复杂配置

## 隧道类型

### Bore 隧道
基于 [bore](https://github.com/ekzhang/bore) 协议的 TCP 端口转发，通过公共服务器暴露本地端口，适合临时测试和快速部署。

### Cloudflare Tunnel
使用 Cloudflare 官方的 `cloudflared` 隧道技术，支持两种模式：
- **临时隧道**：无需账号，启动后自动分配 URL，适合快速测试
- **永久隧道**：需 Cloudflare Token，可在 CF 后台配置域名路由，连接稳定

## 构建

```bash
# 使用 Gradle 构建 Release APK
ANDROID_HOME=/path/to/android-sdk ./gradlew assembleRelease
```

## 技术栈

- 纯 Java + Android 原生开发
- Material Design 3 (Material You)
- 无第三方 SDK 依赖，轻量安全

## 许可证

MIT