# Wabridge

**将 WhatsApp 语音消息作为手表来电接听。** Wabridge 会把 WhatsApp 收到的语音消息以电话来电的形式转到 Android 手机或华为手表，并将录制的语音回复作为 WhatsApp 语音消息发送回去。

[English](README.md) · [Português](README.pt-BR.md) · [Español](README.es.md) · [简体中文](README.zh-CN.md)

## 工作原理

```text
WhatsApp / Evolution API
        │ 收到语音消息的 webhook
        ▼
Node.js 服务器或内置 Go 引擎
        │ 处理音频并发送 WebSocket 事件
        ▼
Android 应用 ── Bluetooth HFP/SCO ── 手表
        ▲                                  │
        └──────── 录制语音回复 ────────────┘
```

Android 应用支持内置 Go 引擎，也支持外部 Node.js 服务器。收到语音消息后，应用会显示来电；接听后，手表播放语音并录制回复。应用将原始录音上传给引擎，再由引擎作为 WhatsApp 语音消息发送。

## 仓库结构

| 路径 | 用途 |
|---|---|
| `android-bridge/` | Android 应用、Kotlin 源码、Gradle 构建和 JVM 单元测试 |
| `embedded-core/` | 内置 Go WhatsApp 引擎、HTTP/WebSocket API 和测试 |
| `server/` | 可选的 Node.js/TypeScript 服务器，集成 Evolution API |
| `.github/workflows/ci.yml` | CI 测试以及 Android APK 构建和发布 |

## 下载 Android 应用

从 [GitHub Releases](https://github.com/KelvinKSPS/wabridge/releases) 下载签名 APK。推送 `v*` 标签会触发发布工作流。推送和 Pull Request 也会由 CI 生成 debug APK 构建产物。

## 构建与测试

Release APK 由 GitHub Actions 构建。要在本地运行 JVM 单元测试：

```bash
cd android-bridge
./gradlew testDebugUnitTest --console=plain
```

运行内置 Go 引擎测试：

```bash
cd embedded-core
go test ./...
```

运行可选的 Node 服务器：

```bash
cd server
npm ci
npm test
npm run build
```

Android 应用需要 JDK 17 和 Android SDK。CI 工作流会为 Android `arm64-v8a` 和 `x86_64` 构建 Go 原生库、打包 APK 并上传构建产物。签名 Release 需要在仓库中配置 Android 签名 secrets；如果没有配置，工作流会提示 keystore 不可用。

## 配置

1. 将 APK 安装到 Android 手机或手表。
2. 使用应用内的配对流程关联 WhatsApp 账号。
3. 选择内置引擎，或配置外部服务器 URL。
4. 按提示授予麦克风、蓝牙和通知权限。
5. 可选：选择蓝牙设备及允许的联系人/群组，然后启动服务。

使用外部服务器时，请根据 `server/.env.example` 配置 `.env`，填写 Evolution API 凭据和 webhook 主机。不要将 secrets 提交到仓库。服务器路由和配置说明见 [`server/README.md`](server/README.md)。

## 功能

- Android 前台服务，支持 WhatsApp WebSocket 自动重连。
- 通过 Bluetooth HFP/SCO 播放语音并录制回复。
- 可配置播放速度，支持 0.25× 到 4× 范围内的自定义值。
- 可配置播放前延迟。
- 记录收到、接听和拒绝的语音消息，并可清除记录。
- 可选的蓝牙设备过滤及联系人/群组允许列表。
- 音频诊断，并保留原始录音以便排查问题。
- 应用界面支持葡萄牙语、英语、西班牙语和简体中文。

## 安全与隐私

应用会在本地或通过配置的服务器处理 WhatsApp 账号凭据和音频。请使用可信网络，妥善保护 Evolution API 凭据和签名密钥，不要公开包含个人信息的诊断文件或日志。录制回复时才需要麦克风；麦克风实际使用期间，Android 系统隐私指示器会保持显示。

## 许可证

如仓库中提供许可证文件，请参阅该文件。
