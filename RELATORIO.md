# Relatório — wabridge v1.0.1

Data: 07/11/2026

## O que foi entregue

| Parte | Onde | Estado |
|---|---|---|
| Servidor (Node 24 / TypeScript) | `server/` | no ar em `http://192.168.15.100:8097` (pm2, reinicia sozinho) |
| App Android (Kotlin) | `android-bridge/` | APK release assinado, publicado na release v1.0.1 |
| Testes | `server/test/unit/`, `app/src/test/` | 52 testes, todos verdes |
| Chave de assinatura | `android-bridge/wabridge.jks` | gerada; cópia em `~/wabridge-keys/` |
| Evolution API | `docker-compose.yml` | pronta para subir; falta parear o QR |

## Testes

**Servidor — 32 testes (`node --import tsx --test test/unit/`)**

| Arquivo | Casos | O que cobre |
|---|---|---|
| `whatsapp.test.ts` | 9 | normalização do webhook (4 formatos), `document` de áudio, grupo, `fromMe`, jid inválido |
| `config.test.ts` | 6 | lista branca: exato, sufixo, lista vazia, número inválido |
| `media.test.ts` | 4 | ffmpeg: opus → wav PCM 16 kHz mono, opus → opus, entrada inválida |
| `routes.test.ts` | 10 | rotas HTTP, push no WebSocket, `/media`, `/media/test.wav`, `/reply/audio` com `ptt`, bloqueio, grupo |

**App — 21 testes JUnit (`./gradlew test`)**

| Arquivo | Casos | O que cobre |
|---|---|---|
| `CallStateMachineTest` | 9 | transições da chamada, sessão sem Telecom, ordem errada, desligar duas vezes, timeout de 90 s |
| `WsUrlsTest` | 7 | http → ws, https → wss, barra final, caminho extra, url de upload |
| `CallPayloadTest` | 5 | round trip do JSON, payload inválido, parse do evento do servidor |

Rodados em dois lugares:

- **Orange Pi (ARM)** — 21/21 em ~28 min (`testDebugUnitTest`)
- **PC Windows (16 GB)** — 42/42 em ~14 s (variantes debug e release)

## Validação no emulador (PC Windows)

Feita pelo outro agente, com `scripts/remote-validate.ps1`:

- repositório baixado em tarball, `assembleDebug` + `assembleRelease` → **BUILD SUCCESSFUL**
- APK release instalado no emulador `sdk_gphone64_x86_64` → **Success**
- `MainActivity` abre, processo vivo, **sem crash**
- botão **Testar chamada** clicado de verdade (via uiautomator) →
  **a tela de chamada do app aparece em primeiro plano**, estado `RINGING`
- **Atender funciona**: `WatchCall`/`VoiceSession` iniciados, chamada `ACTIVE`
- **o tom de teste toca**: HTTP 200 em `/media/test.wav`, decodificado em PCM
  16 kHz mono, 32.000 frames entregues no `AudioTrack`, sem erro e sem crash
- logs do app conferidos no logcat

## APK release

- `com.orangepi.wabridge`, versionCode 1, minSdk 26, targetSdk 34, compileSdk 35
- Assinatura: `CN=WA Bridge, OU=Home, O=wabridge, C=BR`
- SHA-256 do certificado: `27405a554f869dbe46d9eda85d35879baaee489805fa39c1cc65be89f442844b`
- Release: https://github.com/KelvinKSPS/wabridge/releases/tag/v1.0.1

## Bugs que só apareceram no emulador

1. **O serviço em primeiro plano crashava ao abrir.**
   `TelecomManager.getSelfManagedPhoneAccounts()` exige `READ_PHONE_STATE`;
   a permissão não é declarada, então o `SecurityException` matava o serviço —
   e com ele o WebSocket, ou seja, nada funcionava. Agora o app registra a
   conta sem consultar a lista, e o registro está protegido para o serviço
   nunca mais cair.

2. **A tela de chamada não aparecia** quando o aparelho não tem telephony:
   o `TelecomManager` vinha antes do `startActivity` e o método retornava cedo.
   Agora a tela do app e a notificação vêm primeiro; o Telecom é opcional.

3. **API do Telecom diferente da esperada** — no SDK 35, `ConnectionService`
   recebe `ConnectionRequest` (não `Bundle`), `PhoneAccount` se constrói com
   `PhoneAccount.Builder(handle, label)`, e `PhoneAccountCapabilities`,
   `Connection.setAudioState` e `TelecomManager.EXTRA_HAS_MEDIA_STATE` não são
   API pública.

## Detalhes que custaram tempo (e como foram resolvidos)

1. **aapt2 x86-64 numa board ARM** — o `aapt2` do SDK não roda aqui. Resolvido com um
   sysroot x86 mínimo (glibc + libgcc) e um wrapper com `qemu-user`:
   `~/.local/bin/aapt2`, apontado pelo AGP via `android.aapt2FromMavenOverride`
   em `~/.gradle/gradle.properties` (por máquina, para o mesmo projeto compilar
   no Windows). O AGP exige que o override termine em "aapt2".
2. **`PORT=39390` no ambiente** — o shell herda o `PORT` do painel web, e o
   `node --env-file` não sobrescreve variável já existente. `server/scripts/start.sh`
   exporta o `.env` por cima do ambiente.
3. **Enter no outro agente** — a sessão opencode precisa de `\r`, não `\n`, e ela
   costuma estar em modo shell: o jeito confiável é mandar um script PowerShell
   versionado no repositório.
4. **Build local é lento** (ARM, RAM apertada): 11–45 min por APK. Build e
   emulador ficam com o PC de 16 GB.

## Pendências (precisa de ação humana)

1. **Evolution API**: `docker compose up -d` (na raiz do projeto) e parear o
   WhatsApp lendo o QR. Sem isso o servidor fica esperando a conexão.
2. **Lista branca**: `ALLOWED_NUMBERS` no `server/.env` está vazio (aceita
   qualquer remetente). Definir antes de usar de verdade.
3. **Chave**: guardar `~/wabridge-keys/wabridge.jks` — sem ela não dá para
   atualizar o app no relógio, só reinstalar.
4. **Teste no relógio de verdade**: instalar o APK, abrir, colocar a URL
   `http://192.168.15.100:8097`, conectar e tocar em **Testar chamada**.
