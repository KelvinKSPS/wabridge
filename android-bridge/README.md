# android-bridge — app do relógio (Kotlin)

App que transforma o áudio de voz do WhatsApp em uma **chamada telefônica**
no relógio, usando `android.telecom.ConnectionService` com conta
autogerenciada (self-managed).

## Build

```bash
export ANDROID_HOME=/home/orangepi/android-sdk   # ou edite local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # com o relógio conectado por adb
```

Requisitos: JDK 17, Android SDK (compileSdk 35), AGP 8.6.0, Kotlin 1.9.25.

> **ARM64**: o `aapt2` do SDK é binário x86-64. Nesta board (ARM) o build usa
> um wrapper com `qemu-user`:
> `bash scripts/setup-x86-aapt2.sh` (monta o sysroot x86 em
> `~/.local/share/wabridge-x86root`, cria `~/.local/bin/aapt2` e registra o
> `android.aapt2FromMavenOverride` no `~/.gradle/gradle.properties`).
> O override fica na config da máquina, e não no repositório — assim o mesmo
> projeto compila no Windows sem o wrapper.

## Como funciona

| Classe | Papel |
|---|---|
| `BridgeForegroundService` | FGS: mantém o WebSocket vivo e registra a `PhoneAccount` self-managed. |
| `BridgeConnectionService` | `ConnectionService`: o Telecom cria a chamada a partir do evento. |
| `WatchCallConnection` | adaptador do Telecom: nome/número do contato e delegação para a sessão. |
| `VoiceSession` | a chamada de voz em si: SCO → tocar → gravar → enviar (independe do Telecom). |
| `CallActivity` | tela de chamada do app (Atender/Recusar/Desligar), para relógio sem discador. |
| `CallNotifier` | notificação de serviço e de chamada (full screen intent + botões). |
| `WsManager` | WebSocket (OkHttp) com reconexão automática. |
| `Uploader` | `POST /reply/audio` (multipart). |
| `PhoneAccountHelper` | registro da conta telefônica (sem `READ_PHONE_STATE`). |
| `CallStateMachine` | máquina de estados pura, coberta por testes. |

### Fluxo

1. `NEW_VOICE_NOTE` chega no WebSocket → notificação de chamada + tela do app
   (`CallActivity`) + `telecom.addNewIncomingCall()`.
2. A tela mostra **nome e número do contato** (no Telecom: `setCallerDisplayName`
   + `setAddress`, `PRESENTATION_ALLOWED`).
3. Ao atender: `setActive()`, `MODE_IN_COMMUNICATION`, `startBluetoothSco()`;
   aguarda `SCO_AUDIO_STATE_CONNECTED` + 800 ms e toca o wav com
   `USAGE_VOICE_COMMUNICATION`.
4. Fim da reprodução → 300 ms → tom curto (`TONE_PROP_BEEP`) →
   `MediaRecorder` (`VOICE_COMMUNICATION`, AAC, 16 kHz, 32 kbps) no cache.
5. Desligar (`onDisconnect`/`onReject`/botão **Desligar**) → para tudo, restaura
   `MODE_NORMAL`, `setDisconnected(LOCAL)`, `destroy()` e envia o arquivo ao
   servidor.
6. Timeout de segurança: 90 s de gravação → desligamento automático.

> O `VoiceSession` não depende do Telecom: se o aparelho não tiver telephony,
> a tela do app conduz a chamada do mesmo jeito.

## Permissões

`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `MANAGE_OWN_CALLS`,
`BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `INTERNET`.

O app pede `RECORD_AUDIO` (e `BLUETOOTH_CONNECT` no Android 12+) ao tocar
**Conectar**.

> O `FOREGROUND_SERVICE_SPECIAL_USE` + `foregroundServiceType="specialUse"` é
> o que permite o FGS sem "type" restrito no Android 14+. Em aparelhos
> anteriores o atributo é ignorado e o `FOREGROUND_SERVICE` basta.

## Configuração

- **Servidor**: campo de texto na tela inicial (salvo em `SharedPreferences`).
  Padrão: `http://192.168.15.100:8097`.
- O relógio precisa alcançar o servidor na mesma rede.

## Assinatura (release)

```bash
cp keystore.properties.template keystore.properties
# ajuste storeFile / storePassword / keyAlias / keyPassword
./gradlew assembleRelease
```

Sem `keystore.properties`, o build release usa a assinatura de debug.
