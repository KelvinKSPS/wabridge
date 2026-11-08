# Relatório — wabridge v1.0.2

Data: 08/11/2026

## Validação com WhatsApp real (08/11)

O WhatsApp foi pareado de verdade na Evolution v2.3.7 (número
**5519982617622**). As duas direções foram exercitadas com mídia real, não
só com mock:

**Entrada** — uma nota de voz real do WhatsApp (buscada no histórico da
Evolution) foi injetada no webhook e percorreu a ponte inteira:

```
webhook (v2)  -> Evolution /chat/getBase64FromMediaMessage (base64 real)
              -> ffmpeg -> wav
              -> wav baixado: 150.270 bytes | RIFF | mono | 16 kHz
              -> push NEW_VOICE_NOTE no WebSocket
```

**Saída** — uma gravação (m4a AAC 16 kHz, como a do relógio) foi enviada via
`POST /reply/audio` e o WhatsApp aceitou:

```json
{"ok": true, "to": "5519982617622",
 "sent": {"key": {"id": "3EB088947CC694A059D955", "fromMe": true},
          "status": "PENDING",
          "message": {"audioMessage": {"url": "https://mmg.whatsapp.net/..."}}}}
```

### Bugs do dia (todos corrigidos)
5. **O app crashou no aparelho do usuário** (achados pelo logcat real):
   - `NullPointerException` em `onCreateIncomingConnectionFailed`: o Telecom
     chama esse método com a conta **nula** quando não encontra a
     PhoneAccount; o parâmetro estava declarado como não-nulo e o Kotlin
     estourava. O processo morria — e o WebSocket junto.
   - `ForegroundServiceStartNotAllowedException`: `startForeground()` é
     bloqueado no Android 12+ quando o serviço é criado em segundo plano.
     O serviço morria na criação → **as notas de voz paravam de chegar**.
   - **Chamada por cima**: uma nota que chegava durante outra chamada
     abria uma segunda chamada. Agora há fila (`CallQueue`): uma por vez.
   - **Resposta muda**: o usuário desligava logo depois do bipe e mandava
     0,5 s de silêncio (1.831 bytes). Agora a tela mostra o estado
     ("tocando" / "grave sua resposta" / segundos) e o desligar só passa
     depois de 1,5 s de gravação.
6. **A release v1.0.1 do GitHub era anterior ao fix** do crash por
   `READ_PHONE_STATE` (fix 13:06, release 12:00) — quem instalou aquele
   APK teve o serviço morrendo ao abrir. republished em v1.0.2.

1. **A Evolution parou de aceitar pareamento.** Ela conta cada QR gerado (o
   QR gira a cada ~30 s) e recusa no limite — o padrão é 30, ou seja, ~15
   minutos de janela. Depois disso o estado ia para `refused` e o WhatsApp
   respondia "não foi possível conectar o dispositivo".
   `QRCODE_LIMIT` agora é 500.
2. **A ponte reiniciava a instância ao buscar o QR**, o que invalidava o QR
   que o usuário estava escaneando. Agora o QR vem só do webhook.
3. **Webhook descartado por corpo grande**: a Evolution v2 manda payload de
   vários MB; o limite de 4 MB do `express.json` respondia 413 e o evento
   morria (59 descartes no log). Limite agora 50 MB.
4. **Canal do WhatsApp tocava o relógio**: o filtro só pegava `@g.us`, então
   `@newsletter` (canal), `@broadcast` e `@lid` passavam como conversa
   privada. Agora só `@s.whatsapp.net`/`@c.us` tocam o relógio.


## Atualização de 08/11 — Evolution API v2 no ar

A imagem antiga (`atendai/evolution-api`) saiu do Docker Hub; o projeto hoje é
`evolution-foundation/evolution-api` e a imagem é **`evoapicloud/evolution-api`**
(v2.3.7). Subir isso derrubou o servidor: a v2 mudou as rotas e o formato do
webhook.

| Antes (v1) | Agora (v2) |
|---|---|
| `POST /chat/downloadMedia/{inst}` com `mediaLink` | `POST /chat/getBase64FromMediaMessage/{inst}` com a **mensagem inteira** (a v2 não expõe link público do áudio) |
| `POST /chat/sendAudio/{inst}` com data URI | `POST /message/sendWhatsAppAudio/{inst}` com base64 puro |
| webhook em `/webhook/whatsapp` | a Evolution **acrescenta o evento**: `/webhook/whatsapp/messages-upsert` |

O que mudou no código:

1. `src/whatsapp.ts` — normalizador aceita o payload v2 (`data` é lista,
   `audioMessage` sem link) e passa a mensagem crua adiante; grupos seguem
   bloqueados por `remoteJid`.
2. `src/evolution.ts` — cliente **v2-first com fallback v1**: tenta a rota v2 e,
   se a rota não existir (404/405) ou a mensagem não for reconhecida (400),
   cai na v1.
