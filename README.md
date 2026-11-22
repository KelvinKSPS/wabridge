# Wabridge

**WhatsApp voice notes as watch calls.** Wabridge routes incoming WhatsApp voice notes to an Android or Huawei watch as phone-style calls, then sends spoken replies back as WhatsApp voice notes.

[English](README.md) · [Português](README.pt-BR.md) · [Español](README.es.md) · [简体中文](README.zh-CN.md)

## How it works

```text
WhatsApp / Evolution API
        │ incoming voice-note webhook
        ▼
Node.js server or embedded Go daemon
        │ decode/prepare audio, WebSocket event
        ▼
Android app ── Bluetooth HFP/SCO ── watch
        ▲                              │
        └──── recorded voice reply ────┘
```

The Android app supports an embedded Go engine and an external Node.js server. Incoming audio is presented as a call; after answering, the watch plays the note and records the reply. The app uploads the original recording and the engine sends it as a WhatsApp voice note.

## Repository

| Path | Purpose |
|---|---|
| [`android-bridge/`](android-bridge/README.md) | Android app, Kotlin source, Gradle build, and JVM unit tests |
| `embedded-core/` | Embedded Go WhatsApp engine, HTTP/WebSocket API, and tests |
| [`server/`](server/README.md) | Optional Node.js/TypeScript server integrated with Evolution API |
| `.github/workflows/ci.yml` | CI tests and Android APK builds/releases |

Want to contribute? Read [CONTRIBUTING.md](CONTRIBUTING.md) first.

## Get the Android app

Download the signed APK from the project's [GitHub Releases](https://github.com/KelvinKSPS/wabridge/releases). The release workflow runs when a `v*` tag is pushed. CI also builds debug APK artifacts for pushes and pull requests.

## Build and test

Android release APKs are built by GitHub Actions. Each part has its own test suite:

| Part | Command | Notes |
|---|---|---|
| Android app (JVM unit tests) | `cd android-bridge && ./gradlew testDebugUnitTest --console=plain` | JDK 17 and Android SDK required |
| Embedded Go engine | `cd embedded-core && go test ./...` | cgo is required for SQLite |
| Node server | `cd server && npm ci && npm test && npm run build` | needs `ffmpeg` with libopus |

The CI workflow runs all three on every push and pull request. See [CONTRIBUTING.md](CONTRIBUTING.md) for the full local setup.

The Android app requires JDK 17 and Android SDK configuration. The CI workflow builds native Go libraries for Android `arm64-v8a` and `x86_64`, packages the APK, and uploads build artifacts. A signed release requires the Android signing secrets configured in the repository; without them, the release workflow reports that the keystore is unavailable.

## Setup

1. Install the APK on the Android phone or watch.
2. Pair the WhatsApp account using the in-app pairing flow.
3. Select the embedded engine, or configure the URL of an external server.
4. Grant microphone, Bluetooth, and notification permissions when prompted.
5. Select optional Bluetooth devices and allowed contacts/groups, then start the service.

For the external server, configure `.env` from `server/.env.example`, including Evolution API credentials and the webhook host. Keep secrets out of source control. See [`server/README.md`](server/README.md) for server routes and configuration.

## Known limitations

- **Huawei watches (GT series, HarmonyOS):** the watch drops the Bluetooth call audio when the call is answered on the phone, so the app cannot reply from the phone side. The *Custom integration → Huawei* option works around this: the note appears on the watch's music card, pressing play rings the call on the watch, and the call is answered there. This flow is experimental and still under testing.
- Other watches may behave differently. If your watch does not carry call audio, please open an issue with a log (see [CONTRIBUTING.md](CONTRIBUTING.md#reporting-a-device-problem)).

## Features

- Android foreground service with WhatsApp WebSocket reconnection.
- Call-style playback and reply recording over Bluetooth HFP/SCO.
- Configurable playback rate, including custom values from 0.25× to 4×.
- Configurable delay before playback.
- Call history for received, answered, and rejected voice notes, with clear-history action.
- Optional Bluetooth device gate and contact/group allowlist.
- Audio diagnostics and original-recording preservation for troubleshooting.
- Portuguese, English, Spanish, and Simplified Chinese app localization.

## Security and privacy

The app processes WhatsApp account credentials and audio locally or through the configured server. Use a trusted network, protect Evolution API credentials and signing keys, and do not publish diagnostic audio or logs containing personal data. Microphone access is required only while recording a reply; Android's system privacy indicator remains visible during active microphone use.

## Support the project

Wabridge is a personal project built to solve a problem many watches have. If it saves you time and you want to help keep it going, you can support the work:

- **Ko-fi:** https://ko-fi.com/kspss
- **USDT** (EVM address, same on Ethereum and other EVM networks; send on the network you choose): `0xA8515Bd402e9d7655dA09d60A10aA1E1d42de591`

Donations are optional and never unlock features. Contributions of code, testing on new watches and bug reports help just as much.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) for setup, tests, code style and the release process.

## License

See the repository's license file, if provided.
