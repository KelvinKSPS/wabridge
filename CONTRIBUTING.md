# Contributing to Wabridge

Thanks for helping. Wabridge turns WhatsApp voice notes into watch calls, and most of the hard work is hardware-specific: every watch routes Bluetooth call audio differently. Reports from new devices are as valuable as code.

## Ways to help

- **Test on your watch.** Install a release, run through a voice note end to end, and tell us what happened (see [Reporting a device problem](#reporting-a-device-problem)).
- **Fix a bug or add a feature.** Open an issue first for anything bigger than a small fix, so we agree on the approach.
- **Improve the docs.** Translations of the READMEs are welcome.

## Project layout

| Path | Language | What it is |
|---|---|---|
| `android-bridge/` | Kotlin | Android app: call screen, Telecom connection, Bluetooth audio, media-card mode |
| `embedded-core/` | Go | Embedded WhatsApp engine (whatsmeow), HTTP and WebSocket API |
| `server/` | TypeScript | Optional Node server that talks to Evolution API |
| `.github/workflows/ci.yml` | YAML | Tests and builds for every push, pull request and `v*` tag |

## Local setup

### Android app

- JDK 17
- Android SDK (compileSdk 35). Set `ANDROID_HOME` or create `android-bridge/local.properties`.
- Run the JVM unit tests:

  ```bash
  cd android-bridge
  ./gradlew testDebugUnitTest --console=plain
  ```

- On ARM boards (e.g. Orange Pi) the SDK's `aapt2` is x86-64 only. See [`android-bridge/README.md`](android-bridge/README.md) for the `qemu` wrapper.

### Embedded Go engine

- Go (stable), and a C compiler, because the SQLite driver needs cgo.

  ```bash
  cd embedded-core
  go test ./...
  ```

### Node server

- Node 24, and `ffmpeg` built with libopus (used to convert audio between Opus and WAV).

  ```bash
  cd server
  npm ci
  npm run typecheck
  npm test
  npm run build
  ```

Optional end-to-end scripts live in `server/test/` (`e2e.js`, `mock-evolution.js`). They need a running Evolution API or the mock.

## Tests

Every change should keep these green:

| Suite | Command | Where it runs |
|---|---|---|
| Android JVM tests | `./gradlew testDebugUnitTest` | CI (`android` job) |
| Go engine | `go test ./...` | CI (`daemon` job) |
| Node server | `npm test` | CI (`servidor` job) |

When you add logic, add a unit test next to the existing ones:

- Android: `android-bridge/app/src/test/java/com/orangepi/wabridge/` (state machines, queues, payload parsing, URL building)
- Go: `*_test.go` next to the code it tests
- Server: `server/test/unit/*.test.ts`

Anything that touches Bluetooth, Telecom or a real watch cannot be unit-tested. Say in the pull request how you tested it on hardware.

## Code style

- **Comments and documentation are in English.** Existing Portuguese comments are being translated; if you touch a file, translate the comments you see there.
- User-facing strings go in `res/values*/strings.xml` for every supported locale (English, Portuguese, Spanish, Simplified Chinese). Do not hard-code text in Kotlin.
- Keep changes small and focused. A fix should come with the smallest diff that solves the root cause.
- Do not commit secrets, WhatsApp account data, audio recordings or logs with personal numbers.
- Prefer existing helpers over new abstractions.

## Commits and pull requests

- Commit subjects follow the existing history, for example `android: v1.0.66 short description`.
- Explain the *why* in the commit body, especially for device-specific behaviour.
- One logical change per pull request. Link the issue if there is one.
- In the pull request, list what you tested and on which device or emulator.

## Releases

Releases are built by CI, not on a developer machine.

1. Bump `versionCode` and `versionName` in `android-bridge/app/build.gradle.kts`.
2. Merge to `master`.
3. Push a tag `vX.Y.Z`. The workflow builds the APK and publishes it to GitHub Releases.

The signing keystore is committed for consistent builds; release secrets are configured in the repository settings.

## Reporting a device problem

Open an issue with:

- Watch model and firmware version, and phone model and Android version
- Whether the watch is paired and connected before the call
- What you saw: did the call ring on the watch, did the audio play on the phone or the watch, did recording start
- The log from the app (`adb logcat -s WatchCall VoiceSession QuietMedia BridgeConn`), with phone numbers removed

Logs contain WhatsApp identifiers. Remove phone numbers before posting.

## Conduct

Be direct and kind. Assume good faith. Keep discussions about the code and the device, not the person.
