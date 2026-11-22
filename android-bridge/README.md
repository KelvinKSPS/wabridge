# android-bridge: the watch app (Kotlin)

Android app that turns a WhatsApp voice note into a **phone-style call** on the
watch. It uses `android.telecom.ConnectionService` with a self-managed phone
account, plays the note over Bluetooth call audio, records the reply, and
uploads it to the engine.

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk   # or set sdk.dir in local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # with the watch connected over adb
./gradlew testDebugUnitTest    # JVM unit tests
```

Requirements: JDK 17, Android SDK (compileSdk 35), AGP 8.6.0, Kotlin 1.9.25.

> **ARM64 boards**: the SDK's `aapt2` is x86-64. On ARM the build needs a
> wrapper that runs it under `qemu-user`:
> `bash scripts/setup-x86-aapt2.sh`. It creates the x86 sysroot under
> `~/.local/share/wabridge-x86root`, installs `~/.local/bin/aapt2`, and registers
> `android.aapt2FromMavenOverride` in `~/.gradle/gradle.properties`. The override
> lives in the machine config, not in the repository, so the project still builds
> on Windows without it.

Release APKs are built by CI. See [CONTRIBUTING.md](../CONTRIBUTING.md#releases).

## How it works

| Class | Role |
|---|---|
| `BridgeForegroundService` | Foreground service: keeps the WebSocket alive, registers the `PhoneAccount`, decides ring vs. quiet mode. |
| `BridgeConnectionService` | `ConnectionService`: Telecom creates the call for each incoming note. |
| `WatchCallConnection` | Telecom adapter: contact name and number, audio route, hangup. |
| `VoiceSession` | The voice exchange itself: play the note, beep, record, upload. Works without Telecom. |
| `QuietMedia` | Quiet mode: exposes the pending note as a `MediaSession` so the watch music card can start it. |
| `CallActivity` | The app's own call screen (answer / reject / hang up), for watches without a system dialer. |
| `CallNotifier` | Service and call notifications. |
| `WsManager` | WebSocket (OkHttp) with automatic reconnection. |
| `Uploader` | `POST /reply/audio` (multipart). |
| `PhoneAccountHelper` | Registers the self-managed phone account (no `READ_PHONE_STATE`). |
| `CallStateMachine` | Pure state machine, covered by unit tests. |

### Flow

1. A `NEW_VOICE_NOTE` arrives on the WebSocket.
2. **Ring mode (default):** a call notification and the app's call screen open,
   and `addNewIncomingCall()` asks Telecom to ring the watch. The screen shows
   the contact name and number.
3. **Quiet mode:** no ringing. A quiet notification is posted and the note is
   offered on the watch music card. Pressing play starts a self-managed call
   that is answered on the watch (see the Huawei note in the root README).
4. On answer, `VoiceSession` plays the downloaded WAV. It waits for the Bluetooth
   SCO link (up to 3 s) and the configured delay, then plays a beep and vibrates.
5. The app records the reply with `MediaRecorder` (Opus in OGG on Android 10+).
   A safety timeout stops recording after 90 s.
6. Hanging up stops playback and recording, ends the call, and uploads the file
   to the engine. Replies shorter than the configured minimum are discarded.

## Permissions

See `app/src/main/AndroidManifest.xml` for the full list. The main ones are
`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `MANAGE_OWN_CALLS`, `BLUETOOTH_CONNECT`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` and `INTERNET`.

The app asks for `RECORD_AUDIO` (and `BLUETOOTH_CONNECT` on Android 12+) when you
tap **Connect**.

> `FOREGROUND_SERVICE_SPECIAL_USE` with `foregroundServiceType="specialUse"` lets
> the service run without a restricted type on Android 14+. On older devices the
> attribute is ignored and `FOREGROUND_SERVICE` is enough.

## Configuration

- **Server**: text field on the home screen, saved in `SharedPreferences`.
  Default: `http://192.168.15.100:8097`.
- The watch must reach the engine on the same network.

## Signing (release)

```bash
cp keystore.properties.template keystore.properties
# set storeFile / storePassword / keyAlias / keyPassword
./gradlew assembleRelease
```

Without `keystore.properties`, the release build uses the debug signature.