3. `src/index.ts` — rota do webhook aceita `/webhook/whatsapp` **e**
   `/webhook/whatsapp/:event`; o evento `qrcode-updated` é guardado e
   exposto em `GET /qrcode` e `GET /qrcode.png` (QR de pareamento).
4. `test/mock-evolution.js` e `test/e2e.js` — passam a usar o formato v2.
5. `test/unit/evolution.test.ts` — novo: 5 casos para o cliente (v2, fallback
   v1, envio de nota de voz, headers).

Infra: a Evolution v2 exige **PostgreSQL e Redis**, então o `docker-compose.yml`
ganhou três serviços (`evolution`, `evolution-postgres`, `evolution-redis`).
A porta 8080 do host é do qbittorrent, então a Evolution ficou na **8098**
(`EVOLUTION_API_URL=http://127.0.0.1:8098`).

Estado atual: Evolution v2.3.7 no ar, instância `wabridge` criada
(`integration: WHATSAPP-BAILEYS`) e o webhook global já apontando para a ponte
— o evento `qrcode-updated` chega e o QR aparece em
`http://192.168.15.100:8097/qrcode`. **Falta só escanear o QR com o
celular** (ou pelo painel `http://192.168.15.100:8098/manager`).

## O que foi entregue

| Parte | Onde | Estado |
|---|---|---|
| Servidor (Node 24 / TypeScript) | `server/` | no ar em `http://192.168.15.100:8097` (pm2, reinicia sozinho) |
| App Android (Kotlin) | `android-bridge/` | APK release assinado, publicado na release v1.0.1 |
| Testes | `server/test/unit/`, `app/src/test/` | 40 (servidor) + 21 (app), todos verdes |
| Chave de assinatura | `android-bridge/wabridge.jks` | gerada; cópia em `~/wabridge-keys/` |
| Evolution API | `docker-compose.yml` | v2.3.7 no ar em `http://192.168.15.100:8098`; instância `wabridge` criada; **falta parear o QR** |

## Testes

**Servidor — 40 testes (`npm test` na pasta `server/`)**

| Arquivo | Casos | O que cobre |
|---|---|---|
| `whatsapp.test.ts` | 10 | normalização do webhook (4 formatos + payload v2), `document` de áudio, grupo, `fromMe`, jid inválido |
| `config.test.ts` | 6 | lista branca: exato, sufixo, lista vazia, número inválido |
| `media.test.ts` | 4 | ffmpeg: opus → wav PCM 16 kHz mono, opus → opus, entrada inválida |
| `routes.test.ts` | 12 | rotas HTTP, push no WebSocket, `/media`, `/media/test.wav`, `/reply/audio` com `ptt`, bloqueio, grupo, webhook v2, QR de pareamento |
| `evolution.test.ts` | 5 | cliente v2 (download por mensagem, envio de nota de voz) e fallback para a v1 |

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

1. **Parear o WhatsApp** (única etapa que falta no lado do Evolution): a
   instância `wabridge` já existe e o QR chega na ponte. Duas formas:
   - painel `http://192.168.15.100:8098/manager` → QR na tela; ou
   - `http://192.168.15.100:8097/qrcode` (a ponte mostra o QR que a Evolution
     mandou por webhook — expira em ~30 s, então o painel é melhor).
2. **Lista branca**: `ALLOWED_NUMBERS` no `server/.env` está vazio (aceita
   qualquer remetente). Definir antes de usar de verdade.
3. **Chave**: guardar `~/wabridge-keys/wabridge.jks` — sem ela não dá para
   atualizar o app no relógio, só reinstalar.
4. **Teste no relógio de verdade**: instalar o APK, abrir, colocar a URL
   `http://192.168.15.100:8097`, conectar e tocar em **Testar chamada**.

## Detalhes que custaram tempo (2ª rodada)

1. **A imagem `atendai/evolution-api` não existe mais** — o Docker Hub devolve
   "pull access denied". A imagem atual é `evoapicloud/evolution-api` (v2.3.7).
2. **A v2 exige Postgres e Redis** — não sobe só com o container da API.
3. **A v2 anexa o evento na URL do webhook** (`/webhook/whatsapp/qrcode-updated`).
   Com o path fixo a Evolution recebia 404 e o webhook morria.
4. **A v2 não expõe link público da mídia** — o áudio é baixado passando a
   mensagem inteira para `getBase64FromMediaMessage`.
5. **Porta 8080 do host é do qbittorrent** — a Evolution foi para a 8098.
6. **`/var/log` é tmpfs de 47 MB e encheu** com o `sysstat` (62 arquivos
   `saNN`); a máquina ficou com CPU em 100%. Precisa de `sudo` para limpar
   (não disponível sem senha nesta sessão).
