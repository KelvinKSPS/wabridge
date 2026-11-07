# Relatório — wabridge v1.0.1

Data: 07/11/2026

## O que foi entregue

| Parte | Onde | Estado |
|---|---|---|
| Servidor (Node 24 / TypeScript) | `server/` | no ar em `http://192.168.15.100:8097` (pm2, reinicia sozinho) |
| App Android (Kotlin) | `android-bridge/` | APK release assinado, publicado na release v1.0.1 |
| Testes | `server/test/unit/`, `app/src/test/` | 48 testes, todos verdes |
| Chave de assinatura | `android-bridge/wabridge.jks` | gerada; cópia em `~/wabridge-keys/` |

## Testes

**Servidor — 28 testes (`node --import tsx --test test/unit/`)**

| Arquivo | Casos | O que cobre |
|---|---|---|
| `whatsapp.test.ts` | 9 | normalização do webhook (4 formatos de payload), `document` de áudio, grupo, `fromMe`, jid inválido |
| `config.test.ts` | 6 | lista branca: número exato, comparação por sufixo, lista vazia, número inválido |
| `media.test.ts` | 4 | ffmpeg: opus → wav PCM 16 kHz mono, opus → opus, entrada inválida |
| `routes.test.ts` | 9 | rotas HTTP, push no WebSocket, `/media`, `/reply/audio` com `ptt`, bloqueio de remetente, grupo |

**App — 20 testes JUnit (`./gradlew test`)**

| Arquivo | Casos | O que cobre |
|---|---|---|
| `CallStateMachineTest` | 8 | transições da chamada, ordem errada, desligar duas vezes, timeout de 90 s |
| `WsUrlsTest` | 7 | http → ws, https → wss, barra final, caminho extra, url de upload |
| `CallPayloadTest` | 5 | round trip do JSON, payload inválido, parse do evento do servidor |

Rodados em dois lugares:

- **Orange Pi (ARM)** — 20/20 passaram em 28 min (`testDebugUnitTest`)
- **PC Windows (16 GB)** — 40/40 passaram em 14 s (variantes debug e release)

## Validação no emulador (PC Windows)

- Clone em `C:\Users\sarke\wabridge`
- `./gradlew assembleDebug` → **BUILD SUCCESSFUL** (APK de 2.587.596 bytes)
- Emulador `emulator-5554` (`sdk_gphone64_x86_64`) subido
- `adb install` → **Success**
- `MainActivity` aberta, processo do app vivo, **sem crash**

## APK release

- `com.orangepi.wabridge`, versionCode 1, minSdk 26, targetSdk 34, compileSdk 35
- Assinatura: `CN=WA Bridge, OU=Home, O=wabridge, C=BR`
- SHA-256 do certificado: `27405a554f869dbe46d9eda85d35879baaee489805fa39c1cc65be89f442844b`
- Release: https://github.com/KelvinKSPS/wabridge/releases/tag/v1.0.1

## Detalhes que custaram tempo (e como foram resolvidos)

1. **aapt2 x86-64 numa board ARM** — o `aapt2` do SDK não roda aqui. Resolvido com um
   sysroot x86 mínimo (glibc + libgcc) e um wrapper com `qemu-user`:
   `~/.local/bin/aapt2`, apontado pelo AGP via `android.aapt2FromMavenOverride`.
   O AGP exige que o override termine em `aapt2` — por isso o nome do wrapper.
2. **API do Telecom diferente da esperada** — no SDK 35, `ConnectionService`
   recebe `ConnectionRequest` (não `Bundle`), `PhoneAccount` se constrói com
   `PhoneAccount.Builder(handle, label)`, e `PhoneAccountCapabilities`,
   `Connection.setAudioState` e `TelecomManager.EXTRA_HAS_MEDIA_STATE` não são
   API pública. Corrigido no app.
3. **`PORT=39390` no ambiente** — o shell desta máquina herda o `PORT` do painel
   web, e o `node --env-file` não sobrescreve variável já existente. O
   `server/scripts/start.sh` agora exporta o `.env` por cima do ambiente.
4. **Enter não registrava no outro agente** — a sessão opencode precisa de `\r`,
   não de `\n`, para submeter o prompt.

## Pendências (precisa de ação humana)

1. **Evolution API**: subir `docker compose up -d` (na raiz do projeto) e parear
   o WhatsApp lendo o QR. Sem isso o servidor fica esperando a conexão.
2. **Lista branca**: `ALLOWED_NUMBERS` no `server/.env` está vazio (aceita
   qualquer remetente). Definir antes de usar de verdade.
3. **Chave**: guardar `~/wabridge-keys/wabridge.jks` — sem ela não dá para
   atualizar o app no relógio, só reinstalar.
