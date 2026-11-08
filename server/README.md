# server — ponte WhatsApp (Node.js + TypeScript)

## Instalação

```bash
npm install
cp .env.example .env   # edite ALLOWED_NUMBERS / PUBLIC_HOST / EVOLUTION_*
npm run dev            # tsx watch
npm run build && npm start
```

Variáveis são lidas de `process.env` (use `node --env-file=.env dist/index.js`
no Node 20+ ou exporte no shell).

## Requisitos

- `ffmpeg` com `libopus` e `pcm_s16le` (`ffmpeg -encoders | grep opus`).
- Evolution API (v1 ou v2). Na v2 o webhook global aponta para
  `http://<IP_DA_PONTE>:8097/webhook/whatsapp` — a Evolution acrescenta o
  evento no final do caminho (`/webhook/whatsapp/messages-upsert`).

## Rotas

### `POST /webhook/whatsapp`

Aceita o payload `messages.upsert` da Evolution API. O normalizador
(`normalize()` em `src/index.ts`) tolera as variações de formato: a mensagem
pode estar em `data.message.audio`, `message.audio`, `audioMessage` ou
`document` (PTT). Group (`@g.us`), mensagens de saída (`fromMe`) e remetentes
fora da lista branca são ignorados.

Sequência do processamento:

1. Baixa o áudio: na **v2** a mensagem inteira vai para
   `POST {EVOLUTION}/chat/getBase64FromMediaMessage/{instance}` (a v2 não
   expõe link público); a **v1** usa `chat/downloadMedia` com o link.
2. `ffmpeg` → `wav` PCM 16kHz mono (obrigatório para o SCO do Android).
3. Publica `NEW_VOICE_NOTE` no WebSocket.
4. Cache expira em `MEDIA_TTL_MS`.

### `GET /media/:audioId`

Serve o wav em cache. O `audioId` é o id da mensagem sanitizado.

### `POST /reply/audio`

`multipart/form-data`:

| campo | obrigatório | descrição |
|---|---|---|
| `file` | sim | gravação do relógio (`.m4a`/`.wav`) |
| `audioId` | não | id do áudio original (resolve o destinatário) |
| `to` | não | número do destinatente, só dígitos |

Converte para `ogg`/Opus e envia como nota de voz (`ptt: true`): na **v2**
vai como base64 em `POST {EVOLUTION}/message/sendWhatsAppAudio/{instance}`;
a v1 usa `chat/sendAudio` (fallback `chat/sendMedia`).

### `GET /qrcode` e `GET /qrcode.png`

Último QR code de pareamento enviado pela Evolution (v2). O QR expira em
~30 s — o painel da Evolution (`/manager`) gera um novo a qualquer momento.

## Teste sem WhatsApp real

Há uma Evolution API falsa em `test/` para validar o ciclo completo
(webhook → conversão → WebSocket → upload → nota de voz):

```bash
bash test/make-sample.sh
npm run test:mock &                       # Evolution falsa em :8081
PORT=8097 EVOLUTION_API_URL=http://127.0.0.1:8081 npm start &
npm run test:e2e
```

Saída esperada:

```
webhook  -> 200 {"ok":true}
ws push  -> {"type":"NEW_VOICE_NOTE",...}
media    -> 200 96078 bytes
reply    -> 200 {"ok":true,...}
```

O áudio recebido (opus) fica em `test/sample.ogg` e o enviado pelo app em
`test/recebido.ogg`.

## WebSocket (`/ws`)

```json
{ "type": "NEW_VOICE_NOTE",
  "audioId": "MSG_ABC",
  "senderName": "João",
  "senderNumber": "5511999999999",
  "audioUrl": "http://192.168.0.10:8097/media/MSG_ABC.wav" }
```

O app responde `ping` (texto puro) e recebe `pong`.
